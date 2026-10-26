package lk.peliyagoda.marketpro.stagingowner

/**
 * A distinct registration transcript: old QA signatures cannot be replayed
 * for pending registration. Same exact ten-line byte encoding as the Edge
 * P-256 verifier, but a different domain/version string.
 */
internal data class PendingOwnerTranscript(
    val workspaceId:String,val ownerUserId:String,val deviceId:String,
    val sessionId:String,val challengeId:String,val nonceHex:String,
    val fingerprintSha256:String,val issuedAtMillis:Long,val expiresAtMillis:Long
){
    companion object {
        const val VERSION="marketpro-owner-pending-registration-v1"
        private val HEX64=Regex("^[a-f0-9]{64}$")
    }
    fun bytes(nowMillis:Long):ByteArray {
        require(listOf(workspaceId,ownerUserId,deviceId,sessionId,challengeId)
            .all { v -> runCatching { java.util.UUID.fromString(v).toString()==v }.getOrDefault(false) }) {
            "Invalid Pending UUID"
        }
        require(HEX64.matches(nonceHex) && HEX64.matches(fingerprintSha256)) {
            "Invalid Pending nonce or key"
        }
        require(issuedAtMillis>=0L && expiresAtMillis>issuedAtMillis) {
            "Invalid Pending time interval"
        }
        require(expiresAtMillis-issuedAtMillis<=60_000L) {
            "Pending challenge TTL too long"
        }
        require(nowMillis>=issuedAtMillis && nowMillis<expiresAtMillis) {
            "Pending challenge expired"
        }
        return listOf(
            VERSION,workspaceId,ownerUserId,deviceId,sessionId,
            challengeId,nonceHex,fingerprintSha256,
            issuedAtMillis.toString(),expiresAtMillis.toString()
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
