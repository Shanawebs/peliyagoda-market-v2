package lk.peliyagoda.marketpro.cloud

import android.content.Context
import android.os.Build
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Staging Cloud Phone Management for the SAME Market Pro APK.
 *
 * Owner = one active phone; Team = multiple approved phones.
 * Requests do not grant Team financial write access; old Owner-only writer
 * remains disabled by Supabase privileges during the staged rollout.
 *
 * The server authenticates JWT, checks live auth.sessions, member role and
 * the registry on EVERY call. Local role state is only a convenience.
 */
internal class CloudPhoneRegistryClient(context: Context) {
    private val app=context.applicationContext
    private val workspace=CloudSyncConfig.WORKSPACE_ID

    data class Phone(
        val id:String,val userId:String,val label:String,
        val role:String,val enabled:Boolean,val legacyCanWrite:Boolean
    )
    data class Snapshot(val role:String,val phones:List<Phone>)

    private fun allowed() {
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_PHONE_REGISTRY_RPC_DEPLOYED) {
            "Phone management is disabled in this app version"
        }
    }

    private suspend fun bearer():String {
        return when(MarketProActiveRole.current(app)) {
            TeamRole.OWNER ->
                CloudOwnerAuth(app).accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
            TeamRole.PURCHASING_STAFF ->
                CloudStaffAuth(app).verifiedPhoneSession().accessToken
            else -> error("Sign in as Owner or Team first")
        }
    }

    private suspend fun rpc(name:String,params:JSONObject):JSONObject =
        withContext(Dispatchers.IO) {
            allowed()
            require(name in setOf(
                "marketpro_qa_cloud_request_phone",
                "marketpro_qa_cloud_decide_team_phone",
                "marketpro_qa_cloud_list_phones"
            ))
            val origin=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
            require(origin.protocol=="https" &&
                origin.host==BuildConfig.MARKETPRO_SUPABASE_HOST &&
                origin.port in listOf(-1,443) &&
                (origin.path.isBlank() || origin.path=="/"))
            val target=URL(origin,"/rest/v1/rpc/$name")
            val token=bearer()
            val conn=(target.openConnection() as HttpURLConnection).apply {
                requestMethod="POST"
                doOutput=true
                instanceFollowRedirects=false
                connectTimeout=12000
                readTimeout=20000
                setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization","Bearer $token")
                setRequestProperty("Accept","application/json")
                setRequestProperty("Content-Type","application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use {
                    it.write(params.toString().toByteArray(StandardCharsets.UTF_8))
                }
                val status=conn.responseCode
                if(status !in 200..299) {
                    throw IOException(when(status) {
                        401,403 -> "Phone request denied. Check login, Owner MFA and approval."
                        404 -> "Cloud Phone API not deployed"
                        429,500,502,503,504 -> "Cloud unavailable. Retry when connected."
                        else -> "Phone request rejected (HTTP $status)"
                    })
                }
                val response=conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText().take(65536) }
                JSONObject(response)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun requestThisPhone():String {
        allowed()
        val deviceId=CloudDeviceIdentity.get(app)
        UUID.fromString(deviceId)
        val label=("Market Pro • "+Build.MANUFACTURER+" "+Build.MODEL)
            .trim().take(100)
        val result=rpc("marketpro_qa_cloud_request_phone",JSONObject()
            .put("p_workspace_id",workspace)
            .put("p_device_id",deviceId)
            .put("p_device_label",label))
        check(result.optBoolean("ok")) { "Phone request was not accepted" }
        return if(result.optString("status")=="approved")
            "Owner phone approved for Cloud READ. Financial sync is not enabled yet."
        else
            "Team phone PENDING. Owner must approve this phone before Cloud access."
    }

    suspend fun listPhones():Snapshot {
        allowed()
        val response=rpc("marketpro_qa_cloud_list_phones",JSONObject()
            .put("p_workspace_id",workspace))
        check(response.optBoolean("ok"))
        val role=response.getString("role")
        check(role in setOf("owner","editor"))
        val rows=response.getJSONArray("devices")
        check(rows.length()<=100)
        val found=(0 until rows.length()).map { idx ->
            val p=rows.getJSONObject(idx)
            val id=p.getString("device_id")
            UUID.fromString(id)
            Phone(id,p.getString("user_id"),p.getString("label"),
                p.getString("role"),p.getBoolean("enabled"),
                p.getBoolean("can_write"))
        }
        return Snapshot(role,found)
    }

    suspend fun decideTeamPhone(deviceId:String,approve:Boolean) {
        allowed()
        require(MarketProActiveRole.current(app)==TeamRole.OWNER) {
            "Only Owner can approve or unlink Team phones"
        }
        UUID.fromString(deviceId)
        check(deviceId!=CloudDeviceIdentity.get(app)) {
            "Owner phone cannot be changed through Team management"
        }
        val result=rpc("marketpro_qa_cloud_decide_team_phone",JSONObject()
            .put("p_workspace_id",workspace)
            .put("p_owner_device_id",CloudDeviceIdentity.get(app))
            .put("p_team_device_id",deviceId)
            .put("p_approve",approve))
        check(result.optBoolean("ok") &&
            result.optBoolean("enabled") == approve &&
            !result.optBoolean("can_write",true)) {
            "Owner approval did not return the expected safe state"
        }
    }
}
