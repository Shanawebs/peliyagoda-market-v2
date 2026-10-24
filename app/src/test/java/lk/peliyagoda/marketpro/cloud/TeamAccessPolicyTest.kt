package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamAccessPolicyTest {
    private val ownerId = "22222222-2222-4222-8222-222222222222"
    private val staffId = "33333333-3333-4333-8333-333333333333"
    private val today = "2026-10-24"

    private fun ctx(
        role: TeamRole = TeamRole.OWNER,
        userId: String = ownerId,
        pin: Boolean = false,
        date: String = today
    ) = TeamAccessContext(
        role = role,
        authenticatedUserId = userId,
        authorizedDeviceUserId = userId,
        deviceEnabled = true,
        accountMembershipVerified = true,
        ownerPinVerifiedForThisAction = pin,
        businessDate = date,
        serverBusinessToday = today,
        dateVerifiedByServer = true
    )

    @Test fun staffCanCreateAndCorrectAnyNumberOfTimesOnCurrentBusinessDay() {
        val staff = ctx(role = TeamRole.PURCHASING_STAFF,userId = staffId)
        val todayActions = listOf(
            TeamAction.CREATE_PURCHASE, TeamAction.CREATE_REQUISITION,
            TeamAction.CREATE_EXTRA_LOT, TeamAction.ENTER_CASH,
            TeamAction.ENTER_OTHER_EXPENSES,
            TeamAction.CORRECT_PURCHASE, TeamAction.CORRECT_REQUISITION,
            TeamAction.CORRECT_EXTRA_LOT, TeamAction.CORRECT_CASH,
            TeamAction.CORRECT_OTHER_EXPENSES
        )
        for (a in todayActions) {
            repeat(10) { assertTrue(TeamAccessPolicy.authorize(staff, a).allowed) }
        }
    }

    @Test fun yesterdayAndOlderDaysAreReadOnlyForStaffEvenOnSamePhone() {
        val staff = ctx(role=TeamRole.PURCHASING_STAFF,userId=staffId,date="2026-10-23")
        for (a in listOf(
            TeamAction.CREATE_PURCHASE,TeamAction.CREATE_REQUISITION,
            TeamAction.ENTER_CASH,TeamAction.ENTER_OTHER_EXPENSES,
            TeamAction.CORRECT_PURCHASE,TeamAction.CORRECT_REQUISITION,
            TeamAction.CORRECT_EXTRA_LOT,TeamAction.CORRECT_CASH,
            TeamAction.CORRECT_OTHER_EXPENSES
        )) assertFalse(TeamAccessPolicy.authorize(staff,a).allowed)
        assertTrue(TeamAccessPolicy.authorize(staff,TeamAction.VIEW).allowed)
        assertTrue(TeamAccessPolicy.authorize(staff,TeamAction.EXPORT_REPORT).allowed)
    }

    @Test fun ownerCanCorrectPastAndTodayWithoutReviewQueueOrPinForOrdinaryEdits() {
        val past = ctx(date="2026-10-01")
        val current = ctx()
        for (action in listOf(
            TeamAction.CORRECT_PURCHASE,TeamAction.CORRECT_REQUISITION,
            TeamAction.CORRECT_EXTRA_LOT,TeamAction.CORRECT_CASH,
            TeamAction.CORRECT_OTHER_EXPENSES
        )) {
            assertTrue(TeamAccessPolicy.authorize(past,action).allowed)
            assertTrue(TeamAccessPolicy.authorize(current,action).allowed)
        }
    }

    @Test fun staffEditingAfterMidnightMustBeRecheckedAgainstServerToday() {
        val yesterday = ctx(role=TeamRole.PURCHASING_STAFF,userId=staffId,date=today)
            .copy(serverBusinessToday="2026-10-25")
        assertFalse(TeamAccessPolicy.authorize(
            yesterday,TeamAction.CORRECT_PURCHASE
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            yesterday,TeamAction.ENTER_CASH
        ).allowed)
    }

    @Test fun phoneClockOrUnverifiedDateCannotGrantStaffOldDayRights() {
        val staff = ctx(role=TeamRole.PURCHASING_STAFF,userId=staffId)
        assertFalse(TeamAccessPolicy.authorize(
            staff.copy(dateVerifiedByServer=false),TeamAction.CORRECT_PURCHASE
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            staff.copy(serverBusinessToday=null),TeamAction.CORRECT_PURCHASE
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            staff.copy(businessDate=null),TeamAction.CORRECT_PURCHASE
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            staff.copy(businessDate="2026-02-30"),TeamAction.CORRECT_PURCHASE
        ).allowed)
    }

    @Test fun futureDateDeniedForBothStaffAndOwner() {
        val tomorrow="2026-10-25"
        assertFalse(TeamAccessPolicy.authorize(
            ctx(date=tomorrow),TeamAction.CORRECT_CASH
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            ctx(role=TeamRole.PURCHASING_STAFF,userId=staffId,date=tomorrow),
            TeamAction.CREATE_PURCHASE
        ).allowed)
    }

    @Test fun staffStillCannotDeleteChangeCatalogOrManageDevices() {
        val staff=ctx(role=TeamRole.PURCHASING_STAFF,userId=staffId,pin=true)
        for (action in listOf(
            TeamAction.MODIFY_CATALOG,TeamAction.DELETE_RECORD,
            TeamAction.REGISTER_DEVICE,TeamAction.REVOKE_DEVICE
        )) assertFalse(TeamAccessPolicy.authorize(staff,action).allowed)
    }

    @Test fun ownerPinNeededOnlyForHighRiskActions() {
        assertTrue(TeamAccessPolicy.authorize(ctx(),TeamAction.CORRECT_PURCHASE).allowed)
        assertFalse(TeamAccessPolicy.authorize(ctx(),TeamAction.REVOKE_DEVICE).allowed)
        assertFalse(TeamAccessPolicy.authorize(ctx(),TeamAction.DELETE_RECORD).allowed)
        assertTrue(TeamAccessPolicy.authorize(ctx(pin=true),TeamAction.REVOKE_DEVICE).allowed)
    }

    @Test fun viewerAndDisabledDeviceNeverWrite() {
        assertFalse(TeamAccessPolicy.authorize(
            ctx(role=TeamRole.VIEWER),TeamAction.CORRECT_PURCHASE
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            ctx().copy(deviceEnabled=false),TeamAction.VIEW
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            ctx().copy(authorizedDeviceUserId=staffId),TeamAction.CREATE_PURCHASE
        ).allowed)
        assertFalse(TeamAccessPolicy.authorize(
            ctx().copy(accountMembershipVerified=false),TeamAction.CORRECT_PURCHASE
        ).allowed)
    }

    @Test fun newEntriesUseUniqueUuidAndBusinessKey() {
        assertTrue(TeamRevisionPolicy.approve(
            TeamRevisionCheck(true,null,null,false,TeamRole.PURCHASING_STAFF)
        ).allowed)
        assertFalse(TeamRevisionPolicy.approve(
            TeamRevisionCheck(true,null,1L,true,TeamRole.PURCHASING_STAFF)
        ).allowed)
    }

    @Test fun anyNumberOfEditsPossibleAfterEachCurrentRevisionAck() {
        var revision = 1L
        repeat(10) {
            assertTrue(TeamRevisionPolicy.approve(
                TeamRevisionCheck(false,revision,revision,false,TeamRole.PURCHASING_STAFF)
            ).allowed)
            revision++
        }
    }

    @Test fun staleOfflineEditCannotOverrideConcurrentOwnerCorrection() {
        assertFalse(TeamRevisionPolicy.approve(
            TeamRevisionCheck(false,1,2,false,TeamRole.PURCHASING_STAFF)
        ).allowed)
        assertTrue(TeamRevisionPolicy.approve(
            TeamRevisionCheck(false,2,2,false,TeamRole.OWNER)
        ).allowed)
        assertFalse(TeamRevisionPolicy.approve(
            TeamRevisionCheck(false,1,2,false,TeamRole.OWNER)
        ).allowed)
    }
}
