package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import lk.peliyagoda.marketpro.security.StaffDeviceProofPolicy
import java.util.UUID

/**
 * Protocol-only boundary, independent from Android network and KeyStore.
 * JWT claims decoded on the phone are just a consistency check; ONLY the
 * authenticated Edge Function/PG Auth session can authorize a Staff writer.
 */
internal object StaffSignedFinancePolicy {
    private fun uuid(value:String):Boolean = runCatching {
        UUID.fromString(value).toString().equals(value,true)
    }.getOrDefault(false)
    private val fingerprintPattern=Regex("[0-9a-f]{64}")

    fun acceptServerChallenge(
        challenge:StaffDeviceChallenge,
        session:TeamPullSession,
        verifiedJwtSubject:String,
        verifiedJwtSessionId:String,
        localDeviceId:String,
        serverApprovedFingerprint:String,
        phoneKeyFingerprint:String,
        nowMillis:Long
    ) {
        require(challenge.purpose=="write" &&
            StaffDeviceProofPolicy.valid(challenge,nowMillis)) {
            "Invalid, expired or non-write Staff device challenge"
        }
        require(uuid(verifiedJwtSubject) && uuid(verifiedJwtSessionId) &&
            uuid(localDeviceId) && uuid(session.authenticatedUserId) &&
            uuid(session.enrolledDeviceId) && uuid(session.workspaceId)) {
            "Verified Staff session context is missing"
        }
        require(challenge.workspaceId==session.workspaceId &&
            challenge.staffUserId==session.authenticatedUserId &&
            challenge.staffUserId==verifiedJwtSubject &&
            challenge.sessionId==verifiedJwtSessionId &&
            challenge.deviceId==localDeviceId &&
            challenge.deviceId==session.enrolledDeviceId) {
            "Server challenge does not belong to this Staff session and phone"
        }
        require(fingerprintPattern.matches(serverApprovedFingerprint) &&
            fingerprintPattern.matches(phoneKeyFingerprint) &&
            serverApprovedFingerprint==phoneKeyFingerprint) {
            "Owner-approved phone signing key does not match; re-pair phone"
        }
    }

    fun receiptMatches(
        plan:StaffOutboxUploadPolicy.Plan,
        accepted:Boolean,
        operationId:String,
        revision:Long?,
        replayed:Boolean
    ):Boolean {
        // Only exact same operation and expected revision may be acknowledged.
        // Replay is an idempotent ACK, never a new financial event.
        return accepted && uuid(operationId) &&
            operationId==plan.operationUuid && revision!=null &&
            revision == if(plan.expectedRevision==null)1L
            else plan.expectedRevision+1L
    }
}
