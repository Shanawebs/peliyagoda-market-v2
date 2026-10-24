package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.DailyCashReceivedEntity
import lk.peliyagoda.marketpro.data.DailyOtherExpenseEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/**
 * PHASE B (SOURCE ONLY): Staged canonical Cloud rows -> selected local Room
 * domain rows when exact UUID identity, local outbox and revision baselines agree.
 *
 * Not called by any Worker/UI. Cloud transport, user enrollment, catalog
 * bootstrap and server RPC v2 must be deployed & verified before integration.
 *
 * Supports purchase, requisition, daily cash and other expenses. Doesn't touch photo files,
 * delete any row, edit the catalog or enqueue a Cloud write.
 */
internal class CloudDomainMergeEngine(private val db: MarketDatabase) {
    private val inbox = db.inboxDao()
    private val cloud = db.cloudDao()
    private val records = db.dao()

    data class Result(val decision: DomainMergeDecision, val cloudRevision: Long?)

    private val mergeable = setOf("purchase", "requisition", "daily_cash_received", "daily_other_expenses")

    private fun fingerprint(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun validDate(input: String): String {
        require(LocalDate.parse(input).toString() == input)
        return input
    }

    /**
     * All reads, identity changes, local data changes and per-record checkpoints
     * use one Room transaction so a crash cannot half-apply a Cloud correction.
     * Returns an explicit blocked decision without changing visible data.
     */
    suspend fun mergeVerifiedShadow(
        type: String, remoteUuid: String, trustedWorkspace: String
    ): Result {
        require(type in mergeable) { "Catalog and addition-lot import are not activated" }
        require(CloudInboxPagePolicy.uuid(remoteUuid))
        require(trustedWorkspace == CloudSyncConfig.WORKSPACE_ID)
        return db.withTransaction {
            val shadow = inbox.snapshot(type, remoteUuid)
                ?: return@withTransaction Result(DomainMergeDecision.BLOCK_UNTRUSTED, null)
            val row = JSONObject(shadow.canonicalJson)
            require(shadow.workspaceId == trustedWorkspace &&
                row.getString("workspace_id") == trustedWorkspace &&
                shadow.cloudId == remoteUuid &&
                row.getString("id") == remoteUuid &&
                row.getLong("revision") == shadow.revision &&
                shadow.revision > 0 &&
                shadow.entityType == type) { "Untrusted staged Cloud row" }

            val date = validDate(row.getString("business_date"))
            val remoteTombstone = shadow.deletedAtUtc != null
            val itemId: Long?
            val localKey: String
            val remoteSignature: String
            val localSignature: String?
            val remoteQty: Long
            val remotePrice: Long
            val remoteKitchen: String?
            var otherExpenses: DailyOtherExpenseEntity? = null

            if (type != "daily_cash_received" && type != "daily_other_expenses") {
                val cloudItemId = row.getString("item_id")
                require(CloudInboxPagePolicy.uuid(cloudItemId))
                val localItemKey = inbox.localKeyForCloudId("item", cloudItemId)
                val candidateLocalId = localItemKey?.toLongOrNull()?.takeIf { it > 0L }
                val localItem = candidateLocalId?.let { cloud.findItem(it) }
                val reverse = candidateLocalId?.let {
                    cloud.cloudId("item", it.toString())
                }
                if (localItem == null || reverse != cloudItemId) {
                    return@withTransaction Result(
                        DomainMergeDecision.WAIT_FOR_EXACT_ITEM_UUID_MAPPING,
                        shadow.revision
                    )
                }
                itemId = localItem.id
            } else {
                itemId = null
            }

            when (type) {
                "purchase" -> {
                    remoteQty = row.getLong("quantity_grams")
                    remotePrice = row.getLong("price_cents_per_kg")
                    require(remoteQty in 0L..1_000_000_000L &&
                        remotePrice in 0L..1_000_000_000L)
                    remoteKitchen = null
                    localKey = CloudRecordKey.purchase(date, requireNotNull(itemId))
                    remoteSignature = "$date|$itemId|$remoteQty|$remotePrice"
                    val old = cloud.findPurchase(date, itemId)
                    localSignature = old?.let {
                        "${it.businessDate}|${it.itemId}|${it.quantityGrams}|${it.priceCentsPerKg}"
                    }
                }
                "requisition" -> {
                    remoteQty = row.getLong("quantity_grams")
                    require(remoteQty in 0L..1_000_000_000L)
                    remotePrice = 0L
                    remoteKitchen = row.getString("kitchen")
                    localKey = CloudRecordKey.requisition(
                        date,remoteKitchen,requireNotNull(itemId)
                    )
                    remoteSignature = "$date|$remoteKitchen|$itemId|$remoteQty"
                    val old = cloud.findRequisition(date,remoteKitchen,itemId)
                    localSignature = old?.let {
                        "${it.businessDate}|${it.kitchen}|${it.itemId}|${it.quantityGrams}"
                    }
                }
                "daily_cash_received" -> {
                    remoteQty = row.getLong("amount_cents")
                    require(remoteQty in 0L..9_999_999_999L)
                    remotePrice = 0L
                    remoteKitchen = null
                    localKey = CloudRecordKey.cash(date)
                    remoteSignature = "$date|$remoteQty"
                    val old = cloud.findCash(date)
                    localSignature = old?.let { "${it.businessDate}|${it.amountCents}" }
                }
                "daily_other_expenses" -> {
                    // Snapshot was previously validated by CloudInboxStager.
                    // Validate again because a local file/DB can be restored
                    // or modified independently between staging and merging.
                    val transport = row.getLong("transport_cents")
                    val labor = row.getLong("labor_charge_cents")
                    val meals = row.getLong("meals_drinks_cents")
                    val others = row.getLong("others_cents")
                    val remarks = row.getString("remarks")
                    OtherExpensesShadowPolicy.validateAmounts(
                        transport,labor,meals,others,remarks
                    )
                    require(row.get("remarks") is String)
                    remoteQty = 0L
                    remotePrice = 0L
                    remoteKitchen = null
                    localKey = CloudRecordKey.cash(date)
                    otherExpenses = DailyOtherExpenseEntity(
                        date,transport,labor,meals,others,remarks
                    )
                    remoteSignature = OtherExpensesShadowPolicy.signature(
                        date,transport,labor,meals,others,remarks
                    )
                    val old = records.otherExpensesForDate(date)
                    localSignature = old?.let {
                        OtherExpensesShadowPolicy.signature(
                            it.businessDate,it.transportCents,it.laborChargeCents,
                            it.mealsDrinksCents,it.othersCents,it.remarks
                        )
                    }
                }
                else -> error("Unreachable merge type")
            }

            val mappedCloud = cloud.cloudId(type,localKey)
            val reverseKey = inbox.localKeyForCloudId(type,remoteUuid)
            val lastRevisionKey = "pull_applied_rev|$type|$remoteUuid"
            val lastSignatureKey = "pull_applied_hash|$type|$remoteUuid"
            val oldRevisionRaw = cloud.state(lastRevisionKey)
            val oldRevision = oldRevisionRaw?.toLongOrNull()
            if (oldRevisionRaw != null && oldRevision == null) {
                return@withTransaction Result(DomainMergeDecision.BLOCK_UNTRUSTED,shadow.revision)
            }
            val existingHash = cloud.state(lastSignatureKey)
            val localMatchesPrevious = localSignature != null &&
                existingHash != null && existingHash == fingerprint(localSignature)
            val hasPending = inbox.pendingForLocalKey(type,localKey) > 0 ||
                inbox.hasPendingLocalEditForCloudId(type,remoteUuid) > 0
            val decision = DomainMergePolicy.evaluate(
                DomainMergeEvidence(
                    workspaceMatches=true,
                    recordIdentityIsValid=true,
                    referencedItemUuidIsMapped=true,
                    hasPendingLocalEdit=hasPending,
                    localBusinessKeyExists=localSignature != null,
                    localBusinessKeyMapsToIncomingUuid=mappedCloud == remoteUuid,
                    incomingUuidMapsToAnotherBusinessKey=
                        reverseKey != null && reverseKey != localKey ||
                        mappedCloud != null && mappedCloud != remoteUuid,
                    remoteTombstone=remoteTombstone,
                    remoteRevision=shadow.revision,
                    lastAppliedRevision=oldRevision,
                    localContentsMatchRemote=localSignature == remoteSignature,
                    localContentsMatchLastApplied=localMatchesPrevious
                )
            )
            if (decision !in setOf(
                DomainMergeDecision.INSERT_NEW,
                DomainMergeDecision.UPDATE_EXISTING,
                DomainMergeDecision.ACK_MATCHING_EXISTING
            )) return@withTransaction Result(decision,shadow.revision)

            if (decision != DomainMergeDecision.ACK_MATCHING_EXISTING) {
                // Only after exact UUID and local pending-edit checks passed.
                when (type) {
                    "purchase" -> records.savePurchase(
                        PurchaseEntity(date,requireNotNull(itemId),remoteQty,remotePrice)
                    )
                    "requisition" -> records.saveRequisition(
                        RequisitionEntity(date,requireNotNull(remoteKitchen),
                            requireNotNull(itemId),remoteQty)
                    )
                    "daily_cash_received" -> records.upsertCashReceived(
                        DailyCashReceivedEntity(date,remoteQty)
                    )
                    "daily_other_expenses" -> records.saveOtherExpenses(
                        requireNotNull(otherExpenses)
                    )
                }
            }
            if (mappedCloud == null) {
                cloud.insertIdentity(CloudIdentityEntity(type,localKey,remoteUuid))
                // Unique Cloud UUID belongs to EXACTLY one local business key.
                check(cloud.cloudId(type,localKey) == remoteUuid &&
                    inbox.localKeyForCloudId(type,remoteUuid) == localKey) {
                    "Identity collision; rolling back merge"
                }
            }
            // Commit local contents and revision/signature checkpoint atomically.
            cloud.setState(CloudSyncStateEntity(lastRevisionKey,shadow.revision.toString()))
            cloud.setState(CloudSyncStateEntity(lastSignatureKey,fingerprint(remoteSignature)))
            Result(decision,shadow.revision)
        }
    }
}
