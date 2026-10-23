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
    val financialAudit: FinancialAudit,
    val uuidAudit: UuidAuditResult,
    val migrationDryRun: MigrationDryRunResult,
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
        val database = MarketDatabase.get(app)
        val localDao = database.dao()
        val cloudDao = database.cloudDao()
        // Both are READ operations. JSON restore generates local UUIDs in this
        // table; none may be assumed identical to the existing cloud UUIDs.
        val localIdentities = cloudDao.allIdentities().associate {
            (it.entityType to it.entityKey) to it.cloudId
        }
        val localPendingCount = cloudDao.pendingCountNow()
        fun localIdentity(type: String, key: String): String? =
            localIdentities[type to key]
        val phoneCategories = localDao.allCategories()
        val phoneItems = localDao.allItems()
        // Materialize a consistent local read phase before remote comparisons.
        val phoneRequirements = localDao.allRequisitions()
        val phonePurchases = localDao.allPurchases()
        val phoneAdditions = localDao.allAdditions()
        val phoneCash = localDao.allCashReceived()
        val localCounts = linkedMapOf(
            "categories" to phoneCategories.size,
            "items" to phoneItems.size,
            "requisitions" to phoneRequirements.size,
            "purchases" to phonePurchases.size,
            "purchase_additions" to phoneAdditions.size,
            "daily_cash_received" to phoneCash.size
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

        val phoneItemKeys = phoneItems.associate { it.id to
            FinancialReconciliation.itemKey(it.name, it.category)
        }
        fun phoneSemanticItem(itemId: Long): String =
            phoneItemKeys[itemId] ?: "[missing-phone-item]"
        val phoneUuidRecords = buildList {
            phoneCategories.forEach { category ->
                add(UuidAuditRecord(
                    "category",
                    category.name.trim().lowercase(java.util.Locale.ROOT),
                    localIdentity("category", lk.peliyagoda.marketpro.data.CloudRecordKey.category(category.name))
                ))
            }
            phoneItems.forEach { item ->
                add(UuidAuditRecord("item", phoneSemanticItem(item.id),
                    localIdentity("item", lk.peliyagoda.marketpro.data.CloudRecordKey.item(item.id))))
            }
            phoneRequirements.forEach { record ->
                val semantic = FinancialReconciliation.row(
                    "requisitions", record.businessDate, phoneSemanticItem(record.itemId),
                    kitchen = record.kitchen, grams = record.quantityGrams
                ).signature
                add(UuidAuditRecord("requisition", semantic,
                    localIdentity("requisition", lk.peliyagoda.marketpro.data.CloudRecordKey.requisition(
                        record.businessDate, record.kitchen, record.itemId))))
            }
            phonePurchases.forEach { record ->
                val semantic = FinancialReconciliation.row(
                    "purchases", record.businessDate, phoneSemanticItem(record.itemId),
                    grams = record.quantityGrams,
                    priceCentsPerKg = record.priceCentsPerKg
                ).signature
                add(UuidAuditRecord("purchase", semantic,
                    localIdentity("purchase", lk.peliyagoda.marketpro.data.CloudRecordKey.purchase(
                        record.businessDate, record.itemId))))
            }
            phoneAdditions.forEach { record ->
                val semantic = FinancialReconciliation.row(
                    "purchase_additions", record.businessDate, phoneSemanticItem(record.itemId),
                    grams = record.quantityGrams,
                    priceCentsPerKg = record.priceCentsPerKg
                ).signature
                add(UuidAuditRecord("purchase_addition", semantic,
                    localIdentity("purchase_addition",
                        lk.peliyagoda.marketpro.data.CloudRecordKey.addition(record.id))))
            }
            phoneCash.forEach { record ->
                val semantic = FinancialReconciliation.row(
                    "daily_cash_received", record.businessDate, cashCents = record.amountCents
                ).signature
                add(UuidAuditRecord("daily_cash_received", semantic,
                    localIdentity("daily_cash_received",
                        lk.peliyagoda.marketpro.data.CloudRecordKey.cash(record.businessDate))))
            }
        }
        val remoteUuidRecords = mutableListOf<UuidAuditRecord>()
        var archivedCloudFinancialRows = 0

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
            remoteUuidRecords.add(UuidAuditRecord(
                "category",
                c.getString("name").trim().lowercase(java.util.Locale.ROOT),
                c.getString("id")
            ))
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
        val cloudItemKeys = mutableMapOf<String, String>()
        val remoteCatalog = buildList {
            for (i in 0 until itemRows.length()) {
                val row = itemRows.getJSONObject(i)
                val category = categoryNames[row.getString("category_id")]
                    ?: throw IOException("Cloud item has an unknown category")
                cloudItemKeys[row.getString("id")] =
                    FinancialReconciliation.itemKey(row.getString("name"), category)
                remoteUuidRecords.add(UuidAuditRecord(
                    "item", cloudItemKeys.getValue(row.getString("id")), row.getString("id")
                ))
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

        // Compare complete transaction FINGERPRINTS rather than aggregate row counts.
        // Read-only REST queries scoped by workspace RLS. No write method exists here.
        var unmappedPhone = 0
        fun phoneKey(id: Long): String {
            return phoneItemKeys[id] ?: run {
                unmappedPhone++
                "[missing-local-item:$id]"
            }
        }
        val phoneFinancial = buildList {
            for (r in phoneRequirements) add(FinancialReconciliation.row(
                "requisitions", r.businessDate, phoneKey(r.itemId),
                kitchen = r.kitchen, grams = r.quantityGrams
            ))
            for (p in phonePurchases) add(FinancialReconciliation.row(
                "purchases", p.businessDate, phoneKey(p.itemId),
                grams = p.quantityGrams, priceCentsPerKg = p.priceCentsPerKg
            ))
            for (p in phoneAdditions) add(FinancialReconciliation.row(
                "purchase_additions", p.businessDate, phoneKey(p.itemId),
                grams = p.quantityGrams, priceCentsPerKg = p.priceCentsPerKg
            ))
            for (c in phoneCash) add(FinancialReconciliation.row(
                "daily_cash_received", c.businessDate, cashCents = c.amountCents
            ))
        }
        var unmappedCloud = 0
        fun cloudKey(id: String): String {
            return cloudItemKeys[id] ?: run {
                unmappedCloud++
                "[missing-cloud-item:$id]"
            }
        }
        val cloudFinancial = buildList {
            for (table in listOf("requisitions", "purchases",
                "purchase_additions", "daily_cash_received")) {
                val columns = when (table) {
                    "requisitions" -> "id,business_date,kitchen,item_id,quantity_grams,deleted_at"
                    "daily_cash_received" -> "id,business_date,amount_cents,deleted_at"
                    else -> "id,business_date,item_id,quantity_grams,price_cents_per_kg,deleted_at"
                }
                val (body, range) = fetch(
                    jwt, "$table?select=$columns" +
                        "&workspace_id=eq.${CloudSyncConfig.WORKSPACE_ID}&limit=500",
                    countOnly = true, maxBodyChars = 160000
                )
                val rows = JSONArray(body)
                check(rows.length() == CloudReadinessRules.countFromContentRange(range) &&
                    rows.length() == cloudCounts[table]) {
                    "Cloud $table changed during finance audit; retry"
                }
                for (i in 0 until rows.length()) {
                    val data = rows.getJSONObject(i)
                    // Cloud can retain soft-deleted rows; local Room has no
                    // matching tombstone table. Fail CLOSED, never hide a difference.
                    val date = data.getString("business_date")
                    val id = if (table == "daily_cash_received") "" else
                        cloudKey(data.getString("item_id"))
                    val record = when (table) {
                        "requisitions" -> FinancialReconciliation.row(
                            table, date, id,
                            kitchen = data.getString("kitchen"),
                            grams = data.getLong("quantity_grams")
                        )
                        "daily_cash_received" -> FinancialReconciliation.row(
                            table, date, cashCents = data.getLong("amount_cents")
                        )
                        else -> FinancialReconciliation.row(
                            table, date, id, grams = data.getLong("quantity_grams"),
                            priceCentsPerKg = data.getLong("price_cents_per_kg")
                        )
                    }
                    val isArchived = !data.isNull("deleted_at")
                    val semantic = if (isArchived) {
                        archivedCloudFinancialRows++
                        record.signature + ":cloud-archived"
                    } else record.signature
                    remoteUuidRecords.add(UuidAuditRecord(
                        when (table) {
                            "requisitions" -> "requisition"
                            "purchases" -> "purchase"
                            "purchase_additions" -> "purchase_addition"
                            else -> "daily_cash_received"
                        },
                        semantic, data.getString("id")
                    ))
                    add(if (!isArchived) record
                        else record.copy(signature = semantic))
                }
            }
        }
        val financeAudit = FinancialReconciliation.compare(
            phoneFinancial, cloudFinancial, unmappedPhone, unmappedCloud
        )

        val uuidAudit = UuidMappingAudit.compare(
            phone = phoneUuidRecords,
            cloud = remoteUuidRecords,
            pendingOperations = localPendingCount,
            archivedRemoteRows = archivedCloudFinancialRows
        )

        val migrationDryRun = MigrationDryRun.evaluate(
            identities = uuidAudit,
            catalog = audit,
            finance = financeAudit
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
            financialAudit = financeAudit,
            uuidAudit = uuidAudit,
            migrationDryRun = migrationDryRun,
            writeBlockedReason = "Existing Cloud data and device-specific UUID mappings " +
                "must be reconciled before enabling another writer. No upload or " +
                "overwrite is allowed in this preview."
        )
    }

    companion object {
        const val WEB_DASHBOARD = "https://marketpro-cloud-dashboard.vercel.app/"
    }
}
