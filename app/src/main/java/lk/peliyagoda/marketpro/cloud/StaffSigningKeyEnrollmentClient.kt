package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import lk.peliyagoda.marketpro.security.StaffDeviceProofKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Pending signing-key proof: this does NOT approve Staff permissions. */
internal data class PendingStaffSigningKey(
    val fingerprintSha256:String,
    val hardwareBacked:Boolean,
    val ownerApprovalRequired:Boolean=true
) {
    override fun toString() =
        "PendingStaffSigningKey(fingerprint=$fingerprintSha256,approved=false)"
}

/**
 * Never activated in current preview; no INTERNET in AndroidManifest.
 *
 * Staff must FIRST accept Owner invitation and receive Owner Read-Only
 * activation. Then prove local P-256 private-key possession using a
 * one-time Server challenge. The Owner approves the pending fingerprint
 * through a distinct endpoint. No signing key is auto-approved.
 */
internal class StaffSigningKeyEnrollmentClient(context:Context) {
    private val app=context.applicationContext
    private val auth=CloudStaffAuth(app)
    private val keyStore=StaffDeviceProofKeyStore()
    private val mutex=Mutex()

    private fun requireEnabled() {
        check(StaffKeyEnrollmentPolicy.allowClient(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_KEY_ENROLLMENT_RPC_DEPLOYED
        )) { "Staff signing key enrollment not deployed" }
    }

    private fun claims(jwt:String):Pair<String,String> {
        require(jwt.length in 20..8192)
        val p=jwt.split(".")
        require(p.size==3 && p[1].length in 16..4096)
        val raw=Base64.getUrlDecoder().decode(p[1])
        require(raw.size in 16..4096)
        val json=JSONObject(String(raw,StandardCharsets.UTF_8))
        require(json.getLong("exp")>System.currentTimeMillis()/1000L)
        return json.getString("sub") to json.getString("session_id")
    }

    private fun post(token:String,payload:JSONObject):JSONObject {
        val bytes=payload.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 2..4096)
        val base=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol=="https" &&
            base.host=="lkoptqhodgjeuwwfihfr.supabase.co" &&
            (base.port==-1||base.port==443) &&
            (base.path.isBlank()||base.path=="/") &&
            base.query==null && base.ref==null && base.userInfo==null)
        val url=URL(base,"/functions/v1/marketpro-staff-key-enrollment")
        val conn=(url.openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=12000
            readTimeout=18000
            setRequestProperty("Accept","application/json")
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer $token")
        }
        try{
            conn.outputStream.use{it.write(bytes)}
            val status=conn.responseCode
            if(status !in 200..299) {
                if(status in listOf(408,429,500,502,503,504))
                    throw IOException("Staff pairing service temporarily unavailable")
                throw SecurityException("Staff pairing was not authorized")
            }
            val out=ByteArrayOutputStream()
            conn.inputStream.use{input->
                val buf=ByteArray(4096)
                while(true) {
                    val n=input.read(buf)
                    if(n<0)break
                    if(out.size()+n>16384)
                        throw IOException("Staff pairing response too large")
                    out.write(buf,0,n)
                }
            }
            return JSONObject(out.toString(StandardCharsets.UTF_8.name()))
        } finally{conn.disconnect()}
    }

    suspend fun provePendingKey():PendingStaffSigningKey =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                requireEnabled()
                val session=auth.verifiedPullSession()
                val physical=CloudDeviceIdentity.get(app)
                require(session.enrolledDeviceId==physical &&
                    session.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
                    session.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID)
                val (sub,sid)=claims(session.accessToken)
                val info=keyStore.publicInfo()
                val started=post(session.accessToken,JSONObject()
                    .put("action","begin")
                    .put("workspace_id",session.workspaceId)
                    .put("device_id",physical)
                    .put("spki_base64url",info.spkiBase64Url))
                require(started.getBoolean("ok"))
                val row=started.getJSONObject("challenge")
                val c=StaffDeviceChallenge(
                    workspaceId=row.getString("workspace_id"),
                    staffUserId=row.getString("staff_user_id"),
                    deviceId=row.getString("device_id"),
                    sessionId=row.getString("session_id"),
                    challengeId=row.getString("challenge_id"),
                    nonceHex=row.getString("nonce_hex"),
                    purpose=row.getString("purpose"),
                    issuedAtMillis=row.getLong("issued_at_millis"),
                    expiresAtMillis=row.getLong("expires_at_millis")
                )
                StaffKeyEnrollmentPolicy.checkChallenge(
                    c,session,sub,sid,physical,info.sha256Fingerprint,
                    row.getString("key_fingerprint_sha256"),
                    System.currentTimeMillis()
                )
                val signed=keyStore.sign(c)
                require(signed.publicKey.sha256Fingerprint==info.sha256Fingerprint)
                val proved=post(session.accessToken,JSONObject()
                    .put("action","prove")
                    .put("challenge_id",c.challengeId)
                    .put("signature_der_base64url",signed.signatureDerBase64Url))
                require(proved.getBoolean("ok") &&
                    proved.getBoolean("proof_of_possession_verified") &&
                    proved.getBoolean("key_pending_owner_approval") &&
                    proved.getString("key_fingerprint_sha256")==info.sha256Fingerprint) {
                    "Phone signing key was not confirmed as pending Owner review"
                }
                PendingStaffSigningKey(info.sha256Fingerprint,info.hardwareBacked)
            }
        }
}
