package lk.peliyagoda.marketpro.cloud

/**
 * A local explicit Owner *confirmation*, not a permission grant.
 * Server is authoritative about Auth role, activated session, old-device
 * status, dates, and live write lease on EACH RPC.
 */
internal enum class OwnerStaffDeviceAction(
    val phrase: String
) {
    APPROVE_WRITING("APPROVE"),
    PAUSE_WRITING("PAUSE"),
    REVOKE_LOST_PHONE("REVOKE")
}

internal data class OwnerWriterTarget(
    val workspaceId: String,
    val expectedOwnerUserId: String,
    val ownerDeviceId: String,
    val staffUserId: String,
    val staffDeviceId: String
)

internal object OwnerStaffWriterReviewPolicy {
    fun networkReady(
        offlinePreview: Boolean,
        staffLoginEnabled: Boolean,
        pullDeployed: Boolean,
        pairingDeployed: Boolean,
        writeApprovalDeployed: Boolean,
        transferDeployed: Boolean,
        action: OwnerStaffDeviceAction
    ): Boolean {
        val common = TeamSessionPairingPolicy.deploymentReady(
            offlinePreview,staffLoginEnabled,pullDeployed,pairingDeployed
        )
        return common && when(action) {
            OwnerStaffDeviceAction.APPROVE_WRITING,
            OwnerStaffDeviceAction.PAUSE_WRITING -> writeApprovalDeployed
            OwnerStaffDeviceAction.REVOKE_LOST_PHONE -> transferDeployed
        }
    }

    fun validTarget(t: OwnerWriterTarget): Boolean {
        if (listOf(t.workspaceId,t.expectedOwnerUserId,t.ownerDeviceId,
                t.staffUserId,t.staffDeviceId)
                .any { !TeamSessionPairingPolicy.validUuid(it) }) return false
        if (t.staffUserId == t.expectedOwnerUserId ||
            t.staffDeviceId == t.ownerDeviceId ||
            t.staffUserId == t.staffDeviceId) return false
        return true
    }

    fun confirmed(
        target: OwnerWriterTarget,
        current: OwnerWriterTarget,
        action: OwnerStaffDeviceAction,
        confirmation: String,
        ownerChecked: Boolean
    ): Boolean = validTarget(target) &&
        validTarget(current) &&
        target == current &&
        ownerChecked &&
        confirmation == action.phrase
}
