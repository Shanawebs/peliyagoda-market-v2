package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudInboxPagePolicyTest {
    private val workspace = "e46b90c5-ed6a-4952-82e9-8582fac06d89"
    private fun watermark(id: Int, at: String = "2026-10-24T08:00:00+00:00") =
        InboxWatermark(at,"d0000000-0000-4000-8000-" + id.toString().padStart(12,'0'))

    @Test fun firstPageAndPaginatedContinuations() {
        val page1=listOf(watermark(1),watermark(2))
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,page1,true,page1.last(),"2026-10-24"
        )
        CloudInboxPagePolicy.validate(
            "purchase",workspace,page1.last(),
            listOf(watermark(3)),false,watermark(3),"2026-10-24"
        )
    }

    @Test fun revisionMonotonicallyAdvancesAndNeverRollsBack() {
        assertTrue(CloudInboxPagePolicy.shouldReplaceShadowSnapshot(null,1))
        assertTrue(CloudInboxPagePolicy.shouldReplaceShadowSnapshot(2,3))
        assertFalse(CloudInboxPagePolicy.shouldReplaceShadowSnapshot(2,2))
        assertFalse(CloudInboxPagePolicy.shouldReplaceShadowSnapshot(2,1))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectZeroRevision() {
        CloudInboxPagePolicy.shouldReplaceShadowSnapshot(1,0)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectDuplicateRowInSamePage() {
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,
            listOf(watermark(1),watermark(1)),
            false,watermark(1),"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectCursorRollback() {
        CloudInboxPagePolicy.validate(
            "purchase",workspace,watermark(5),
            listOf(watermark(3)),false,watermark(3),"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectSpoofedCursorJump() {
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,listOf(watermark(1)),
            false,watermark(40),"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectEmptyNonterminalPage() {
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,emptyList(),true,null,"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectWrongType() {
        CloudInboxPagePolicy.validate(
            "sync_operations",workspace,null,emptyList(),false,null,"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectTooManyRows() {
        val rows=(1..101).map { watermark(it) }
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,rows,true,rows.last(),"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectBadCloudTimestamp() {
        val invalid=InboxWatermark("today","d0000000-0000-4000-8000-000000000001")
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,listOf(invalid),false,invalid,"2026-10-24"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectBadServerBusinessDate() {
        CloudInboxPagePolicy.validate(
            "purchase",workspace,null,emptyList(),false,null,"2026-02-30"
        )
    }

    @Test fun shadowPageNeverIncludesPhotosOrOperations() {
        assertFalse("invoice_photos" in CloudInboxPagePolicy.allowedTypes)
        assertFalse("sync_operations" in CloudInboxPagePolicy.allowedTypes)
        assertFalse("daily_other_expenses" in CloudInboxPagePolicy.allowedTypes)
        assertTrue("daily_cash_received" in CloudInboxPagePolicy.allowedTypes)
    }
}
