package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import lk.peliyagoda.marketpro.BuildConfig

/**
 * Best-effort periodic session maintenance, never an alternative to the
 * authoritative server's session revocation or approved-device checks.
 * Offline failures never delete stored credentials or local photos.
 */
internal class MarketProSessionKeepAlive(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (BuildConfig.MARKETPRO_LOCAL_PREVIEW || !BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
            return Result.success()
        val app = applicationContext
        return try {
            when (MarketProActiveRole.current(app)) {
                TeamRole.OWNER ->
                    CloudOwnerAuth(app).accessTokenForOwner(BuildConfig.MARKETPRO_OWNER_USER_ID)
                TeamRole.PURCHASING_STAFF ->
                    CloudStaffAuth(app).verifiedPhoneSession().accessToken
                else -> return Result.success()
            }
            Result.success()
        } catch (_: Exception) {
            // The session may be offline, expired or revoked. Never log secrets,
            // revoke an account, or clear a device's encrypted session here.
            Result.retry()
        }
    }
}

internal object MarketProSessionMaintenance {
    fun schedule(context: Context) {
        if (BuildConfig.MARKETPRO_LOCAL_PREVIEW || !BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
            return
        val request = PeriodicWorkRequestBuilder<MarketProSessionKeepAlive>(
            30, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "marketpro_auth_refresh_safety_v1",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
