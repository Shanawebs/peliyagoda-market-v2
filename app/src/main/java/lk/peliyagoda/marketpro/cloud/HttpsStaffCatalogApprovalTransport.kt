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

/** Source-only: no calls in the existing app. Auth is always a dedicated STAFF session. */
internal interface StaffCatalogApprovalTransport {
    suspend fun fetch(
        session: CatalogReviewNetworkSession,
        type: String,
        cloudUuid: String
    ): String
}

internal class HttpsStaffCatalogApprovalTransport(
    private val expectedDeviceId: String
) : StaffCatalogApprovalTransport {
    private companion object {
        val HOST=BuildConfig.MARKETPRO_SUPABASE_HOST
        const val RPC = "marketpro_device_catalog_link_approval"
        const val MAX_BODY = 8192
    }

    override suspend fun fetch(
        session: CatalogReviewNetworkSession,
        type: String,
        cloudUuid: String
    ): String = withContext(Dispatchers.IO) {
        check(CatalogReviewNetworkPolicy.canEnable(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED
        )) { "Secure Owner-approved Staff link fetch is not live in this app" }
        CatalogReviewNetworkPolicy.validate(
            session,expectedDeviceId,CloudSyncConfig.WORKSPACE_ID,
            CatalogReviewAction.STAFF_SUBMIT
        )
        require(type in setOf("category","item") && CloudInboxPagePolicy.uuid(cloudUuid))
        require(session.userUuid != CloudSyncConfig.OWNER_USER_ID)
        val base=URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol=="https" && base.host==HOST &&
            (base.port == -1 || base.port == 443) &&
            (base.path.isEmpty() || base.path=="/") &&
            base.query==null && base.userInfo==null && base.ref==null)
        val body=JSONObject()
            .put("p_workspace_id",session.workspaceUuid)
            .put("p_device_id",session.deviceUuid)
            .put("p_entity_type",type)
            .put("p_cloud_id",cloudUuid).toString().toByteArray(StandardCharsets.UTF_8)
        require(body.size <= 8192)
        val conn=(URL(base,"/rest/v1/rpc/$RPC")
            .openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            connectTimeout=12000
            readTimeout=15000
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("Accept","application/json")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer ${session.token}")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status=conn.responseCode
            if(status !in 200..299) {
                // Never include the server's sensitive response or JWT in logs.
                throw IOException(
                    if(status==401 || status==403)
                        "Staff account, device or approved mapping was denied"
                    else "Secure Staff approval lookup failed (HTTP $status)"
                )
            }
            val data=ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val buffer=ByteArray(2048)
                while(true) {
                    val len=stream.read(buffer)
                    if(len<0)break
                    if(data.size()+len>MAX_BODY)throw IOException("Approval response too large")
                    data.write(buffer,0,len)
                }
            }
            data.toString(StandardCharsets.UTF_8.name()).also {
                if(it.isBlank())throw IOException("Empty approval response")
            }
        } finally {
            conn.disconnect()
        }
    }
}
