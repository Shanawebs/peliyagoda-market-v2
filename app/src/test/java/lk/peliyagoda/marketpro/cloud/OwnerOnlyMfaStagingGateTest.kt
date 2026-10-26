package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.*
import org.junit.Test

class OwnerOnlyMfaStagingGateTest {
    @Test fun ownerMfaIsIndependentOfStaffEnrollment() {
        // A verified Owner Authenticator must never silently activate Staff.
        assertTrue(OwnerTotpMfaPolicy.canCallOwnerAuth(
            preview = false, ownerMfaDeployed = true
        ))
        assertFalse(OwnerTotpMfaPolicy.canCallOwnerAuth(
            preview = false, ownerMfaDeployed = false
        ))
        assertFalse(OwnerTotpMfaPolicy.canCallOwnerAuth(
            preview = true, ownerMfaDeployed = true
        ))
        // Staff/pairing/writer activation remains gated separately.
        assertFalse(OwnerTotpMfaPolicy.canCallAuth(
            preview=false,ownerMfaDeployed=true,
            staffLogin=false,pairingDeployed=false
        ))
    }
}
