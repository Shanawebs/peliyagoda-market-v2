package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.DailyCashReceivedEntity
import lk.peliyagoda.marketpro.data.DailyOtherExpenseEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
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

    private val mergeable = setOf("purchase", "requisition", "daily_cash_received", "daily_other_expenses", "purchase_addition")

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
     * Additional purchase lots are NOT keyed by date+item: one vegetable
     * can have several separate prices/lots per day. A new Cloud lot is
     * imported only if the local date/item has NO unmapped historical lots.
     * Existing mapped lots require exact UUID/revision and no pending edit.
     *
     * Each decision, insertion, UUID link and revision state uses one Room
     * transaction. Legacy photos and other purchase lots are never changed.
     */
    private suspend fun mergeAdditionShadow(
        remoteUuid: String, trustedWorkspace: String
    ): Result = db.withTransaction {
        val type = "purchase_addition"
        val shadow = inbox.snapshot(type,remoteUuid)
            ?: return@withTransaction Result(DomainMergeDecision.BLOCK_UNTRUSTED,null)
        val row = JSONObject(shadow.canonicalJson)
        require(shadow.workspaceId == trustedWorkspace &&
            row.getString("workspace_id") == trustedWorkspace &&
            row.getString("id") == remoteUuid &&
            shadow.cloudId == remoteUuid && shadow.entityType == type &&
            row.getLong("revision") == shadow.revision && shadow.revision > 0L) {
            "Untrusted purchase addition snapshot"
        }
        if(shadow.deletedAtUtc != null)
            return@withTransaction Result(
                DomainMergeDecision.REVIEW_REMOTE_TOMBSTONE,shadow.revision
            )
        val date=validDate(row.getString("business_date"))
        val cloudItemUuid=row.getString("item_id")
        require(CloudInboxPagePolicy.uuid(cloudItemUuid))
        val localItemKey=inbox.localKeyForCloudId("item",cloudItemUuid)
        val localItemId=localItemKey?.toLongOrNull()?.takeIf { it > 0L }
        val localItem=localItemId?.let { cloud.findItem(it) }
        if(localItem == null || cloud.cloudId("item",localItem.id.toString()) != cloudItemUuid)
            return@withTransaction Result(
                DomainMergeDecision.WAIT_FOR_EXACT_ITEM_UUID_MAPPING,shadow.revision
            )

        val qty=row.getLong("quantity_grams")
        val price=row.getLong("price_cents_per_kg")
        require(qty in 0L..1_000_000_000L &&
            price in 0L..1_000_000_000L)
        val remoteSignature="$date|${localItem.id}|$qty|$price"
        val mappedKey=inbox.localKeyForCloudId(type,remoteUuid)
        val mappedId=mappedKey?.toLongOrNull()?.takeIf { it > 0L }
        val old=mappedId?.let { cloud.findAddition(it) }
        if(mappedKey != null && (mappedId == null || old == null ||
                old.businessDate != date || old.itemId != localItem.id ||
                cloud.cloudId(type,mappedKey) != remoteUuid)) {
            return@withTransaction Result(
                DomainMergeDecision.CONFLICT_UUID_OR_BUSINESS_KEY,shadow.revision
            )
        }
        if(mappedKey == null && !PurchaseAdditionImportGate.canInsertNew(
                existingRemoteMapping=false,
                unboundLocalLotsForDateItem=inbox.unmappedAdditionCount(
                    date,localItem.id
                ),
                pendingLocalEditForRemoteUuid=
                    inbox.hasPendingLocalEditForCloudId(type,remoteUuid)>0
            )) return@withTransaction Result(
                DomainMergeDecision.REVIEW_UNBASELINED_LOCAL_ROW,shadow.revision
            )

        val lastRevisionKey="pull_applied_rev|$type|$remoteUuid"
        val lastHashKey="pull_applied_hash|$type|$remoteUuid"
        val lastRevisionRaw=cloud.state(lastRevisionKey)
        val lastRevision=lastRevisionRaw?.toLongOrNull()
        if(lastRevisionRaw != null && lastRevision == null)
            return@withTransaction Result(DomainMergeDecision.BLOCK_UNTRUSTED,shadow.revision)
        val oldSignature=old?.let {
            "${it.businessDate}|${it.itemId}|${it.quantityGrams}|${it.priceCentsPerKg}"
        }
        val previousHash=cloud.state(lastHashKey)
        val decision=DomainMergePolicy.evaluate(DomainMergeEvidence(
            workspaceMatches=true,
            recordIdentityIsValid=true,
            referencedItemUuidIsMapped=true,
            hasPendingLocalEdit=(mappedKey!=null &&
                inbox.pendingForLocalKey(type,mappedKey)>0) ||
                inbox.hasPendingLocalEditForCloudId(type,remoteUuid)>0,
            localBusinessKeyExists=old!=null,
            localBusinessKeyMapsToIncomingUuid=mappedKey!=null &&
                cloud.cloudId(type,mappedKey)==remoteUuid,
            incomingUuidMapsToAnotherBusinessKey=false,
            remoteTombstone=false,
            remoteRevision=shadow.revision,
            lastAppliedRevision=lastRevision,
            localContentsMatchRemote=oldSignature==remoteSignature,
            localContentsMatchLastApplied=oldSignature != null &&
                previousHash!=null && previousHash==fingerprint(oldSignature)
        ))
        if(decision !in setOf(
            DomainMergeDecision.INSERT_NEW,
            DomainMergeDecision.UPDATE_EXISTING,
            DomainMergeDecision.ACK_MATCHING_EXISTING
        )) return@withTransaction Result(decision,shadow.revision)
        if(decision==DomainMergeDecision.INSERT_NEW) {
            check(mappedKey==null) { "Mapped lot cannot be imported twice" }
            val localId=records.insertAddition(
                PurchaseAdditionEntity(
                    businessDate=date,itemId=localItem.id,
                    quantityGrams=qty,priceCentsPerKg=price
                )
            )
            check(localId > 0L &&
                cloud.insertIdentity(CloudIdentityEntity(
                    type,localId.toString(),remoteUuid
                ))>0L) { "Could not bind new purchase lot UUID" }
            check(inbox.localKeyForCloudId(type,remoteUuid)==localId.toString())
        } else if(decision==DomainMergeDecision.UPDATE_EXISTING) {
            val existing=requireNotNull(old)
            check(records.updateAddition(
                existing.id,date,localItem.id,qty,price
            )==1) { "Remote addition changed after verification" }
        }
        cloud.setState(CloudSyncStateEntity(
            lastRevisionKey,shadow.revision.toString()
        ))
        cloud.setState(CloudSyncStateEntity(
            lastHashKey,fingerprint(remoteSignature)
        ))
        Result(decision,shadow.revision)
    }

    /**
     * All reads, identity changes, local data changes and per-record checkpoints
     * use one Room transaction so a crash cannot half-apply a Cloud correction.
     * Returns an explicit blocked decision without changing visible data.
     */
    suspend fun mergeVerifiedShadow(
        type: String, remoteUuid: String, trustedWorkspace: String
    ): Result {
        require(type in mergeable) { "Catalog import is not activated" }
        require(CloudInboxPagePolicy.uuid(remoteUuid))
        require(trustedWorkspace == CloudSyncConfig.WORKSPACE_ID)
        if(type == "purchase_addition") {
            return mergeAdditionShadow(remoteUuid,trustedWorkspace)
        }
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
                    localKey = CloudRecordKey.otherExpense(date)
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

            /*
             * Cash Received and Other Expenses have a server UNIQUE key of
             * (workspace_id, business_date). A newly linked phone can create a
             * provisional local UUID before it learns that the other approved
             * phone already owns the canonical Cloud UUID for that date.
             *
             * Never overwrite the pending local value. If and only if that old
             * UUID is provably local-only and never ACKed/pulled, atomically
             * rebind the local business key to the canonical remote UUID and
             * baseline the remote revision. The uploader can then perform an
             * ordinary revision-safe UPDATE instead of a duplicate INSERT.
             */
            if (mappedCloud != null && mappedCloud != remoteUuid &&
                type in setOf("daily_cash_received","daily_other_expenses")
            ) {
                val oldPullRaw = cloud.state("pull_applied_rev|$type|$mappedCloud")
                val oldAckRaw = cloud.state("staff_last_ack_rev|$type|$mappedCloud")
                val oldPull = oldPullRaw?.toLongOrNull()
                val oldAck = oldAckRaw?.toLongOrNull()
                if ((oldPullRaw != null && (oldPull == null || oldPull <= 0L)) ||
                    (oldAckRaw != null && (oldAck == null || oldAck <= 0L))
                ) {
                    return@withTransaction Result(
                        DomainMergeDecision.BLOCK_UNTRUSTED,shadow.revision
                    )
                }
                val staffOrigin =
                    cloud.state("staff_fresh_origin|$type|$mappedCloud") == mappedCloud
                val attachOrigin =
                    cloud.state(FreshAttachState.originKey(type,localKey)) == mappedCloud
                val canAdopt = DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
                    DailySingletonIdentityEvidence(
                        type=type,
                        localRowExists=localSignature != null,
                        hasPendingLocalEdit=hasPending,
                        remoteTombstone=remoteTombstone,
                        localKey=localKey,
                        mappedCloudId=mappedCloud,
                        remoteCloudId=remoteUuid,
                        reverseLocalKey=reverseKey,
                        provisionalOriginMatches=staffOrigin || attachOrigin,
                        oldMappedPullRevision=oldPull,
                        oldMappedAckRevision=oldAck
                    )
                )
                if (canAdopt) {
                    check(cloud.rebindIdentity(
                        type=type,
                        key=localKey,
                        expectedOldCloudId=mappedCloud,
                        newCloudId=remoteUuid
                    ) == 1) { "Canonical daily UUID rebind lost a race" }
                    check(cloud.cloudId(type,localKey) == remoteUuid &&
                        inbox.localKeyForCloudId(type,remoteUuid) == localKey) {
                        "Canonical daily UUID rebind did not verify"
                    }
                    cloud.setState(CloudSyncStateEntity(
                        lastRevisionKey,shadow.revision.toString()
                    ))
                    cloud.setState(CloudSyncStateEntity(
                        lastSignatureKey,fingerprint(remoteSignature)
                    ))
                    return@withTransaction Result(
                        DomainMergeDecision.CONFLICT_LOCAL_PENDING,shadow.revision
                    )
                }
            }

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
