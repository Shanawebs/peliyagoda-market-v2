package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerStaffWriterReviewPolicyTest {
    private val base=OwnerWriterTarget(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "a1111111-1111-4111-8111-111111111111",
        "33333333-3333-4333-8333-333333333333",
        "b1111111-1111-4111-8111-111111111111"
    )

    @Test fun allPreviewActionsRemainDisabledEvenIfOtherFlagsTrue() {
        for (action in OwnerStaffDeviceAction.entries) {
            assertFalse(OwnerStaffWriterReviewPolicy.networkReady(
                true,true,true,true,true,true,action))
        }
        assertFalse(OwnerStaffWriterReviewPolicy.networkReady(
            false,true,true,true,false,true,
            OwnerStaffDeviceAction.APPROVE_WRITING))
        assertFalse(OwnerStaffWriterReviewPolicy.networkReady(
            false,true,true,true,false,true,
            OwnerStaffDeviceAction.PAUSE_WRITING))
        assertFalse(OwnerStaffWriterReviewPolicy.networkReady(
            false,true,true,true,true,false,
            OwnerStaffDeviceAction.REVOKE_LOST_PHONE))
    }

    @Test fun explicitConfirmationIsBoundToExactPhoneUserAndAction() {
        for (action in OwnerStaffDeviceAction.entries) {
            assertTrue(OwnerStaffWriterReviewPolicy.confirmed(
                base,base,action,action.phrase,true))
            assertFalse(OwnerStaffWriterReviewPolicy.confirmed(
                base,base,action,action.phrase,false))
            assertFalse(OwnerStaffWriterReviewPolicy.confirmed(
                base,base,action,action.phrase.lowercase(),true))
            assertFalse(OwnerStaffWriterReviewPolicy.confirmed(
                base,base.copy(staffDeviceId="c1111111-1111-4111-8111-111111111111"),
                action,action.phrase,true))
            assertFalse(OwnerStaffWriterReviewPolicy.confirmed(
                base,base.copy(staffUserId="d1111111-1111-4111-8111-111111111111"),
                action,action.phrase,true))
        }
        assertFalse(OwnerStaffWriterReviewPolicy.confirmed(
            base,base,OwnerStaffDeviceAction.APPROVE_WRITING,"REVOKE",true))
    }

    @Test fun ownerCannotTargetOwnDeviceOrIdentity() {
        assertFalse(OwnerStaffWriterReviewPolicy.validTarget(
            base.copy(staffUserId=base.expectedOwnerUserId)))
        assertFalse(OwnerStaffWriterReviewPolicy.validTarget(
            base.copy(staffDeviceId=base.ownerDeviceId)))
        assertFalse(OwnerStaffWriterReviewPolicy.validTarget(
            base.copy(staffDeviceId="not-a-uuid")))
        assertFalse(OwnerStaffWriterReviewPolicy.validTarget(
            base.copy(staffUserId=base.staffDeviceId)))
    }
}
