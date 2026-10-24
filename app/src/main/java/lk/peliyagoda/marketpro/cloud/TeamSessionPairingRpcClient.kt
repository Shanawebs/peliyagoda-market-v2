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
 * DORMANT V3.7.3 Android adapter. The preview has no INTERNET permission.
 * Staff login + RPC activation + paired Auth session must ALL be deployed
 * before these methods can connect. Successful Auth login alone grants NOTHING.
 *
 * This client deliberately never persists OTPs, JWTs, or remote role flags.
 */
internal class TeamSessionPairingRpcClient(context: Context) {
    private val app = context.applicationContext
    private val staffAuth = CloudStaffAuth(app)
    private val ownerAuth = CloudOwnerAuth(app)
    private val localDeviceId = CloudDeviceIdentity.get(app)

    companion object {
        private const val HOST = "lkoptqhodgjeuwwfihfr.supabase.co"
        private const val RESPONSE_LIMIT = 16384
        private val RPC_NAMES = setOf(
            "marketpro_owner_invite_staff",
            "marketpro_staff_accept_invitation",
            "marketpro_owner_activate_staff_readonly",
            "marketpro_owner_enable_staff_writer",
            "marketpro_owner_disable_staff_writer",
            "marketpro_owner_pending_key_review",
            "marketpro_owner_approve_staff_signing_key",
            "marketpro_owner_revoke_staff_key_and_phone"
        )
    }

    private fun requirePairingDeployed() {
        check(TeamSessionPairingPolicy.deploymentReady(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED
        )) { "Phone pairing is disabled in this Security Preview" }
    }

    private fun requireWriteApprovalDeployed() {
        requirePairingDeployed()
        check(BuildConfig.MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED) {
            "Staff write approval has not passed live security cutover"
        }
    }

    private fun requireKeyEnrollmentDeployed() {
        requirePairingDeployed()
        check(BuildConfig.MARKETPRO_STAFF_KEY_ENROLLMENT_RPC_DEPLOYED) {
            "Staff signing-key approval is disabled in preview"
        }
    }

    private fun ownerPayload(): JSONObject =
        JSONObject().put("p_workspace_id", CloudSyncConfig.WORKSPACE_ID)
            .put("p_owner_device_id", localDeviceId)

