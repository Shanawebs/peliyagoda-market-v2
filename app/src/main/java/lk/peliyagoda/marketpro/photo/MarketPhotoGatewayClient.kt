package lk.peliyagoda.marketpro.photo

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.CloudOwnerAuth
import lk.peliyagoda.marketpro.cloud.CloudStaffAuth
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.TeamRole
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Authenticated staging-only photo API client; neither Box token is present on device. */
internal class MarketPhotoGatewayClient(context:Context) {
    private val app=context.applicationContext
    private val uploadUrl=URL("${BuildConfig.MARKETPRO_SUPABASE_URL}/functions/v1/marketpro-photo-gateway-v430")
    private val enrollUrl=URL("${BuildConfig.MARKETPRO_SUPABASE_URL}/functions/v1/marketpro-photo-key-enroll-v430")
    data class CloudPhoto(val id:String,val sha256:String,val bytes:Long,val capturedAt:String, val itemId:String?=null, val itemName:String?=null)

    private fun uuid(input:String):String=
        UUID.fromString(input).toString().also {require(it==input)}
    private fun guard() {
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW && BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
        check(uploadUrl.protocol=="https" && enrollUrl.protocol=="https" &&
            uploadUrl.host==BuildConfig.MARKETPRO_SUPABASE_HOST &&
            enrollUrl.host==BuildConfig.MARKETPRO_SUPABASE_HOST &&
            uploadUrl.port==-1 && enrollUrl.port==-1)
    }
    private suspend fun bearer():String=when(MarketProActiveRole.current(app)){
        TeamRole.OWNER -> CloudOwnerAuth(app).accessTokenForOwner(BuildConfig.MARKETPRO_OWNER_USER_ID)
        TeamRole.PURCHASING_STAFF -> CloudStaffAuth(app).verifiedPhoneSession().accessToken
        else -> error("Sign in as Owner or Team")
    }
    private fun sha256(file:File):String{
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use {input ->
            val block=ByteArray(65536)
            while(true) {val size=input.read(block);if(size<0)break;digest.update(block,0,size)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private suspend fun request(
        action:String,body:ByteArray,
        extra:Map<String,String> = emptyMap(),
        enrollment:Boolean=false,imageTo:File?=null
    ):JSONObject?=withContext(Dispatchers.IO){
        guard()
        require(body.size in 1..(16*1024*1024))
        val device=uuid(CloudDeviceIdentity.get(app))
        val proof=MarketPhotoDeviceProof.sign(action,device,body)
        val destination=if(enrollment)enrollUrl else uploadUrl
        val token=bearer()
        val conn=(destination.openConnection() as HttpURLConnection).apply{
            requestMethod="POST"
            doOutput=true
            instanceFollowRedirects=false
            connectTimeout=15000
            readTimeout=90000
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer $token")
            setRequestProperty("Content-Type",if(action=="upload")"image/jpeg" else "application/json")
            setRequestProperty("Accept",if(imageTo==null)"application/json" else "image/jpeg")
            setRequestProperty("X-MP-Photo-Action",action)
            setRequestProperty("X-MP-Device",proof.deviceId)
            setRequestProperty("X-MP-Timestamp",proof.timestamp.toString())
            setRequestProperty("X-MP-Nonce",proof.nonce)
            setRequestProperty("X-MP-Signature",proof.signature)
            setRequestProperty("X-MP-Body-Sha256",proof.bodySha256)
            extra.forEach{(name,value)->
                require(name in setOf("X-MP-Item","X-MP-Photo","X-MP-Day","X-MP-Captured-At"))
                setRequestProperty(name,value)
            }
        }
        try{
            conn.outputStream.use{it.write(body)}
            val status=conn.responseCode
            if(status !in 200..299) {
                val reason=when(status){
                    401,403 -> "Photo access denied. Verify approved phone, login and Owner MFA."
                    409 -> "Photo request conflict or busy server. Retry or request Owner review."
                    404 -> "Photo Cloud backend is unavailable."
                    429,500,502,503,504 -> "Photo Cloud temporarily unavailable. Auto retry will follow."
                    else -> "Photo request rejected (HTTP $status)"
                }
                throw IOException(reason)
            }
            if(imageTo!=null) {
                check(action=="download" && conn.contentType?.startsWith("image/jpeg")==true)
                FileOutputStream(imageTo).use {output ->
                    conn.inputStream.use {input ->
                        val buf=ByteArray(65536)
                        var total=0L
                        while(true){
                            val count=input.read(buf)
                            if(count<0)break
                            total+=count
                            require(total<=16L*1024*1024)
                            output.write(buf,0,count)
                        }
                    }
                }
                return@withContext null
            }
            val bodyText=conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
                .use{it.readText().take(50_000)}
            val reply=JSONObject(bodyText)
            require(reply.optBoolean("ok")) { "Photo Cloud response was not successful" }
            reply
        }finally{conn.disconnect()}
    }
    suspend fun enrollThisPhone():String{
        val device=uuid(CloudDeviceIdentity.get(app))
        val payload=JSONObject().put("action","enroll").put("device_id",device)
            .put("public_key_spki",MarketPhotoDeviceProof.publicKeySpkiBase64url())
        return requireNotNull(request("enroll",payload.toString().toByteArray(StandardCharsets.UTF_8),
            enrollment=true)).getString("code")
    }
    suspend fun approveTeamPhotoKey(teamDeviceId:String):String{
        MarketProActiveRole.requireOwnerAdmin(app)
        val payload=JSONObject().put("action","approve")
            .put("device_id",uuid(CloudDeviceIdentity.get(app)))
            .put("target_device_id",uuid(teamDeviceId))
        return requireNotNull(request("approve",payload.toString().toByteArray(StandardCharsets.UTF_8),
            enrollment=true)).getString("code")
    }
    /** Signed and authorized exact catalog lookup; rejects collisions. */
    suspend fun resolveItemId(itemName:String):String? {
        require(itemName.isNotBlank() && itemName==itemName.trim() &&
            itemName.length<=250)
        val payload=JSONObject().put("item_name",itemName)
        val reply=requireNotNull(request("resolve_item",
            payload.toString().toByteArray(StandardCharsets.UTF_8)))
        val id=reply.optString("item_id")
        return if(id.isBlank() || id=="null") null else uuid(id)
    }

    suspend fun upload(original:File,photoId:String,cloudItemId:String,day:String,capturedAt:String):String{
        uuid(photoId);uuid(cloudItemId)
        require(original.isFile && original.length() in 100L..(16L*1024*1024))
        check(original.canonicalPath.contains("/invoices/"))
        require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val bytes=withContext(Dispatchers.IO){original.readBytes()}
        check((bytes[0].toInt() and 255)==255 && (bytes[1].toInt() and 255)==216)
        val data=requireNotNull(request("upload",bytes,mapOf(
            "X-MP-Item" to cloudItemId,"X-MP-Photo" to photoId,
            "X-MP-Day" to day,"X-MP-Captured-At" to capturedAt)))
        check(data.getString("photo_id")==photoId)
        return data.getString("code")
    }
    suspend fun list(cloudItemId:String,day:String):List<CloudPhoto>{
        val payload=JSONObject().put("item_id",uuid(cloudItemId))
            .put("business_date",day)
        val reply=requireNotNull(request("list",payload.toString().toByteArray(StandardCharsets.UTF_8)))
        val items=reply.getJSONArray("photos")
        require(items.length()<=200)
        return (0 until items.length()).map {i ->
            val photo=items.getJSONObject(i)
            CloudPhoto(uuid(photo.getString("photo_id")),photo.getString("sha256"),
                photo.getLong("bytes"),photo.getString("captured_at"))
        }
    }
    /**
     * Signed read-only date gallery. The server determines the item's canonical
     * cloud UUID and display name; local Room UUID mappings are not required.
     * The old per-item list endpoint remains unchanged for older APK versions.
     */
    suspend fun listDay(day:String):List<CloudPhoto>{
        require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(java.time.LocalDate.parse(day).toString()==day)
        val payload=JSONObject().put("business_date",day)
        val reply=requireNotNull(
            request("list_day",payload.toString().toByteArray(StandardCharsets.UTF_8))
        )
        val entries=reply.getJSONArray("photos")
        require(entries.length()<=200)
        return (0 until entries.length()).map { i ->
            val row=entries.getJSONObject(i)
            val name=row.getString("item_name").trim().take(90)
            CloudPhoto(
                id=uuid(row.getString("photo_id")),
                sha256=row.getString("sha256"),
                bytes=row.getLong("bytes"),
                capturedAt=row.getString("captured_at"),
                itemId=uuid(row.getString("item_id")),
                itemName=name
            )
        }
    }

    suspend fun download(photo:CloudPhoto):File=withContext(Dispatchers.IO){
        require(photo.bytes in 1L..(16L*1024*1024))
        val dir=File(app.cacheDir,"marketpro_box_previews")
        check(dir.isDirectory||dir.mkdirs())
        val dest=File(dir,uuid(photo.id)+".jpg")
        if(dest.isFile&&dest.length()==photo.bytes&&sha256(dest)==photo.sha256)
            return@withContext dest
        val tmp=File(dir,photo.id+".part")
        try {
            val payload=JSONObject().put("photo_id",photo.id).toString()
                .toByteArray(StandardCharsets.UTF_8)
            request("download",payload,imageTo=tmp)
            check(tmp.length()==photo.bytes&&sha256(tmp)==photo.sha256){
                "Downloaded photo failed checksum verification"
            }
            if(dest.exists())dest.delete()
            check(tmp.renameTo(dest))
            dest
        }finally{if(tmp.exists())tmp.delete()}
    }
}
