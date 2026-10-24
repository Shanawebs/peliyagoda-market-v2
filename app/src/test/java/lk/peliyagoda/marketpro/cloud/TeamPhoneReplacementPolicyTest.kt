package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamPhoneReplacementPolicyTest {
    private val owner="22222222-2222-4222-8222-222222222222"
    private val ownerDevice="a1111111-1111-4111-8111-111111111111"
    private val old="b1111111-1111-4111-8111-111111111111"
    private val replacement="d1111111-1111-4111-8111-111111111111"
    private fun request()=TeamPhoneReplacementRequest(
        workspaceId="11111111-1111-4111-8111-111111111111",
        ownerDeviceId=ownerDevice,
        staffUserId="33333333-3333-4333-8333-333333333333",
        oldDeviceId=old,newDeviceId=replacement,
        staffDeviceLabel="New Team Phone",
        expectedOwnerUserId=owner
    )
    @Test fun ownerMayStartReplacementWithNewDeviceId() {
        assertEquals(TeamPhoneReplacementDecision.READY_FOR_OWNER_SERVER_AUTHORIZATION,
            TeamPhoneReplacementPolicy.validate(request()))
    }
    @Test fun ownerPhoneMustNotBeDisabledAsStaffPhone() {
        assertEquals(TeamPhoneReplacementDecision.OWNER_DEVICE_TARGETED,
            TeamPhoneReplacementPolicy.validate(request().copy(oldDeviceId=ownerDevice)))
        assertEquals(TeamPhoneReplacementDecision.OWNER_DEVICE_TARGETED,
            TeamPhoneReplacementPolicy.validate(request().copy(newDeviceId=ownerDevice)))
        assertEquals(TeamPhoneReplacementDecision.OWNER_DEVICE_TARGETED,
            TeamPhoneReplacementPolicy.validate(request().copy(staffUserId=owner)))
    }
    @Test fun oldDeviceUuidCannotBeReusedAsNewUuid() {
        assertEquals(TeamPhoneReplacementDecision.NEW_DEVICE_REUSED,
            TeamPhoneReplacementPolicy.validate(request().copy(newDeviceId=old)))
    }
    @Test fun invalidUuidOrEmptyDeviceLabelDenied() {
        assertEquals(TeamPhoneReplacementDecision.INVALID_IDENTITIES,
            TeamPhoneReplacementPolicy.validate(request().copy(oldDeviceId="not-a-uuid")))
        assertEquals(TeamPhoneReplacementDecision.DEVICE_LABEL_INVALID,
            TeamPhoneReplacementPolicy.validate(request().copy(staffDeviceLabel=" ")))
    }
    @Test fun everyRolloutGateRequiredBeforeNetwork() {
        assertFalse(TeamPhoneReplacementPolicy.networkActivated(true,true,true,true))
        assertFalse(TeamPhoneReplacementPolicy.networkActivated(false,false,true,true))
        assertFalse(TeamPhoneReplacementPolicy.networkActivated(false,true,false,true))
        assertFalse(TeamPhoneReplacementPolicy.networkActivated(false,true,true,false))
        assertTrue(TeamPhoneReplacementPolicy.networkActivated(false,true,true,true))
    }
}
