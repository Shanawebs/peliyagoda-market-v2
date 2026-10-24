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
