package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherExpensesShadowPolicyTest {
    private fun denied(action:()->Unit) {
        var thrown = false
        try { action() } catch (_:IllegalArgumentException) { thrown = true }
        assertTrue("Cloud expenses should be rejected",thrown)
    }

    @Test fun fourAmountsAreNotMergedOrRounded() {
        val one = OtherExpensesShadowPolicy.signature(
            "2026-10-24",100,220,330,400,"Meals & Snacks")
        val two = OtherExpensesShadowPolicy.signature(
            "2026-10-24",100,220,331,400,"Meals & Snacks")
        assertNotEquals(one,two)
        assertEquals(one,OtherExpensesShadowPolicy.signature(
            "2026-10-24",100,220,330,400,"Meals & Snacks"))
    }

    @Test fun remarksCannotCollideWithAmountDelimiters() {
        val a=OtherExpensesShadowPolicy.signature(
            "2026-10-24",1,23,4,5,"a|b")
        val b=OtherExpensesShadowPolicy.signature(
            "2026-10-24",12,3,4,5,"a|b")
        assertNotEquals(a,b)
        assertNotEquals(a,OtherExpensesShadowPolicy.signature(
            "2026-10-24",1,23,4,5,"a"))
    }

    @Test fun allNegativeAndOverLimitExpensesDenied() {
        denied { OtherExpensesShadowPolicy.validateAmounts(-1,0,0,0,"") }
        denied { OtherExpensesShadowPolicy.validateAmounts(0,-1,0,0,"") }
        denied { OtherExpensesShadowPolicy.validateAmounts(0,0,-1,0,"") }
        denied { OtherExpensesShadowPolicy.validateAmounts(0,0,0,-1,"") }
        denied { OtherExpensesShadowPolicy.validateAmounts(
            9999999999L+1,0,0,0,"") }
    }

    @Test fun abnormalRemarksRejected() {
        denied { OtherExpensesShadowPolicy.validateAmounts(
            0,0,0,0,"A".repeat(501)) }
        denied { OtherExpensesShadowPolicy.validateAmounts(0,0,0,0,"a\u0000b") }
    }
}
