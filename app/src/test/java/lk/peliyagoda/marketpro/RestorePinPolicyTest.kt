package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.security.RestorePinPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePinPolicyTest {
    @Test fun onlySixAsciiDigitsAreValid() {
        assertTrue(RestorePinPolicy.validPin("123456"))
        assertFalse(RestorePinPolicy.validPin("12345"))
        assertFalse(RestorePinPolicy.validPin("1234567"))
        assertFalse(RestorePinPolicy.validPin("12345x"))
        assertFalse(RestorePinPolicy.validPin("１２３４５６"))
    }

    @Test fun threeFailuresTriggerLock() {
        assertFalse(RestorePinPolicy.shouldLock(1))
        assertFalse(RestorePinPolicy.shouldLock(2))
        assertTrue(RestorePinPolicy.shouldLock(3))
    }

    @Test fun lockDurationRoundsUpAndExpires() {
        assertEquals(300L, RestorePinPolicy.remainingSeconds(305000L, 5000L))
        assertEquals(1L, RestorePinPolicy.remainingSeconds(305000L, 304001L))
        assertEquals(0L, RestorePinPolicy.remainingSeconds(305000L, 305000L))
    }
}
