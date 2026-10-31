package lk.peliyagoda.marketpro.cloud

import java.util.Base64
import org.json.JSONObject

/**
 * Untrusted JWT expiration hint for proactively refreshing a session.
 * Never uses decoded JWT claims as proof of authentication or authorization.
 * Supabase /auth/v1/user and the server-side role/device policies remain authoritative.
 */
internal object MarketProSessionExpiry {
    fun shouldRefresh(accessToken: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val parts = accessToken.split('.')
            if (parts.size != 3) return true
            val decoded = String(
                Base64.getUrlDecoder().decode(parts[1]),
                Charsets.UTF_8
            )
            val expiration = JSONObject(decoded).optLong("exp", 0L)
            if (expiration <= 0L) true
            else expiration <= (nowMs / 1000L + 120L)
        } catch (_: Exception) {
            true
        }
    }
}
