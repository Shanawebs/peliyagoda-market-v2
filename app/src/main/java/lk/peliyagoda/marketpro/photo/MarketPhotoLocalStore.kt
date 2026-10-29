package lk.peliyagoda.marketpro.photo

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.data.MarketPhotoEntity
import java.io.File
import java.io.IOException

/** Save camera original unchanged; register it in Room and MediaStore. No Box requests here. */
internal class MarketPhotoLocalStore(private val context:Context,private val db:MarketDatabase) {
    companion object { private val lock=Mutex() }
    data class Result(val photoId:String,val gallerySaved:Boolean)

    suspend fun registerCapturedOriginal(day:String,itemId:Long,original:File):Result =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val date=MarketPhotoIdentity.validateDay(day)
                require(itemId>0)
                val expectedFolder=File(File(File(context.filesDir,"invoices"),date),"item-$itemId").canonicalFile
                val safe=original.canonicalFile
                require(safe.parentFile==expectedFolder && safe.extension.equals("jpg",true))
                require(safe.isFile && safe.length() in 1L..52_428_800L)
                safe.inputStream().use { stream ->
                    require(stream.read()==255 && stream.read()==216) { "Invalid JPEG" }
                }
                val id=MarketPhotoIdentity.id(safe,date,itemId)
                val hash=MarketPhotoIdentity.sha256(safe)
                val photoDao=db.photoDao()
                val old=photoDao.byId(id)
                if(old==null) {
                    photoDao.insertIfAbsent(MarketPhotoEntity(
                        photoId=id,businessDate=date,itemId=itemId,
                        cloudItemId=null,originDeviceId=null,localOriginalPath=safe.absolutePath,
                        galleryUri=null,sha256=hash,bytes=safe.length(),
                        capturedAtMs=safe.lastModified().takeIf{it>0}?:System.currentTimeMillis(),
                        syncState="WAITING_ITEM_LINK",boxFileId=null,
                        retryCount=0,lastError=null,updatedAtMs=System.currentTimeMillis()
                    ))
                } else {
                    require(old.sha256==hash && old.bytes==safe.length()) {
                        "Original photo has changed after registration"
                    }
                }
                val registered=requireNotNull(photoDao.byId(id))
                registered.galleryUri?.let { saved ->
                    val uri=Uri.parse(saved)
                    if(sameHash(uri,hash))return@withLock Result(id,true)
                    photoDao.forgetMissingGalleryUri(id,saved,System.currentTimeMillis())
                }
                if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q)
                    return@withLock Result(id,false)
                val gallery=copyOriginalToGallery(safe,date,itemId,id,hash)
                photoDao.saveGalleryUri(id,gallery.toString(),System.currentTimeMillis())
                Result(id,true)
            }
        }

    private fun sameHash(uri:Uri,expected:String):Boolean=runCatching{
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer=ByteArray(65536)
            while(true){
                val length=stream.read(buffer)
                if(length<0)break
                digest.update(buffer,0,length)
            }
        }?:throw IOException("Gallery entry unavailable")
        digest.digest().joinToString(""){"%02x".format(it)}==expected
    }.getOrDefault(false)

    private fun existingCopy(day:String,itemId:Long,id:String,hash:String):Uri? {
        val resolver=context.contentResolver
        val filename=MarketPhotoIdentity.filename(day,itemId,id)
        val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.IS_PENDING)
        val selection="${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,projection,selection,
            arrayOf(filename,Environment.DIRECTORY_PICTURES+"/Market Pro%"),null)?.use{ cursor ->
            while(cursor.moveToNext()){
                val uri=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)))
                require(sameHash(uri,hash)) { "Same Gallery filename contains different image" }
                if(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING))!=0) {
                    val update=ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)}
                    require(resolver.update(uri,update,null,null)>0)
                }
                return uri
            }
        }
        return null
    }

    private fun copyOriginalToGallery(original:File,day:String,itemId:Long,id:String,hash:String):Uri {
        existingCopy(day,itemId,id,hash)?.let{return it}
        val resolver=context.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,MarketPhotoIdentity.filename(day,itemId,id))
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Market Pro")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
            ?:throw IOException("Unable to create Gallery backup")
        try {
            resolver.openOutputStream(uri,"w")?.use { destination ->
                original.inputStream().buffered().use{source->source.copyTo(destination,65536)}
            }?:throw IOException("Gallery write failed")
            require(sameHash(uri,hash)){"Gallery backup failed SHA256 verification"}
            require(resolver.update(uri,ContentValues().apply{
                put(MediaStore.Images.Media.IS_PENDING,0)
            },null,null)>0)
            return uri
        } catch(error:Exception){
            // Delete only the new Gallery row, never the protected camera original.
            runCatching{resolver.delete(uri,null,null)}
            throw error
        }
    }
}
