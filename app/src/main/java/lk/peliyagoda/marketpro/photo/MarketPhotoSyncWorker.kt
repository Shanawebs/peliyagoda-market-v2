package lk.peliyagoda.marketpro.photo

import android.content.Context
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
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.TeamRole
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Separate photo queue. Never modifies the financial sync journal or original files. */
internal class MarketPhotoSyncWorker(context:Context,parameters:WorkerParameters):
    CoroutineWorker(context,parameters){
    override suspend fun doWork():Result{
        val app=applicationContext
        if(BuildConfig.MARKETPRO_LOCAL_PREVIEW || !BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
            return Result.success()
        val role=MarketProActiveRole.current(app) ?: return Result.success()
        val database=MarketDatabase.get(app)
        val dao=database.photoDao()
        val identity=database.cloudDao()
        val device=CloudDeviceIdentity.get(app)
        if(runCatching{UUID.fromString(device).toString()!=device}.getOrDefault(true))
            return Result.failure()
        val now=System.currentTimeMillis()
        dao.recoverInterruptedUploads(now-5*60*1000L,now)
        // Never use local Room item numbers as cloud identities.
        for(photo in dao.waitingItemLink(20)){
            val id=identity.cloudId("item",photo.itemId.toString())
            if(id!=null && runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false)){
                dao.bindVerifiedCloudItem(photo.photoId,id,device,now)
            }
        }
        val today=LocalDate.now(ZoneId.of("Asia/Colombo")).toString()
        val gateway=MarketPhotoGatewayClient(app)
        var retry=false
        for(photo in dao.pendingUpload(4)){
            val original=File(photo.localOriginalPath)
            // Team may capture/edit today's data only. Historical photos
            // remain pending for owner approval; never silently change their date.
            if(photo.businessDate>today ||
                (role==TeamRole.PURCHASING_STAFF && photo.businessDate!=today))continue
            val valid=runCatching{
                original.isFile && original.length()==photo.bytes &&
                MarketPhotoIdentity.sha256(original)==photo.sha256
            }.getOrDefault(false)
            if(!valid)continue
            val cloudItem=photo.cloudItemId ?: continue
            if(!runCatching{UUID.fromString(cloudItem).toString()==cloudItem}.getOrDefault(false))
                continue
            if(photo.originDeviceId!=device)continue
            if(dao.claimUpload(photo.photoId,System.currentTimeMillis())==0)continue
            try{
                gateway.upload(original,photo.photoId,cloudItem,photo.businessDate,
                    Instant.ofEpochMilli(photo.capturedAtMs).toString())
                check(dao.confirmUploaded(photo.photoId,null,System.currentTimeMillis())==1)
            }catch(e:Exception){
                retry=true
                dao.retryUpload(photo.photoId,e.message?.take(140)?:"Cloud unavailable",
                    System.currentTimeMillis())
            }
        }
        return if(retry)Result.retry() else Result.success()
    }
}

/** Network-constrained retry schedule. No permanent foreground service required. */
internal object MarketPhotoSyncScheduler {
    private fun enabled()=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE
    private val network=Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun onPhotoSaved(context:Context){
        if(!enabled())return
        val work=OneTimeWorkRequestBuilder<MarketPhotoSyncWorker>()
            .setConstraints(network).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("marketpro_photo_once",ExistingWorkPolicy.KEEP,work)
    }
    fun schedule(context:Context){
        if(!enabled())return
        val request=PeriodicWorkRequestBuilder<MarketPhotoSyncWorker>(6,TimeUnit.HOURS)
            .setConstraints(network).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "marketpro_photo_retry",ExistingPeriodicWorkPolicy.KEEP,request)
    }
}
