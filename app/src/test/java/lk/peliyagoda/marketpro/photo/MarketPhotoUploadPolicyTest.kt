package lk.peliyagoda.marketpro.photo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketPhotoUploadPolicyTest {
    @Test fun bothPhonesCanUploadSameDay() {
        assertTrue(MarketPhotoUploadPolicy.eligible("2026-10-29","2026-10-29",false))
        assertTrue(MarketPhotoUploadPolicy.eligible("2026-10-29","2026-10-29",true))
    }
    @Test fun immutableDelayedEvidenceCanUploadButFinancialEditsRemainOwnerOnly() {
        assertTrue(MarketPhotoUploadPolicy.eligible("2026-10-28","2026-10-29",false))
        assertFalse(MarketPhotoUploadPolicy.eligible("2026-10-21","2026-10-29",false))
        assertTrue(MarketPhotoUploadPolicy.eligible("2026-10-21","2026-10-29",true))
        assertFalse(MarketPhotoUploadPolicy.eligible("2026-10-30","2026-10-29",true))
        assertTrue(MarketPhotoUploadPolicy.capturedOnDay(
            "2026-10-29",java.time.Instant.parse("2026-10-29T15:45:00Z").toEpochMilli()))
        assertFalse(MarketPhotoUploadPolicy.capturedOnDay(
            "2026-10-28",java.time.Instant.parse("2026-10-29T15:45:00Z").toEpochMilli()))
    }
    @Test fun invalidDatesAndOversizedFilesNeverUpload() {
        assertFalse(MarketPhotoUploadPolicy.eligible("bad","2026-10-29",true))
        assertFalse(MarketPhotoUploadPolicy.allowedLength(17L*1024*1024))
        assertTrue(MarketPhotoUploadPolicy.allowedLength(3502L*1024L))
    }
}
