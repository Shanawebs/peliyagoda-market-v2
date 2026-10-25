package lk.peliyagoda.marketpro.cloud

/** Two-stage Owner review: a real on-screen Staff fingerprint must be compared
 * against the pending Cloud candidate. Entering a fingerprint from the Cloud
 * response automatically would defeat this important out-of-band check.
 */
internal data class OwnerPublicKeyCandidate(
 val workspaceId:String,
 val staffUserId:String,
 val staffDeviceId:String,
 val challengeId:String,
 val fingerprintSha256:String
)

internal object OwnerPublicKeyApprovalPolicy {
 private val fp=Regex("[0-9a-f]{64}")
 fun confirm(
    pending:OwnerPublicKeyCandidate,
    fresh:OwnerPublicKeyCandidate,
    staffPhoneFingerprintTyped:String,
    exactConfirmation:String,
    ownerCheckedPhysicalPhone:Boolean
 ):Boolean = pending==fresh &&
    listOf(pending.workspaceId,pending.staffUserId,
      pending.staffDeviceId,pending.challengeId)
      .all(TeamSessionPairingPolicy::validUuid) &&
    fp.matches(pending.fingerprintSha256) &&
    fp.matches(staffPhoneFingerprintTyped) &&
    staffPhoneFingerprintTyped==pending.fingerprintSha256 &&
    exactConfirmation=="APPROVE KEY" && ownerCheckedPhysicalPhone
}