    private suspend fun ownerJwt(): String =
        ownerAuth.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)

    /**
     * All request paths come from an internal RPC allowlist. Redirects,
     * response reflection and response-body errors are denied/ignored.
     */
    private fun post(rpc: String, json: JSONObject, jwt: String): JSONObject {
        requirePairingDeployed()
        require(rpc in RPC_NAMES)
        require(jwt.length in 20..8192 && !jwt.contains('\n') &&
            !jwt.contains('\r'))
        val origin = URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(origin.protocol == "https" && origin.host == HOST &&
            (origin.port == -1 || origin.port == 443) &&
            (origin.path.isEmpty() || origin.path == "/") &&
            origin.query == null && origin.ref == null && origin.userInfo == null)
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 2..8192)
        val conn = (URL(origin, "/rest/v1/rpc/$rpc")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $jwt")
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            if (status !in 200..299) {
                // Never expose a remote error body: pairing code may be reflected.
                throw IOException(
                    when (status) {
                        401,403 -> "Owner/Staff login or device is not authorized"
                        404 -> "Pairing server API is not yet deployed"
                        408,429,500,502,503,504 -> "Pairing server temporarily unavailable"
                        else -> "Pairing request refused (HTTP $status)"
                    }
                )
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > RESPONSE_LIMIT)
                        throw IOException("Pairing reply too large")
                    out.write(buffer,0,n)
                }
            }
            require(out.size() > 0) { "Pairing server did not confirm an action" }
            return JSONObject(out.toString(StandardCharsets.UTF_8.name()))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Owner invite creates only a PENDING one-time code, not a Staff member,
     * registered phone, or writer. Output belongs to Owner's transient UI.
     */
    suspend fun ownerIssueInvitation(
        staffUserId: String,
        staffDeviceId: String,
        label: String
    ): OneTimeOwnerInvitation = withContext(Dispatchers.IO) {
        requirePairingDeployed()
        TeamSessionPairingPolicy.validateOwnerInvite(
            CloudSyncConfig.WORKSPACE_ID, CloudSyncConfig.OWNER_USER_ID,
            CloudSyncConfig.OWNER_USER_ID, localDeviceId, localDeviceId,
            staffUserId, staffDeviceId, label
        )
        val response = post("marketpro_owner_invite_staff",
            ownerPayload().put("p_staff_user_id", staffUserId)
                .put("p_staff_device_id", staffDeviceId)
                .put("p_staff_device_label", label),
            ownerJwt()
        )
        require(response.getBoolean("invitation_created") &&
            response.getString("staff_user_id") == staffUserId &&
            response.getString("intended_device_id") == staffDeviceId) {
            "Invitation was not confirmed by server"
        }
        OneTimeOwnerInvitation.validated(
            response.getString("one_time_code"),
            response.getString("expires_at")
        )
    }

    /**
     * Staff must be signed in to THEIR OWN verified Supabase Auth account.
     * The server binds the one-time code to JWT session_id and local device.
     * On success the Staff device remains NOT enrolled/not writable.
     */
    suspend fun staffClaimInvitation(code: String): Boolean =
        withContext(Dispatchers.IO) {
            requirePairingDeployed()
            val session = staffAuth.verifiedPullSession()
            TeamSessionPairingPolicy.validateStaffClaim(
                CloudSyncConfig.WORKSPACE_ID, session.authenticatedUserId,
                CloudSyncConfig.OWNER_USER_ID,
                CloudDeviceIdentity.get(app), session.enrolledDeviceId, code
            )
            require(session.workspaceId == CloudSyncConfig.WORKSPACE_ID)
            val result = post("marketpro_staff_accept_invitation",
                JSONObject().put("p_workspace_id",session.workspaceId)
                    .put("p_staff_device_id",session.enrolledDeviceId)
                    .put("p_one_time_code",code),
                session.accessToken
            )
            TeamSessionPairingPolicy.validateClaimResult(
                result.getBoolean("invitation_accepted"),
                result.getString("status"),
                result.getString("workspace_id"),
                session.workspaceId
            )
            true
        }

    /** Owner activates read-only access after the invitation was consumed. */
    suspend fun ownerActivateStaffReadOnly(staffUserId: String): Boolean =
        withContext(Dispatchers.IO) {
            requirePairingDeployed()
            require(TeamSessionPairingPolicy.validUuid(staffUserId) &&
                staffUserId != CloudSyncConfig.OWNER_USER_ID)
            val result = post("marketpro_owner_activate_staff_readonly",
                ownerPayload().put("p_staff_user_id", staffUserId),
                ownerJwt()
            )
            require(result.getBoolean("activated") &&
                result.getBoolean("read_only") &&
                !result.getBoolean("can_write")) {
                "Server did not confirm read-only staff activation"
            }
            true
        }

    /** A SEPARATE Owner action is mandatory before using any v2 writer RPC. */
    suspend fun ownerApproveStaffWriter(
        staffUserId: String,
        staffDeviceId: String
    ): Boolean = withContext(Dispatchers.IO) {
        requireWriteApprovalDeployed()
        TeamSessionPairingPolicy.validateOwnerInvite(
            CloudSyncConfig.WORKSPACE_ID, CloudSyncConfig.OWNER_USER_ID,
            CloudSyncConfig.OWNER_USER_ID, localDeviceId, localDeviceId,
            staffUserId, staffDeviceId, "Purchasing Team Phone"
        )
        val result = post("marketpro_owner_enable_staff_writer",
            ownerPayload().put("p_staff_user_id", staffUserId)
                .put("p_staff_device_id", staffDeviceId),
            ownerJwt()
        )
        TeamSessionPairingPolicy.validateGrantResult(
            result.getBoolean("writer_enabled"),
            result.getString("staff_device_id"),staffDeviceId
        )
        require(result.getBoolean("legacy_owner_writer_unchanged"))
        true
    }

    /** Does NOT disable read access; lost phones require full device revoke. */
    suspend fun ownerDisableStaffWriter(
        staffUserId: String, staffDeviceId: String
    ): Boolean = withContext(Dispatchers.IO) {
        requireWriteApprovalDeployed()
        require(TeamSessionPairingPolicy.validUuid(staffUserId) &&
            TeamSessionPairingPolicy.validUuid(staffDeviceId) &&
            staffUserId != CloudSyncConfig.OWNER_USER_ID &&
            staffDeviceId != localDeviceId)
        val result = post("marketpro_owner_disable_staff_writer",
            ownerPayload().put("p_staff_user_id", staffUserId)
                .put("p_staff_device_id", staffDeviceId),
            ownerJwt()
        )
        require(result.getBoolean("staff_write_disabled") &&
            result.getBoolean("legacy_owner_writer_unchanged"))
        true
    }
    /**
     * Owner views only a Staff key that already passed server ECDSA proof.
     * A fingerprint is NOT hardware attestation or automatic writer approval.
     */
    suspend fun ownerPendingStaffKey(
        staffUserId:String,staffDeviceId:String
    ):String=withContext(Dispatchers.IO) {
        requireKeyEnrollmentDeployed()
        require(TeamSessionPairingPolicy.validUuid(staffUserId) &&
            TeamSessionPairingPolicy.validUuid(staffDeviceId) &&
            staffUserId!=CloudSyncConfig.OWNER_USER_ID &&
            staffDeviceId!=localDeviceId)
        val r=post("marketpro_owner_pending_key_review",
            ownerPayload().put("p_staff_user_id",staffUserId)
                .put("p_staff_device_id",staffDeviceId),ownerJwt())
        require(r.getBoolean("proof_of_possession_verified") &&
            !r.getBoolean("owner_approved") &&
            r.getString("staff_user_id")==staffUserId &&
            r.getString("staff_device_id")==staffDeviceId)
        r.getString("key_fingerprint_sha256")
    }

    /**
     * Only explicit matching Staff screen + pending Server fingerprint,
     * checked by Owner with typed APPROVE KEY, can request activation.
     */
    suspend fun ownerApproveStaffSigningKey(
        target:OwnerWriterTarget,pendingServerFingerprint:String,
        staffPhoneFingerprint:String,typedConfirmation:String,
        ownerChecked:Boolean
    ):Boolean=withContext(Dispatchers.IO) {
        requireKeyEnrollmentDeployed()
        val expected=ownerPendingStaffKey(target.staffUserId,target.staffDeviceId)
        check(StaffKeyEnrollmentPolicy.ownerMayApprove(
            target,pendingServerFingerprint,expected,staffPhoneFingerprint,
            typedConfirmation,ownerChecked
        )) { "Owner must compare exact Staff Key fingerprint before approval" }
        require(target.workspaceId==CloudSyncConfig.WORKSPACE_ID &&
            target.ownerDeviceId==localDeviceId &&
            target.expectedOwnerUserId==CloudSyncConfig.OWNER_USER_ID)
        val r=post("marketpro_owner_approve_staff_signing_key",
            ownerPayload().put("p_staff_user_id",target.staffUserId)
                .put("p_staff_device_id",target.staffDeviceId)
                .put("p_expected_fingerprint",expected)
                .put("p_confirmation","APPROVE KEY"),ownerJwt())
        check(r.getBoolean("key_approved") &&
            r.getString("key_fingerprint_sha256")==expected &&
            r.getString("staff_device_id")==target.staffDeviceId &&
            !r.getBoolean("staff_writer_enabled") &&
            r.getBoolean("legacy_owner_writer_unchanged"))
        true
    }

    /** Owner emergency lost-phone stop: disables Staff Phone, key and lease. */
    suspend fun ownerRevokeStaffSigningKeyAndPhone(
        staffUserId:String,staffDeviceId:String
    ):Boolean=withContext(Dispatchers.IO) {
        requireKeyEnrollmentDeployed()
        require(TeamSessionPairingPolicy.validUuid(staffUserId) &&
            TeamSessionPairingPolicy.validUuid(staffDeviceId) &&
            staffUserId!=CloudSyncConfig.OWNER_USER_ID &&
            staffDeviceId!=localDeviceId)
        val r=post("marketpro_owner_revoke_staff_key_and_phone",
            ownerPayload().put("p_staff_user_id",staffUserId)
                .put("p_staff_device_id",staffDeviceId),ownerJwt())
        check(r.getBoolean("staff_key_revoked") &&
            r.getBoolean("staff_phone_disabled") &&
            r.getBoolean("legacy_owner_writer_unchanged"))
        true
    }

}
