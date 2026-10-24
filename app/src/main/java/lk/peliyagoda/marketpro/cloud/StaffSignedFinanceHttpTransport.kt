package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
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
 * Dormant, fully User-JWT-authenticated Staff finance Edge transport.
 *
 * Protocol:
 *   1. POST one exact finance payload as action_type=issue_finance
 *   2. Strictly verify server-issued nonce, JWT session/device/workspace,
 *      clock window and Owner-approved key fingerprint
 *   3. Sign server nonce transcript with nonexportable AndroidKeystore P-256
 *   4. POST SAME finance payload + challenge + DER ECDSA signature as
 *      action_type=submit_finance
 *   5. Accept exact server operation UUID + optimistic revision ACK only.
 *
 * No service-role key, no token/nonce/signature/file path in logs or URL.
 * A network error does NOT clear the Room queue. SQL server consumes proof
 * and writes financial data in one transaction. This client is gated OFF in
 * the unshipped security preview; the real Owner-installed app is untouched.
 */
internal class StaffSignedFinanceHttpTransport(
    context: Context,
    private val signingKeys: StaffDeviceProofKeyStore = StaffDeviceProofKeyStore()
) : StaffProofBoundWriteTransport {
    private val app=context.applicationContext
    private companion object {
        const val SERVER_HOST="lkoptqhodgjeuwwfihfr.supabase.co"
        const val PATH="/functions/v1/marketpro-staff-finance"
        const val MAX_REQUEST_BYTES=8192
        const val MAX_RESPONSE_BYTES=32768
        const val MAX_BEARER_BYTES=8192
    }

    private fun networkReady()=StaffOutboxUploadPolicy.releaseReady(
        BuildConfig.MARKETPRO_LOCAL_PREVIEW,
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED,
        BuildConfig.MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD
    )

    /**
     * Local client-side JWT claim inspection is only a sanity check.
     * NEVER use unverified decoded token contents to authorize user/device;
     * Supabase Auth JWT, current auth.sessions and Owner lease are server checked.
     */
    private fun sessionClaims(jwt:String):Pair<String,String> {
        require(jwt.length in 20..MAX_BEARER_BYTES)
        val parts=jwt.split(".")
        require(parts.size==3 && parts[1].length in 16..4096)
        val bytes=Base64.getUrlDecoder().decode(parts[1])
        require(bytes.size in 16..4096)
        val claims=JSONObject(String(bytes,StandardCharsets.UTF_8))
        val sub=claims.getString("sub")
        val id=claims.getString("session_id")
        val exp=claims.getLong("exp")
        require(exp > System.currentTimeMillis()/1000L) {
            "Staff access token expired; refresh login"
        }
        return sub to id
    }

    private val mandatory=mapOf(
        "purchase" to setOf("business_date","item_id","quantity_grams","price_cents_per_kg"),
        "purchase_addition" to setOf("business_date","item_id","quantity_grams","price_cents_per_kg"),
        "requisition" to setOf("business_date","item_id","kitchen","quantity_grams"),
        "daily_cash_received" to setOf("business_date","amount_cents"),
        "daily_other_expenses" to setOf(
            "business_date","transport_cents","labor_charge_cents",
            "meals_drinks_cents","others_cents","remarks"
        )
    )

    private fun validateBody(plan:StaffOutboxUploadPolicy.Plan,body:JSONObject) {
        require(plan.action=="create" || plan.action=="correct")
        require((plan.action=="create" && plan.expectedRevision==null) ||
            (plan.action=="correct" && plan.expectedRevision!=null &&
                plan.expectedRevision>0L))
        require(TeamSessionPairingPolicy.validUuid(plan.operationUuid) &&
            TeamSessionPairingPolicy.validUuid(plan.cloudUuid))
        val fields=mandatory[plan.entityType] ?: error("Unknown financial type")
        val actual=body.keys().asSequence().toSet()
        require(actual==fields) { "Server financial request has invalid fields" }
        require(body.getString("business_date")==plan.businessDate)
        val amounts=fields.filter {
            it.endsWith("_cents") || it=="quantity_grams" ||
                it=="price_cents_per_kg"
        }
        require(amounts.all { key ->
            val v=body.get(key)
            (v is Int || v is Long) &&
                (v as Number).toLong() in 0L..9_999_999_999L
        }) { "Financial amounts must be valid integer cents/grams" }
        if("item_id" in fields)
            require(TeamSessionPairingPolicy.validUuid(body.getString("item_id")))
        if("remarks" in fields) {
            val v=body.get("remarks")
            require(v is String && v.length<=500 && !v.contains('\u0000'))
        }
        if("kitchen" in fields)
            require(body.getString("kitchen") in setOf(
                "Main Kitchen","Indian Kitchen","Pastry Kitchen"
            ))
    }

    private fun endpoint():URL {
        val base=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol=="https" && base.host==SERVER_HOST &&
            (base.port==-1 || base.port==443) &&
            (base.path.isBlank() || base.path=="/") &&
            base.userInfo==null && base.query==null && base.ref==null) {
            "Unexpected finance backend origin"
        }
        return URL(base,PATH)
    }

    private fun post(
        url:URL,token:String,payload:JSONObject
    ):JSONObject {
        val bytes=payload.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 2..MAX_REQUEST_BYTES)
        val conn=(url.openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=12000
            readTimeout=20000
            setRequestProperty("Accept","application/json")
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("Cache-Control","no-store")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code=conn.responseCode
            if(code in listOf(401,403,404,409,422)) {
                throw SecurityException(
                    "Staff finance identity, device proof or server RPC not approved"
                )
            }
            if(code !in 200..299) {
                if(code in listOf(408,429,500,502,503,504))
                    throw IOException("Staff finance connection unavailable")
                throw SecurityException("Staff finance request rejected")
            }
            val content=ByteArrayOutputStream()
            conn.inputStream.use { source ->
                val block=ByteArray(4096)
                while(true) {
                    val n=source.read(block)
                    if(n<0)break
                    if(content.size()+n>MAX_RESPONSE_BYTES)
                        throw IOException("Staff finance response too large")
                    content.write(block,0,n)
                }
            }
            if(content.size()==0)throw IOException("Empty financial server receipt")
            return JSONObject(content.toString(StandardCharsets.UTF_8.name()))
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun submit(
        session:TeamPullSession,
        plan:StaffOutboxUploadPolicy.Plan,
        payload:JSONObject
    ):StaffServerWriteReceipt = withContext(Dispatchers.IO) {
        check(networkReady()) { "Staff signed finance API not deployed for this app" }
        val physical=CloudDeviceIdentity.get(app)
        require(session.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
            physical==session.enrolledDeviceId &&
            session.authenticatedUserId!=CloudSyncConfig.OWNER_USER_ID)
        val (jwtSubject,jwtSessionId)=sessionClaims(session.accessToken)
        require(jwtSubject==session.authenticatedUserId)
        // Freeze the financial JSON for BOTH requests. A Room edit after this
        // snapshot gets a new operationId and remains in the durable queue.
        val body=JSONObject(payload.toString())
        validateBody(plan,body)
        val envelope=JSONObject()
            .put("workspace_id",session.workspaceId)
            .put("device_id",physical)
            .put("operation_id",plan.operationUuid)
            .put("entity_type",plan.entityType)
            .put("entity_id",plan.cloudUuid)
            .put("action",plan.action)
            .put("expected_revision",plan.expectedRevision ?: JSONObject.NULL)
            .put("payload",body)
        val url=endpoint()
        val issueBody=JSONObject(envelope.toString())
            .put("action_type","issue_finance")
        val issue=post(url,session.accessToken,issueBody)
        require(issue.getBoolean("ok"))
        val data=issue.getJSONObject("challenge")
        val challenge=StaffDeviceChallenge(
            workspaceId=data.getString("workspace_id"),
            staffUserId=data.getString("staff_user_id"),
            deviceId=data.getString("device_id"),
            sessionId=data.getString("session_id"),
            challengeId=data.getString("challenge_id"),
            nonceHex=data.getString("nonce_hex"),
            purpose=data.getString("purpose"),
            issuedAtMillis=data.getLong("issued_at_millis"),
            expiresAtMillis=data.getLong("expires_at_millis")
        )
        val phoneKey=signingKeys.publicInfo()
        StaffSignedFinancePolicy.acceptServerChallenge(
            challenge,session,jwtSubject,jwtSessionId,physical,
            data.getString("key_fingerprint_sha256"),
            phoneKey.sha256Fingerprint,System.currentTimeMillis()
        )
        // Android Keystore's nonexportable key signs the exact challenge.
        // The server recomputes the operation-payload commitment and verifies
        // this signature before allowing ONE transactional finance mutation.
        val signature=signingKeys.sign(challenge)
        require(signature.publicKey.sha256Fingerprint==phoneKey.sha256Fingerprint)
        val submitBody=JSONObject(envelope.toString())
            .put("action_type","submit_finance")
            .put("challenge_id",challenge.challengeId)
            .put("signature_der_base64url",signature.signatureDerBase64Url)
        val receipt=post(url,session.accessToken,submitBody)
        val accepted=receipt.optBoolean("ok",false) &&
            receipt.optBoolean("accepted",false)
        val echoed=receipt.optString("operation_id","")
        val revision=if(receipt.has("revision") && !receipt.isNull("revision"))
            receipt.getLong("revision") else null
        if(!StaffSignedFinancePolicy.receiptMatches(
            plan,accepted,echoed,revision,receipt.optBoolean("replayed",false)
        )) throw SecurityException(
            "Server did not confirm this exact Staff financial operation"
        )
        StaffServerWriteReceipt(true,echoed,revision)
    }
}
