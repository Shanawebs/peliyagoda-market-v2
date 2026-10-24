package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject

/**
 * Read-only transactional capture of ONE explicitly selected Cloud UUID and
 * existing Staff Phone Room key. No automatic name→UUID association.
 *
 * A successful capture only prepares pending evidence for a server-authorized
 * Owner review. It NEVER persists a UUID mapping, rewrites financial data,
 * clears pending edits or grants Cloud access.
 */
internal class StaffCatalogCandidateReader(
    private val db: MarketDatabase,
    private val actualDeviceId: String
) {
    data class CaptureResult(
        val decision: StaffCatalogCaptureDecision,
        val candidate: StaffCatalogCandidate? = null
    )

    private val inbox = db.inboxDao()
    private val cloud = db.cloudDao()

    suspend fun capture(
        type: String,
        localKey: String,
        cloudUuid: String,
        nowMs: Long = System.currentTimeMillis()
    ): CaptureResult {
        require(type in setOf("category", "item"))
        require(CloudInboxPagePolicy.uuid(cloudUuid))
        require(CloudInboxPagePolicy.uuid(actualDeviceId))
        require(nowMs > 0)
        // Distinguish canonical Room ID from a literal product name.
        val itemId = if (type == "item") localKey.toLongOrNull()
            ?.takeIf { it > 0 && it.toString() == localKey } else null
        if (type == "item" && itemId == null) {
            return CaptureResult(StaffCatalogCaptureDecision.REJECT_MISSING_OR_CHANGED_LOCAL_ROW)
        }
        if (type == "category" && (localKey.isBlank() ||
            localKey != localKey.trim() || localKey.length > 250 ||
            localKey != CloudRecordKey.category(localKey))) {
            return CaptureResult(StaffCatalogCaptureDecision.REJECT_MISSING_OR_CHANGED_LOCAL_ROW)
        }

        return db.withTransaction {
            val shadow = inbox.snapshot(type, cloudUuid)
            val row = runCatching {
                shadow?.canonicalJson?.let { JSONObject(it) }
            }.getOrNull()

            val validShadow = shadow != null && row != null &&
                shadow.entityType == type &&
                shadow.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
                shadow.cloudId == cloudUuid &&
                row.optString("workspace_id") == shadow.workspaceId &&
                row.optString("id") == cloudUuid &&
                row.optLong("revision", -1L) == shadow.revision &&
                shadow.revision > 0 &&
                shadow.receivedAtMs in 1..nowMs &&
                (nowMs - shadow.receivedAtMs) <= 86_400_000L &&
                row.optString("name").isNotBlank() &&
                row.optString("name").length <= 250
            val notDeleted = shadow?.deletedAtUtc == null &&
                row?.isNull("deleted_at") == true

            val item = if (itemId != null) cloud.findItem(itemId) else null
            val category = if (type == "category") cloud.findCategory(localKey)
                else null
            val localName = if (type == "item") item?.name else category?.name
            val localCategory = if (type == "item") item?.category else null
            val localActive = if (type == "item") item?.isActive else null
            val actualKey = if (type == "item" && item != null)
                CloudRecordKey.item(item.id)
            else if (type == "category" && category != null)
                CloudRecordKey.category(category.name) else null

            val remoteCategoryId = if (type == "item")
                row?.optString("category_id").orEmpty() else ""
            val categoryShadow = if (CloudInboxPagePolicy.uuid(remoteCategoryId))
                inbox.snapshot("category", remoteCategoryId) else null
            val categoryRow = runCatching {
                categoryShadow?.canonicalJson?.let { JSONObject(it) }
            }.getOrNull()
            val catName = categoryRow?.optString("name").orEmpty()
            val categoryTrusted = categoryShadow != null &&
                categoryRow != null &&
                categoryShadow.entityType == "category" &&
                categoryShadow.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
                categoryShadow.cloudId == remoteCategoryId &&
                categoryRow.optString("workspace_id") == CloudSyncConfig.WORKSPACE_ID &&
                categoryRow.optString("id") == remoteCategoryId &&
                categoryRow.optLong("revision",-1L) == categoryShadow.revision &&
                categoryShadow.revision > 0 &&
                categoryShadow.deletedAtUtc == null &&
                categoryRow.isNull("deleted_at") &&
                catName.isNotBlank() &&
                cloud.findCategory(CloudRecordKey.category(catName))?.name == catName
            val catKey = if (categoryTrusted) CloudRecordKey.category(catName)
                else null
            val categoryVerified = if (type == "category") true
                else categoryTrusted && localCategory == catName &&
                    cloud.cloudId("category", requireNotNull(catKey)) == remoteCategoryId &&
                    inbox.localKeyForCloudId("category", remoteCategoryId) == catKey

            val actualFieldsMatchRemote = localName != null && row != null &&
                localName == row.optString("name") &&
                actualKey == localKey &&
                (if (type == "item")
                    row.opt("is_active") is Boolean &&
                        localActive == row.getBoolean("is_active") &&
                        localCategory == catName
                else true)

            val pending = inbox.pendingForLocalKey(type,localKey) > 0 ||
                inbox.hasPendingLocalEditForCloudId(type,cloudUuid) > 0
            val fromKey = cloud.cloudId(type,localKey)
            val fromCloud = inbox.localKeyForCloudId(type,cloudUuid)
            val decision = StaffCatalogCapturePolicy.decide(
                StaffCatalogCaptureEvidence(
                    exactWorkspaceAndCloudUuid=validShadow,
                    remoteRevisionPositive=shadow?.revision?.let { it > 0 } == true,
                    remoteNotDeleted=notDeleted,
                    localRecordExists=localName != null && actualKey == localKey,
                    currentLocalFieldsMatchCloud=actualFieldsMatchRemote,
                    referencedCategoryExactlyMapped=categoryVerified,
                    pendingLocalEdit=pending,
                    localKeyHasCloudIdentity=fromKey != null,
                    cloudUuidHasLocalIdentity=fromCloud != null
                ),
                categoryCandidate=(type == "category")
            )
            if (decision != StaffCatalogCaptureDecision.READY_FOR_OWNER_REVIEW) {
                CaptureResult(decision)
            } else {
                val verifiedRow = requireNotNull(shadow)
                CaptureResult(
                    decision,
                    StaffCatalogCandidatePolicy.draft(
                        StaffCandidateInputs(
                            workspaceId=verifiedRow.workspaceId,
                            deviceId=actualDeviceId,
                            cloudUuid=cloudUuid,
                            cloudRevision=verifiedRow.revision,
                            cloudWorkspaceMatches=true,
                            cloudRecordDeleted=false,
                            entityType=type,
                            localKey=localKey,
                            localName=requireNotNull(localName),
                            localCategory=localCategory,
                            localActive=localActive,
                            localPendingEdit=false
                        )
                    )
                )
            }
        }
    }
}
