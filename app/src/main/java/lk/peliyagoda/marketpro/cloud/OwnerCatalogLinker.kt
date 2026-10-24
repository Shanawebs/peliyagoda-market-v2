package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.time.Instant

/**
 * Dormant V3.6.2 owner-reviewed binding of EXISTING Room catalog rows.
 * The approval must be fetched from an audited server RPC under JWT/device
 * authorization; NEVER take it from an unverified deep link, QR or UI field.
 *
 * This file changes only cloud_entity_ids + the link checkpoint atomically.
 * It NEVER changes preexisting ItemEntity/CategoryEntity or any financial
 * entries, images, requisitions, outbox or cash.
 */
internal class OwnerCatalogLinker(private val db: MarketDatabase) {
    private val cloud = db.cloudDao()
    private val inbox = db.inboxDao()
    private val catalog = db.dao()

    suspend fun linkExistingCatalog(
        approval: OwnerCatalogLinkApproval,
        physicalDeviceId: String,
        authenticatedUserId: String,
        ownerUserId: String,
        serverAuthenticatedApproval: Boolean,
        now: Instant
    ): OwnerCatalogLinkResult {
        // Prevent a programming mistake enabling linking in offline preview.
        check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED) {
            "Owner-approved catalog linking has no live backend in this build"
        }
        return db.withTransaction {
            val shadow = inbox.snapshot(approval.type,approval.cloudUuid)
            val json = shadow?.let { JSONObject(it.canonicalJson) }
            val trusted = shadow != null && json != null &&
                shadow.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
                approval.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
                shadow.cloudId == approval.cloudUuid &&
                json.optString("id") == approval.cloudUuid &&
                json.optString("workspace_id") == approval.workspaceId &&
                json.optLong("revision") == shadow.revision &&
                shadow.revision > 0
            val type = approval.type
            val localKey = approval.localKey
            val linkedId = localKey.toLongOrNull()?.takeIf { it > 0L }
            val localItem = if (type == "item" && linkedId != null)
                cloud.findItem(linkedId) else null
            val localCategory = if (type == "category")
                catalog.allCategories().singleOrNull {
                    CloudRecordKey.category(it.name) == localKey
                } else null

            val localPresent = when(type) {
                "item" -> localItem != null
                "category" -> localCategory != null
                else -> false
            }
            val fingerprint = when(type) {
                "item" -> if (localItem != null)
                    OwnerCatalogLinkPolicy.fingerprint(
                        localItem.name,localItem.category,localItem.isActive)
                    else ""
                "category" -> if (localCategory != null)
                    OwnerCatalogLinkPolicy.fingerprint(localCategory.name,null,null)
                    else ""
                else -> ""
            }

            val remoteCategoryUuid = if (type == "item")
                json?.optString("category_id").orEmpty() else ""
            val categoryShadow = if (CloudInboxPagePolicy.uuid(remoteCategoryUuid))
                inbox.snapshot("category",remoteCategoryUuid) else null
            val remoteCategoryName = if (categoryShadow != null &&
                categoryShadow.workspaceId == approval.workspaceId &&
                categoryShadow.deletedAtUtc == null) {
                val categoryRow = JSONObject(categoryShadow.canonicalJson)
                if (categoryRow.optString("id") == remoteCategoryUuid &&
                    categoryRow.optString("workspace_id") == approval.workspaceId &&
                    categoryRow.optLong("revision") == categoryShadow.revision &&
                    categoryShadow.revision > 0)
                    categoryRow.optString("name")
                else null
            } else null
            val exactCategory = if (type == "category") true else {
                val expectedKey = remoteCategoryName?.let(CloudRecordKey::category)
                expectedKey != null && localItem?.category == remoteCategoryName &&
                    inbox.localKeyForCloudId("category",remoteCategoryUuid) == expectedKey &&
                    cloud.cloudId("category",expectedKey) == remoteCategoryUuid &&
                    catalog.allCategories().any { it.name == remoteCategoryName }
            }
            val matchingDetails = json != null && trusted &&
                json.optString("name").isNotBlank() &&
                when(type) {
                    "category" -> localCategory?.name == json.optString("name")
                    "item" -> localItem?.name == json.optString("name") &&
                        localItem.isActive == json.optBoolean("is_active",false)
                    else -> false
                }
            val forwardUuid = if (localPresent) cloud.cloudId(type,localKey) else null
            val reverseLocal = inbox.localKeyForCloudId(type,approval.cloudUuid)
            val pending = if (localPresent)
                inbox.pendingForLocalKey(type,localKey)>0 ||
                    inbox.hasPendingLocalEditForCloudId(type,approval.cloudUuid)>0
                else false
            val decision = OwnerCatalogLinkPolicy.decide(
                CatalogLinkCandidate(
                    workspaceId=CloudSyncConfig.WORKSPACE_ID,
                    physicalDeviceId=physicalDeviceId,
                    type=type,
                    localKey=localKey,
                    cloudUuid=approval.cloudUuid,
                    cloudRevision=shadow?.revision ?: -1,
                    localFingerprint=fingerprint,
                    authenticatedUserId=authenticatedUserId,
                    ownerUserId=ownerUserId,
                    serverAuthenticatedApproval=serverAuthenticatedApproval &&
                        trusted,
                    nowEpochSec=now.epochSecond,
                    localPresent=localPresent,
                    remoteNotDeleted=shadow?.deletedAtUtc==null &&
                        json?.isNull("deleted_at")==true,
                    exactCategoryUuidLinked=exactCategory,
                    cloudAndLocalDetailsAgree=matchingDetails,
                    hasPendingLocalEdit=pending,
                    existingUuidForLocalKey=forwardUuid,
                    existingLocalKeyForUuid=reverseLocal
                ),approval
            )
            if (decision == OwnerCatalogLinkResult.CAN_LINK) {
                check(cloud.insertIdentity(
                    CloudIdentityEntity(type,localKey,approval.cloudUuid))>0L) {
                    "Identity collision, Room transaction rolled back"
                }
            }
            if (decision in setOf(
                OwnerCatalogLinkResult.CAN_LINK,OwnerCatalogLinkResult.ALREADY_LINKED
            )) {
                check(cloud.cloudId(type,localKey)==approval.cloudUuid &&
                    inbox.localKeyForCloudId(type,approval.cloudUuid)==localKey)
                val key="owner_catalog_approved_rev|$type|${approval.cloudUuid}"
                val previous=cloud.state(key)?.toLongOrNull()
                check(previous==null || previous<=approval.cloudRevision) {
                    "Outdated owner mapping approval; Room rolled back"
                }
                cloud.setState(CloudSyncStateEntity(key,approval.cloudRevision.toString()))
            }
            decision
        }
    }
}
