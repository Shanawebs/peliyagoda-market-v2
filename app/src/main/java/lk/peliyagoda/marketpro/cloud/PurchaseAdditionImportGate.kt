package lk.peliyagoda.marketpro.cloud

/**
 * Purchase additions are separate purchase lots, NOT one daily amount.
 * Auto-importing a remote lot on a phone with existing unbound local lots
 * risks doubling quantities and Final PDF totals. Fail closed.
 */
internal object PurchaseAdditionImportGate {
    fun canInsertNew(
        existingRemoteMapping: Boolean,
        unboundLocalLotsForDateItem: Int,
        pendingLocalEditForRemoteUuid: Boolean
    ): Boolean {
        require(unboundLocalLotsForDateItem >= 0)
        return !existingRemoteMapping &&
            unboundLocalLotsForDateItem == 0 &&
            !pendingLocalEditForRemoteUuid
    }
}
