package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseAdditionImportGateTest {
    @Test fun cleanStaffPhoneCanImportMultipleDistinctApprovedCloudLots() {
        assertTrue(PurchaseAdditionImportGate.canInsertNew(false,0,false))
    }

    @Test fun oldUnmappedLocalAdditionalPurchaseNeverGetsDuplicated() {
        assertFalse(PurchaseAdditionImportGate.canInsertNew(false,1,false))
        assertFalse(PurchaseAdditionImportGate.canInsertNew(false,20,false))
    }

    @Test fun existingRemoteUuidOrQueuedEditCannotBeImportedAsNewLot() {
        assertFalse(PurchaseAdditionImportGate.canInsertNew(true,0,false))
        assertFalse(PurchaseAdditionImportGate.canInsertNew(false,0,true))
    }

    @Test(expected=IllegalArgumentException::class)
    fun malformedLocalLotCountIsRejected() {
        PurchaseAdditionImportGate.canInsertNew(false,-1,false)
    }
}
