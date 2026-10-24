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
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Future automatic Staff sync. A separate worker/queue from the old Owner
 * V1 writer. No change to old Owner auto-sync scheduling.
 *
 * WorkManager 15-min periodic runs complement immediate edits after release.
 * All worker registration paths remain DISABLED in this preview.
 */
internal object StaffFinanceAutoSyncScheduler {
    private const val IMMEDIATE="marketpro_staff_finance_immediate_v2"
    private const val PERIODIC="marketpro_staff_finance_periodic_v2"

    private fun enabled():Boolean=StaffOutboxUploadPolicy.releaseReady(
        BuildConfig.MARKETPRO_LOCAL_PREVIEW,
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED,
        BuildConfig.MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD
    )

    private fun constraints():Constraints=Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun kick(context:Context) {
        if(!enabled())return
        val req=OneTimeWorkRequestBuilder<StaffFinanceAutoSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE,ExistingWorkPolicy.KEEP,req
        )
    }

    fun schedule(context:Context) {
        if(!enabled())return
        val req=PeriodicWorkRequestBuilder<StaffFinanceAutoSyncWorker>(
            15,TimeUnit.MINUTES
        ).setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC,ExistingPeriodicWorkPolicy.KEEP,req
        )
    }
}

internal class StaffFinanceAutoSyncWorker(
    app:Context,params:WorkerParameters
):CoroutineWorker(app,params) {
    override suspend fun doWork():Result=try {
        val report=StaffFinancialOutboxCoordinator(applicationContext)
            .deliverBatch(max=20)
        when {
            // Old-day offline edits and other row conflicts stay visible
            // as pending/review; do not loop forever in background.
            report.requiresOwnerReview -> Result.success()
            report.pending>0 -> Result.retry()
            else -> Result.success()
        }
    } catch(e:CancellationException) {
        throw e
    } catch(_:IOException) {
        // Offline/5xx response or lost ACK: keep UUID-tagged outbox intact.
        Result.retry()
    } catch(_:Exception) {
        // Invalid Auth, revoked phone, UTC clock problems or invalid payload.
        // Leave the ledger untouched until an explicit user decision.
        Result.failure()
    }
}
