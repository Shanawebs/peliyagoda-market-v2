package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Inactive until server v2 is audited/deployed and device onboarding is verified. */
internal interface TeamPullTransport {
    suspend fun pull(session: TeamPullSession, request: TeamPullPageRequest): String
}

internal class TeamPullHttpTransport : TeamPullTransport {
    companion object {
        private const val HOST = "lkoptqhodgjeuwwfihfr.supabase.co"
        private const val MAX_BYTES = 262144
    }

    private fun boundedRead(connection: HttpURLConnection): String {
        val stream = connection.inputStream
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                if (read == 0) continue
                if (out.size() + read > MAX_BYTES) {
                    throw IOException("Cloud response exceeds bounded download")
                }
                out.write(buf, 0, read)
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    override suspend fun pull(
        session: TeamPullSession,
        request: TeamPullPageRequest
    ): String = withContext(Dispatchers.IO) {
        // Staff must NEVER fall back to the older direct PostgREST bearer
        // token RPC: only the separate one-use AndroidKeystore signature
        // channel is permitted. Owner's installed V1 pull remains unchanged.
        if (session.authenticatedUserId != CloudSyncConfig.OWNER_USER_ID) {
            return@withContext StaffSignedTeamPullTransport().pull(session,request)
        }
        // The coordinator verifies physical device matching separately.
        TeamPullRequestPolicy.validate(session,request,session.enrolledDeviceId)
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED) {
            "Team Cloud endpoint is not deployed for this APK"
        }
        val base = URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol == "https" && base.host == HOST &&
            (base.port == -1 || base.port == 443) &&
            (base.path.isEmpty() || base.path == "/")) {
            "Untrusted Cloud host"
        }
        val target = URL(base,"/rest/v1/rpc/marketpro_team_pull_page")
        val params = JSONObject()
            .put("p_workspace_id",request.workspaceId)
            .put("p_device_id",request.deviceId)
            .put("p_entity_type",request.entityType)
            .put("p_after_updated_at",request.afterUpdatedAt ?: JSONObject.NULL)
            .put("p_after_id",request.afterCloudId ?: JSONObject.NULL)
            .put("p_page_size",request.pageSize)
        val conn = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept","application/json")
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer ${session.accessToken}")
        }
        try {
            conn.outputStream.use { output ->
                output.write(params.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = conn.responseCode
            when (status) {
                in 200..299 -> boundedRead(conn).also { require(it.isNotBlank()) }
                401,403 -> throw IOException("Cloud team session or device is no longer authorized")
                404 -> throw IOException("Cloud team download API has not been deployed")
                408,429,500,502,503,504 -> throw IOException("Cloud download unavailable; retry after reconnect")
                else -> throw IOException("Cloud team download rejected (HTTP $status)")
            }
        } finally {
            conn.disconnect()
        }
    }
}
