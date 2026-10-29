package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Owner-only STAGING Box OAuth bootstrap.
 * This sends an existing verified Supabase owner JWT only to our fixed origin.
 * Never stores a Box token or developer credential on Android.
 * Photo upload and download intentionally remain OFF in this candidate.
 */
internal class BoxOAuthConnectionClient(context: Context) {
    private val app = context.applicationContext
    private val ownerAuth = CloudOwnerAuth(app)

    data class ConnectionStatus(val connected: Boolean, val connectedAt: String?)

    private fun enabled() {
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW && BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE) {
            "Box connection is not available in this build"
        }
        check(MarketProActiveRole.current(app) == TeamRole.OWNER) {
            "Sign in using the Market Pro Cloud Owner account"
        }
    }

    private fun url(): URL {
        val origin = URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        check(origin.protocol == "https" &&
            origin.host == BuildConfig.MARKETPRO_SUPABASE_HOST &&
            origin.port in listOf(-1, 443) &&
            (origin.path.isBlank() || origin.path == "/") &&
            origin.query == null && origin.ref == null && origin.userInfo == null
        ) { "Unexpected Market Pro cloud destination" }
        return URL(origin, "/functions/v1/marketpro-box-oauth-start")
    }

    private suspend fun request(action: String): JSONObject = withContext(Dispatchers.IO) {
        enabled()
        require(action == "start" || action == "status")
        val bearer = ownerAuth.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val target = url()
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Cache-Control", "no-store")
        }
        try {
            connection.outputStream.use {
                it.write(JSONObject().put("action", action).toString()
                    .toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException(when (status) {
                    401, 403 -> "Verify your Owner Authenticator again, then retry within 5 minutes."
                    404 -> "Box OAuth service is not deployed on staging."
                    429, 500, 502, 503, 504 -> "Cloud is temporarily unavailable. Please retry."
                    else -> "Box connection was rejected (HTTP $status)."
                })
            }
            val json = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use {
                it.readText().take(8192)
            }
            JSONObject(json).also { check(it.optBoolean("ok", false)) {
                "Box OAuth service did not confirm the operation"
            } }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun startAuthorization(): String {
        val response = request("start")
        val expectedRedirect = BuildConfig.MARKETPRO_SUPABASE_URL +
            "/functions/v1/marketpro-box-oauth-callback"
        return BoxOAuthAuthorizationUrlPolicy.verify(
            response.getString("authorization_url"), expectedRedirect
        )
    }

    suspend fun checkStatus(): ConnectionStatus {
        val response = request("status")
        return ConnectionStatus(
            connected = response.getBoolean("connected"),
            connectedAt = response.optString("connected_at").takeIf { it.isNotBlank() }
        )
    }
}
