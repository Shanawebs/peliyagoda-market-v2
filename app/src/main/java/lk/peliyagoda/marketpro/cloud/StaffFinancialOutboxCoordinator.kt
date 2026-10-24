package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.room.withTransaction
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A proof-bound financial RPC has to atomically redeem a distinct
 * AndroidKeyStore-signed nonce within the financial transaction.
 * The V375 proof "verified once" response alone is NOT a write grant.
 *
 * All implementations MUST use this reviewed server channel: never send
 * Staff writes through legacy Owner marketpro_apply_change and never put
 * the Supabase service-role key on Android.
 */
internal fun interface StaffProofBoundWriteTransport {
    suspend fun submit(
        session: TeamPullSession,
        plan: StaffOutboxUploadPolicy.Plan,
        payload: JSONObject
    ): StaffServerWriteReceipt
}

internal data class StaffServerWriteReceipt(
    val accepted:Boolean,
    val echoedOperationUuid:String,
    val revision:Long?
)

internal object UnavailableStaffWriteTransport:StaffProofBoundWriteTransport {
    override suspend fun submit(
        session:TeamPullSession,
        plan:StaffOutboxUploadPolicy.Plan,
        payload:JSONObject
    ):StaffServerWriteReceipt {
        throw IOException("Atomic signed device-proof Staff finance RPC not deployed")
    }
}

/**
 * Durable offline→cloud Staff finance coordinator.
 *
 * It is NOT currently registered from UI or WorkManager. Both independent
 * server gates are false in shipped preview. Its network transport refuses
 * all writes until a reviewed proof-bound backend implementation exists.
 *
 * Reads a local queue snapshot inside Room; posts ONLY the typed financial
 * envelope outside Room; stores a verified server revision, and deletes the
 * pending row ONLY if its original UUID is still the latest edit.
 */
