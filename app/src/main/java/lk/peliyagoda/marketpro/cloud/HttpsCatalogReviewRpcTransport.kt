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

/**
 * V3.6.5 network adapter - SOURCE ONLY and NEVER instantiated by the running
 * UI/Worker. No service-role credential, no arbitrary URL or function name.
 *
 * The private server SQL and role/device RLS must be deployed and independently
 * audited first. All calls are disabled in the current offline APK.
 */
internal interface CatalogReviewRpcTransport {
    suspend fun submit(session: CatalogReviewNetworkSession,
                       candidate: StaffCatalogCandidate): String
    suspend fun ownerPage(session: CatalogReviewNetworkSession,
                          afterId: String?, pageSize: Int): String
    suspend fun approve(session: CatalogReviewNetworkSession,
                        verifiedCandidateId: String): String
}

internal class HttpsCatalogReviewRpcTransport(
    private val actualDeviceUuid: String
) : CatalogReviewRpcTransport {
    companion object {
        private const val HOST = "lkoptqhodgjeuwwfihfr.supabase.co"
        private const val MAX_RESPONSE_BYTES = 262144
        private val ALLOWED_RPC = setOf(
            "marketpro_staff_submit_catalog_candidate",
            "marketpro_owner_list_catalog_candidates",
            "marketpro_owner_approve_submitted_catalog"
        )
    }

    private fun requireReady() {
        check(CatalogReviewNetworkPolicy.canEnable(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED
        )) { "Staff/Owner review network is not deployed in this APK" }
    }

    private suspend fun call(
        session: CatalogReviewNetworkSession,
        rpcName: String,
        action: CatalogReviewAction,
        body: JSONObject
    ): String = withContext(Dispatchers.IO) {
        requireReady() // Must not access token/network on unactivated build.
        require(rpcName in ALLOWED_RPC)
        CatalogReviewNetworkPolicy.validate(session,actualDeviceUuid,
            CloudSyncConfig.WORKSPACE_ID,action)
        val base = URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol == "https" && base.host == HOST &&
            (base.port == -1 || base.port == 443) &&
            (base.path.isEmpty() || base.path == "/") &&
            base.userInfo == null && base.query == null && base.ref == null
        ) { "Untrusted Supabase origin" }
        val url=URL(base,"/rest/v1/rpc/$rpcName")
        val bodyBytes=body.toString().toByteArray(StandardCharsets.UTF_8)
        require(bodyBytes.size in 2..8192) { "Review request too large" }
        val conn=(url.openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            connectTimeout=12000
            readTimeout=20000
            setRequestProperty("Accept","application/json")
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer ${session.token}")
        }
        try {
            conn.outputStream.use { it.write(bodyBytes) }
            val status=conn.responseCode
            if (status !in 200..299) {
                // Never print server error bodies, access tokens or candidate data.
                throw IOException(when(status) {
                    401,403 -> "Staff/Owner session or enrolled device denied"
                    404 -> "Catalog review RPC is not yet deployed"
                    408,429,500,502,503,504 -> "Catalog review server unavailable"
                    else -> "Catalog review rejected (HTTP $status)"
                })
            }
            val bytes=ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val buffer=ByteArray(8192)
                while(true) {
                    val count=stream.read(buffer)
                    if(count<0) break
                    if(count==0) continue
                    if(bytes.size()+count>MAX_RESPONSE_BYTES)
                        throw IOException("Oversized server review response")
                    bytes.write(buffer,0,count)
                }
            }
            val result=bytes.toString(StandardCharsets.UTF_8.name())
            require(result.isNotBlank()) { "Empty server response" }
            // All response handling is bounded and strict at the coordinator.
            result
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun submit(
        session: CatalogReviewNetworkSession,
        candidate: StaffCatalogCandidate
    ): String {
        requireReady()
        require(candidate.workspaceId == session.workspaceUuid &&
            candidate.deviceId == session.deviceUuid &&
            candidate.entityType in setOf("category","item") &&
            CloudInboxPagePolicy.uuid(candidate.cloudUuid) &&
            candidate.cloudRevision>0 &&
            candidate.fingerprint.matches(Regex("[0-9a-f]{64}"))
        ) { "Invalid staff catalog candidate" }
        // A validated snapshot MUST have been built from current local Room
        // fields through StaffCatalogCandidatePolicy, not UI-supplied IDs.
        require(OwnerCatalogLinkPolicy.fingerprint(
            candidate.localName,candidate.localCategory,candidate.localActive
        ) == candidate.fingerprint) {
            "Staff catalog fingerprint does not match local fields"
        }
        val params=JSONObject()
            .put("p_workspace_id",candidate.workspaceId)
            .put("p_device_id",candidate.deviceId)
            .put("p_entity_type",candidate.entityType)
            .put("p_cloud_id",candidate.cloudUuid)
            .put("p_cloud_revision",candidate.cloudRevision)
            .put("p_local_key",candidate.localKey)
            .put("p_local_name",candidate.localName)
            .put("p_local_category",candidate.localCategory?:JSONObject.NULL)
            .put("p_local_is_active",candidate.localActive?:JSONObject.NULL)
            .put("p_local_fingerprint",candidate.fingerprint)
        return call(session,"marketpro_staff_submit_catalog_candidate",
            CatalogReviewAction.STAFF_SUBMIT,params)
    }

    override suspend fun ownerPage(
        session: CatalogReviewNetworkSession,
        afterId: String?,
        pageSize: Int
    ): String {
        requireReady()
        CatalogReviewNetworkPolicy.validCursor(afterId)
        CatalogReviewNetworkPolicy.validPageSize(pageSize)
        val params=JSONObject()
            .put("p_workspace_id",session.workspaceUuid)
            .put("p_owner_device_id",session.deviceUuid)
            .put("p_after_id",afterId?:JSONObject.NULL)
            .put("p_page_size",pageSize)
        return call(session,"marketpro_owner_list_catalog_candidates",
            CatalogReviewAction.OWNER_LIST,params)
    }

    override suspend fun approve(
        session: CatalogReviewNetworkSession,
        verifiedCandidateId: String
    ): String {
        requireReady()
        require(CloudInboxPagePolicy.uuid(verifiedCandidateId))
        val params=JSONObject()
            .put("p_workspace_id",session.workspaceUuid)
            .put("p_owner_device_id",session.deviceUuid)
            .put("p_candidate_id",verifiedCandidateId)
        return call(session,"marketpro_owner_approve_submitted_catalog",
            CatalogReviewAction.OWNER_APPROVE,params)
    }
}
