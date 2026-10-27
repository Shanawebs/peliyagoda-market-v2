package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity

/**
 * Owner + Team share ONE app, ONE paged server-read transport.
 * The server is authoritative for membership, live Auth session and phone
 * revocation. The local role selects only which encrypted Auth token to use.
 */
internal class OneMarketProPullSessionProvider(context:Context):TeamPullSessionProvider {
    private val app=context.applicationContext
    override suspend fun currentSession():TeamPullSession {
        return when(MarketProActiveRole.current(app)) {
            TeamRole.OWNER -> TeamPullSession(
                accessToken=CloudOwnerAuth(app).accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID),
                authenticatedUserId=CloudSyncConfig.OWNER_USER_ID,
                enrolledDeviceId=CloudDeviceIdentity.get(app),
                workspaceId=CloudSyncConfig.WORKSPACE_ID
            )
            TeamRole.PURCHASING_STAFF ->
                CloudStaffAuth(app).verifiedPullSession()
            else -> error("Sign in to Market Pro as Owner or Team first")
        }
    }
}
