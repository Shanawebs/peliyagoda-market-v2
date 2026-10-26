package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import lk.peliyagoda.marketpro.security.StaffDeviceProofKeyStore
import lk.peliyagoda.marketpro.security.StaffSignedPurchaseChallenge
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DEV-ONLY client for the dedicated SIGNED PURCHASE Edge endpoint.
 *
 * No other financial entity types are accepted. This is deliberately NOT
 * wired as the default worker transport; Owner enrollment, direct-REST RLS,
 * and physical-device tests must be completed first.
 */
internal class ProofBoundStaffPurchaseHttpTransport:
    StaffProofBoundWriteTransport {
    private val signer=StaffDeviceProofKeyStore()
    private val endpoint="/functions/v1/marketpro-staff-signed-purchase"

    private fun uuid(s:String):Boolean=runCatching {
        UUID.fromString(s).toString().equals(s,true)
    }.getOrDefault(false)

    private fun request(
        session:TeamPullSession, body:JSONObject
    ):JSONObject {
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED &&
            BuildConfig.MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED) {
            "Signed purchase server backend has not been audited and deployed"
        }
        require(body.toString().length<=4096)
        require(session.accessToken.length in 20..8192 &&
            !session.accessToken.contains('\r') &&
            !session.accessToken.contains('\n'))
        val root=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(root.protocol=="https"&&
            root.host==BuildConfig.MARKETPRO_SUPABASE_HOST &&
            (root.port==-1||root.port==443)&&
            (root.path.isBlank()||root.path=="/")&&
            root.query==null && root.ref==null && root.userInfo==null)
        val url=URL(root,endpoint)
        val connection=(url.openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            connectTimeout=12000
            readTimeout=20000
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("Accept","application/json")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer ${session.accessToken}")
        }
        try{
            connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status=connection.responseCode
            if(status==401||status==403)
                throw CloudUploadRejected("Staff signed purchase was rejected by Cloud")
            if(status !in 200..299)
                throw IOException("Signed purchase backend unavailable (HTTP $status)")
            val buffer=ByteArrayOutputStream()
            connection.inputStream.use { stream->
                val bytes=ByteArray(4096)
                while(true){
                    val n=stream.read(bytes)
                    if(n<0)break
                    if(buffer.size()+n>16384)
                        throw IOException("Signed purchase receipt is oversized")
                    buffer.write(bytes,0,n)
                }
            }
            return JSONObject(buffer.toString(StandardCharsets.UTF_8.name()))
        }finally{
            connection.disconnect()
        }
    }

    override suspend fun submit(
        session:TeamPullSession,
        plan:StaffOutboxUploadPolicy.Plan,
        payload:JSONObject
    ):StaffServerWriteReceipt=withContext(Dispatchers.IO) {
        require(plan.entityType=="purchase" &&
            session.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
            uuid(session.enrolledDeviceId) &&
            uuid(session.authenticatedUserId) &&
            session.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID)
        val issue=JSONObject()
          .put("intent","issue")
          .put("workspace_id",session.workspaceId)
          .put("device_id",session.enrolledDeviceId)
          .put("operation_id",plan.operationUuid)
          .put("entity_id",plan.cloudUuid)
          .put("purchase_action",plan.action)
          .put("expected_revision",plan.expectedRevision?:JSONObject.NULL)
          .put("payload",payload)
        val issued=request(session,issue)
        check(issued.optBoolean("ok"))
        val c=issued.getJSONObject("challenge")
        require(c.getString("proof_version")==
            "marketpro-device-proof-purchase-v2" &&
            c.getString("workspace_id")==session.workspaceId &&
            c.getString("staff_user_id")==session.authenticatedUserId &&
            c.getString("device_id")==session.enrolledDeviceId &&
            c.getString("operation_id")==plan.operationUuid &&
            c.getString("entity_id")==plan.cloudUuid &&
            c.getString("financial_action")==plan.action &&
            (if(plan.expectedRevision==null)c.isNull("expected_revision")
             else c.getLong("expected_revision")==plan.expectedRevision)) {
            "Cloud returned a mismatched signed purchase challenge"
        }
        val challenge=StaffSignedPurchaseChallenge(
            StaffDeviceChallenge(
                workspaceId=c.getString("workspace_id"),
                staffUserId=c.getString("staff_user_id"),
                deviceId=c.getString("device_id"),
                sessionId=c.getString("session_id"),
                challengeId=c.getString("challenge_id"),
                nonceHex=c.getString("nonce_hex"),purpose=c.getString("purpose"),
                issuedAtMillis=c.getLong("issued_at_millis"),
                expiresAtMillis=c.getLong("expires_at_millis")
            ),
            operationId=c.getString("operation_id"),
            entityId=c.getString("entity_id"),
            financialAction=c.getString("financial_action"),
            expectedRevision=plan.expectedRevision,
            operationSha256=c.getString("operation_sha256"),
            proofVersion=c.getString("proof_version")
        )
        val signed=signer.signPurchase(challenge)
        val receipt=request(session,JSONObject()
            .put("intent","commit")
            .put("challenge_id",challenge.deviceChallenge.challengeId)
            .put("signature_der_base64url",signed.signatureDerBase64Url))
        if(!receipt.optBoolean("accepted")||
           receipt.optString("operation_id")!=plan.operationUuid)
            throw CloudUploadRejected("Signed purchase receipt was not accepted")
        StaffServerWriteReceipt(
            accepted=true,echoedOperationUuid=plan.operationUuid,
            revision=receipt.getLong("revision")
        )
    }
}
