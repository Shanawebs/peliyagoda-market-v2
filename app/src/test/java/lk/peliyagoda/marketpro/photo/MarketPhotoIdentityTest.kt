package lk.peliyagoda.marketpro.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MarketPhotoIdentityTest {
    @Test fun sameOriginalGetsSameStablePhotoId(){
        val file=File.createTempFile("marketpro_qa",".jpg")
        try{
            file.writeBytes(byteArrayOf(0xFF.toByte(),0xD8.toByte(),3,4))
            val first=MarketPhotoIdentity.id(file,"2026-10-29",1L)
            assertEquals(first,MarketPhotoIdentity.id(file,"2026-10-29",1L))
            assertFalse(first==MarketPhotoIdentity.id(file,"2026-10-29",2L))
        }finally{file.delete()}
    }
    @Test fun sha256MatchesOriginalBytes(){
        val file=File.createTempFile("marketpro_qa",".jpg")
        try{
            val bytes=byteArrayOf(0xFF.toByte(),0xD8.toByte(),1,2,3,4,5)
            file.writeBytes(bytes)
            val expected=MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(""){"%02x".format(it)}
            assertEquals(expected,MarketPhotoIdentity.sha256(file))
        }finally{file.delete()}
    }
    @Test fun rejectInvalidBusinessDate(){
        assertTrue(runCatching{MarketPhotoIdentity.validateDay("2026-02-30")}.isFailure)
        assertTrue(runCatching{MarketPhotoIdentity.validateDay("2026-10-29")}.isSuccess)
    }
    @Test fun consistentGalleryName(){
        val photoId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertEquals("MarketPro_20261029_Item25_"+photoId+".jpg",
            MarketPhotoIdentity.filename("2026-10-29",25L,photoId))
    }
}
