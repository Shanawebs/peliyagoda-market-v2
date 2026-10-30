package lk.peliyagoda.marketpro.photo

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.TeamRole
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.data.MarketPhotoEntity
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Staging, bounded, offline-first Cloud Photo Sync.
 * Never deletes photos, changes financial rows, or copies Android Gallery files.
 * Idempotent original registration -> canonical ID -> signed upload.
 */
internal class MarketPhotoSyncWorker(context:Context,parameters:WorkerParameters):
    CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val app=applicationContext
        if(BuildConfig.MARKETPRO_LOCAL_PREVIEW || !BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
            return@withContext Result.success()
        val role=MarketProActiveRole.current(app) ?: return@withContext Result.retry()
        val owner=role==TeamRole.OWNER
        val today=LocalDate.now(ZoneId.of("Asia/Colombo")).toString()
        val cutoff=LocalDate.parse(today).minusDays(7).toString()
        val db=MarketDatabase.get(app)
        val dao=db.photoDao()
        val identity=db.cloudDao()
        val device=CloudDeviceIdentity.get(app)
        if(!runCatching { UUID.fromString(device).toString()==device }.getOrDefault(false))
            return@withContext Result.failure()

        // Recover originals captured while cloud uploads were intentionally paused.
        // Bounded to 40 files in recent app-private day folders only.
        runCatching { registerRecentOriginals(app,db,today,owner) }
            .onFailure { Log.w("MarketPhotoSync", "Local photo recovery failed", it) }
        dao.recoverInterruptedUploads(System.currentTimeMillis()-300_000L,
            System.currentTimeMillis())
        val gateway=MarketPhotoGatewayClient(app)
        // A linked phone self-registers its non-exportable signing key.
        // Server authorization remains bound to enabled, writable Team/Owner device.
        // Owner key recovery continues to require owner MFA.
        val keyReady=runCatching { gateway.enrollThisPhone() }
            .onFailure { Log.w("MarketPhotoSync", "Photo key enrollment failed", it) }
            .isSuccess
        if(!keyReady) return@withContext Result.retry()
        val items=db.dao().allItems().associate { it.id to it.name }
        var retry=false

        for(photo in dao.waitingItemLinkEligible(today,cutoff,owner,20)) {
            val name=items[photo.itemId] ?: continue
            val journalId=runCatching {
                identity.cloudId("item",photo.itemId.toString())
            }.getOrNull()?.takeIf { id ->
                runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)
            }
            val cloudId=if(journalId!=null) journalId else try {
                gateway.resolveItemId(name)
            } catch(e:Exception) {
                retry=true
                null
            }
            if(cloudId!=null) {
                dao.bindVerifiedCloudItem(photo.photoId,cloudId,device,
                    System.currentTimeMillis())
            } else {
                // Missing cloud catalog linkage must remain retryable.
                retry=true
            }
        }

        var claimed=0
        for(photo in dao.pendingUploadEligible(today,cutoff,owner,MarketPhotoUploadPolicy.BATCH)){
            if(!MarketPhotoUploadPolicy.eligible(photo.businessDate,today,owner) ||
                (!owner && !MarketPhotoUploadPolicy.capturedOnDay(
                    photo.businessDate,photo.capturedAtMs))) continue
            val original=File(photo.localOriginalPath)
            val valid=runCatching {
                original.isFile &&
                MarketPhotoUploadPolicy.allowedLength(original.length()) &&
                original.length()==photo.bytes &&
                original.canonicalPath.startsWith(
                    File(app.filesDir,"invoices").canonicalPath+File.separator) &&
                MarketPhotoIdentity.sha256(original)==photo.sha256
            }.getOrDefault(false)
            if(!valid){
                retry=true
                continue
            }
            val itemId=photo.cloudItemId
            if(itemId==null){retry=true;continue}
            if(!runCatching { UUID.fromString(itemId).toString()==itemId }
                .getOrDefault(false))continue
            if(photo.originDeviceId!=device)continue
            if(dao.claimUpload(photo.photoId,System.currentTimeMillis())!=1)continue
            claimed++
            try {
                gateway.upload(original,photo.photoId,itemId,photo.businessDate,
                    Instant.ofEpochMilli(photo.capturedAtMs).toString())
                check(dao.confirmUploaded(photo.photoId,null,System.currentTimeMillis())==1)
            } catch(e:Exception) {
                retry=true
                Log.w("MarketPhotoSync", "Cloud photo upload failed for "+photo.photoId,e)
                dao.retryUpload(photo.photoId,e.message?.take(140)?:"Cloud unreachable",
                    System.currentTimeMillis())
            }
        }
        // Exponential WorkManager backoff avoids a busy retry loop.
        if(retry || (claimed==MarketPhotoUploadPolicy.BATCH &&
            dao.pendingEligibleCount(today,cutoff,owner)>0)) Result.retry()
        else Result.success()
    }

    private suspend fun registerRecentOriginals(
        app:Context,db:MarketDatabase,today:String,owner:Boolean
    ){
        val days=(0L..7L).map {
            LocalDate.parse(today).minusDays(it).toString()
        }
        val validItems=db.dao().allItems().map { it.id }.toSet()
        val photoDao=db.photoDao()
        var examined=0
        for(day in days){
            val dateFolder=File(File(app.filesDir,"invoices"),day)
            for(folder in dateFolder.listFiles().orEmpty().filter { it.isDirectory }){
                val id=folder.name.removePrefix("item-").toLongOrNull() ?: continue
                if(folder.name!="item-$id"||id !in validItems)continue
                for(file in folder.listFiles().orEmpty()){
                    if(examined++>=40)return
                    if(!file.isFile || !file.extension.equals("jpg",true) ||
                        !MarketPhotoUploadPolicy.allowedLength(file.length()))continue
                    val safe=runCatching{
                        folder.canonicalFile.parentFile==dateFolder.canonicalFile &&
                        file.canonicalFile.parentFile==folder.canonicalFile &&
                        file.inputStream().use{ it.read()==255 && it.read()==216 }
                    }.getOrDefault(false)
                    if(!safe)continue
                    runCatching {
                        val photoId=MarketPhotoIdentity.id(file,day,id)
                        if(photoDao.byId(photoId)!=null)return@runCatching
                        photoDao.insertIfAbsent(MarketPhotoEntity(
                            photoId=photoId,businessDate=day,itemId=id,
                            cloudItemId=null,originDeviceId=null,
                            localOriginalPath=file.canonicalPath,galleryUri=null,
                            sha256=MarketPhotoIdentity.sha256(file),bytes=file.length(),
                            capturedAtMs=file.lastModified().takeIf{it>0}
                                ?:System.currentTimeMillis(),
                            syncState="WAITING_ITEM_LINK",boxFileId=null,retryCount=0,
                            lastError=null,updatedAtMs=System.currentTimeMillis()
                        ))
                    }
                }
            }
        }
    }
}

internal object MarketPhotoSyncScheduler {
    private fun enabled()=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE
    private val network=Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun onPhotoSaved(context:Context){
        if(!enabled())return
        val request=OneTimeWorkRequestBuilder<MarketPhotoSyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30L,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("marketpro_photo_once",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    fun schedule(context:Context){
        if(!enabled())return
        val periodic=PeriodicWorkRequestBuilder<MarketPhotoSyncWorker>(15,TimeUnit.MINUTES)
            .setConstraints(network).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "marketpro_photo_retry",ExistingPeriodicWorkPolicy.UPDATE,periodic)
        // Immediate catch-up on app start, including older paused app versions.
        onPhotoSaved(context)
    }
}
