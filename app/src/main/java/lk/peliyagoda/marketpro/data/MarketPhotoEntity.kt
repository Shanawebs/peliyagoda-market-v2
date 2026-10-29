package lk.peliyagoda.marketpro.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="market_photos",indices=[Index(value=["businessDate","itemId"]),Index(value=["syncState","capturedAtMs"])])
data class MarketPhotoEntity(
 @PrimaryKey val photoId:String,val businessDate:String,val itemId:Long,
 val cloudItemId:String?,val originDeviceId:String?,val localOriginalPath:String,
 val galleryUri:String?,val sha256:String,val bytes:Long,val capturedAtMs:Long,
 val syncState:String,val boxFileId:String?,val retryCount:Int,
 val lastError:String?,val updatedAtMs:Long
)

@Dao interface MarketPhotoDao {
 @Insert(onConflict=OnConflictStrategy.IGNORE)
 suspend fun insertIfAbsent(photo:MarketPhotoEntity):Long
 @Query("SELECT * FROM market_photos WHERE photoId=:id LIMIT 1")
 suspend fun byId(id:String):MarketPhotoEntity?
 @Query("SELECT * FROM market_photos WHERE businessDate=:day AND itemId=:itemId ORDER BY capturedAtMs,photoId")
 fun observePhotos(day:String,itemId:Long):Flow<List<MarketPhotoEntity>>
 @Query("UPDATE market_photos SET galleryUri=:uri,updatedAtMs=:now WHERE photoId=:id AND galleryUri IS NULL")
 suspend fun saveGalleryUri(id:String,uri:String,now:Long):Int
 @Query("UPDATE market_photos SET galleryUri=NULL,updatedAtMs=:now WHERE photoId=:id AND galleryUri=:oldUri")
 suspend fun forgetMissingGalleryUri(id:String,oldUri:String,now:Long):Int
 @Query("SELECT * FROM market_photos WHERE syncState='WAITING_ITEM_LINK' ORDER BY capturedAtMs,photoId LIMIT :maxRows")
 suspend fun waitingItemLink(maxRows:Int):List<MarketPhotoEntity>
 @Query("SELECT * FROM market_photos WHERE syncState='PENDING_UPLOAD' ORDER BY capturedAtMs,photoId LIMIT :maxRows")
 suspend fun pendingUpload(maxRows:Int):List<MarketPhotoEntity>

 @Query("UPDATE market_photos SET cloudItemId=:cloudId,originDeviceId=:deviceId,syncState='PENDING_UPLOAD',updatedAtMs=:now WHERE photoId=:photoId AND syncState='WAITING_ITEM_LINK' AND cloudItemId IS NULL")
 suspend fun bindVerifiedCloudItem(photoId:String,cloudId:String,deviceId:String,now:Long):Int
 @Query("UPDATE market_photos SET syncState='UPLOADING',updatedAtMs=:now WHERE photoId=:photoId AND syncState='PENDING_UPLOAD'")
 suspend fun claimUpload(photoId:String,now:Long):Int
 @Query("UPDATE market_photos SET syncState='UPLOADED',boxFileId=:boxId,lastError=NULL,updatedAtMs=:now WHERE photoId=:photoId AND syncState='UPLOADING'")
 suspend fun confirmUploaded(photoId:String,boxId:String?,now:Long):Int
 @Query("UPDATE market_photos SET syncState='PENDING_UPLOAD',retryCount=retryCount+1,lastError=:reason,updatedAtMs=:now WHERE photoId=:photoId AND syncState='UPLOADING'")
 suspend fun retryUpload(photoId:String,reason:String,now:Long):Int
 @Query("UPDATE market_photos SET syncState='PENDING_UPLOAD',updatedAtMs=:now WHERE syncState='UPLOADING' AND updatedAtMs<:beforeMs")
 suspend fun recoverInterruptedUploads(beforeMs:Long,now:Long):Int
}