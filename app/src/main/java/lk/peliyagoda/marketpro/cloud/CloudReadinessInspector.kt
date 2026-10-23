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
        countOnly: Boolean = false
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
                .use { it.readText().take(4096) }
            return payload to range
        } finally {
            connection.disconnect()
        }
    }

    suspend fun inspect(): CloudPreflightReport = withContext(Dispatchers.IO) {
        // Supabase checks the encrypted, refreshed session and owner ID first.
        val jwt = CloudOwnerAuth(app).accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val localDao = MarketDatabase.get(app).dao()
        val localCounts = linkedMapOf(
            "categories" to localDao.allCategories().size,
            "items" to localDao.allItems().size,
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
            writeBlockedReason = "Existing Cloud data and device-specific UUID mappings " +
                "must be reconciled before enabling another writer. No upload or " +
                "overwrite is allowed in this preview."
        )
    }

    companion object {
        const val WEB_DASHBOARD = "https://marketpro-cloud-dashboard.vercel.app/"
    }
}
