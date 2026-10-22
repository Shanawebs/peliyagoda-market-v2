package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.room.withTransaction
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudAction
import lk.peliyagoda.marketpro.data.CloudChangeJournal
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudRecordType
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * V2.31 single-owner DEV workspace. These IDs are public identifiers, not secrets.
 * The server independently checks JWT identity, role and registered device on each RPC.
 */
object CloudSyncConfig {
    const val WORKSPACE_ID = "e46b90c5-ed6a-4952-82e9-8582fac06d89"
    const val OWNER_USER_ID = "bf49c22f-f245-4d3c-987e-c5ec081d7cad"
    const val ENABLED = "enrolled"
    const val LAST_SUCCESS = "last_success_utc"
    const val LAST_ERROR = "last_error"
    val ORDERED_TYPES = CloudRecordType.values().toList()
}

/** No generic JSON transport: only specifically allowlisted financial metadata. */
internal class CloudRestClient {
    private val origin = BuildConfig.MARKETPRO_SUPABASE_URL

    private fun send(path: String, bearer: String, body: JSONObject? = null): String {
        val url = URL(origin + path)
        require(url.protocol == "https" && url.host == "lkoptqhodgjeuwwfihfr.supabase.co")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val response = (
                if (code in 200..299) connection.inputStream else connection.errorStream
            )?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(8192) }.orEmpty()
            if (code !in 200..299) {
                // Never surface remote JSON errors or tokens to the UI, backups or logcat.
                if (code == 401 || code == 408 || code == 429 || code >= 500) {
                    throw IOException("Cloud temporarily unavailable (HTTP $code)")
                }
                throw CloudUploadRejected("Cloud rejected record (HTTP $code); pending record retained")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun verifyPrimaryOwner(token: String, deviceId: String) {
        UUID.fromString(deviceId)
        val rows = JSONArray(send(
            "/rest/v1/registered_devices?select=id,workspace_id,user_id,enabled,can_write" +
                "&id=eq.$deviceId&limit=2", token
        ))
        if (rows.length() != 1) throw CloudUploadRejected("Primary phone is not registered for this account")
        val row = rows.getJSONObject(0)
        if (row.getString("id") != deviceId ||
            row.getString("workspace_id") != CloudSyncConfig.WORKSPACE_ID ||
            row.getString("user_id") != CloudSyncConfig.OWNER_USER_ID ||
            !row.optBoolean("enabled") || !row.optBoolean("can_write")
        ) throw CloudUploadRejected("Cloud device enrollment is not authorized")
    }

    fun requireEmptyInitialWorkspace(token: String) {
        // First-time import must never overwrite an existing cloud dataset.
        val tables = listOf(
            "categories", "items", "purchases", "purchase_additions",
            "requisitions", "daily_cash_received"
        )
        for (table in tables) {
            val rows = JSONArray(send("/rest/v1/$table?select=id&limit=1", token))
            if (rows.length() != 0) throw CloudUploadRejected(
                "Cloud already contains records. Initial import blocked to prevent overwrite."
            )
        }
    }

    fun upload(token: String, deviceId: String, change: CloudPreparedChange) {
        val envelope = JSONObject()
            .put("p_workspace_id", CloudSyncConfig.WORKSPACE_ID)
            .put("p_device_id", deviceId)
            .put("p_operation_id", change.row.operationId)
            .put("p_entity_type", change.row.entityType)
            .put("p_entity_id", change.cloudId)
            .put("p_action", change.row.action)
            .put("p_payload", change.payload)
        val response = JSONObject(send("/rest/v1/rpc/marketpro_apply_change", token, envelope))
        if (!response.optBoolean("accepted") ||
            response.optString("operation_id") != change.row.operationId
        ) throw IOException("Cloud response could not be verified")
    }
}

internal class CloudUploadRejected(message: String) : Exception(message)
internal data class CloudPreparedChange(
    val row: CloudOutboxEntity,
    val cloudId: String,
    val payload: JSONObject
)

/**
 * No image/file references are ever consulted here.
 * Read record data *inside Room transaction*, then call the network outside it.
 */
class CloudSyncEngine(private val context: Context) {
    /**
     * Immediate + periodic WorkManager jobs may overlap. A shared process-level
     * lock guarantees only one uploader can apply and ACK operations at a time.
     * Never hold a Room transaction across network requests.
     */
    companion object {
        private val singleWriterMutex = Mutex()
    }
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val dao = db.dao()
    private val cloud = db.cloudDao()
    private val client = CloudRestClient()
    private val auth = CloudOwnerAuth(app)

    suspend fun isEnabled(): Boolean = cloud.state(CloudSyncConfig.ENABLED) == "true"

