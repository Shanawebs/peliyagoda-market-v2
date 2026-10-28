package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The future Staff/Owner auth adapter supplies a *fresh*, JWT-backed session. */
internal fun interface TeamPullSessionProvider {
    suspend fun currentSession(): TeamPullSession
}

/**
 * V3.5.7 READ-ONLY, DISABLED download orchestration.
 *
 * Not instantiated by app UI, Activity or WorkManager and incapable of
 * running in today's offline preview (double compile-time guard).
 * When eventually enabled, it must be wired only after:
 * - staff's own account + registered/authorized physical phone,
 * - reviewed and deployed authenticated marketpro_team_pull_page RPC,
 * - catalog bootstrap & domain revision conflict QA.
 *
 * Stages verified JSON only, does NOT merge into visible Room tables.
 */
internal class TeamInboxDownloadCoordinator(
    context: Context,
    private val sessionProvider: TeamPullSessionProvider,
    private val transport: TeamPullTransport = TeamPullHttpTransport()
) {
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val inbox = db.inboxDao()
    private val stager = CloudInboxStager(db)
    private val mutex = Mutex()

    data class PullResult(
        val type: String,
        val pagesReceived: Int,
        val recordsStaged: Int,
        val pendingLocalEditConflicts: Int,
        val hasMore: Boolean
    )

    /**
     * Development-only, opt-in verified Team pull + bounded Room domain merge.
     * Never invoked by the existing Owner UI/WorkManager. The server must
     * acknowledge a complete authenticated pull BEFORE any visible Room write.
     *
     * Cursors in staging and revision checkpoints in domain merging are
     * separate: a successful pull cannot silently ACK/drop a pending edit.
     * Catalog mutations and tombstones remain explicitly blocked.\n     * Addition-lot imports are allowed only when no unbound historical lot\n     * shares their date/item; otherwise Owner review is required.
     */
    data class FinanceSyncResult(
        val type: String,
        val pagesReceived: Int,
        val rowsReviewed: Int,
        val rowsMergedOrBaselined: Int,
        val rowsHeldForReview: Int,
        val cloudHasMore: Boolean,
        val reviewLimitHit: Boolean
    )

    suspend fun stageAndMergeFinancialType(
        type: String,
        maxReviewRows: Int = 100
    ): FinanceSyncResult {
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
            BuildConfig.MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED) {
            "Team domain merge is disabled pending verified two-phone cutover"
        }
        require(type in setOf(
            "purchase","purchase_addition","requisition",
            "daily_cash_received","daily_other_expenses"
        ))
        require(maxReviewRows in 1..200)
        val fetched = stageCloudType(type,pageSize=35,maxPages=8)
        if(fetched.hasMore) return FinanceSyncResult(
            type,fetched.pagesReceived,0,0,0,true,false
        )
        val merger = CloudDomainMergeEngine(db)
        var reviewed=0
        var merged=0
        var held=0
        var afterId=""
        while(reviewed<maxReviewRows) {
            val batch=inbox.financialMergeCandidates(
                type,afterId,minOf(25,maxReviewRows-reviewed)
            )
            if(batch.isEmpty())break
            for (candidate in batch) {
                val result=merger.mergeVerifiedShadow(
                    type,candidate.cloudId,CloudSyncConfig.WORKSPACE_ID
                )
                reviewed++
                if(result.decision in setOf(
                    DomainMergeDecision.INSERT_NEW,
                    DomainMergeDecision.UPDATE_EXISTING,
                    DomainMergeDecision.ACK_MATCHING_EXISTING,
                    DomainMergeDecision.ALREADY_CURRENT,
                    DomainMergeDecision.REMOVE_DELETED_REQUISITION
                )) merged++ else held++
                afterId=candidate.cloudId
            }
        }
        // Report review-limit status rather than giving a false
        // impression that every remote row was safely reconciled.
        val next=inbox.financialMergeCandidates(type,afterId,1)
        return FinanceSyncResult(
            type,fetched.pagesReceived,reviewed,merged,held,
            false,next.isNotEmpty()
        )
    }

    suspend fun stageCloudType(
        type: String,
        pageSize: Int = 35,
        maxPages: Int = 8
    ): PullResult = mutex.withLock {
        // Do not move this guard after obtaining an auth token or device ID.
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED) {
            "V3.5.7 is an offline, unprovisioned team-download development branch"
        }
        require(type in CloudInboxPagePolicy.allowedTypes)
        require(pageSize in 1..50 && maxPages in 1..20)

        val identity = CloudDeviceIdentity.get(app)
        val session = sessionProvider.currentSession()
        var cursor = inbox.cursor(type)?.let {
            if (it.lastUpdatedAtUtc != null && it.lastCloudId != null)
                InboxWatermark(it.lastUpdatedAtUtc,it.lastCloudId) else null
        }
        var count = 0
        var staged = 0
        var conflicts = 0
        var hasMore: Boolean

        do {
            val request = TeamPullPageRequest(
                workspaceId=CloudSyncConfig.WORKSPACE_ID,
                deviceId=identity,
                entityType=type,
                afterUpdatedAt=cursor?.updatedAt,
                afterCloudId=cursor?.cloudId,
                pageSize=pageSize
            )
            TeamPullRequestPolicy.validate(session,request,identity)
            // Strictly bounded response is parsed and validated before the
            // complete page/cursor is committed by CloudInboxStager.
            val response = transport.pull(session,request)
            val result = stager.stagePage(
                trustedWorkspaceId=CloudSyncConfig.WORKSPACE_ID,
                requestedType=type,
                expectedCursor=cursor,
                rawPage=response,
                receivedAtMs=System.currentTimeMillis()
            )
            count++
            staged += result.recordsStaged
            conflicts += result.recordsWithPendingLocalEdit
            cursor = result.nextCursor
            hasMore = result.hasMore
        } while (hasMore && count < maxPages)

        PullResult(type,count,staged,conflicts,hasMore)
    }
}
