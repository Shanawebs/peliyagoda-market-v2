package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class TeamStaffEnrollmentPolicyTest {
    private val staff = "33333333-3333-4333-8333-333333333333"
    private val owner = "22222222-2222-4222-8222-222222222222"
    private val staffDevice = "b1111111-1111-4111-8111-111111111111"

    private fun evidence()=StaffEnrollmentEvidence(
        role=TeamRole.PURCHASING_STAFF,
        sessionUserId=staff,
        invitationUserId=staff,
        physicalDeviceId=staffDevice,
        invitationDeviceId=staffDevice,
        ownerAccountUserId=owner,
        verifiedAuthEmail=true,
        authenticatedServerVerified=true,
        serverMembershipVerified=false,
        enrolledDeviceEnabled=false,
        serverCanWrite=false,
        serverTeamRpcDeployed=false,
        localPreview=true,
        state=StaffEnrollmentState.ACCEPTED_AWAITING_OWNER_ACTIVATION
    )
    private fun outcome(e:StaffEnrollmentEvidence) =
        TeamStaffEnrollmentPolicy.evaluate(e)

    @Test fun invitationClaimNeverGivesWriterAccess() {
        assertEquals(StaffEnrollmentOutcome.WAIT_FOR_OWNER_AND_SERVER_CUTOVER,
            outcome(evidence()))
    }

    @Test fun unclaimedInviteCannotReadCloud() {
        assertEquals(StaffEnrollmentOutcome.WAIT_FOR_INVITATION,
            outcome(evidence().copy(state=StaffEnrollmentState.INVITED_NOT_ACCEPTED)))
    }

    @Test fun noStaffCanUseOwnersAuthenticatedIdentity() {
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(sessionUserId=owner,invitationUserId=owner)))
    }

    @Test fun secondPhoneOrForgedUserCannotClaimEnrollment() {
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(physicalDeviceId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")))
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(invitationUserId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")))
    }

    @Test fun unverifiedJwtEmailOrRevokedSessionNeverGranted() {
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(verifiedAuthEmail=false)))
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(authenticatedServerVerified=false)))
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(state=StaffEnrollmentState.REVOKED)))
    }

    @Test fun clientFlagsCannotEnableWithoutServerRoleAndRegisteredDevice() {
        val attempted=evidence().copy(
            localPreview=false,serverTeamRpcDeployed=true,
            state=StaffEnrollmentState.ENROLLED_WRITER,serverCanWrite=true
        )
        assertEquals(StaffEnrollmentOutcome.WAIT_FOR_OWNER_AND_SERVER_CUTOVER,
            outcome(attempted))
        assertEquals(StaffEnrollmentOutcome.WAIT_FOR_OWNER_AND_SERVER_CUTOVER,
            outcome(attempted.copy(serverMembershipVerified=true)))
        assertEquals(StaffEnrollmentOutcome.STAFF_WRITER,
            outcome(attempted.copy(serverMembershipVerified=true,enrolledDeviceEnabled=true)))
    }

    @Test fun readOnlyDeviceNeverBecomesWriter() {
        val enrolled=evidence().copy(
            localPreview=false,serverTeamRpcDeployed=true,
            serverMembershipVerified=true,enrolledDeviceEnabled=true,
            state=StaffEnrollmentState.ENROLLED_READ_ONLY,serverCanWrite=false
        )
        assertEquals(StaffEnrollmentOutcome.READ_ONLY,outcome(enrolled))
        assertEquals(StaffEnrollmentOutcome.READ_ONLY,
            outcome(enrolled.copy(state=StaffEnrollmentState.ENROLLED_WRITER)))
    }

    @Test fun readonlyStageCannotOverridePreviewFlag() {
        val e=evidence().copy(
            serverMembershipVerified=true,enrolledDeviceEnabled=true,
            state=StaffEnrollmentState.ENROLLED_WRITER,
            serverCanWrite=true,serverTeamRpcDeployed=true
        )
        assertEquals(StaffEnrollmentOutcome.WAIT_FOR_OWNER_AND_SERVER_CUTOVER,outcome(e))
    }

    @Test fun viewerRoleAndOwnerRoleCannotPretendToBeStaff() {
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(role=TeamRole.VIEWER)))
        assertEquals(StaffEnrollmentOutcome.NO_ACCESS,
            outcome(evidence().copy(role=TeamRole.OWNER)))
    }
}
