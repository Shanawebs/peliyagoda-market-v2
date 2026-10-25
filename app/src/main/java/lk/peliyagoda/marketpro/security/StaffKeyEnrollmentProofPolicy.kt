package lk.peliyagoda.marketpro.security

import java.nio.charset.StandardCharsets
import java.util.UUID

/** The Staff private key never leaves Android Keystore. The signed challenge
 * proves possession but is NOT a writer grant or owner approval.
 */
internal data class StaffKeyEnrollmentChallenge(
    val workspaceId:String,
    val staffUserId:String,
    val deviceId:String,
    val sessionId:String,
    val challengeId:String,
    val nonceHex:String,
    val fingerprintSha256:String,
    val issuedAtMillis:Long,
    val expiresAtMillis:Long,
    val proofVersion:String
){
    override fun toString()="StaffKeyEnrollmentChallenge([REDACTED])"
}

internal object StaffKeyEnrollmentProofPolicy {
    const val VERSION="marketpro-staff-key-enrollment-v1"
    private fun uuid(s:String)=runCatching {
        UUID.fromString(s).toString().equals(s,true)
    }.getOrDefault(false)
    fun transcript(c:StaffKeyEnrollmentChallenge,now:Long):ByteArray {
        require(listOf(c.workspaceId,c.staffUserId,c.deviceId,
                c.sessionId,c.challengeId).all(::uuid) &&
            c.proofVersion==VERSION &&
            Regex("[0-9a-f]{64}").matches(c.nonceHex) &&
            Regex("[0-9a-f]{64}").matches(c.fingerprintSha256) &&
            c.issuedAtMillis>=0 && c.expiresAtMillis>c.issuedAtMillis &&
            c.expiresAtMillis-c.issuedAtMillis<=120_000L &&
            now>=c.issuedAtMillis && now<c.expiresAtMillis) {
            "Untrusted or expired Staff public key proof challenge"
        }
        return listOf(VERSION,c.workspaceId,c.staffUserId,c.deviceId,
            c.sessionId,c.challengeId,c.nonceHex,c.fingerprintSha256,
            c.issuedAtMillis.toString(),c.expiresAtMillis.toString()
        ).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
    }
}
