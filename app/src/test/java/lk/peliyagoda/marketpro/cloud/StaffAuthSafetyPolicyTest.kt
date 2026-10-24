package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffAuthSafetyPolicyTest {
    private val staffId = "33333333-3333-4333-8333-333333333333"
    private val ownerId = "bf49c22f-f245-4d3c-987e-c5ec081d7cad"

    private fun staffIdentity() = StaffVerifiedIdentity(
        userId = staffId,
        email = "Staff@Example.lk",
        emailVerified = true,
        isAnonymous = false
    )

    @Test fun verifiedStaffAccountUsesItsOwnConfirmedEmail() {
        StaffAuthSafetyPolicy.checkIdentity(staffIdentity(), " staff@example.lk ",
            ownerId)
        assertEquals("staff@example.lk",
            StaffAuthSafetyPolicy.normalizedEmail("  Staff@Example.LK  "))
    }

    @Test(expected=IllegalArgumentException::class)
    fun ownerCannotSignInAsStaffEvenWithValidOwnerPassword() {
        StaffAuthSafetyPolicy.checkIdentity(
            staffIdentity().copy(userId = ownerId),
            "staff@example.lk",ownerId
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun anonymousIdentityDenied() {
        StaffAuthSafetyPolicy.checkIdentity(
            staffIdentity().copy(isAnonymous = true),"staff@example.lk",ownerId
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun unverifiedEmailDenied() {
        StaffAuthSafetyPolicy.checkIdentity(
            staffIdentity().copy(emailVerified=false),"staff@example.lk",ownerId
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun mismatchedEmailDenied() {
        StaffAuthSafetyPolicy.checkIdentity(
            staffIdentity(),"other@company.lk",ownerId
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun refreshedTokenSwitchingStaffUserIsDenied() {
        StaffAuthSafetyPolicy.checkIdentity(
            staffIdentity(),"staff@example.lk",ownerId,
            previousVerifiedUserId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        )
    }

    @Test(expected=IllegalArgumentException::class)
    fun malformedUuidCannotBecomeStaffAccount() {
        StaffAuthSafetyPolicy.checkIdentity(
            staffIdentity().copy(userId="not-a-uuid"),
            "staff@example.lk",ownerId
        )
    }

    @Test fun allThreeDeploymentFlagsMustBeSatisfiedBeforeAnyAuthNetworkCall() {
        assertFalse(StaffAuthSafetyPolicy.allowNetwork(true,true,true))
        assertFalse(StaffAuthSafetyPolicy.allowNetwork(false,false,true))
        assertFalse(StaffAuthSafetyPolicy.allowNetwork(false,true,false))
        assertFalse(StaffAuthSafetyPolicy.allowNetwork(true,false,false))
        assertTrue(StaffAuthSafetyPolicy.allowNetwork(false,true,true))
    }

    @Test fun staffAuthCannotBeUsedToEscalateToOwnerRights() {
        val verified = staffIdentity()
        StaffAuthSafetyPolicy.checkIdentity(verified,verified.email,ownerId,staffId)
        assertTrue(verified.userId != ownerId)
        // Even this successful client check does NOT assert any enrolled
        // workspace permissions: authenticated device-bound RPC decides them.
    }
}
