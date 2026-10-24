package lk.peliyagoda.marketpro.cloud

/**
 * Replacement is NOT equivalent to granting Staff a financial writer role.
 * Removing the old phone must always be available to the verified Owner, even
 * if the handset is stolen/offline or no replacement device exists yet.
 */
internal data class TeamPhoneReplacementRequest(
    val workspaceId: String,
    val ownerDeviceId: String,
    val staffUserId: String,
    val oldDeviceId: String,
    val newDeviceId: String,
    val staffDeviceLabel: String,
    val expectedOwnerUserId: String
)

internal enum class TeamPhoneReplacementDecision {
    READY_FOR_OWNER_SERVER_AUTHORIZATION,
    INVALID_IDENTITIES,
    OWNER_DEVICE_TARGETED,
    UNVERIFIED_STAFF_ACCOUNT,
    NEW_DEVICE_REUSED,
    DEVICE_LABEL_INVALID
}

internal object TeamPhoneReplacementPolicy {
    fun validate(input:TeamPhoneReplacementRequest): TeamPhoneReplacementDecision {
        if(listOf(input.workspaceId,input.ownerDeviceId,input.staffUserId,
                input.oldDeviceId,input.newDeviceId,input.expectedOwnerUserId)
                .any { !CloudInboxPagePolicy.uuid(it) })
            return TeamPhoneReplacementDecision.INVALID_IDENTITIES
        if(input.oldDeviceId == input.ownerDeviceId ||
           input.newDeviceId == input.ownerDeviceId ||
           input.staffUserId == input.expectedOwnerUserId)
            return TeamPhoneReplacementDecision.OWNER_DEVICE_TARGETED
        if(input.oldDeviceId == input.newDeviceId)
            return TeamPhoneReplacementDecision.NEW_DEVICE_REUSED
        if(input.staffDeviceLabel.trim().length !in 1..100)
            return TeamPhoneReplacementDecision.DEVICE_LABEL_INVALID
        return TeamPhoneReplacementDecision.READY_FOR_OWNER_SERVER_AUTHORIZATION
    }

    fun networkActivated(
        offlinePreview: Boolean,
        teamPullRpcDeployed: Boolean,
        staffLoginEnabled: Boolean,
        phoneTransferRpcDeployed: Boolean
    ): Boolean = !offlinePreview && teamPullRpcDeployed &&
        staffLoginEnabled && phoneTransferRpcDeployed
}
