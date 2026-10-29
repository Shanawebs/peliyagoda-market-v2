package lk.peliyagoda.marketpro.cloud

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Accept only an authorization link produced by our trusted staging Edge Function.
 * This guards against a misconfigured response opening an arbitrary website.
 * No tokens or customer data are read here.
 */
internal object BoxOAuthAuthorizationUrlPolicy {
    private val statePattern = Regex("[A-Za-z0-9_-]{43}")
    private val clientPattern = Regex("[A-Za-z0-9_-]{8,200}")

    fun verify(rawUrl: String, expectedRedirect: String): String {
        require(rawUrl.length in 40..4096) { "Invalid Box authorization URL length" }
        val uri = URI(rawUrl)
        require(
            uri.scheme == "https" &&
                uri.host == "account.box.com" &&
                uri.port == -1 &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                uri.rawPath == "/api/oauth2/authorize"
        ) { "Untrusted Box authorization address" }
        val pairs = uri.rawQuery?.split('&') ?: emptyList()
        require(pairs.size == 4) { "Unexpected Box authorization parameters" }
        val parameters = mutableMapOf<String, String>()
        for (pair in pairs) {
            val pieces = pair.split('=', limit = 2)
            require(pieces.size == 2) { "Malformed Box authorization URL" }
            val name = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.name())
            require(parameters.put(name, value) == null) { "Duplicate Box authorization parameter" }
        }
        require(parameters.keys == setOf("client_id", "response_type", "redirect_uri", "state")) {
            "Unexpected Box authorization options"
        }
        require(parameters["response_type"] == "code" &&
            parameters["redirect_uri"] == expectedRedirect &&
            parameters["state"]?.matches(statePattern) == true &&
            parameters["client_id"]?.matches(clientPattern) == true
        ) { "Box authorization request does not match Market Pro staging" }
        return rawUrl
    }
}
