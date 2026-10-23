package lk.peliyagoda.marketpro.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Offline approval guard for ARCHIVING one catalog item.
 * A six-digit operator code is required by product specification.
 * This is a local UI safeguard, not enterprise-grade authentication:
 * shipped APK code can be reverse engineered. Cloud sync remains blocked
 * in Expressive preview builds.
 */
object CatalogArchiveAuthorization {
    // SHA-256 of approved six-digit operator code. Plaintext not embedded.
    private const val APPROVED_SHA256 =
        "a1207c940d694af400714ef293e2030bef5a922e3122199e8d09f5e8fa544ada"

    fun valid(code: String): Boolean {
        if (code.length != 6 || !code.all(Char::isDigit)) return false
        val expected = APPROVED_SHA256
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val supplied = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
        return MessageDigest.isEqual(expected, supplied)
    }
}
