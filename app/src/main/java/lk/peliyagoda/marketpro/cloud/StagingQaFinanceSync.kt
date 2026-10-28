package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject

/**
 * Staging-only direct financial writer.
 *
 * This deliberately does NOT pretend that the production AndroidKeyStore
 * proof-bound route is deployed. The build flag is true only in stagingMarket.
 * Production keeps this route compiled but unreachable.
 */
internal class StagingQaFinanceHttpTransport {
    companion object {
        private const val MAX_BYTES = 16 * 1024
    }

    data class Receipt(
        val accepted: Boolean,
        val operationId: String,
        val revision: Long
    )

    private fun boundedRead(connection: HttpURLConnection): String {
        val stream = connection.inputStream
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (out.size() + read > MAX_BYTES) {
                    throw IOException("Cloud acknowledgement is too large")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    suspend fun submit(
        session: TeamPullSession,
        deviceId: String,
        row: CloudOutboxEntity,
        cloudId: String,
        expectedRevision: Long,
        payload: JSONObject
    ): Receipt = withContext(Dispatchers.IO) {
        check(
            !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
                BuildConfig.MARKETPRO_STAGING_QA_FINANCE_RPC
        ) {
            "Staging finance route is disabled"
        }
        require(session.workspaceId == CloudSyncConfig.WORKSPACE_ID)
        require(session.enrolledDeviceId == deviceId)
        require(CloudInboxPagePolicy.uuid(deviceId))
        require(CloudInboxPagePolicy.uuid(cloudId))
        require(CloudInboxPagePolicy.uuid(row.operationId))
        require(expectedRevision in 0L..2_147_483_647L)
        require(row.action == "upsert" ||
            (row.action == "delete" && row.entityType == "requisition" &&
                BuildConfig.MARKETPRO_REQUISITION_DELETE_RPC))
        if (row.action == "delete") require(expectedRevision > 0L)

        val base = URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(
            base.protocol == "https" &&
                base.host == BuildConfig.MARKETPRO_SUPABASE_HOST &&
                (base.port == -1 || base.port == 443) &&
                (base.path.isEmpty() || base.path == "/") &&
                base.query == null &&
                base.ref == null &&
                base.userInfo == null
        ) {
            "Untrusted Cloud host"
        }
        require(!session.accessToken.contains('\r') && !session.accessToken.contains('\n'))

        val body = JSONObject()
            .put("p_workspace_id", CloudSyncConfig.WORKSPACE_ID)
            .put("p_device_id", deviceId)
            .put("p_operation_id", row.operationId)
            .put("p_entity_type", row.entityType)
            .put("p_entity_id", cloudId)
            .put("p_expected_revision", expectedRevision)
            .put("p_payload", payload)

        val rpc = if (row.action == "delete")
            "/rest/v1/rpc/marketpro_qa_requisition_delete_v421"
            else "/rest/v1/rpc/marketpro_qa_finance_apply_v1"
        val target = URL(base, rpc)
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + session.accessToken)
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            when (status) {
                in 200..299 -> {
                    val json = JSONObject(boundedRead(connection))
                    val revision = json.optLong("revision", -1L)
                    if (!json.optBoolean("accepted") ||
                        json.optString("operation_id") != row.operationId ||
                        revision != expectedRevision + 1L
                    ) {
                        throw IOException("Cloud acknowledgement could not be verified")
                    }
                    Receipt(true, row.operationId, revision)
                }
                400, 409, 422 ->
                    throw StagingQaHeld("Cloud record changed or request needs review")
                401, 403 ->
                    throw CloudUploadRejected("Cloud login or linked phone is no longer authorized")
                404 ->
                    throw CloudUploadRejected("Staging finance API is not deployed")
                408, 429, 500, 502, 503, 504 ->
                    throw IOException("Cloud temporarily unavailable")
                else ->
                    throw IOException("Cloud finance request failed (HTTP " + status + ")")
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal class StagingQaHeld(message: String) : Exception(message)

/** Pure local guard. The server repeats every authorization rule transactionally. */
internal object StagingQaWritePolicy {
    data class Decision(val expectedRevision: Long)

    fun decide(
        role: TeamRole,
        businessDate: String,
        serverToday: String,
        pullAgeMs: Long,
        remoteRevision: Long?,
        lastAppliedRevision: Long?,
        lastAckRevision: Long?,
        freshCreation: Boolean
    ): Decision {
        val day = LocalDate.parse(businessDate)
        val today = LocalDate.parse(serverToday)
        require(day.toString() == businessDate && today.toString() == serverToday)
        require(pullAgeMs in 0L..60_000L) {
            "Fresh authenticated Cloud clock required"
        }
        when (role) {
            TeamRole.OWNER -> require(!day.isAfter(today)) {
                "Owner cannot write a future business date"
            }
            TeamRole.PURCHASING_STAFF -> require(day == today) {
                "Team can upload only the current Colombo business date"
            }
            TeamRole.VIEWER -> error("Viewer cannot upload financial records")
        }

        val base = lastAckRevision ?: lastAppliedRevision
        if (remoteRevision != null) {
            require(base != null) {
                "Existing Cloud row has no trusted local revision baseline"
            }
            require(remoteRevision <= base) {
                "A newer Cloud revision must be merged before correction"
            }
        }
        if (base == null) {
            require(remoteRevision == null && freshCreation) {
                "Only a fresh post-activation row may be created"
            }
            return Decision(0L)
        }
        require(base > 0L) { "Invalid Cloud revision baseline" }
        return Decision(base)
    }
}

/**
 * Reads the durable Room outbox, sends only allowlisted financial payloads,
 * and removes an operation only after a verified server ACK.
 *
 * Owner and Team use this staging coordinator. The server remains the
 * authority for role, Auth session, device revocation and Colombo date.
 */
internal class StagingQaFinancialOutboxCoordinator(
    context: Context,
    private val sessionProvider: TeamPullSessionProvider =
        OneMarketProPullSessionProvider(context)
) {
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val dao = db.dao()
    private val cloud = db.cloudDao()
    private val inbox = db.inboxDao()
    private val transport = StagingQaFinanceHttpTransport()

    data class Summary(
        val uploaded: Int,
        val pending: Int,
        val held: Int
    )

    private fun split(key: String, expected: Int): List<String> =
        key.split("|").also { require(it.size == expected) }

    private suspend fun itemUuid(localId: Long): String =
        cloud.cloudId("item", localId.toString())
            ?: throw StagingQaHeld("Item is not linked to an approved Cloud UUID")

    /**
     * After the server ACKs a singleton daily row, local contents are exactly
     * the accepted Cloud contents. Persist that revision/hash as the new pull
     * baseline so the next remote edit can be merged safely instead of being
     * mistaken for an unbaselined conflict.
     */
    private fun singletonAckHash(type: String, body: JSONObject): String? {
        val signature = when (type) {
            "daily_cash_received" ->
                body.getString("business_date") + "|" + body.getLong("amount_cents")
            "daily_other_expenses" ->
                OtherExpensesShadowPolicy.signature(
                    body.getString("business_date"),
                    body.getLong("transport_cents"),
                    body.getLong("labor_charge_cents"),
                    body.getLong("meals_drinks_cents"),
                    body.getLong("others_cents"),
                    body.getString("remarks")
                )
            else -> return null
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun payload(row: CloudOutboxEntity): Pair<String, JSONObject> =
        if (row.entityType == "requisition" && row.action == "delete") {
            // Removed Room row is gone; reconstruct the trusted business key.
            val key = split(row.entityKey, 3)
            require(key[1] in lk.peliyagoda.marketpro.data.MarketConstants.kitchens)
            key[0] to JSONObject()
                .put("business_date", key[0])
                .put("kitchen", key[1])
                .put("item_id", itemUuid(key[2].toLong()))
        } else when (row.entityType) {
            "purchase" -> {
                val key = split(row.entityKey, 2)
                val itemId = key[1].toLong()
                val value = cloud.findPurchase(key[0], itemId)
                    ?: throw StagingQaHeld("Pending purchase no longer exists")
                value.businessDate to JSONObject()
                    .put("business_date", value.businessDate)
                    .put("item_id", itemUuid(itemId))
                    .put("quantity_grams", value.quantityGrams)
                    .put("price_cents_per_kg", value.priceCentsPerKg)
            }
            "requisition" -> {
                val key = split(row.entityKey, 3)
                val itemId = key[2].toLong()
                val value = cloud.findRequisition(key[0], key[1], itemId)
                    ?: throw StagingQaHeld("Pending requisition no longer exists")
                value.businessDate to JSONObject()
                    .put("business_date", value.businessDate)
                    .put("item_id", itemUuid(itemId))
                    .put("kitchen", value.kitchen)
                    .put("quantity_grams", value.quantityGrams)
            }
            "purchase_addition" -> {
                val value = cloud.findAddition(row.entityKey.toLong())
                    ?: throw StagingQaHeld("Pending purchase lot no longer exists")
                value.businessDate to JSONObject()
                    .put("business_date", value.businessDate)
                    .put("item_id", itemUuid(value.itemId))
                    .put("quantity_grams", value.quantityGrams)
                    .put("price_cents_per_kg", value.priceCentsPerKg)
            }
            "daily_cash_received" -> {
                val value = cloud.findCash(row.entityKey)
                    ?: throw StagingQaHeld("Pending cash record no longer exists")
                value.businessDate to JSONObject()
                    .put("business_date", value.businessDate)
                    .put("amount_cents", value.amountCents)
            }
            "daily_other_expenses" -> {
                val value = dao.otherExpensesForDate(row.entityKey)
                    ?: throw StagingQaHeld("Pending other-expense record no longer exists")
                OtherExpensesShadowPolicy.validateAmounts(
                    value.transportCents,
                    value.laborChargeCents,
                    value.mealsDrinksCents,
                    value.othersCents,
                    value.remarks
                )
                value.businessDate to JSONObject()
                    .put("business_date", value.businessDate)
                    .put("transport_cents", value.transportCents)
                    .put("labor_charge_cents", value.laborChargeCents)
                    .put("meals_drinks_cents", value.mealsDrinksCents)
                    .put("others_cents", value.othersCents)
                    .put("remarks", value.remarks)
            }
            else -> throw StagingQaHeld("Catalog/delete operation is not a staging finance write")
        }

    suspend fun deliverBatch(max: Int = 50): Summary {
        require(max in 1..100)
        check(StagingTwoPhoneSyncScheduler.enabled()) {
            "Two-phone staging upload is disabled"
        }
        check(cloud.state(StagingCatalogBinder.READY) == "true" &&
            cloud.state(StagingCatalogBinder.VERIFIED) == "true") {
            "Catalog UUID mapping has not been activated"
        }

        val session = sessionProvider.currentSession()
        val role = MarketProActiveRole.current(app)
            ?: throw CloudUploadRejected("Sign in as Owner or Team")
        val deviceId = CloudDeviceIdentity.get(app)
        require(
            session.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
                session.enrolledDeviceId == deviceId
        ) {
            "Cloud session does not belong to this physical phone"
        }
        if (role == TeamRole.OWNER) {
            require(session.authenticatedUserId == CloudSyncConfig.OWNER_USER_ID)
        } else {
            require(role == TeamRole.PURCHASING_STAFF &&
                session.authenticatedUserId != CloudSyncConfig.OWNER_USER_ID)
        }

        val pending = cloud.pendingStaffFinancial(max)
        if (pending.isEmpty()) return Summary(0, 0, 0)

        var uploaded = 0
        var held = 0
        for (row in pending) {
            try {
                if (row.action != "upsert" &&
                    !(row.action == "delete" && row.entityType == "requisition" &&
                        BuildConfig.MARKETPRO_REQUISITION_DELETE_RPC)) {
                    throw StagingQaHeld("Financial delete type is not supported")
                }
                val cloudId = cloud.cloudId(row.entityType, row.entityKey)
                    ?: throw StagingQaHeld("Financial row has no durable Cloud UUID")
                UUID.fromString(cloudId)
                val pair = payload(row)
                val businessDate = pair.first
                val body = pair.second

                val cursor = inbox.cursor(row.entityType)
                    ?: throw StagingQaHeld("Authenticated Cloud clock has not been fetched")
                val serverToday = cursor.lastServerToday
                    ?: throw StagingQaHeld("Cloud did not provide Colombo business date")
                val age = System.currentTimeMillis() - cursor.lastCheckpointMs
                val snapshot = inbox.snapshot(row.entityType, cloudId)

                suspend fun revision(prefix: String): Long? {
                    val raw = cloud.state(prefix + "|" + row.entityType + "|" + cloudId)
                    val parsed = raw?.toLongOrNull()
                    if (raw != null) require(parsed != null && parsed > 0L)
                    return parsed
                }

                val decision = StagingQaWritePolicy.decide(
                    role = role,
                    businessDate = businessDate,
                    serverToday = serverToday,
                    pullAgeMs = age,
                    remoteRevision = snapshot?.revision,
                    lastAppliedRevision = revision("pull_applied_rev"),
                    lastAckRevision = revision("staff_last_ack_rev"),
                    freshCreation = cloud.state(
                        "staff_fresh_origin|" + row.entityType + "|" + cloudId
                    ) == cloudId
                )

                // Recheck exact operation identity immediately before HTTP.
                val stillCurrent = cloud.pendingStaffFinancial(100).any {
                    it.entityType == row.entityType &&
                        it.entityKey == row.entityKey &&
                        it.operationId == row.operationId
                }
                if (!stillCurrent) throw StagingQaHeld("A newer local edit replaced this operation")

                val receipt = transport.submit(
                    session = session,
                    deviceId = deviceId,
                    row = row,
                    cloudId = cloudId,
                    expectedRevision = decision.expectedRevision,
                    payload = body
                )

                db.withTransaction {
                    val old = cloud.state(
                        "staff_last_ack_rev|" + row.entityType + "|" + cloudId
                    )?.toLongOrNull() ?: 0L
                    check(receipt.revision >= old)
                    cloud.setState(
                        CloudSyncStateEntity(
                            "staff_last_ack_rev|" + row.entityType + "|" + cloudId,
                            receipt.revision.toString()
                        )
                    )
                    if (row.entityType == "requisition" && row.action == "delete") {
                        // Persist tombstone state atomically with conditional ACK.
                        cloud.setState(CloudSyncStateEntity(
                            "pull_applied_rev|requisition|$cloudId",
                            receipt.revision.toString()))
                        cloud.setState(CloudSyncStateEntity(
                            "tombstone_applied|requisition|$cloudId",
                            receipt.revision.toString()))
                    }
                    singletonAckHash(row.entityType, body)?.let { acceptedHash ->
                        cloud.setState(
                            CloudSyncStateEntity(
                                "pull_applied_rev|" + row.entityType + "|" + cloudId,
                                receipt.revision.toString()
                            )
                        )
                        cloud.setState(
                            CloudSyncStateEntity(
                                "pull_applied_hash|" + row.entityType + "|" + cloudId,
                                acceptedHash
                            )
                        )
                    }
                    // Conditional ACK cannot erase a newer edit created during HTTP.
                    cloud.acknowledge(row.entityType, row.entityKey, row.operationId)
                }
                uploaded++
            } catch (_: StagingQaHeld) {
                held++
            } catch (_: IllegalArgumentException) {
                held++
            }
        }

        return Summary(
            uploaded = uploaded,
            pending = cloud.pendingStaffFinancial(100).size,
            held = held
        )
    }
}

/**
 * Full staging convergence loop:
 * 1) pull + safe merge,
 * 2) upload durable local outbox,
 * 3) pull + safe merge the server ACK/revision,
 * 4) leave conflicts/pending edits intact for the next explicit decision.
 */
internal class StagingTwoPhoneSyncCoordinator(context: Context) {
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val cloud = db.cloudDao()
    private val sessionProvider = OneMarketProPullSessionProvider(app)

    data class Report(
        val uploaded: Int,
        val pending: Int,
        val held: Int,
        val pulledReviewed: Int,
        val pulledMerged: Int
    )

    data class Activation(
        val categories: Int,
        val items: Int,
        val archivedCloudItemsProtected: Int,
        val firstSync: Report
    )

    companion object {
        private val gate = Mutex()
        private val financeTypes = listOf(
            "purchase",
            "purchase_addition",
            "requisition",
            "daily_cash_received",
            "daily_other_expenses"
        )
    }

    private suspend fun pullAndMerge(): Pair<Int, Int> {
        val coordinator = TeamInboxDownloadCoordinator(app, sessionProvider)
        var reviewed = 0
        var merged = 0
        for (type in financeTypes) {
            val result = coordinator.stageAndMergeFinancialType(type, maxReviewRows = 200)
            check(!result.cloudHasMore && !result.reviewLimitHit) {
                "Cloud financial page exceeds safe staging merge window"
            }
            reviewed += result.rowsReviewed
            merged += result.rowsMergedOrBaselined
        }
        return reviewed to merged
    }

    suspend fun activateFreshPhone(): Activation = gate.withLock {
        val receipt = StagingCatalogBinder(app).activateFreshPhone()
        StagingTwoPhoneSyncScheduler.schedule(app)
        val first = syncInternal()
        Activation(
            categories = receipt.localCategories,
            items = receipt.localItems,
            archivedCloudItemsProtected = receipt.protectedArchivedCloudItems,
            firstSync = first
        )
    }

    suspend fun isReady(): Boolean = StagingCatalogBinder(app).isReady()

    suspend fun syncNow(): Report = gate.withLock {
        syncInternal()
    }

    suspend fun pullOnly(): Report = gate.withLock {
        check(StagingTwoPhoneSyncScheduler.enabled() && isReady()) {
            "Two-phone sync is not active"
        }
        val pulled = pullAndMerge()
        cloud.setState(
            CloudSyncStateEntity(
                CloudSyncConfig.LAST_SUCCESS,
                Instant.now().toString()
            )
        )
        Report(
            uploaded = 0,
            pending = cloud.pendingStaffFinancial(100).size,
            held = 0,
            pulledReviewed = pulled.first,
            pulledMerged = pulled.second
        )
    }

    private suspend fun syncInternal(): Report {
        check(StagingTwoPhoneSyncScheduler.enabled() && isReady()) {
            "Two-phone sync is not active"
        }

        // Fresh pull supplies server Colombo date and latest remote revisions.
        val before = pullAndMerge()
        val upload = StagingQaFinancialOutboxCoordinator(app, sessionProvider)
            .deliverBatch(max = 50)
        val after = pullAndMerge()

        cloud.setState(
            CloudSyncStateEntity(
                CloudSyncConfig.LAST_SUCCESS,
                Instant.now().toString()
            )
        )
        cloud.setState(CloudSyncStateEntity(CloudSyncConfig.LAST_ERROR, ""))

        return Report(
            uploaded = upload.uploaded,
            pending = upload.pending,
            held = upload.held,
            pulledReviewed = before.first + after.first,
            pulledMerged = before.second + after.second
        )
    }
}
