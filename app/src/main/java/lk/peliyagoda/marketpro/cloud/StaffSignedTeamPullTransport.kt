package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import lk.peliyagoda.marketpro.security.StaffDeviceProofKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * NO LIVE SYNC. Signed Staff page transport only after ALL rollout
 * and server proof controls have separately been deployed and verified.
 * No service_role token or Android photos are sent in any request.
 */
internal class StaffSignedTeamPullTransport(
    private val keys: StaffDeviceProofKeyStore=StaffDeviceProofKeyStore()
): TeamPullTransport {
    private companion object {
        const val HOST="lkoptqhodgjeuwwfihfr.supabase.co"
        const val PATH="/functions/v1/marketpro-staff-signed-read"
        const val MAX_PAGE=262144
    }
    private fun jwtContext(jwt:String):Pair<String,String> {
        require(jwt.length in 20..8192)
        val parts=jwt.split(".")
        require(parts.size==3 && parts[1].length in 16..4096)
        val raw=Base64.getUrlDecoder().decode(parts[1])
        require(raw.size in 16..4096)
        val claims=JSONObject(String(raw,StandardCharsets.UTF_8))
        require(claims.getLong("exp")>System.currentTimeMillis()/1000L)
        return claims.getString("sub") to claims.getString("session_id")
    }
    private fun post(jwt:String,body:JSONObject):JSONObject {
        val bytes=body.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..4096)
        val base=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol=="https" && base.host==HOST &&
            (base.port==-1||base.port==443) &&
            (base.path=="/"||base.path.isEmpty()) &&
            base.query==null && base.ref==null && base.userInfo==null) {
            "Untrusted signed Staff read origin"
        }
        val conn=(URL(base,PATH).openConnection() as HttpURLConnection).apply{
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=12000
            readTimeout=20000
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("Accept","application/json")
            setRequestProperty("Cache-Control","no-store")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer $jwt")
        }
        try{
            conn.outputStream.use { it.write(bytes) }
            val status=conn.responseCode
            if(status==401||status==403||status==404||status==422)
                throw SecurityException("Current signed Staff read is not authorized")
            if(status !in 200..299) {
                if(status in listOf(408,429,500,502,503,504))
                    throw IOException("Signed Staff read temporarily unavailable")
                throw SecurityException("Signed Staff read rejected")
            }
            val out=ByteArrayOutputStream()
            conn.inputStream.use{input->
                val buf=ByteArray(8192)
                while(true){
                    val n=input.read(buf)
                    if(n<0)break
                    if(out.size()+n>MAX_PAGE)
                        throw IOException("Signed Staff page exceeds size limit")
                    out.write(buf,0,n)
                }
            }
            return JSONObject(out.toString(StandardCharsets.UTF_8.name()))
        }finally{conn.disconnect()}
    }

    override suspend fun pull(
        session:TeamPullSession,
        request:TeamPullPageRequest
    ):String=withContext(Dispatchers.IO){
        check(StaffSignedReadPolicy.ready(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_STAFF_READ_PROOF_ENFORCED
        )){"Per-page signed Staff Cloud read is disabled"}
        TeamPullRequestPolicy.validate(session,request,session.enrolledDeviceId)
        require(session.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID)
        val (subject,sid)=jwtContext(session.accessToken)
        require(subject==session.authenticatedUserId)
        val envelope=JSONObject()
            .put("workspace_id",request.workspaceId)
            .put("device_id",request.deviceId)
            .put("entity_type",request.entityType)
            .put("after_updated_at",request.afterUpdatedAt?:JSONObject.NULL)
            .put("after_id",request.afterCloudId?:JSONObject.NULL)
            .put("page_size",request.pageSize)
        val issued=post(session.accessToken,JSONObject(envelope.toString())
            .put("action","issue_read"))
        require(issued.getBoolean("ok"))
        val c=issued.getJSONObject("challenge")
        val proof=StaffDeviceChallenge(
            workspaceId=c.getString("workspace_id"),
            staffUserId=c.getString("staff_user_id"),
            deviceId=c.getString("device_id"),
            sessionId=c.getString("session_id"),
            challengeId=c.getString("challenge_id"),
            nonceHex=c.getString("nonce_hex"),
            purpose=c.getString("purpose"),
            issuedAtMillis=c.getLong("issued_at_millis"),
            expiresAtMillis=c.getLong("expires_at_millis")
        )
        val pub=keys.publicInfo()
        StaffSignedReadPolicy.checkChallenge(
            proof,session,subject,sid,request.deviceId,
            c.getString("key_fingerprint_sha256"),
            pub.sha256Fingerprint,System.currentTimeMillis()
        )
        val signature=keys.sign(proof)
        require(signature.publicKey.sha256Fingerprint==pub.sha256Fingerprint)
        val page=post(session.accessToken,JSONObject(envelope.toString())
            .put("action","submit_read")
            .put("challenge_id",proof.challengeId)
            .put("signature_der_base64url",signature.signatureDerBase64Url))
        require(page.getString("entity_type")==request.entityType &&
            page.get("rows") is org.json.JSONArray &&
            page.get("has_more") is Boolean &&
            !page.isNull("server_today_colombo")) {
            "Invalid proof-bound server read response"
        }
        page.toString()
    }
}
