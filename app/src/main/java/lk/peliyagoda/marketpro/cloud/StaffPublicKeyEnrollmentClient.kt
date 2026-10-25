package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.security.StaffDeviceProofKeyStore
import lk.peliyagoda.marketpro.security.StaffKeyEnrollmentChallenge
import lk.peliyagoda.marketpro.security.StaffKeyEnrollmentProofPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** DORMANT. Staff proposes a public key; proof alone grants no writer rights. */
internal class StaffPublicKeyEnrollmentClient(context:Context) {
    private val app=context.applicationContext
    private val auth=CloudStaffAuth(app)
    private val signer=StaffDeviceProofKeyStore()

    data class CandidateReceipt(
        val staffDeviceId:String,
        val fingerprintSha256:String,
        val ownerApprovalRequired:Boolean=true
    )

    private fun assertEnabled(){
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
            BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED &&
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
            BuildConfig.MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED) {
            "Staff public key enrollment has not been audited or deployed"
        }
    }

    private fun call(session:TeamPullSession,payload:JSONObject):JSONObject {
        assertEnabled()
        val bytes=payload.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 2..4096)
        require(session.accessToken.length in 20..8192 &&
            !session.accessToken.contains('\n') &&
            !session.accessToken.contains('\r'))
        val origin=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(origin.protocol=="https" &&
            origin.host=="lkoptqhodgjeuwwfihfr.supabase.co" &&
            (origin.port==-1||origin.port==443) &&
            (origin.path.isEmpty()||origin.path=="/") &&
            origin.query==null && origin.ref==null && origin.userInfo==null)
        val conn=(URL(origin,"/functions/v1/marketpro-staff-key-enrollment")
            .openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            connectTimeout=12000
            readTimeout=16000
            setRequestProperty("Accept","application/json")
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer " + session.accessToken)
        }
        try{
            conn.outputStream.use { it.write(bytes) }
            val status=conn.responseCode
            if(status !in 200..299)
                throw IOException(
                    if(status==401||status==403) "Staff key proof denied by server"
                    else "Staff key enrollment unavailable (HTTP " + status + ")"
                )
            val output=ByteArrayOutputStream()
            conn.inputStream.use{stream->
                val buffer=ByteArray(4096)
                while(true){
                    val n=stream.read(buffer)
                    if(n<0)break
                    if(output.size()+n>16384)
                        throw IOException("Staff key proof response too large")
                    output.write(buffer,0,n)
                }
            }
            return JSONObject(output.toString(StandardCharsets.UTF_8.name()))
        }finally{conn.disconnect()}
    }

    /** Stage exactly one candidate SPKI for a separate Owner fingerprint check. */
    suspend fun stageForOwnerReview():CandidateReceipt=withContext(Dispatchers.IO){
        assertEnabled()
        val s=auth.verifiedPullSession()
        val phone=CloudDeviceIdentity.get(app)
        require(s.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
            s.enrolledDeviceId==phone &&
            s.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID)
        val pk=signer.publicInfo()
        val issued=call(s,JSONObject()
            .put("intent","issue")
            .put("workspace_id",s.workspaceId)
            .put("device_id",phone)
            .put("spki_base64url",pk.spkiBase64Url))
        require(issued.getBoolean("ok") &&
            !issued.getBoolean("owner_approval_granted"))
        val j=issued.getJSONObject("challenge")
        require(j.getString("staff_user_id")==s.authenticatedUserId &&
            j.getString("workspace_id")==s.workspaceId &&
            j.getString("device_id")==phone &&
            j.getString("fingerprint_sha256")==pk.sha256Fingerprint &&
            j.getString("proof_version")==StaffKeyEnrollmentProofPolicy.VERSION)
        val challenge=StaffKeyEnrollmentChallenge(
            workspaceId=j.getString("workspace_id"),
            staffUserId=j.getString("staff_user_id"),
            deviceId=j.getString("device_id"),
            sessionId=j.getString("session_id"),
            challengeId=j.getString("challenge_id"),
            nonceHex=j.getString("nonce_hex"),
            fingerprintSha256=j.getString("fingerprint_sha256"),
            issuedAtMillis=j.getLong("issued_at_millis"),
            expiresAtMillis=j.getLong("expires_at_millis"),
            proofVersion=j.getString("proof_version")
        )
        val signed=signer.signEnrollment(challenge)
        val result=call(s,JSONObject().put("intent","verify")
            .put("challenge_id",challenge.challengeId)
            .put("signature_der_base64url",signed.signatureDerBase64Url))
        require(result.getBoolean("ok") && result.getBoolean("proof_verified") &&
            !result.getBoolean("owner_approval_granted") &&
            result.getString("device_id")==phone &&
            result.getString("fingerprint_sha256")==pk.sha256Fingerprint)
        CandidateReceipt(phone,pk.sha256Fingerprint)
    }
}
