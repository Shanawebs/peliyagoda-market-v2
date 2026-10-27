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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase

/**
 * Final staging two-phone scheduler.
 *
 * Immediate work follows local edits. Periodic 15-minute work is only a
 * background recovery path. While the app is visible, MarketViewModel also
 * performs a bounded pull-only refresh at a shorter interval.
 */
internal object StagingTwoPhoneSyncScheduler {
    private const val IMMEDIATE = "marketpro_staging_two_phone_immediate_v410"
    private const val PERIODIC = "marketpro_staging_two_phone_periodic_v410"

    fun enabled(): Boolean =
        !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_STAGING_QA_FINANCE_RPC &&
            BuildConfig.MARKETPRO_TWO_PHONE_AUTO_SYNC &&
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
            BuildConfig.MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED &&
            BuildConfig.MARKETPRO_PHONE_REGISTRY_RPC_DEPLOYED

    private fun constraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun kick(context: Context) {
        if (!enabled()) return
        val request = OneTimeWorkRequestBuilder<StagingTwoPhoneSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun schedule(context: Context) {
        if (!enabled()) return
        val request = PeriodicWorkRequestBuilder<StagingTwoPhoneSyncWorker>(
            15,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

internal class StagingTwoPhoneSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!StagingTwoPhoneSyncScheduler.enabled()) return Result.success()
        val db = MarketDatabase.get(applicationContext)
        val cloud = db.cloudDao()
        if (cloud.state(StagingCatalogBinder.READY) != "true") {
            return Result.success()
        }

        return try {
            val report = StagingTwoPhoneSyncCoordinator(applicationContext).syncNow()
            if (report.pending > 0 && report.held == 0) Result.retry()
            else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            cloud.setState(
                CloudSyncStateEntity(
                    CloudSyncConfig.LAST_ERROR,
                    "Network unavailable; two-phone sync will retry"
                )
            )
            Result.retry()
        } catch (e: CloudUploadRejected) {
            cloud.setState(
                CloudSyncStateEntity(
                    CloudSyncConfig.LAST_ERROR,
                    e.message ?: "Cloud authorization rejected"
                )
            )
            Result.failure()
        } catch (_: Exception) {
            cloud.setState(
                CloudSyncStateEntity(
                    CloudSyncConfig.LAST_ERROR,
                    "Two-phone sync paused; open Account & Phone to verify login/linking"
                )
            )
            Result.failure()
        }
    }
}
