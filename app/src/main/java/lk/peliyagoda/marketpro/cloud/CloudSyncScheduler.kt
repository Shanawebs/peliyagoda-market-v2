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
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Work is network-constrained and re-created by WorkManager across normal
 * process exits / phone reboot. No passwords, JWTs, images or record bytes
 * are placed in WorkManager Data. Only the encrypted Auth store owns tokens.
 */
object CloudSyncScheduler {
    private const val IMMEDIATE = "marketpro_cloud_single_writer_v1"
    private const val PERIODIC = "marketpro_cloud_periodic_v1"

    private fun constraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun kick(context: Context) {
        // Final two-phone staging uses its own Owner+Team coordinator and must
        // never fall through to the legacy single-owner marketpro_apply_change worker.
        if (StagingTwoPhoneSyncScheduler.enabled()) {
            StagingTwoPhoneSyncScheduler.kick(context)
            return
        }
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE, ExistingWorkPolicy.KEEP, request
        )
        // Separate Staff ledger; live Owner V1 behavior is unchanged.
        StaffFinanceAutoSyncScheduler.kick(context)
    }

    fun schedulePeriodic(context: Context) {
        if (StagingTwoPhoneSyncScheduler.enabled()) {
            StagingTwoPhoneSyncScheduler.schedule(context)
            return
        }
        // The WorkManager minimum periodic interval is 15 minutes; edits
        // also trigger immediate unique work while the app is in use.
        val periodic = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        StaffFinanceAutoSyncScheduler.schedule(context)
    }
}

class CloudSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val engine = CloudSyncEngine(applicationContext)
        return try {
            val remaining = engine.drain(maxChanges = 100)
            if (remaining > 0) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudUploadRejected) {
            // A server-side 403/constraint failure MUST not be retried forever.
            engine.recordFailure(e.message ?: "Cloud refused this operation")
            Result.failure()
        } catch (e: IOException) {
            engine.recordFailure(e.message ?: "Network unavailable; will retry")
            Result.retry()
        } catch (e: Exception) {
            // Auth/session changes are recoverable by manual Sign In + Sync Now.
            engine.recordFailure("Cloud Sync paused: check login and pending records")
            Result.failure()
        }
    }
}
