package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Owner-only future transport. Never called by current offline preview.
 * Uses the Owner's own encrypted Auth session; never the Staff JWT or any
 * arbitrary caller-supplied URL. Every RPC ALSO verifies Owner and Device.
 */
internal class OwnerTeamPhoneTransferClient(context: Context) {
    private val app=context.applicationContext
    private val owner=CloudOwnerAuth(app)
    private val ownerDeviceId=CloudDeviceIdentity.get(app)
    private companion object {
        const val HOST="lkoptqhodgjeuwwfihfr.supabase.co"
        const val MAX_RESPONSE=16384
        val RPCS=setOf(
            "marketpro_owner_disable_team_phone",
            "marketpro_owner_replace_team_phone",
            "marketpro_owner_activate_replacement_readonly"
        )
    }

    private fun requireActive() {
        check(TeamPhoneReplacementPolicy.networkActivated(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_TEAM_PHONE_TRANSFER_RPC_DEPLOYED
        )) { "Owner phone transfer network is disabled in this build" }
    }

    private suspend fun call(rpc: String, params: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            requireActive()
            require(rpc in RPCS)
            require(CloudInboxPagePolicy.uuid(ownerDeviceId))
            val origin=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
            require(origin.protocol=="https" && origin.host==HOST &&
                (origin.port == -1 || origin.port==443) &&
                (origin.path.isEmpty() || origin.path=="/") &&
                origin.query==null && origin.ref==null && origin.userInfo==null)
            val body=params.toString().toByteArray(StandardCharsets.UTF_8)
            require(body.size in 2..8192)
            val jwt=owner.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
            require(jwt.length in 20..8192 && !jwt.contains('\r') && !jwt.contains('\n'))
            val conn=(URL(origin,"/rest/v1/rpc/$rpc")
                .openConnection() as HttpURLConnection).apply {
                requestMethod="POST"
                doOutput=true
                instanceFollowRedirects=false
                connectTimeout=12000
                readTimeout=15000
                setRequestProperty("Accept","application/json")
                setRequestProperty("Content-Type","application/json; charset=utf-8")
                setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization","Bearer $jwt")
            }
            try {
                conn.outputStream.use { it.write(body) }
                val status=conn.responseCode
                if(status !in 200..299)
                    throw IOException(
                        if(status==401 || status==403)
                            "Only the authenticated Owner may manage Team phones"
                        else "Team phone management unavailable (HTTP $status)"
                    )
                val output=ByteArrayOutputStream()
                conn.inputStream.use { stream ->
                    val buffer=ByteArray(2048)
                    while(true){
                        val n=stream.read(buffer)
                        if(n<0)break
                        if(output.size()+n>MAX_RESPONSE)
                            throw IOException("Oversized management response")
                        output.write(buffer,0,n)
                    }
                }
                JSONObject(output.toString(StandardCharsets.UTF_8.name()))
            } finally {
                conn.disconnect()
            }
        }

    /** Immediate old-device denial, even without a new phone available. */
    suspend fun disableOldPhone(staffUserId: String,oldDeviceId: String): Boolean {
        requireActive()
        require(CloudInboxPagePolicy.uuid(staffUserId) &&
            CloudInboxPagePolicy.uuid(oldDeviceId) &&
            staffUserId != CloudSyncConfig.OWNER_USER_ID &&
            oldDeviceId != ownerDeviceId)
        val json=call("marketpro_owner_disable_team_phone",
            JSONObject().put("p_workspace_id",CloudSyncConfig.WORKSPACE_ID)
                .put("p_owner_device_id",ownerDeviceId)
                .put("p_staff_user_id",staffUserId)
                .put("p_old_device_id",oldDeviceId))
        return json.getBoolean("old_device_disabled") &&
            json.getBoolean("cloud_history_retained")
    }

    /** A short-lived code is shown only to the verified Owner, once. */
    suspend fun replace(
        request:TeamPhoneReplacementRequest
    ): String {
        requireActive()
        require(TeamPhoneReplacementPolicy.validate(request) ==
            TeamPhoneReplacementDecision.READY_FOR_OWNER_SERVER_AUTHORIZATION)
        require(request.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
            request.ownerDeviceId==ownerDeviceId &&
            request.expectedOwnerUserId==CloudSyncConfig.OWNER_USER_ID)
        val json=call("marketpro_owner_replace_team_phone",
            JSONObject().put("p_workspace_id",request.workspaceId)
                .put("p_owner_device_id",ownerDeviceId)
                .put("p_staff_user_id",request.staffUserId)
                .put("p_old_device_id",request.oldDeviceId)
                .put("p_new_device_id",request.newDeviceId)
                .put("p_new_device_label",request.staffDeviceLabel))
        require(json.getBoolean("old_device_disabled") &&
            json.getBoolean("replacement_pending") &&
            json.getBoolean("new_device_not_active") &&
            !json.getBoolean("new_device_can_write") &&
            json.getString("new_device_id")==request.newDeviceId)
        return json.getString("one_time_code").also {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
    }

    /** Read-only first: Staff writer access needs separately approved v2 RPC. */
    suspend fun activateReadOnlyReplacement(staffUserId:String):String {
        requireActive()
        require(CloudInboxPagePolicy.uuid(staffUserId) &&
            staffUserId!=CloudSyncConfig.OWNER_USER_ID)
        val json=call("marketpro_owner_activate_replacement_readonly",
            JSONObject().put("p_workspace_id",CloudSyncConfig.WORKSPACE_ID)
                .put("p_owner_device_id",ownerDeviceId)
                .put("p_staff_user_id",staffUserId))
        require(json.getBoolean("replacement_activated") &&
            json.getBoolean("new_device_enabled") &&
            !json.getBoolean("new_device_can_write") &&
            json.getBoolean("old_phone_stays_disabled"))
        return json.getString("new_device_id")
    }
}
