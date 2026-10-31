package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MarketProSessionExpiryTest {
    private fun jwt(expSeconds: Long): String {
        val payload = "{\"exp\":$expSeconds}"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "header.$encoded.signature"
    }

    @Test fun refreshesExpiredToken() {
        assertTrue(MarketProSessionExpiry.shouldRefresh(jwt(1_000), nowMs=2_000_000))
    }

    @Test fun refreshesBeforeExpiryWithinTwoMinutes() {
        assertTrue(MarketProSessionExpiry.shouldRefresh(jwt(1_119), nowMs=1_000_000))
    }

    @Test fun retainsValidTokenUntilNearExpiry() {
        assertFalse(MarketProSessionExpiry.shouldRefresh(jwt(2_000), nowMs=1_000_000))
    }

    @Test fun malformedTokenTriggersSafeRefresh() {
        assertTrue(MarketProSessionExpiry.shouldRefresh("not-a-jwt", nowMs=1_000_000))
    }
}
