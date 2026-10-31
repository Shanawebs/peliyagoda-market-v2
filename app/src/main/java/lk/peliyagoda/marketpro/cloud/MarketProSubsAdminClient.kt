package lk.peliyagoda.marketpro.cloud

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import org.json.JSONObject

/** Owner-only, fresh-MFA-gated provisioning of up to five distinct Subs users. */
internal class MarketProSubsAdminClient(context: Context) {
    private val app = context.applicationContext

    suspend fun approveVerifiedMember(email: String): String = withContext(Dispatchers.IO) {
        MarketProActiveRole.requireOwnerAdmin(app)
        val cleanEmail = CloudOwnerAuth.normalizedEmail(email)
        require(CloudOwnerAuth.validEmail(cleanEmail)) { "Valid Subs email required" }
        val token = CloudOwnerAuth(app).accessTokenForOwner(BuildConfig.MARKETPRO_OWNER_USER_ID)
        val device = CloudDeviceIdentity.get(app)
        require(UUID.fromString(device).toString() == device)
        val endpoint = URL(
            BuildConfig.MARKETPRO_SUPABASE_URL +
                "/rest/v1/rpc/marketpro_owner_add_subs_v448"
        )
        require(endpoint.protocol == "https" &&
            endpoint.host == BuildConfig.MARKETPRO_SUPABASE_HOST)
        val body = JSONObject().put("p_workspace_id", CloudSyncConfig.WORKSPACE_ID)
            .put("p_owner_device_id", device)
            .put("p_email", cleanEmail)
            .put("p_confirm", true).toString().toByteArray(StandardCharsets.UTF_8)
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(
                    when (status) {
                        401 -> "Cloud Owner Session Expired — Please Sign In Again"
                        403 -> "Owner MFA verification or device approval required"
                        409, 422 -> "Subs limit or account restriction; review member access"
                        else -> "Subs approval failed (HTTP $status)"
                    }
                )
            }
            val result = conn.inputStream.bufferedReader().use { it.readText().take(4096) }
            val json = JSONObject(result)
            check(json.optBoolean("ok")) { "Subs membership was not confirmed" }
            when (json.optString("status")) {
                "member_added" -> "Subs member approved. They can now request phone linking."
                "already_member" -> "This Subs member is already approved."
                else -> "Owner approval recorded."
            }
        } finally {
            conn.disconnect()
        }
    }
}
