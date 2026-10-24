package lk.peliyagoda.marketpro.security

import lk.peliyagoda.marketpro.cloud.TeamSessionPairingPolicy
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * V3.7.4 signing *contract* only. No server challenge endpoint is live yet.
 * Only server-issued, one-time challenges may eventually be signed, never
 * a phone-created nonce. The server must verify and atomically consume them.
 *
 * Cryptographic signatures are NOT enough until the remote public-key
 * enrollment and per-request verification have been independently deployed.
 */
internal data class StaffDeviceChallenge(
    val workspaceId: String,
    val staffUserId: String,
    val deviceId: String,
    val sessionId: String,
    val challengeId: String,
    val nonceHex: String,
    val purpose: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long
) {
    override fun toString(): String =
        "StaffDeviceChallenge([REDACTED],purpose=$purpose)"
}

internal object StaffDeviceProofPolicy {
    const val MAX_WINDOW_MILLIS = 120_000L
    private const val DOMAIN = "marketpro-device-proof-v1"
    private val noncePattern = Regex("[0-9a-f]{64}")
    private val purposes = setOf("pair", "read", "write")

    fun valid(
        c: StaffDeviceChallenge,
        nowMillis: Long
    ): Boolean {
        if (listOf(c.workspaceId,c.staffUserId,c.deviceId,c.sessionId,c.challengeId)
                .any { !TeamSessionPairingPolicy.validUuid(it) }) return false
        if (!noncePattern.matches(c.nonceHex) || c.purpose !in purposes) return false
        if (c.issuedAtMillis < 0 || c.expiresAtMillis <= c.issuedAtMillis ||
            c.expiresAtMillis - c.issuedAtMillis > MAX_WINDOW_MILLIS) return false
        if (nowMillis < c.issuedAtMillis || nowMillis >= c.expiresAtMillis) return false
        return true
    }

    /** Deterministic ASCII bytes with a domain tag. Fields cannot add delimiters. */
    fun transcript(c: StaffDeviceChallenge, nowMillis: Long): ByteArray {
        require(valid(c, nowMillis)) { "Missing, expired or untrusted server challenge" }
        return listOf(
            DOMAIN,c.purpose,c.workspaceId,c.staffUserId,c.deviceId,
            c.sessionId,c.challengeId,c.nonceHex,
            c.issuedAtMillis.toString(),c.expiresAtMillis.toString()
        ).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
    }

    /** Pure JVM verifier for protocol tests; backend must implement equivalent. */
    fun verifySignature(
        publicKey: PublicKey, challenge: StaffDeviceChallenge,
        nowMillis: Long, signatureDer: ByteArray
    ): Boolean {
        if (!valid(challenge,nowMillis) ||
            signatureDer.isEmpty() || signatureDer.size > 512) return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(transcript(challenge,nowMillis))
                verify(signatureDer)
            }
        }.getOrDefault(false)
    }

    fun fingerprint(publicKey: PublicKey): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(publicKey.encoded)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
