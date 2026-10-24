package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSessionPairingPolicyTest {
    private val workspace="11111111-1111-4111-8111-111111111111"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val staff="33333333-3333-4333-8333-333333333333"
    private val ownerPhone="a1111111-1111-4111-8111-111111111111"
    private val staffPhone="b1111111-1111-4111-8111-111111111111"
    private val code="a".repeat(64)

    @Test fun allNetworkSwitchesMustBeIndependentlyEnabled() {
        val p=TeamSessionPairingPolicy
        assertFalse(p.deploymentReady(true,true,true,true))
        assertFalse(p.deploymentReady(false,false,true,true))
        assertFalse(p.deploymentReady(false,true,false,true))
        assertFalse(p.deploymentReady(false,true,true,false))
        assertTrue(p.deploymentReady(false,true,true,true))
    }

    private fun staffClaim(
        user: String=staff, physical: String=staffPhone,
        target: String=staffPhone, invite: String=code
    ) = TeamSessionPairingPolicy.validateStaffClaim(
        workspace,user,owner,physical,target,invite
    )

    private fun denied(block: () -> Unit) {
        var thrown=false
        try { block() } catch (_: IllegalArgumentException) { thrown=true }
        assertTrue("Unsafe input was accepted",thrown)
    }

    @Test fun validSeparateStaffClaimIsOnlyARequestNotEnrollment() {
        staffClaim() // Validation alone carries no server grant, session, or role.
    }

    @Test fun ownerCredentialsCannotBeUsedOnStaffPhone() {
        denied { staffClaim(user=owner) }
    }

    @Test fun secondPhoneCannotReuseAnotherPhoneId() {
        denied { staffClaim(physical=ownerPhone) }
    }

    @Test fun codeMustBeExactAndLowercase() {
        denied { staffClaim(invite="123456") }
        denied { staffClaim(invite=code.dropLast(1)) }
        denied { staffClaim(invite="A".repeat(64)) }
        denied { staffClaim(invite=code + "0") }
        denied { staffClaim(invite="0".repeat(63)+"!") }
    }

    @Test fun invalidWorkspaceOrIdentityDenied() {
        denied { TeamSessionPairingPolicy.validateStaffClaim(
            "not-a-uuid",staff,owner,staffPhone,staffPhone,code) }
        denied { TeamSessionPairingPolicy.validateStaffClaim(
            workspace,"untrusted",owner,staffPhone,staffPhone,code) }
    }

    private fun invite(
        user: String=staff, device: String=staffPhone,
        label: String="Market Team Phone",
        actualOwner: String=owner,
        actualOwnerPhone: String=ownerPhone
    ) = TeamSessionPairingPolicy.validateOwnerInvite(
        workspace,owner,actualOwner,ownerPhone,actualOwnerPhone,
        user,device,label
    )

    @Test fun ownerCanOnlyInviteSeparateStaffAccountAndPhone() {
        invite()
        denied { invite(user=owner) }
        denied { invite(device=ownerPhone) }
        denied { invite(actualOwner=staff) }
        denied { invite(actualOwnerPhone=staffPhone) }
        denied { invite(label=" ") }
        denied { invite(label="a".repeat(101)) }
        denied { invite(label="Device\nAnother") }
    }

    @Test fun clientAcceptsOnlyPendingServerActivationNeverOwnWriteSuccess() {
        TeamSessionPairingPolicy.validateClaimResult(
            true,"awaiting_owner_activation_and_server_cutover",workspace,workspace)
        denied { TeamSessionPairingPolicy.validateClaimResult(
            true,"writer_enabled",workspace,workspace) }
        denied { TeamSessionPairingPolicy.validateClaimResult(
            false,"awaiting_owner_activation_and_server_cutover",workspace,workspace) }
        denied { TeamSessionPairingPolicy.validateClaimResult(
            true,"awaiting_owner_activation_and_server_cutover",owner,workspace) }
    }

    @Test fun ownerWriterGrantMustMatchSpecificPhone() {
        TeamSessionPairingPolicy.validateGrantResult(true,staffPhone,staffPhone)
        denied { TeamSessionPairingPolicy.validateGrantResult(true,ownerPhone,staffPhone) }
        denied { TeamSessionPairingPolicy.validateGrantResult(false,staffPhone,staffPhone) }
    }

    @Test fun oneTimeCodeIsRedactedFromDebugAndCannotBeMalformed() {
        val pair=OneTimeOwnerInvitation.validated(code,"2026-10-24T16:00:00Z")
        assertEquals(code,pair.code)
        assertFalse(pair.toString().contains(code))
        assertTrue(pair.toString().contains("[REDACTED]"))
        denied { OneTimeOwnerInvitation.validated("secret","now") }
        denied { OneTimeOwnerInvitation.validated(code,"") }
    }
}
