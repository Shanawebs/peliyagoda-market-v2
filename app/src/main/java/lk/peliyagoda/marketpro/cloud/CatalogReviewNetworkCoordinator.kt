package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * No Worker/Activity references this coordinator. It has no power in preview.
 * Each network request gets its OWN role-verified, refreshed Supabase JWT.
 * A Staff user can submit evidence but can never request Owner approval.
 */
internal class CatalogReviewNetworkCoordinator(
    context: Context,
    transportOverride: CatalogReviewRpcTransport? = null
) {
    private val app=context.applicationContext
    private val actualDeviceId = CloudDeviceIdentity.get(app)
    private val transport = transportOverride ?: HttpsCatalogReviewRpcTransport(actualDeviceId)
    private val staffAuth=CloudStaffAuth(app)
    private val ownerAuth=CloudOwnerAuth(app)
    private val mutex=Mutex()
    private val lastOwnerCandidates=LinkedHashMap<String,OwnerRemoteCandidate>()

    internal data class OwnerReviewPage(
        val candidates: List<OwnerRemoteCandidate>,
        val nextId: String?
    )

    private fun requireReady() {
        check(CatalogReviewNetworkPolicy.canEnable(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED
        )) { "Catalog review backend is not activated in this APK" }
    }

    private suspend fun staffSession(): CatalogReviewNetworkSession {
        val session=staffAuth.verifiedPullSession()
        require(session.authenticatedUserId != CloudSyncConfig.OWNER_USER_ID)
        return CatalogReviewNetworkSession(
            token=session.accessToken,
            userUuid=session.authenticatedUserId,
            deviceUuid=session.enrolledDeviceId,
            workspaceUuid=session.workspaceId,
            role=CatalogReviewActorRole.STAFF
        ).also {
            CatalogReviewNetworkPolicy.validate(it,actualDeviceId,
                CloudSyncConfig.WORKSPACE_ID,CatalogReviewAction.STAFF_SUBMIT)
        }
    }

    private suspend fun ownerSession(): CatalogReviewNetworkSession {
        val token=ownerAuth.accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        return CatalogReviewNetworkSession(
            token=token,
            userUuid=CloudSyncConfig.OWNER_USER_ID,
            deviceUuid=actualDeviceId,
            workspaceUuid=CloudSyncConfig.WORKSPACE_ID,
            role=CatalogReviewActorRole.OWNER
        ).also {
            CatalogReviewNetworkPolicy.validate(it,actualDeviceId,
                CloudSyncConfig.WORKSPACE_ID,CatalogReviewAction.OWNER_LIST)
        }
    }

    private val staffReader by lazy {
        StaffCatalogCandidateReader(
            lk.peliyagoda.marketpro.data.MarketDatabase.get(app),
            actualDeviceId
        )
    }

    /**
     * Staff explicitly selects a *local logical key* and a *Cloud UUID*;
     * BOTH records are re-read from Room in one read-only transaction.
     * No arbitrary UI-provided Item name, category, fingerprint or revision
     * may be sent to the server; they always originate from live local rows.
     */
    suspend fun staffSubmitLocalCatalog(
        type: String, localKey: String, cloudUuid: String
    ): String = mutex.withLock {
        requireReady()
        val captured = staffReader.capture(type,localKey,cloudUuid)
        require(captured.decision == StaffCatalogCaptureDecision.READY_FOR_OWNER_REVIEW) {
            "Staff catalog changed, has pending edits, or requires category mapping"
        }
        val candidate=requireNotNull(captured.candidate)
        val session=staffSession()
        require(candidate.workspaceId==session.workspaceUuid &&
            candidate.deviceId==actualDeviceId)
        val json=JSONObject(transport.submit(session,candidate))
        require(json.optString("status")=="awaiting_owner_review" &&
            CloudInboxPagePolicy.uuid(json.getString("candidate_id")) &&
            json.getLong("cloud_revision")==candidate.cloudRevision) {
            "Invalid server staff candidate acknowledgement"
        }
        // If Room changed while the network request was in flight, never
        // report success. A server pending record may still exist; target
        // phone fingerprint/revision must be checked again before binding.
        val after = staffReader.capture(type,localKey,cloudUuid)
        check(after.decision == StaffCatalogCaptureDecision.READY_FOR_OWNER_REVIEW &&
            after.candidate == candidate) {
            "Local catalog changed during upload; do not treat candidate as current"
        }
        json.getString("candidate_id")
    }

    /**
     * Owner-only fetch, bounded, strict workspace and server-candidate identity.
     * No local owner's Room data is substituted for Staff Phone evidence.
     */
    suspend fun ownerReviewPage(
        afterId: String?=null,pageSize:Int=30
    ): OwnerReviewPage = mutex.withLock {
        requireReady()
        CatalogReviewNetworkPolicy.validCursor(afterId)
        CatalogReviewNetworkPolicy.validPageSize(pageSize)
        val session=ownerSession()
        val page=JSONObject(transport.ownerPage(session,afterId,pageSize))
        require(page.getBoolean("requires_explicit_owner_approval"))
        val array=page.getJSONArray("candidates")
        require(array.length()<=pageSize)
        val results=ArrayList<OwnerRemoteCandidate>(array.length())
        var previous=afterId
        for(i in 0 until array.length()) {
            val row=array.getJSONObject(i)
            require(row.getString("workspace_id")==session.workspaceUuid &&
                row.getString("status")=="pending" &&
                OffsetDateTime.parse(row.getString("submitted_at")).toInstant()
                    .isBefore(OffsetDateTime.parse(row.getString("expires_at")).toInstant())
            ) { "Untrusted Owner candidate response" }
            val candidate=OwnerRemoteCandidate(
                candidateUuid=row.getString("candidate_id"),
                staffDeviceId=row.getString("device_id"),
                staffAccountId=row.getString("submitted_by"),
                cloudUuid=row.getString("cloud_id"),
                cloudRevision=row.getLong("cloud_revision"),
                entityType=row.getString("entity_type"),
                localItemKey=row.getString("local_key"),
                localName=row.getString("local_name"),
                localCategory=if(row.isNull("local_category")) null
                    else row.getString("local_category"),
                localActive=if(row.isNull("local_is_active")) null
                    else row.getBoolean("local_is_active"),
                localFingerprint=row.getString("local_fingerprint_sha256")
            )
            require(OwnerRemoteCandidatePolicy.reviewable(
                candidate,candidate.staffDeviceId)) {
                "Malformed Staff Phone candidate"
            }
            require(OwnerCatalogLinkPolicy.fingerprint(
                candidate.localName,candidate.localCategory,candidate.localActive
            )==candidate.localFingerprint) {
                "Staff candidate data/fingerprint mismatch"
            }
            if(previous!=null) require(candidate.candidateUuid>previous) {
                "Candidate page cursor did not advance"
            }
            previous=candidate.candidateUuid
            results.add(candidate)
        }
        val next=if(page.isNull("next_id")) null else page.getString("next_id")
        require(next==results.lastOrNull()?.candidateUuid) {
            "Candidate page contains an inconsistent cursor"
        }
        // A later UI can only pass selection from one server-verified page.
        lastOwnerCandidates.clear()
        results.forEach { lastOwnerCandidates[it.candidateUuid]=it }
        OwnerReviewPage(results,next)
    }

    /**
     * Only a candidate ID from the previously authenticated Owner fetch can
     * reach the network approval method. The SERVER still atomically checks
     * Owner JWT, actual enrolled Owner phone, Staff device & Cloud revision.
     */
    suspend fun ownerApproveSelected(candidateId: String): String = mutex.withLock {
        requireReady()
        val candidate=lastOwnerCandidates[candidateId]
            ?: error("Reload Owner Staff Phone review before approval")
        val session=ownerSession()
        require(OwnerRemoteCandidatePolicy.reviewable(
            candidate,candidate.staffDeviceId))
        val json=JSONObject(transport.approve(session,candidateId))
        require(json.getBoolean("approved") &&
            json.getString("candidate_id")==candidateId &&
            json.getString("target_device_id")==candidate.staffDeviceId &&
            json.getBoolean("requires_target_device_recheck") &&
            CloudInboxPagePolicy.uuid(json.getString("approval_id"))) {
            "Owner approval response did not match selected Staff Phone"
        }
        lastOwnerCandidates.remove(candidateId)
        json.getString("approval_id")
    }
}
