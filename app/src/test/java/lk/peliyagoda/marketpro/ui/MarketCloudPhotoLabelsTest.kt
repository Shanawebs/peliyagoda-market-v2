package lk.peliyagoda.marketpro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCloudPhotoLabelsTest {
    @Test fun distinctCloudPhotosCapturedInTheSameMinuteHaveDifferentLabels() {
        val first = MarketCloudPhotoLabels.title(0,"2026-10-30T04:55:29.048Z")
        val second = MarketCloudPhotoLabels.title(1,"2026-10-30T04:55:38.443Z")
        assertNotEquals(first,second)
        assertTrue(first.contains("10:25:29"))
        assertTrue(second.contains("10:25:38"))
    }
    @Test fun immutableIdsAndSizesDistinguishSeparateOriginals() {
        val a = MarketCloudPhotoLabels.detail(2724817L,"c3f82d68-3c5d-39ee-a074-f140d5121c34")
        val b = MarketCloudPhotoLabels.detail(2965222L,"a74914fc-8b2a-38cc-8db0-8c52ebfc9967")
        assertNotEquals(a,b)
        assertTrue(a.contains("c3f82d68"))
        assertTrue(b.contains("a74914fc"))
    }
    @Test fun fallbackDoesNotCrashForMalformedCaptureTime() {
        assertEquals("bad",MarketCloudPhotoLabels.localTimestamp("bad"))
    }
}
