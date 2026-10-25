package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.*
import org.junit.Test

class OwnerTotpMfaPolicyTest {
    private val factor="91111111-1111-4111-8111-111111111111"

    @Test fun onlyExactAuthenticatorCodeAndVerifiedTotpFactorAllowed() {
        assertTrue(OwnerTotpMfaPolicy.validOtp("010203"))
        for(code in listOf("12345","1234567","123 456","١٢٣٤٥٦",
            "abcdef","123456\n","12-456","")) {
            assertFalse(OwnerTotpMfaPolicy.validOtp(code))
        }
        assertTrue(OwnerTotpMfaPolicy.verifiedTotpFactor(
            "totp","verified",factor))
        assertFalse(OwnerTotpMfaPolicy.verifiedTotpFactor(
            "totp","unverified",factor))
        assertFalse(OwnerTotpMfaPolicy.verifiedTotpFactor(
            "phone","verified",factor))
        assertFalse(OwnerTotpMfaPolicy.verifiedTotpFactor(
            "totp","verified","not-a-factor"))
        assertTrue(OwnerTotpMfaPolicy.validManualSecret("ABCDEF234567ABCD"))
        assertFalse(OwnerTotpMfaPolicy.validManualSecret("invalid key"))
    }

    @Test fun previewAndAnyUnavailableBackendGateCannotPromptForOtp() {
        assertTrue(OwnerTotpMfaPolicy.canCallAuth(false,true,true,true))
        assertFalse(OwnerTotpMfaPolicy.canCallAuth(true,true,true,true))
        assertFalse(OwnerTotpMfaPolicy.canCallAuth(false,false,true,true))
        assertFalse(OwnerTotpMfaPolicy.canCallAuth(false,true,false,true))
        assertFalse(OwnerTotpMfaPolicy.canCallAuth(false,true,true,false))
    }
}
