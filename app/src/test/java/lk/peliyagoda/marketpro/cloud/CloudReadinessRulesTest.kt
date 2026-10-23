package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReadinessRulesTest {
    @Test fun contentRangeParsesNonEmptyAndEmptyRows() {
        assertEquals(184, CloudReadinessRules.countFromContentRange("0-0/184"))
        assertEquals(0, CloudReadinessRules.countFromContentRange("*/0"))
        assertEquals(331, CloudReadinessRules.countFromContentRange("0-0/331"))
    }

    @Test(expected = java.io.IOException::class)
    fun missingContentRangeMustFailClosed() {
        CloudReadinessRules.countFromContentRange(null)
    }

    @Test(expected = java.io.IOException::class)
    fun unknownContentRangeMustFailClosed() {
        CloudReadinessRules.countFromContentRange("0-0/*")
    }

    @Test fun expectedDataScopeIsExplicitlyAllowlisted() {
        assertEquals(
            listOf(
                "categories", "items", "requisitions", "purchases",
                "purchase_additions", "daily_cash_received"
            ),
            CloudReadinessRules.tableNames
        )
        assertTrue(CloudReadinessRules.previewWriteIsBlocked())
    }
}
