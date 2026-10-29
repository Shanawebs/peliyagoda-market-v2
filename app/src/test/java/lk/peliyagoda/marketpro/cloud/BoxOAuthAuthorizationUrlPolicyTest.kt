package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class BoxOAuthAuthorizationUrlPolicyTest {
    private val callback =
        "https://qqiheexpbuzohllugxrk.supabase.co/functions/v1/marketpro-box-oauth-callback"
    private val state = "A".repeat(43)
    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
    private fun good() = "https://account.box.com/api/oauth2/authorize" +
        "?client_id=abcdefghijklmnop12345678&response_type=code" +
        "&redirect_uri=" + enc(callback) + "&state=" + state
    private fun invalid(candidate: String) {
        assertThrows(IllegalArgumentException::class.java) {
            BoxOAuthAuthorizationUrlPolicy.verify(candidate, callback)
        }
    }

    @Test fun validUrlAccepted() {
        assertEquals(good(), BoxOAuthAuthorizationUrlPolicy.verify(good(), callback))
    }
    @Test fun httpRejected() = invalid(good().replace(
        "https://account.box.com", "http://account.box.com"))
    @Test fun suffixHostRejected() = invalid(good().replace(
        "account.box.com", "account.box.com.evil.example"))
    @Test fun arbitraryHostRejected() = invalid(good().replace(
        "account.box.com", "evil.example"))
    @Test fun wrongPathRejected() = invalid(good().replace(
        "/api/oauth2/authorize", "/other"))
    @Test fun shortStateRejected() = invalid(good().replace("state=" + state, "state=ab"))
    @Test fun implicitFlowRejected() = invalid(good().replace(
        "response_type=code", "response_type=token"))
    @Test fun wrongCallbackRejected() = invalid(good().replace(
        enc(callback), enc("https://evil.example/callback")))
    @Test fun duplicateStateRejected() = invalid(good() + "&state=" + state)
    @Test fun emptyClientIdRejected() = invalid(good().replace(
        "client_id=abcdefghijklmnop12345678", "client_id="))
    @Test fun urlUserInfoRejected() = invalid(good().replace(
        "https://account.box.com", "https://user@account.box.com"))
    @Test fun fragmentRejected() = invalid(good() + "#fragment")
}
