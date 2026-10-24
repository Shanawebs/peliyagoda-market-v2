package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamAccessPolicyTest {
    private val ownerId = "22222222-2222-4222-8222-222222222222"
    private val staffId = "33333333-3333-4333-8333-333333333333"
    private fun ctx(
        role: TeamRole = TeamRole.OWNER,
        userId: String = ownerId,
        pin: Boolean = false
    ) = TeamAccessContext(
        role, userId, userId, true, true, pin
    )

    @Test fun purchasingTeamCanEnterOnlyNewDataAndView() {
        val staff = ctx(role = TeamRole.PURCHASING_STAFF, userId = staffId)
        for (action in listOf(
            TeamAction.VIEW, TeamAction.CREATE_PURCHASE,
            TeamAction.CREATE_REQUISITION, TeamAction.CREATE_EXTRA_LOT,
            TeamAction.ENTER_CASH, TeamAction.ENTER_OTHER_EXPENSES
        )) assertTrue(TeamAccessPolicy.authorize(staff, action).allowed)
        for (action in listOf(
            TeamAction.CORRECT_PURCHASE, TeamAction.CORRECT_CASH,
            TeamAction.CORRECT_EXTRA_LOT, TeamAction.MODIFY_CATALOG,
            TeamAction.DELETE_RECORD, TeamAction.REGISTER_DEVICE,
            TeamAction.REVOKE_DEVICE
        )) assertFalse(TeamAccessPolicy.authorize(staff, action).allowed)
    }

    @Test fun ownerCanCorrectAndNeedsFreshPinForSecurityChanges() {
        assertTrue(TeamAccessPolicy.authorize(ctx(), TeamAction.CORRECT_PURCHASE).allowed)
        assertTrue(TeamAccessPolicy.authorize(ctx(), TeamAction.CORRECT_OTHER_EXPENSES).allowed)
        assertFalse(TeamAccessPolicy.authorize(ctx(), TeamAction.REVOKE_DEVICE).allowed)
        assertFalse(TeamAccessPolicy.authorize(ctx(), TeamAction.DELETE_RECORD).allowed)
        assertTrue(TeamAccessPolicy.authorize(ctx(pin = true), TeamAction.REVOKE_DEVICE).allowed)
    }

    @Test fun viewerCannotWriteEvenWithPin() {
        assertFalse(TeamAccessPolicy.authorize(
            ctx(role = TeamRole.VIEWER,pin=true),TeamAction.CORRECT_PURCHASE
        ).allowed)
        assertTrue(TeamAccessPolicy.authorize(
            ctx(role = TeamRole.VIEWER),TeamAction.VIEW
        ).allowed)
    }

    @Test fun disabledOrMismatchedDeviceIsDeniedForEveryRole() {
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

    @Test fun newEntriesRequireNoExistingBusinessKey() {
        assertTrue(TeamRevisionPolicy.approve(
            TeamRevisionCheck(true,null,null,false,false,TeamRole.PURCHASING_STAFF)
        ).allowed)
        assertFalse(TeamRevisionPolicy.approve(
            TeamRevisionCheck(true,null,1L,true,false,TeamRole.PURCHASING_STAFF)
        ).allowed)
    }

    @Test fun staleStaffEditCannotOverwriteOwnerPhoneCorrection() {
        val stale = TeamRevisionCheck(false,1L,2L,false,false,TeamRole.PURCHASING_STAFF)
        assertFalse(TeamRevisionPolicy.approve(stale).allowed)
        assertFalse(TeamRevisionPolicy.approve(
            stale.copy(expectedRevision=2L,ownerReviewed=true)
        ).allowed)
        assertTrue(TeamRevisionPolicy.approve(
            stale.copy(expectedRevision=2L,ownerReviewed=true,callerRole=TeamRole.OWNER)
        ).allowed)
    }

    @Test fun ownerCorrectionMustAlsoUseCurrentVersion() {
        assertFalse(TeamRevisionPolicy.approve(
            TeamRevisionCheck(false,5,6,false,true,TeamRole.OWNER)
        ).allowed)
        assertTrue(TeamRevisionPolicy.approve(
            TeamRevisionCheck(false,6,6,false,true,TeamRole.OWNER)
        ).allowed)
    }
}
