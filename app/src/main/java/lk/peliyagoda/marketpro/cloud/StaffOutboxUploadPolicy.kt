package lk.peliyagoda.marketpro.cloud

import java.time.LocalDate
import java.util.UUID

/**
 * Pure fail-closed Staff outbox request gate.
 *
 * No current Android component invokes this automatically: v2 business RPCs
 * must first enforce Owner-approved device proof PER REQUEST, correct RLS,
 * current auth.sessions and Colombo business day. A local check cannot
 * provide the server's authorization, nor prove device possession.
 */
internal object StaffOutboxUploadPolicy {
    private val financial = setOf(
        "purchase","purchase_addition","requisition",
        "daily_cash_received","daily_other_expenses"
    )

    fun releaseReady(
        preview:Boolean,staffLogin:Boolean,staffPull:Boolean,
        financeRpcs:Boolean,deviceProofEnforced:Boolean,staffOutboxEnabled:Boolean
    ):Boolean = !preview && staffLogin && staffPull &&
        financeRpcs && deviceProofEnforced && staffOutboxEnabled

    data class Candidate(
        val type:String,
        val localKey:String,
        val cloudUuid:String,
        val operationUuid:String,
        val pendingAction:String,
        val date:String,
        val userUuid:String,
        val ownerUuid:String,
        val physicalDeviceUuid:String,
        val enrolledDeviceUuid:String,
        val cloudItemUuid:String?=null,
        /** Last approved server snapshot, never an Android-calculated revision. */
        val observedRevision:Long?=null,
        val lastLocallyAppliedRevision:Long?=null,
        val lastAckRevision:Long?=null,
        val verifiedFreshCreation:Boolean=false,
        val serverBusinessToday:String,
        val lastAuthenticatedPullAgeMillis:Long,
        val signedInStaff:Boolean,
        val enrolledAndApprovedWriter:Boolean,
        val pendingOperationUnchanged:Boolean,
        val hasRemoteBusinessKeyCollision:Boolean=false
    )

    data class Plan(
        val entityType:String,
        val cloudUuid:String,
        val operationUuid:String,
        val action:String,
        val expectedRevision:Long?,
        val businessDate:String
    )

    private fun uuid(v:String):Boolean = runCatching {
        UUID.fromString(v).toString().equals(v,true)
    }.getOrDefault(false)

    fun prepare(c:Candidate):Plan {
        require(c.type in financial) { "Only approved financial rows are uploadable" }
        require(c.pendingAction=="upsert") { "Staff delete and catalog actions are forbidden" }
        require(c.localKey.isNotBlank() && c.localKey.length<=120)
        require(listOf(c.cloudUuid,c.operationUuid,c.userUuid,c.ownerUuid,
            c.physicalDeviceUuid,c.enrolledDeviceUuid).all(::uuid))
        require(c.userUuid!=c.ownerUuid &&
            c.physicalDeviceUuid==c.enrolledDeviceUuid &&
            c.signedInStaff && c.enrolledAndApprovedWriter &&
            c.pendingOperationUnchanged) { "Active Staff identity and exact pending edit required" }
        if(c.type in setOf("purchase","purchase_addition","requisition")) {
            require(c.cloudItemUuid?.let(::uuid)==true) {
                "Reviewed Cloud item UUID link is required"
            }
        }
        val date=runCatching { LocalDate.parse(c.date) }.getOrNull()
        val today=runCatching { LocalDate.parse(c.serverBusinessToday) }.getOrNull()
        require(date!=null && today!=null &&
            date.toString()==c.date && today.toString()==c.serverBusinessToday &&
            date==today) { "Staff edits must be for the authenticated Colombo server day" }
        require(c.lastAuthenticatedPullAgeMillis in 0L..60_000L) {
            "Fresh authenticated server date/pull required before uploading"
        }
        require(!c.hasRemoteBusinessKeyCollision) { "Another Cloud record owns this key" }

        // An unbaselined historical room row must never be uploaded as "new".
        // Successful previous upload ACKs advance the local expected revision.
        val base=c.lastAckRevision ?: c.lastLocallyAppliedRevision
        if(c.observedRevision != null && base != null) {
            require(c.observedRevision <= base) {
                "A newer Cloud change exists; reconcile before correcting"
            }
        }
        val creating=base==null
        if(creating) {
            require(c.verifiedFreshCreation && c.observedRevision==null) {
                "Historical or unverified Cloud row cannot be created"
            }
        } else {
            require(base>=1L) {
                "A verified Cloud baseline is required before correction"
            }
        }
        return Plan(c.type,c.cloudUuid,c.operationUuid,
            if(creating)"create" else "correct",base,c.date)
    }

    fun serverAccepted(
        plan:Plan,echoOperation:String,accepted:Boolean,
        resultingRevision:Long?,acknowledgedSamePendingOperation:Boolean
    ):Boolean = accepted &&
        echoOperation==plan.operationUuid &&
        acknowledgedSamePendingOperation &&
        resultingRevision!=null &&
        resultingRevision==if(plan.expectedRevision==null) 1L
            else plan.expectedRevision+1L
}
