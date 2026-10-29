package lk.peliyagoda.marketpro.photo
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

internal object MarketPhotoIdentity {
 fun day(input:String):String {
   val parsed=LocalDate.parse(input)
   require(parsed.year in 2000..2100 && parsed.toString()==input)
   return input
 }
 fun id(original:File,day:String,itemId:Long):String {
   day(day);require(itemId>0 && original.extension.equals("jpg",true))
   val canonical="marketpro-local-jpeg-v1|$day|$itemId|"+original.canonicalPath
   return UUID.nameUUIDFromBytes(canonical.toByteArray(StandardCharsets.UTF_8)).toString()
 }
 fun sha256(file:File):String {
   require(file.isFile && file.length() in 1L..52_428_800L)
   val digest=MessageDigest.getInstance("SHA-256")
   file.inputStream().buffered().use { input ->
     val block=ByteArray(65536)
     while(true) {val size=input.read(block);if(size<0)break;digest.update(block,0,size)}
   }
   return digest.digest().joinToString(""){"%02x".format(it)}
 }
 fun filename(day:String,itemId:Long,id:String):String {
   day(day);require(itemId>0 && UUID.fromString(id).toString()==id)
   return "MarketPro_"+day.replace("-","")+"_Item"+itemId+"_"+id+".jpg"
 }
}