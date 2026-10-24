package lk.peliyagoda.marketpro.cloud

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * V3.6.2: pure, fail-closed policy. "serverAuthenticatedApproval" must come
 * only from the future owner-authenticated approval RPC and trusted HTTPS read;
 * never turn it on based on a locally entered PIN, UI boolean or QR alone.
 */
internal data class OwnerCatalogLinkApproval(
    val approvalId: String,
    val workspaceId: String,
    val targetDeviceId: String,
    val type: String,
    val localKey: String,
    val cloudUuid: String,
    val cloudRevision: Long,
    val localFingerprint: String,
    val ownerUserId: String,
    val approvedAtEpochSec: Long,
    val expiresAtEpochSec: Long
)

internal data class CatalogLinkCandidate(
    val workspaceId: String,
    val physicalDeviceId: String,
    val type: String,
    val localKey: String,
    val cloudUuid: String,
    val cloudRevision: Long,
    val localFingerprint: String,
    val authenticatedUserId: String,
    val ownerUserId: String,
    val serverAuthenticatedApproval: Boolean,
    val nowEpochSec: Long,
    val localPresent: Boolean,
    val remoteNotDeleted: Boolean,
    val exactCategoryUuidLinked: Boolean,
    val cloudAndLocalDetailsAgree: Boolean,
    val hasPendingLocalEdit: Boolean,
    val existingUuidForLocalKey: String?,
    val existingLocalKeyForUuid: String?
)

internal enum class OwnerCatalogLinkResult {
    CAN_LINK,
    ALREADY_LINKED,
    BLOCK_NO_TRUSTED_OWNER_APPROVAL,
    BLOCK_CHANGED_OR_EXPIRED_APPROVAL,
    BLOCK_WRONG_DEVICE_OR_WORKSPACE,
    BLOCK_REVIEW_CHANGED_ITEM,
    BLOCK_UNVERIFIED_CATEGORY,
    BLOCK_PENDING_LOCAL_EDIT,
    BLOCK_IDENTITY_COLLISION
}

internal object OwnerCatalogLinkPolicy {
    private fun uuid(value: String) = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    /** SHA-256 of *current* Room values, not untrusted name-only matching. */
    fun fingerprint(name: String, category: String?, active: Boolean?): String {
        fun segment(value: String): String = "${value.length}:$value"
        val canonical = segment(name.trim()) + "|" +
            segment(category?.trim() ?: "") + "|" +
            when (active) {
                true -> "1"
                false -> "0"
                null -> "-"
            }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun decide(
        candidate: CatalogLinkCandidate,
        approval: OwnerCatalogLinkApproval?
    ): OwnerCatalogLinkResult {
        if (approval == null || !candidate.serverAuthenticatedApproval ||
            !uuid(candidate.authenticatedUserId) || !uuid(candidate.ownerUserId) ||
            !uuid(candidate.cloudUuid) || !uuid(candidate.workspaceId) ||
            !uuid(candidate.physicalDeviceId) || !uuid(approval.approvalId) ||
            candidate.ownerUserId != approval.ownerUserId ||
            !uuid(approval.ownerUserId)) {
            return OwnerCatalogLinkResult.BLOCK_NO_TRUSTED_OWNER_APPROVAL
        }
        if (candidate.workspaceId != approval.workspaceId ||
            candidate.physicalDeviceId != approval.targetDeviceId ||
            candidate.cloudUuid != approval.cloudUuid ||
            candidate.type != approval.type ||
            candidate.localKey != approval.localKey ||
            candidate.type !in setOf("category","item")) {
            return OwnerCatalogLinkResult.BLOCK_WRONG_DEVICE_OR_WORKSPACE
        }
        if (candidate.cloudRevision < 1L ||
            candidate.cloudRevision != approval.cloudRevision ||
            candidate.localFingerprint != approval.localFingerprint ||
            !Regex("^[0-9a-f]{64}$").matches(approval.localFingerprint) ||
            candidate.nowEpochSec < approval.approvedAtEpochSec ||
            candidate.nowEpochSec >= approval.expiresAtEpochSec ||
            approval.expiresAtEpochSec - approval.approvedAtEpochSec > 86400 ||
            approval.approvedAtEpochSec <= 0L) {
            return OwnerCatalogLinkResult.BLOCK_CHANGED_OR_EXPIRED_APPROVAL
        }
        if (!candidate.localPresent || !candidate.remoteNotDeleted ||
            !candidate.cloudAndLocalDetailsAgree) {
            return OwnerCatalogLinkResult.BLOCK_REVIEW_CHANGED_ITEM
        }
        if (candidate.type == "item" && !candidate.exactCategoryUuidLinked)
            return OwnerCatalogLinkResult.BLOCK_UNVERIFIED_CATEGORY
        if (candidate.hasPendingLocalEdit)
            return OwnerCatalogLinkResult.BLOCK_PENDING_LOCAL_EDIT
        val reverse = candidate.existingLocalKeyForUuid
        val forward = candidate.existingUuidForLocalKey
        if (reverse != null || forward != null) {
            return if (reverse == candidate.localKey && forward == candidate.cloudUuid)
                OwnerCatalogLinkResult.ALREADY_LINKED
            else OwnerCatalogLinkResult.BLOCK_IDENTITY_COLLISION
        }
        return OwnerCatalogLinkResult.CAN_LINK
    }
}
