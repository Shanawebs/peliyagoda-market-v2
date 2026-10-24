package lk.peliyagoda.marketpro.cloud

/**
 * A verified server response is a narrow, device-specific decision.
 * Staff must NEVER create this proof from a local PIN, QR, deep-link or UI flag.
 * An approval may only link the matching canonical UUID after Room is
 * transactionally re-checked against the same immutable fingerprint.
 */
internal data class StaffCatalogServerApproval(
    val approvalId: String,
    val workspaceId: String,
    val deviceId: String,
    val cloudUuid: String,
    val type: String,
    val localKey: String,
    val revision: Long,
    val localFingerprint: String,
    val reviewedByOwnerId: String,
    val approvedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val serverNowEpochSeconds: Long,
    val approvedForDeviceRecheck: Boolean,
    val needsRoomRecheck: Boolean
)

internal enum class StaffCatalogApprovalDecision {
    READY_TO_RECHECK_ROOM,
    REJECT_INVALID_SERVER_PROOF,
    REJECT_DIFFERENT_WORKSPACE_OR_DEVICE,
    REJECT_DIFFERENT_CATALOG,
    REJECT_WRONG_OWNER,
    REJECT_EXPIRED_OR_STALE
}

internal object StaffCatalogApprovalPolicy {
    fun decide(
        proof: StaffCatalogServerApproval,
        workspace: String, device: String, type: String, cloudUuid: String,
        expectedOwnerId: String
    ): StaffCatalogApprovalDecision {
        if (!CloudInboxPagePolicy.uuid(proof.approvalId) ||
            !CloudInboxPagePolicy.uuid(proof.workspaceId) ||
            !CloudInboxPagePolicy.uuid(proof.deviceId) ||
            !CloudInboxPagePolicy.uuid(proof.cloudUuid) ||
            !CloudInboxPagePolicy.uuid(proof.reviewedByOwnerId) ||
            proof.localFingerprint.length != 64 ||
            !proof.localFingerprint.matches(Regex("[0-9a-f]{64}")) ||
            !proof.approvedForDeviceRecheck || !proof.needsRoomRecheck
        ) return StaffCatalogApprovalDecision.REJECT_INVALID_SERVER_PROOF

        if (proof.workspaceId != workspace || proof.deviceId != device) {
            return StaffCatalogApprovalDecision.REJECT_DIFFERENT_WORKSPACE_OR_DEVICE
        }
        if (proof.type !in setOf("category", "item") ||
            proof.type != type || proof.cloudUuid != cloudUuid ||
            proof.revision <= 0 ||
            proof.localKey.isBlank() || proof.localKey.length > 200 ||
            (proof.type == "item" &&
                (proof.localKey.toLongOrNull()?.let {
                    it > 0 && it.toString() == proof.localKey
                } != true))
        ) return StaffCatalogApprovalDecision.REJECT_DIFFERENT_CATALOG

        if (proof.reviewedByOwnerId != expectedOwnerId) {
            return StaffCatalogApprovalDecision.REJECT_WRONG_OWNER
        }
        if (proof.approvedAtEpochSeconds <= 0 ||
            proof.expiresAtEpochSeconds <= proof.approvedAtEpochSeconds ||
            proof.expiresAtEpochSeconds - proof.approvedAtEpochSeconds > 86400L ||
            proof.serverNowEpochSeconds < proof.approvedAtEpochSeconds ||
            proof.serverNowEpochSeconds >= proof.expiresAtEpochSeconds
        ) return StaffCatalogApprovalDecision.REJECT_EXPIRED_OR_STALE

        return StaffCatalogApprovalDecision.READY_TO_RECHECK_ROOM
    }
}
