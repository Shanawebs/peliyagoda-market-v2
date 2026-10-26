package lk.peliyagoda.marketpro.stagingowner

/**
 * Identical order, delimiters and version as the review-only Edge verifier
 * owner_bootstrap_key_proof.mjs. This is a LOCAL self-test; fields in this
 * module are synthetic and no networking is allowed.
 */
internal data class OwnerProofTranscript(
    val workspaceId: String,
    val ownerUserId: String,
    val deviceId: String,
    val sessionId: String,
    val challengeId: String,
    val nonceHex: String,
    val fingerprintSha256: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long
) {
    companion object {
        const val VERSION = "marketpro-owner-bootstrap-key-v1"
        private val uuidPattern = Regex(
            "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$"
        )
        private val hex64 = Regex("^[a-f0-9]{64}$")
    }

    fun bytes(nowMillis: Long): ByteArray {
        require(listOf(workspaceId,ownerUserId,deviceId,sessionId,challengeId)
            .all { value -> runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false) }) {
            "Invalid local test UUID"
        }
        require(hex64.matches(nonceHex) && hex64.matches(fingerprintSha256)) {
            "Invalid local test nonce or fingerprint"
        }
        require(issuedAtMillis >= 0L && expiresAtMillis > issuedAtMillis) {
            "Invalid challenge window"
        }
        require(expiresAtMillis - issuedAtMillis <= 60_000L) {
            "Challenge is too long"
        }
        require(nowMillis >= issuedAtMillis && nowMillis < expiresAtMillis) {
            "Expired or future local challenge"
        }
        return listOf(
            VERSION,workspaceId,ownerUserId,deviceId,sessionId,challengeId,
            nonceHex,fingerprintSha256,issuedAtMillis.toString(),
            expiresAtMillis.toString()
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