    suspend fun enableAfterOwnerConfirmation(): Int {
        // Calling "Enable" is an explicit owner decision. Neither app install nor login
        // automatically uploads, imports or enables the account.
        val token = auth.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val deviceId = CloudDeviceIdentity.get(app)
        client.verifyPrimaryOwner(token, deviceId)

        if (!isEnabled()) {
            client.requireEmptyInitialWorkspace(token)
            // Mark enabled and enqueue EVERY historical row in one atomic local
            // transaction; a crash can never leave a half-prepared snapshot.
            db.withTransaction {
                if (cloud.state(CloudSyncConfig.ENABLED) != "true") {
                    val journal = CloudChangeJournal(cloud)
                    dao.allCategories().forEach {
                        journal.record(CloudRecordType.CATEGORY, CloudRecordKey.category(it.name))
                    }
                    dao.allItems().forEach {
                        journal.record(CloudRecordType.ITEM, CloudRecordKey.item(it.id))
                    }
                    dao.allRequisitions().forEach {
                        journal.record(
                            CloudRecordType.REQUISITION,
                            CloudRecordKey.requisition(it.businessDate, it.kitchen, it.itemId)
                        )
                    }
                    dao.allPurchases().forEach {
                        journal.record(
                            CloudRecordType.PURCHASE,
                            CloudRecordKey.purchase(it.businessDate, it.itemId)
                        )
                    }
                    dao.allAdditions().forEach {
                        journal.record(CloudRecordType.PURCHASE_ADDITION, CloudRecordKey.addition(it.id))
                    }
                    dao.allCashReceived().forEach {
                        journal.record(
                            CloudRecordType.DAILY_CASH_RECEIVED, CloudRecordKey.cash(it.businessDate)
                        )
                    }
                    cloud.setState(CloudSyncStateEntity(CloudSyncConfig.ENABLED, "true"))
                }
            }
        }
        CloudSyncScheduler.schedulePeriodic(app)
        CloudSyncScheduler.kick(app)
        return cloud.pendingCountNow()
    }

    private fun keys(row: CloudOutboxEntity, expected: Int): List<String> {
        val parts = row.entityKey.split("|")
        check(parts.size == expected) { "Invalid logical cloud record key" }
        return parts
    }

    private suspend fun linkedItem(id: Long): String =
        cloud.cloudId("item", id.toString())
            ?: throw CloudUploadRejected("Cloud item identity missing; import required")

    private suspend fun requiredCloudId(type: String, key: String): String =
        cloud.cloudId(type, key)
            ?: throw CloudUploadRejected("Cloud identity not found; cannot upload safely")

    private suspend fun prepare(row: CloudOutboxEntity): CloudPreparedChange {
        val id = requiredCloudId(row.entityType, row.entityKey)
        UUID.fromString(id)
        if (row.action == "delete") {
            check(row.entityType in setOf("requisition", "purchase", "purchase_addition")) {
                "Unexpected cloud deletion type"
            }
            return CloudPreparedChange(row, id, JSONObject())
        }
        check(row.action == "upsert") { "Unknown cloud action" }
        val payload = when (row.entityType) {
            "category" -> {
                val data = cloud.findCategory(row.entityKey)
                    ?: throw CloudUploadRejected("Local category missing")
                JSONObject().put("name", data.name)
            }
            "item" -> {
                val data = cloud.findItem(row.entityKey.toLong())
                    ?: throw CloudUploadRejected("Local item missing")
                val catId = requiredCloudId("category", CloudRecordKey.category(data.category))
                JSONObject().put("name", data.name)
                    .put("category_id", catId).put("is_active", data.isActive)
            }
            "requisition" -> {
                val p = keys(row, 3)
                val data = cloud.findRequisition(p[0], p[1], p[2].toLong())
                    ?: throw CloudUploadRejected("Local requisition missing")
                JSONObject().put("business_date", data.businessDate)
                    .put("kitchen", data.kitchen)
                    .put("item_id", linkedItem(data.itemId))
                    .put("quantity_grams", data.quantityGrams)
            }
            "purchase" -> {
                val p = keys(row, 2)
                val data = cloud.findPurchase(p[0], p[1].toLong())
                    ?: throw CloudUploadRejected("Local purchase missing")
                JSONObject().put("business_date", data.businessDate)
                    .put("item_id", linkedItem(data.itemId))
                    .put("quantity_grams", data.quantityGrams)
                    .put("price_cents_per_kg", data.priceCentsPerKg)
            }
            "purchase_addition" -> {
                val data = cloud.findAddition(row.entityKey.toLong())
                    ?: throw CloudUploadRejected("Local additional purchase missing")
                JSONObject().put("business_date", data.businessDate)
                    .put("item_id", linkedItem(data.itemId))
                    .put("quantity_grams", data.quantityGrams)
                    .put("price_cents_per_kg", data.priceCentsPerKg)
            }
            "daily_cash_received" -> {
                val data = cloud.findCash(row.entityKey)
                    ?: throw CloudUploadRejected("Local cash record missing")
                JSONObject().put("business_date", data.businessDate)
                    .put("amount_cents", data.amountCents)
            }
            else -> throw CloudUploadRejected("Unexpected cloud record type")
        }
        return CloudPreparedChange(row, id, payload)
    }

    /** Runs under WorkManager, never on UI thread; bounded to protect 4GB phones. */
    suspend fun drain(maxChanges: Int = 100): Int = singleWriterMutex.withLock {
        require(maxChanges in 1..100) { "Cloud batches must contain 1..100 changes" }
        if (!isEnabled()) return@withLock 0
        val bearer = auth.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val device = CloudDeviceIdentity.get(app)
        client.verifyPrimaryOwner(bearer, device)
        var completed = 0
        while (completed < maxChanges) {
            val next = db.withTransaction {
                cloud.pending(1).firstOrNull()?.let { prepare(it) }
            } ?: break
            // Server commits first. Only then conditionally ACK this exact operation UUID.
            client.upload(bearer, device, next)
            db.withTransaction {
                cloud.acknowledge(next.row.entityType, next.row.entityKey, next.row.operationId)
                cloud.setState(CloudSyncStateEntity(CloudSyncConfig.LAST_SUCCESS, Instant.now().toString()))
                cloud.setState(CloudSyncStateEntity(CloudSyncConfig.LAST_ERROR, ""))
            }
            completed++
        }
        cloud.pendingCountNow()
    }

    suspend fun recordFailure(message: String) {
        cloud.setState(CloudSyncStateEntity(CloudSyncConfig.LAST_ERROR, message.take(200)))
    }
}
