package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Source-only V3.6.7 bridge:
 * verified Staff Auth -> Owner-reviewed server proof -> transactional Room recheck.
 *
 * No Activity/Worker uses this in the offline release. A response from an
 * injected fake transport is not an independent source of authority; only the
 * official HTTPS transport + audited server endpoint may set trust here.
 */
internal class StaffCatalogApprovedLinkCoordinator(context: Context) {
    private val app=context.applicationContext
    private val staffAuth=CloudStaffAuth(app)
    private val device=CloudDeviceIdentity.get(app)
    private val transport: StaffCatalogApprovalTransport =
        HttpsStaffCatalogApprovalTransport(device)
    private val linker=OwnerCatalogLinker(MarketDatabase.get(app))
    private val mutex=Mutex()

    private fun requiredActive() {
        check(CatalogReviewNetworkPolicy.canEnable(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED
        )) { "Staff mapping approval is not active in offline preview" }
    }

    /** Returns a local decision; does not automatically link anything by name. */
    suspend fun fetchAndApply(
        type: String, cloudUuid: String
    ): OwnerCatalogLinkResult = mutex.withLock {
        requiredActive()
        require(type in setOf("category","item") && CloudInboxPagePolicy.uuid(cloudUuid))
        val auth=staffAuth.verifiedPullSession()
        val session=CatalogReviewNetworkSession(
            token=auth.accessToken,
            userUuid=auth.authenticatedUserId,
            deviceUuid=auth.enrolledDeviceId,
            workspaceUuid=auth.workspaceId,
            role=CatalogReviewActorRole.STAFF
        )
        CatalogReviewNetworkPolicy.validate(session,device,
            CloudSyncConfig.WORKSPACE_ID,CatalogReviewAction.STAFF_SUBMIT)
        require(auth.authenticatedUserId != CloudSyncConfig.OWNER_USER_ID)

        // Read-only RPC on actual server requires Staff JWT, currently enabled
        // device/role, already-reviewed exact candidate, current Cloud revision
        // and currently authorized Owner. UI input is ONLY catalog type/UUID.
        val payload=JSONObject(transport.fetch(session,type,cloudUuid))
        fun sec(field:String):Long =
            OffsetDateTime.parse(payload.getString(field)).toEpochSecond()
        val proof=StaffCatalogServerApproval(
            approvalId=payload.getString("approval_id"),
            workspaceId=payload.getString("workspace_id"),
            deviceId=payload.getString("target_device_id"),
            cloudUuid=payload.getString("cloud_id"),
            type=payload.getString("entity_type"),
            localKey=payload.getString("local_key"),
            revision=payload.getLong("cloud_revision"),
            localFingerprint=payload.getString("local_fingerprint_sha256"),
            reviewedByOwnerId=payload.getString("approved_by"),
            approvedAtEpochSeconds=sec("approved_at"),
            expiresAtEpochSeconds=sec("expires_at"),
            serverNowEpochSeconds=sec("server_now"),
            approvedForDeviceRecheck=
                payload.getString("status")=="owner_approved_for_device_recheck",
            needsRoomRecheck=payload.getBoolean("requires_current_room_recheck")
        )
        val state=StaffCatalogApprovalPolicy.decide(
            proof,CloudSyncConfig.WORKSPACE_ID,device,type,cloudUuid,
            CloudSyncConfig.OWNER_USER_ID
        )
        require(state==StaffCatalogApprovalDecision.READY_TO_RECHECK_ROOM) {
            "Invalid/stale Owner approval or wrong Staff device"
        }

        // Only this validated HTTPS proof is allowed into the existing Room
        // withTransaction linker, which RE-READS current local item, category,
        // pending outbox and Cloud Inbox. A changed row cannot be linked.
        val approval=OwnerCatalogLinkApproval(
            approvalId=proof.approvalId,
            workspaceId=proof.workspaceId,
            targetDeviceId=proof.deviceId,
            type=proof.type,
            localKey=proof.localKey,
            cloudUuid=proof.cloudUuid,
            cloudRevision=proof.revision,
            localFingerprint=proof.localFingerprint,
            ownerUserId=proof.reviewedByOwnerId,
            approvedAtEpochSec=proof.approvedAtEpochSeconds,
            expiresAtEpochSec=proof.expiresAtEpochSeconds
        )
        linker.linkExistingCatalog(
            approval=approval,
            physicalDeviceId=device,
            authenticatedUserId=session.userUuid,
            ownerUserId=CloudSyncConfig.OWNER_USER_ID,
            serverAuthenticatedApproval=true,
            now=Instant.ofEpochSecond(proof.serverNowEpochSeconds)
        )
    }
}
