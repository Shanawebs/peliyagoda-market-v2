package lk.peliyagoda.marketpro.cloud

/**
 * DTO sent from a verified STAFF phone to the future server-side pending-only
 * catalog candidate RPC. It is evidence presented for the Owner's review,
 * NOT proof of physical-phone identity and NOT an authorization to map.
 */
internal data class StaffCatalogCandidate(
    val workspaceId: String,
    val deviceId: String,
    val cloudUuid: String,
    val cloudRevision: Long,
    val entityType: String,
    val localKey: String,
    val localName: String,
    val localCategory: String?,
    val localActive: Boolean?,
    val fingerprint: String
)

internal data class StaffCandidateInputs(
    val workspaceId: String,
    val deviceId: String,
    val cloudUuid: String,
    val cloudRevision: Long,
    val cloudWorkspaceMatches: Boolean,
    val cloudRecordDeleted: Boolean,
    val entityType: String,
    val localKey: String,
    val localName: String,
    val localCategory: String?,
    val localActive: Boolean?,
    val localPendingEdit: Boolean
)

internal object StaffCatalogCandidatePolicy {
    /** Build a draft only. No server calls, no Room writes, no Owner approval. */
    fun draft(i: StaffCandidateInputs): StaffCatalogCandidate {
        require(CloudInboxPagePolicy.uuid(i.workspaceId) &&
            CloudInboxPagePolicy.uuid(i.deviceId) &&
            CloudInboxPagePolicy.uuid(i.cloudUuid) &&
            i.cloudWorkspaceMatches && !i.cloudRecordDeleted &&
            i.cloudRevision > 0 && !i.localPendingEdit) {
            "Untrusted/stale catalog candidate or pending local edit"
        }
        require(i.entityType in setOf("item","category"))
        require(i.localName.isNotBlank() &&
            i.localName == i.localName.trim() &&
            i.localName.length <= 250)
        if (i.entityType == "item") {
            require(i.localKey.matches(Regex("[1-9][0-9]{0,17}")) &&
                i.localCategory != null &&
                i.localCategory.isNotBlank() &&
                i.localCategory == i.localCategory.trim() &&
                i.localCategory.length <= 250 &&
                i.localActive != null)
        } else {
            require(i.localKey == lk.peliyagoda.marketpro.data.CloudRecordKey.category(i.localName) &&
                i.localCategory == null && i.localActive == null)
        }
        return StaffCatalogCandidate(
            workspaceId=i.workspaceId,
            deviceId=i.deviceId,
            cloudUuid=i.cloudUuid,
            cloudRevision=i.cloudRevision,
            entityType=i.entityType,
            localKey=i.localKey,
            localName=i.localName,
            localCategory=i.localCategory,
            localActive=i.localActive,
            fingerprint=OwnerCatalogLinkPolicy.fingerprint(
                i.localName,i.localCategory,i.localActive
            )
        )
    }
}

/**
 * The eventual Owner UI displays exactly what the server says the STAFF phone
 * submitted. It must not substitute data from the Owner's local Room catalog.
 */
internal data class OwnerRemoteCandidate(
    val candidateUuid: String,
    val staffDeviceId: String,
    val staffAccountId: String,
    val cloudUuid: String,
    val cloudRevision: Long,
    val entityType: String,
    val localItemKey: String,
    val localName: String,
    val localCategory: String?,
    val localActive: Boolean?,
    val localFingerprint: String
)

internal object OwnerRemoteCandidatePolicy {
    fun reviewable(e: OwnerRemoteCandidate, expectedStaffDeviceId: String): Boolean =
        CloudInboxPagePolicy.uuid(e.candidateUuid) &&
        CloudInboxPagePolicy.uuid(e.staffDeviceId) &&
        e.staffDeviceId == expectedStaffDeviceId &&
        CloudInboxPagePolicy.uuid(e.staffAccountId) &&
        CloudInboxPagePolicy.uuid(e.cloudUuid) &&
        e.cloudRevision > 0 &&
        e.entityType in setOf("category","item") &&
        e.localName.isNotBlank() && e.localName.length <= 250 &&
        e.localFingerprint.matches(Regex("[0-9a-f]{64}")) &&
        (if (e.entityType == "item")
            e.localItemKey.matches(Regex("[1-9][0-9]{0,17}")) &&
                e.localCategory?.isNotBlank() == true && e.localActive != null
        else e.localItemKey == e.localName &&
            e.localCategory == null && e.localActive == null)
}
