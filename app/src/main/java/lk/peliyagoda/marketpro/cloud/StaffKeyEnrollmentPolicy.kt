package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import lk.peliyagoda.marketpro.security.StaffDeviceProofPolicy

/** Pure, independently testable signing key enrollment admission rules. */
internal object StaffKeyEnrollmentPolicy {
    private val fingerprint=Regex("[0-9a-f]{64}")

    fun allowClient(
        localPreview:Boolean,staffLogin:Boolean,pairing:Boolean,keyRpc:Boolean
    ) = !localPreview && staffLogin && pairing && keyRpc

    fun checkChallenge(
        challenge:StaffDeviceChallenge,session:TeamPullSession,
        jwtSubject:String,jwtSessionId:String,physicalId:String,
        phoneFingerprint:String,serverFingerprint:String,
        nowMillis:Long
    ) {
        require(challenge.purpose=="pair" &&
            StaffDeviceProofPolicy.valid(challenge,nowMillis)) {
            "Invalid or expired key-pairing challenge"
        }
        require(session.workspaceId==challenge.workspaceId &&
            session.authenticatedUserId==challenge.staffUserId &&
            session.enrolledDeviceId==challenge.deviceId &&
            challenge.staffUserId==jwtSubject &&
            challenge.sessionId==jwtSessionId &&
            challenge.deviceId==physicalId) {
            "Pairing challenge is from another Staff login or phone"
        }
        require(fingerprint.matches(phoneFingerprint) &&
            phoneFingerprint==serverFingerprint) {
            "Supabase pending key does not match this Phone Keystore"
        }
    }

    fun ownerMayApprove(
        target:OwnerWriterTarget,expectedFingerprint:String,
        displayedPendingFingerprint:String,staffPhoneDisplayedFingerprint:String,
        confirmation:String,ownerChecked:Boolean
    ):Boolean = OwnerStaffWriterReviewPolicy.validTarget(target) &&
        fingerprint.matches(expectedFingerprint) &&
        displayedPendingFingerprint==expectedFingerprint &&
        staffPhoneDisplayedFingerprint==expectedFingerprint &&
        ownerChecked && confirmation=="APPROVE KEY"
}
