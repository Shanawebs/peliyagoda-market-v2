package lk.peliyagoda.marketpro.cloud

import android.content.Context
import android.net.Uri
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * READ-ONLY cloud preflight for side-by-side Expressive previews.
 *
 * V2.31 has already imported a nonempty, actively synced cloud workspace.
 * V3.2.8 is a different Android sandbox with DIFFERENT local row IDs and
 * cloud UUID mappings; treating this app as a second uploader would duplicate
 * or overwrite historical financial records.
 *
 * This class has NO POST/PUT/PATCH/DELETE methods, no Room writes, no outbox
 * acknowledgement, no enrolment, and no writer RPC calls. It checks owner
 * identity, cloud totals, local totals and device writer status only.
 */
data class CloudPreflightReport(
    val remote: Map<String, Int>,
    val local: Map<String, Int>,
    val deviceRegisteredAsWriter: Boolean,
    val catalogAudit: CatalogAudit,
    val writeBlockedReason: String
)

internal object CloudReadinessRules {
    val tableNames = listOf(
        "categories", "items", "requisitions", "purchases",
        "purchase_additions", "daily_cash_received"
    )

    fun countFromContentRange(value: String?): Int {
        val total = value?.substringAfterLast("/", "")?.trim()
            ?: throw IOException("Cloud count header missing")
        if (total == "*" || total.isEmpty()) throw IOException("Cloud returned an incomplete count")
        return total.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid cloud count")
    }

    // Preview never writes, even if a device claims enrolment. A dedicated
    // UUID-aware migration + financial reconciliation must be approved first.
    fun previewWriteIsBlocked(): Boolean = true
}

class CloudReadinessInspector(private val context: Context) {
    private val app = context.applicationContext
    private val origin = BuildConfig.MARKETPRO_SUPABASE_URL

    private fun fetch(
        token: String,
        endpoint: String,
        countOnly: Boolean = false,
        maxBodyChars: Int = 4096
    ): Pair<String, String?> {
        val url = URL(origin + "/rest/v1/" + endpoint)
        require(url.protocol == "https" &&
            url.host == "lkoptqhodgjeuwwfihfr.supabase.co") {
            "Cloud endpoint not recognized"
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (countOnly) setRequestProperty("Prefer", "count=exact")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException(
                    if (status == 401 || status == 403)
                        "Cloud login or workspace read permission unavailable"
                    else "Read-only cloud check failed (HTTP $status)"
                )
            }
            val range = connection.getHeaderField("Content-Range")
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    val chars = CharArray(maxBodyChars + 1)
                    val builder = StringBuilder()
                    while (builder.length <= maxBodyChars) {
                        val n = reader.read(chars, 0, minOf(chars.size, maxBodyChars + 1 - builder.length))
                        if (n < 0) break
                        builder.append(chars, 0, n)
                    }
                    if (builder.length > maxBodyChars) throw IOException("Cloud response too large")
                    builder.toString()
                }
            return payload to range
        } finally {
            connection.disconnect()
        }
    }

    suspend fun inspect(): CloudPreflightReport = withContext(Dispatchers.IO) {
        // Supabase checks the encrypted, refreshed session and owner ID first.
        val jwt = CloudOwnerAuth(app).accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val localDao = MarketDatabase.get(app).dao()
        val phoneItems = localDao.allItems()
        val localCounts = linkedMapOf(
            "categories" to localDao.allCategories().size,
            "items" to phoneItems.size,
            "requisitions" to localDao.allRequisitions().size,
            "purchases" to localDao.allPurchases().size,
            "purchase_additions" to localDao.allAdditions().size,
            "daily_cash_received" to localDao.allCashReceived().size
        )
        val cloudCounts = linkedMapOf<String, Int>()
        for (table in CloudReadinessRules.tableNames) {
            // RLS scopes these reads to the authenticated owner's workspace.
            val (_, range) = fetch(
                jwt,
                "$table?select=id&workspace_id=eq.${CloudSyncConfig.WORKSPACE_ID}&limit=1",
                countOnly = true
            )
            cloudCounts[table] = CloudReadinessRules.countFromContentRange(range)
        }

        // Verify actual ITEM NAMES and category assignments, not merely counts.
        // Both reads use the same owner JWT and RLS workspace filter.
        val (catPayload, catRange) = fetch(
            jwt,
            "categories?select=id,name&workspace_id=eq.${CloudSyncConfig.WORKSPACE_ID}&limit=500",
            countOnly = true, maxBodyChars = 100000
        )
        val categoryRows = JSONArray(catPayload)
        check(categoryRows.length() == CloudReadinessRules.countFromContentRange(catRange) &&
            categoryRows.length() == cloudCounts["categories"]) {
            "Cloud categories changed during audit; retry"
        }
        val categoryNames = mutableMapOf<String, String>()
        for (i in 0 until categoryRows.length()) {
            val c = categoryRows.getJSONObject(i)
            categoryNames[c.getString("id")] = c.getString("name")
        }
        val (itemPayload, itemRange) = fetch(
            jwt,
            "items?select=id,name,category_id,is_active,deleted_at" +
                "&workspace_id=eq.${CloudSyncConfig.WORKSPACE_ID}&limit=500",
            countOnly = true, maxBodyChars = 150000
        )
        val itemRows = JSONArray(itemPayload)
        check(itemRows.length() == CloudReadinessRules.countFromContentRange(itemRange) &&
            itemRows.length() == cloudCounts["items"]) {
            "Cloud items changed during audit; retry"
        }
        val remoteCatalog = buildList {
            for (i in 0 until itemRows.length()) {
                val row = itemRows.getJSONObject(i)
                val category = categoryNames[row.getString("category_id")]
                    ?: throw IOException("Cloud item has an unknown category")
                add(CatalogRecord(
                    row.getString("name"), category,
                    row.getBoolean("is_active") && row.isNull("deleted_at")
                ))
            }
        }
        val audit = CatalogReconciliation.compare(
            phoneItems.map { CatalogRecord(it.name, it.category, it.isActive) },
            remoteCatalog
        )

        // The original synced V2.31 phone is protected by a single-writer rule.
        val deviceId = CloudDeviceIdentity.get(app)
        val (devicePayload, _) = fetch(
            jwt,
            "registered_devices?select=id,enabled,can_write" +
                "&workspace_id=eq.${CloudSyncConfig.WORKSPACE_ID}" +
                "&id=eq.$deviceId&limit=1"
        )
        val devices = JSONArray(devicePayload)
        val enrolled = devices.length() == 1 &&
            devices.getJSONObject(0).optBoolean("enabled") &&
            devices.getJSONObject(0).optBoolean("can_write")

        CloudPreflightReport(
            remote = cloudCounts,
            local = localCounts,
            deviceRegisteredAsWriter = enrolled,
            catalogAudit = audit,
            writeBlockedReason = "Existing Cloud data and device-specific UUID mappings " +
                "must be reconciled before enabling another writer. No upload or " +
                "overwrite is allowed in this preview."
        )
    }

    companion object {
        const val WEB_DASHBOARD = "https://marketpro-cloud-dashboard.vercel.app/"
    }
}
