package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.OtherExpenseAmounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherExpensesCloudPolicyTest {
    private val staff="33333333-3333-4333-8333-333333333333"
    private val w="11111111-1111-4111-8111-111111111111"
    private val today="2026-10-24"
    private fun draft(action:ExpenseCloudAction=ExpenseCloudAction.CREATE,
        date:String=today,revision:Long?=null) = OtherExpensesCloudDraft(
        w,"f1111111-1111-4111-8111-111111111111",
        "91111111-1111-4111-8111-111111111111",
        "a1000000-0000-4000-8000-000000000001",date,
        OtherExpenseAmounts(12000,3400,1300,200,"Daily driver receipt"),
        action,revision
    )
    private fun access(role:TeamRole=TeamRole.PURCHASING_STAFF,date:String=today,
        verified:Boolean=true):TeamAccessContext = TeamAccessContext(
        role=role,authenticatedUserId=staff,authorizedDeviceUserId=staff,
        deviceEnabled=true,accountMembershipVerified=true,
        businessDate=date,serverBusinessToday=today,
        dateVerifiedByServer=verified
    )
    private fun denied(block:()->Unit) {
        var rejected=false
        try{block()}catch(_:IllegalArgumentException){rejected=true}
        assertTrue("Unsafe expenses Cloud draft was accepted",rejected)
    }
    @Test fun allFourExpenseCategoriesAndRemarksAreTransferredInIntegerCents() {
        val p=OtherExpensesCloudPolicy.validate(draft(),access(),null,false)
        assertEquals(6,p.size)
        assertEquals(12000L,p["transport_cents"])
        assertEquals(3400L,p["labor_charge_cents"])
        assertEquals(1300L,p["meals_drinks_cents"])
        assertEquals(200L,p["others_cents"])
        assertEquals(16900L,listOf("transport_cents","labor_charge_cents",
            "meals_drinks_cents","others_cents").sumOf { p[it] as Long })
        assertEquals("Daily driver receipt",p["remarks"])
        assertFalse(p.keys.any { it.contains("photo") || it.contains("path") })
    }
    @Test fun staffCanCorrectTodayOnlyWithExactCloudRevision() {
        OtherExpensesCloudPolicy.validate(
            draft(ExpenseCloudAction.CORRECT,revision=2),access(),2,true)
        denied { OtherExpensesCloudPolicy.validate(
            draft(ExpenseCloudAction.CORRECT,revision=1),access(),2,true) }
        denied { OtherExpensesCloudPolicy.validate(
            draft(ExpenseCloudAction.CORRECT,date="2026-10-23",revision=2),
            access(date="2026-10-23"),2,true) }
    }
    @Test fun ownerCanCorrectOldDayButNoFutureDayWrites() {
        OtherExpensesCloudPolicy.validate(
            draft(ExpenseCloudAction.CORRECT,date="2026-10-23",revision=3),
            access(TeamRole.OWNER,date="2026-10-23"),3,true)
        denied { OtherExpensesCloudPolicy.validate(
            draft(ExpenseCloudAction.CREATE,date="2026-10-25"),
            access(TeamRole.OWNER,date="2026-10-25"),null,false) }
    }
    @Test fun noUnverifiedServerDateOrViewerPrivilegeOrDuplicateDate() {
        denied { OtherExpensesCloudPolicy.validate(
            draft(),access(verified=false),null,false) }
        denied { OtherExpensesCloudPolicy.validate(
            draft(),access(TeamRole.VIEWER),null,false) }
        denied { OtherExpensesCloudPolicy.validate(draft(),access(),null,true) }
    }
    @Test fun cannotUploadWithMissingCloudIdOrInvalidBusinessDate() {
        denied { OtherExpensesCloudPolicy.validate(
            draft().copy(cloudId="not-a-uuid"),access(),null,false) }
        denied { OtherExpensesCloudPolicy.validate(
            draft(date="wrong"),access(date="wrong"),null,false) }
    }
    @Test fun allNetworkGatesMustPassAndPreviewAlwaysBlocked() {
        assertFalse(OtherExpensesCloudPolicy.networkReady(true,true,true,true,true))
        assertFalse(OtherExpensesCloudPolicy.networkReady(false,true,false,true,true))
        assertFalse(OtherExpensesCloudPolicy.networkReady(false,true,true,true,false))
        assertTrue(OtherExpensesCloudPolicy.networkReady(false,true,true,true,true))
    }
}
