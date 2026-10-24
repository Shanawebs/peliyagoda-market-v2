package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import lk.peliyagoda.marketpro.security.StaffDeviceProofPolicy

/** Signed per-page Staff reads. JWT claims here are context hints only;
 * current Auth session, role, device/key approval remain enforced by SQL.
 */
internal object StaffSignedReadPolicy {
    fun ready(preview:Boolean,pullEnabled:Boolean,staffLogin:Boolean,
              perPageProofEnabled:Boolean):Boolean =
        !preview && pullEnabled && staffLogin && perPageProofEnabled

    fun checkChallenge(
        challenge:StaffDeviceChallenge,session:TeamPullSession,
        jwtSubject:String,jwtSessionId:String,
        physicalDeviceId:String,serverFingerprint:String,
        ownKeyFingerprint:String,nowMillis:Long
    ) {
        require(challenge.purpose=="read" &&
            StaffDeviceProofPolicy.valid(challenge,nowMillis)) {
            "Invalid, expired or non-read Staff proof"
        }
        require(challenge.staffUserId==jwtSubject &&
            challenge.staffUserId==session.authenticatedUserId &&
            challenge.sessionId==jwtSessionId &&
            challenge.workspaceId==session.workspaceId &&
            challenge.deviceId==physicalDeviceId &&
            challenge.deviceId==session.enrolledDeviceId) {
            "Read challenge does not match the Staff authenticated phone"
        }
        require(Regex("[a-f0-9]{64}").matches(serverFingerprint) &&
            serverFingerprint==ownKeyFingerprint) {
            "Approved Staff read public key was replaced or revoked"
        }
    }
}
