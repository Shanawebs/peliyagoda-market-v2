package lk.peliyagoda.marketpro.cloud

import android.content.Context
import android.os.Build
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.photo.MarketPhotoGatewayClient
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
                "marketpro_qa_cloud_list_phones",
                "marketpro_qa_list_visible_phones_v428",
                "marketpro_qa_retire_phone_v428",
                "marketpro_qa_owner_secure_relink_v1",
                "marketpro_qa_approve_team_phone_and_photo_v433"
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
        if(MarketProActiveRole.current(app)==TeamRole.PURCHASING_STAFF) {
            // This is explicit enrollment, not performed at every app start.
            // A same-cert APK update preserves both the ID and the Keystore key;
            // after uninstall, this fresh UUID and key require one Owner approval.
            // The server can pin the signed key while this Team device is PENDING,
            // but grants no photo/download permissions until Owner approval.
            val key = runCatching {
                MarketPhotoGatewayClient(app).enrollThisPhone()
            }
            val enrolled = key.getOrNull()
            if (enrolled != null) return when (result.optString("status")) {
                "approved" -> "Phone already linked. Photo Key: $enrolled. If pending, ask Owner to verify/approve the Photo Key."
                else -> "Recovery request ready: Phone PENDING + Photo Key $enrolled. Owner approves both together."
            }
            return if(result.optString("status")=="approved")
                "Phone linked; Photo Key needs enrollment. Open Photo Cloud Security and register it."
            else
                "Phone PENDING but Photo Key registration failed. Retry Request This Phone before Owner approval."
        }
        return if(result.optString("status")=="approved")
            "Owner phone already approved. Existing device security keys are preserved."
        else
            "Owner phone requires Secure Re-Link with fresh MFA."
    }

    suspend fun listPhones():Snapshot {
        allowed()
        val response=rpc("marketpro_qa_list_visible_phones_v428",JSONObject()
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

    /**
     * STAGING ONLY. After a reinstall the local UUID no longer matches the
     * formerly approved Owner device. The server requires a freshly verified
     * Authenticator (TOTP AAL2 <= 5 minutes), an active Owner auth session
     * and explicit confirmation before atomically retiring the old device.
     *
     * No local/admin bypass and no direct SQL or service-role key in Android.
     */
    suspend fun secureRelinkThisOwnerPhone():String {
        allowed()
        check(MarketProActiveRole.current(app)==TeamRole.OWNER) {
            "Only a signed-in Owner can recover the Owner phone"
        }
        val id=CloudDeviceIdentity.get(app)
        UUID.fromString(id)
        val label=("Market Pro • "+Build.MANUFACTURER+" "+Build.MODEL)
            .trim().take(100)
        val result=rpc("marketpro_qa_owner_secure_relink_v1",JSONObject()
            .put("p_workspace_id",workspace)
            .put("p_new_device_id",id)
            .put("p_new_device_label",label)
            .put("p_confirm",true))
        check(result.optBoolean("ok") &&
            result.optString("device_id")==id &&
            result.optBoolean("enabled") && result.optBoolean("can_write")) {
            "Owner device recovery did not return an approved Owner state"
        }
        // The trusted Owner just completed fresh AAL2 MFA. Register the NEW
        // non-exportable Keystore proof while the same short MFA window is
        // still fresh. Avoid a redundant second manual Photo Key setup after
        // an Owner app reinstall.
        val photoKey = runCatching { MarketPhotoGatewayClient(app).enrollThisPhone() }
        return when(result.optString("status")) {
            "already_active", "relinked" ->
                if (photoKey.isSuccess)
                    "Owner phone linked and Photo Key approved securely. Existing Cloud photos remain available."
                else
                    "Owner phone linked; Photo Key not yet enrolled. Open Photo Cloud Security to retry."
            else -> error("Unexpected Owner recovery response")
        }
    }

    /**
     * Non-destructive server-side retirement. No Android client can directly
     * delete phone rows or bypass the verified active Owner session.
     * This immediately revokes the selected Team phone (if approved), or
     * archives a previously unlinked Owner phone. Active Owner is protected.
     */
    suspend fun removePhoneFromList(deviceId:String):String {
        allowed()
        require(MarketProActiveRole.current(app)==TeamRole.OWNER) {
            "Only Owner can remove linked phone records"
        }
        UUID.fromString(deviceId)
        val ownerDeviceId=CloudDeviceIdentity.get(app)
        UUID.fromString(ownerDeviceId)
        check(deviceId != ownerDeviceId) {
            "Current Owner phone cannot be removed"
        }
        val result=rpc("marketpro_qa_retire_phone_v428",JSONObject()
            .put("p_workspace_id",workspace)
            .put("p_owner_device_id",ownerDeviceId)
            .put("p_target_device_id",deviceId)
            .put("p_confirm",true))
        check(result.optBoolean("ok") &&
            result.optString("device_id")==deviceId &&
            !result.optBoolean("enabled",true) &&
            !result.optBoolean("can_write",true)) {
            "Server did not confirm phone removal"
        }
        return "Phone access revoked and record removed from the visible list"
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
        if (!approve) {
            // Keep existing explicit Owner revocation semantics unchanged.
            val result=rpc("marketpro_qa_cloud_decide_team_phone",JSONObject()
                .put("p_workspace_id",workspace)
                .put("p_owner_device_id",CloudDeviceIdentity.get(app))
                .put("p_team_device_id",deviceId)
                .put("p_approve",false))
            check(result.optBoolean("ok") && !result.optBoolean("enabled",true)) {
                "Phone revocation not confirmed"
            }
            return
        }
        // Fresh TOTP Owner verification is checked by the new server RPC.
        // The Owner's ONE approval atomically enables this new Team UUID
        // AND its pinned Photo Key. When this is a reinstall with the same
        // Team user/label, only then the old UUID is retired (never deleted).
        val result=rpc("marketpro_qa_approve_team_phone_and_photo_v433",JSONObject()
            .put("p_workspace_id",workspace)
            .put("p_owner_device_id",CloudDeviceIdentity.get(app))
            .put("p_team_device_id",deviceId)
            .put("p_confirm",true))
        check(result.optBoolean("ok") &&
            result.optString("device_id")==deviceId &&
            result.optBoolean("enabled") &&
            result.optString("photo_key_status")=="approved" &&
            !result.optBoolean("can_write",true)) {
            "Phone + Photo Key approval was not confirmed"
        }
    }
}
