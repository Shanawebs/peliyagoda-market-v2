package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import lk.peliyagoda.marketpro.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Safe, automatic Cloud receive path for the SAME Owner/Team Android APK.
 * Never uploads local data or bypasses server phone/session approval.
 *
 * Android WorkManager's minimum periodic window is 15 minutes; foreground
 * resume and account activation enqueue a separate immediate receive.
 * Real-time broadcasts/push and financial outbox writes are separate stages.
 */
internal object CloudReceiveAutoSyncScheduler {
    private const val IMMEDIATE="marketpro_cloud_receive_immediate_v1"
    private const val PERIODIC="marketpro_cloud_receive_periodic_v1"

    fun enabled():Boolean =
        !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_CLOUD_RECEIVE_AUTO_ENABLED &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED

    private fun connected()=Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(context:Context) {
        if(!enabled()) return
        schedule(context)
        kick(context)
    }

    fun kick(context:Context) {
        if(!enabled() || MarketProActiveRole.current(context)==null)return
        val job=OneTimeWorkRequestBuilder<CloudReceiveWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE,ExistingWorkPolicy.KEEP,job)
    }

    fun schedule(context:Context) {
        if(!enabled())return
        val job=PeriodicWorkRequestBuilder<CloudReceiveWorker>(15,TimeUnit.MINUTES)
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC,ExistingPeriodicWorkPolicy.KEEP,job)
    }
}

internal class CloudReceiveWorker(
    context:Context, params:WorkerParameters
):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        if(!CloudReceiveAutoSyncScheduler.enabled())return Result.success()
        if(MarketProActiveRole.current(applicationContext)==null)return Result.success()

        val reader=TeamInboxDownloadCoordinator(
            applicationContext,OneMarketProPullSessionProvider(applicationContext))
        return try {
            for(type in TeamPullRequestPolicy.orderedTypes) {
                val staged=reader.stageCloudType(type,pageSize=35,maxPages=8)
                // No partial dataset gets marked complete. Watermark remains
                // durable for the next limited batch; never delete outbox.
                if(staged.hasMore) return Result.retry()
            }
            // Never merge into live Market/Finance until server writer and
            // all catalog foreign-key mappings pass the two-phone audit.
            Result.success()
        } catch(e:CancellationException) {
            throw e
        } catch(_:IOException) {
            Result.retry() // Network loss; Room inbox cursor remains atomic.
        } catch(_:Exception) {
            // Login/phone approval removed, invalid server response, or
            // untrusted data. Avoid retry loops and preserve local ledger.
            Result.success()
        }
    }
}
