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
        check(!BuildConfig.MARKETPRO_DEMO_ARCHIVE_ONLY) {
            "Offline demo archive has no network access"
        }
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

    /**
     * Authenticated preflight against existing unique Cloud business keys.
     * Never upload an older demo row when a fresh V3 UUID conflicts.
     * The Postgres unique constraints remain the authority during races.
     */
    fun existingBusinessIds(token: String, change: CloudPreparedChange): List<String> {
        val type = change.row.entityType
        val payload = change.payload
        val workspace = CloudSyncConfig.WORKSPACE_ID
        val query = when (type) {
            "requisition" -> {
                val kitchen = java.net.URLEncoder.encode(
                    payload.getString("kitchen"), "UTF-8"
                ).replace("+", "%20")
                "requisitions?select=id&workspace_id=eq.$workspace" +
                    "&business_date=eq.${payload.getString("business_date")}" +
                    "&item_id=eq.${payload.getString("item_id")}" +
                    "&kitchen=eq.$kitchen&limit=2"
            }
            "purchase" -> "purchases?select=id&workspace_id=eq.$workspace" +
                "&business_date=eq.${payload.getString("business_date")}" +
                "&item_id=eq.${payload.getString("item_id")}&limit=2"
            "daily_cash_received" ->
                "daily_cash_received?select=id&workspace_id=eq.$workspace" +
                "&business_date=eq.${payload.getString("business_date")}&limit=2"
            "purchase_addition" ->
                "purchase_additions?select=id&workspace_id=eq.$workspace" +
                "&id=eq.${change.cloudId}&limit=2"
            else -> throw CloudUploadRejected("Fresh-only mode blocks catalog write or delete")
        }
        val found = JSONArray(send("/rest/v1/$query", token))
        if (found.length() > 1) {
            throw CloudUploadRejected("Cloud has ambiguous transaction identities")
        }
        return (0 until found.length()).map { i ->
            found.getJSONObject(i).getString("id").also { UUID.fromString(it) }
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
 * Enforces exactly one active uploader per Android process. This protects against
 * WorkManager's immediate and periodic workers running simultaneously. Server-side
 * operation-ID deduplication remains the fallback for process crashes/retries.
 */
internal object CloudSyncRunGate {
    private val mutex = Mutex()
    suspend fun <T> runExclusive(work: suspend () -> T): T =
        mutex.withLock { work() }
}

/**
 * No image/file references are ever consulted here.
 * Read record data *inside Room transaction*, then call the network outside it.
 */
class CloudSyncEngine(private val context: Context) {
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val dao = db.dao()
    private val cloud = db.cloudDao()
    private val client = CloudRestClient()
    private val auth = CloudOwnerAuth(app)

    suspend fun isEnabled(): Boolean = !BuildConfig.MARKETPRO_DEMO_ARCHIVE_ONLY &&
        cloud.state(CloudSyncConfig.ENABLED) == "true"

    /** The old empty-workspace import is permanently disabled in V3 fresh mode. */
    suspend fun enableAfterOwnerConfirmation(): Int {
        check(!BuildConfig.MARKETPRO_DEMO_ARCHIVE_ONLY) {
            "Offline demo archive cannot enable Cloud Sync"
        }
        throw CloudUploadRejected("Bulk historical import is disabled for V3; use fresh catalog attach")
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
    suspend fun drain(maxChanges: Int = 100): Int = CloudSyncRunGate.runExclusive {
        require(maxChanges in 1..100) { "Cloud batches must contain 1..100 changes" }
        if (!isEnabled()) return@runExclusive 0
        val bearer = auth.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val device = CloudDeviceIdentity.get(app)
        client.verifyPrimaryOwner(bearer, device)
        var completed = 0
        while (completed < maxChanges) {
            val next = db.withTransaction {
                val row = cloud.pending(1).firstOrNull() ?: return@withTransaction null
                check(cloud.state(FreshAttachState.PROTOCOL) == FreshAttachState.VERSION) {
                    "V3 fresh-only enrollment is not verified"
                }
                require(row.entityType in setOf(
                    "purchase", "requisition", "purchase_addition", "daily_cash_received"
                ) && row.action == "upsert") {
                    "Fresh-only V3 upload never modifies historic catalog or deletes records"
                }
                prepare(row)
            } ?: break
            val origin = cloud.state(
                FreshAttachState.originKey(next.row.entityType, next.row.entityKey)
            )
            // This is a server-authenticated, read-only, pre-upload collision check.
            // Old demo financial identities are NEVER rebound to a new V3 UUID.
            val existing = client.existingBusinessIds(bearer, next)
            val key = when (next.row.entityType) {
                "requisition" -> "requisition|${next.payload.getString("business_date")}|" +
                    "${next.payload.getString("item_id")}|${next.payload.getString("kitchen")}"
                "purchase" -> "purchase|${next.payload.getString("business_date")}|" +
                    next.payload.getString("item_id")
                "daily_cash_received" -> "daily_cash_received|" +
                    next.payload.getString("business_date")
                "purchase_addition" -> "purchase_addition|" + next.cloudId
                else -> error("Unexpected V3 type")
            }
            val occupant = existing.firstOrNull()
            val linkedItem = if (next.row.entityType == "daily_cash_received") null
                else next.payload.getString("item_id")
            val decision = FreshOnlyUploadPolicy.evaluate(
                FreshSyncCandidate(
                    entityType = next.row.entityType,
                    cloudEntityId = next.cloudId,
                    action = next.row.action,
                    businessKey = key,
                    catalogItemCloudId = linkedItem,
                    createdAfterFreshEnrollment = origin == next.cloudId
                ),
                FreshSyncServerSnapshot(
                    ownerAuthenticated = true,
                    singleWriterHandoverVerified = true,
                    serverSnapshotVerified = true,
                    protectedLegacyEntityIds =
                        if (occupant != null && occupant != next.cloudId) setOf(occupant)
                        else emptySet(),
                    protectedLegacyBusinessKeys =
                        if (occupant != null && occupant != next.cloudId) setOf(key)
                        else emptySet(),
                    serverExistingIdByBusinessKey =
                        if (occupant == null) emptyMap() else mapOf(key to occupant),
                    verifiedCatalogItemIds =
                        if (linkedItem == null) emptySet() else setOf(linkedItem),
                    freshOwnedEntityIds =
                        if (origin == next.cloudId) setOf(next.cloudId) else emptySet()
                )
            )
            if (!decision.permitted) {
                throw CloudUploadRejected(decision.reason)
            }
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
