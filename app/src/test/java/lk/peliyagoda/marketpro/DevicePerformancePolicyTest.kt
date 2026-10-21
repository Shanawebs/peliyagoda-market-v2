package lk.peliyagoda.marketpro

import lk.peliyagoda.marketpro.util.DevicePerformancePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePerformancePolicyTest {
    private val gib = 1024L * 1024L * 1024L

    @Test fun fourGigabyteDeviceUsesLowCostTabTransitions() {
        assertTrue(DevicePerformancePolicy.preferLightweightAnimations(4 * gib, false))
    }

    @Test fun largerPhoneRetainsNormalTransition() {
        assertFalse(DevicePerformancePolicy.preferLightweightAnimations(8 * gib, false))
    }

    @Test fun androidLowRamFlagAlwaysWins() {
        assertTrue(DevicePerformancePolicy.preferLightweightAnimations(8 * gib, true))
    }

    @Test fun unknownMemoryDefaultsToNormalUnlessFlagged() {
        assertFalse(DevicePerformancePolicy.preferLightweightAnimations(0, false))
        assertTrue(DevicePerformancePolicy.preferLightweightAnimations(0, true))
    }
}
