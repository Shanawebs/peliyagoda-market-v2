package lk.peliyagoda.marketpro.cloud

import java.util.Base64

/**
 * Untrusted JWT expiry hint. This parser never grants authentication or
 * authorization: Supabase /auth/v1/user and RLS/device checks are authoritative.
 */
internal object MarketProSessionExpiry {
    private val expiry = Regex("""(?:^|[,{\}s])"exp"\s*:\s*(\d{1,15})(?=[,}\s]|$)""")

    fun shouldRefresh(accessToken: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val parts = accessToken.split('.')
            if (parts.size != 3) return true
            val body = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            val expiration = expiry.find(body)?.groupValues?.get(1)?.toLongOrNull()
                ?: return true
            expiration <= (nowMs / 1000L + 120L)
        } catch (_: Exception) {
            true
        }
    }
}