internal class StaffFinancialOutboxCoordinator(
    context:Context,
    private val transport:StaffProofBoundWriteTransport=UnavailableStaffWriteTransport,
    private val sessionProvider:TeamPullSessionProvider =
        VerifiedStaffPullSessionProvider(context)
) {
    private val app=context.applicationContext
    private val db=MarketDatabase.get(app)
    private val journal=db.cloudDao()
    private val local=db.dao()
    private val inbox=db.inboxDao()
    private val mutex=Mutex()

    data class DeliverySummary(
        val uploaded:Int,
        val pending:Int,
        val blocked:Int,
        val requiresOwnerReview:Boolean
    )

    private data class Prepared(
        val outbox:CloudOutboxEntity,
        val plan:StaffOutboxUploadPolicy.Plan,
        val payload:JSONObject
    )

    private fun releaseReady():Boolean =
        StaffOutboxUploadPolicy.releaseReady(
            preview=BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            staffLogin=BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            staffPull=BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            financeRpcs=BuildConfig.MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED,
            deviceProofEnforced=BuildConfig.MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED,
            staffOutboxEnabled=BuildConfig.MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD
        )

    private fun split(key:String,expected:Int):List<String> {
        val parts=key.split("|")
        require(parts.size==expected)
        return parts
    }

    private suspend fun itemUuid(localId:Long):String =
        journal.cloudId("item",localId.toString())
            ?: throw IllegalArgumentException("Cloud item UUID is not approved")

    private suspend fun payload(row:CloudOutboxEntity):Pair<String,JSONObject> =
        when(row.entityType) {
            "purchase" -> {
                val key=split(row.entityKey,2)
                val localId=key[1].toLong()
                val v=journal.findPurchase(key[0],localId)
                    ?: throw IllegalArgumentException("Pending purchase not found")
                val uuid=itemUuid(localId)
                v.businessDate to JSONObject()
                    .put("business_date",v.businessDate)
                    .put("item_id",uuid)
                    .put("quantity_grams",v.quantityGrams)
                    .put("price_cents_per_kg",v.priceCentsPerKg)
            }
            "requisition" -> {
                val key=split(row.entityKey,3)
                val id=key[2].toLong()
                val v=journal.findRequisition(key[0],key[1],id)
                    ?: throw IllegalArgumentException("Pending requisition not found")
                val uuid=itemUuid(id)
                v.businessDate to JSONObject()
                    .put("business_date",v.businessDate)
                    .put("item_id",uuid).put("kitchen",v.kitchen)
                    .put("quantity_grams",v.quantityGrams)
            }
            "purchase_addition" -> {
                val v=journal.findAddition(row.entityKey.toLong())
                    ?: throw IllegalArgumentException("Pending purchase lot not found")
                val uuid=itemUuid(v.itemId)
                v.businessDate to JSONObject()
                    .put("business_date",v.businessDate)
                    .put("item_id",uuid).put("quantity_grams",v.quantityGrams)
                    .put("price_cents_per_kg",v.priceCentsPerKg)
            }
            "daily_cash_received" -> {
                val v=journal.findCash(row.entityKey)
                    ?: throw IllegalArgumentException("Pending cash not found")
                v.businessDate to JSONObject()
                    .put("business_date",v.businessDate)
                    .put("amount_cents",v.amountCents)
            }
            "daily_other_expenses" -> {
                val v=local.otherExpensesForDate(row.entityKey)
                    ?: throw IllegalArgumentException("Pending other expenses not found")
                OtherExpensesShadowPolicy.validateAmounts(
                    v.transportCents,v.laborChargeCents,
                    v.mealsDrinksCents,v.othersCents,v.remarks
                )
                v.businessDate to JSONObject()
                    .put("business_date",v.businessDate)
                    .put("transport_cents",v.transportCents)
                    .put("labor_charge_cents",v.laborChargeCents)
                    .put("meals_drinks_cents",v.mealsDrinksCents)
                    .put("others_cents",v.othersCents)
                    .put("remarks",v.remarks)
            }
            else -> throw IllegalArgumentException("Catalog/deletion not writable by Staff")
        }

    private suspend fun prepare(
        row:CloudOutboxEntity,
        session:TeamPullSession,
        enrolledDevice:String
    ):Prepared = db.withTransaction {
        // A changed row gets a NEW operationId and must be retried anew.
        val stillCurrent=journal.pendingStaffFinancial(100).any {
            it.entityType==row.entityType && it.entityKey==row.entityKey &&
                it.operationId==row.operationId
        }
        require(stillCurrent) { "Financial outbox changed while preparing" }
        val id=journal.cloudId(row.entityType,row.entityKey)
            ?: throw IllegalArgumentException("Unmapped Staff financial UUID")
        val (date,body)=payload(row)
        val itemId=when(row.entityType) {
            "purchase","requisition" ->
                body.getString("item_id")
            "purchase_addition" -> body.getString("item_id")
            else -> null
        }
        val cursor=inbox.cursor(row.entityType)
            ?: throw IllegalArgumentException("Must fetch authenticated server date first")
        val age=System.currentTimeMillis()-cursor.lastCheckpointMs
        val snapshot=inbox.snapshot(row.entityType,id)
        require(snapshot==null ||
            snapshot.workspaceId==CloudSyncConfig.WORKSPACE_ID) {
            "Incoming Cloud record has the wrong workspace"
        }
        fun revision(prefix:String):Long? {
            val value=journal.state("$prefix|${row.entityType}|$id")
            val n=value?.toLongOrNull()
            if(value!=null)require(n!=null && n>0L)
            return n
        }
        val base=revision("pull_applied_rev")
        val ack=revision("staff_last_ack_rev")
        val origin=journal.state(
            "staff_fresh_origin|${row.entityType}|$id")==id
        val plan=StaffOutboxUploadPolicy.prepare(
            StaffOutboxUploadPolicy.Candidate(
                type=row.entityType,localKey=row.entityKey,cloudUuid=id,
                operationUuid=row.operationId,pendingAction=row.action,
                date=date,userUuid=session.authenticatedUserId,
                ownerUuid=CloudSyncConfig.OWNER_USER_ID,
                physicalDeviceUuid=enrolledDevice,
                enrolledDeviceUuid=session.enrolledDeviceId,
                cloudItemUuid=itemId,observedRevision=snapshot?.revision,
                lastLocallyAppliedRevision=base,lastAckRevision=ack,
                verifiedFreshCreation=origin,
                serverBusinessToday=cursor.lastServerToday.orEmpty(),
                lastAuthenticatedPullAgeMillis=age,
                signedInStaff=session.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID,
                enrolledAndApprovedWriter=true,
                pendingOperationUnchanged=stillCurrent,
                hasRemoteBusinessKeyCollision=false
            )
        )
        Prepared(row,plan,body)
    }

    suspend fun deliverBatch(max:Int=20):DeliverySummary = mutex.withLock {
        require(max in 1..25) { "Small fixed-size Staff batches protect 4GB phones" }
        check(releaseReady()) { "Staff Cloud uploading is OFF until audited rollout" }
        check(journal.state("staff_outbox_verified_v1")=="true") {
            "Staff finance workspace and catalog have not been reconciled"
        }
        val session=sessionProvider.currentSession()
        val phone=CloudDeviceIdentity.get(app)
        require(session.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
            session.enrolledDeviceId==phone &&
            session.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID) {
            "Dedicated Staff identity and registered physical phone required"
        }

        val pending=journal.pendingStaffFinancial(max)
        var done=0
        var held=0
        for(row in pending) {
            // Preview/API proof-blocked rows are left untouched.
            val prepared=try {
                prepare(row,session,phone)
            } catch (_:IllegalArgumentException) {
                held++
                continue
            }
            // This is the ONLY network call. A proof-bound transport must
            // enforce fresh JWT/device/key and atomically consume its nonce.
            val receipt=transport.submit(session,prepared.plan,prepared.payload)
            if(!receipt.accepted ||
                receipt.echoedOperationUuid!=prepared.plan.operationUuid ||
                receipt.revision !=
                (prepared.plan.expectedRevision?.plus(1L) ?: 1L)) {
                held++
                continue
            }
            db.withTransaction {
                // Even if a newer local edit arrived while HTTP was in flight,
                // advance the confirmed server baseline but never delete that edit.
                val old=journal.state(
                    "staff_last_ack_rev|${row.entityType}|${prepared.plan.cloudUuid}"
                )?.toLongOrNull() ?: 0L
                requireNotNull(receipt.revision)
                check(receipt.revision>=old)
                journal.setState(CloudSyncStateEntity(
                    "staff_last_ack_rev|${row.entityType}|${prepared.plan.cloudUuid}",
                    receipt.revision.toString()
                ))
                journal.acknowledge(row.entityType,row.entityKey,row.operationId)
            }
            done++
        }
        val left=journal.pendingStaffFinancial(100).size
        DeliverySummary(done,left,held,held>0)
    }
}
