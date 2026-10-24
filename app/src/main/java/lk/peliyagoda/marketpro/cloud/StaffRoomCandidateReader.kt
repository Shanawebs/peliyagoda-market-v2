package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CloudInboxSnapshotEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.util.Locale

/**
 * Reads actual staff-phone Room catalog + authenticated/staged Cloud shadows.
 * Returns a draft for Owner REVIEW, not a canonical UUID link or live approval.
 * NOT invoked by the offline app while server/security feature gates are off.
 */
internal class StaffRoomCandidateReader(private val db: MarketDatabase) {
    private val catalog = db.dao()
    private val cloud = db.cloudDao()
    private val inbox = db.inboxDao()

    private fun normalized(name: String) = name.trim().lowercase(Locale.ROOT)

    private fun checkedShadow(
        snapshot: CloudInboxSnapshotEntity?,
        type: String,
        cloudId: String
    ): JSONObject? {
        if (snapshot == null ||
            snapshot.entityType != type ||
            snapshot.cloudId != cloudId ||
            snapshot.workspaceId != CloudSyncConfig.WORKSPACE_ID ||
            snapshot.revision < 1L ||
            !CloudInboxPagePolicy.uuid(cloudId)) return null
        return runCatching {
            JSONObject(snapshot.canonicalJson).takeIf { json ->
                json.getString("id") == cloudId &&
                    json.getString("workspace_id") == CloudSyncConfig.WORKSPACE_ID &&
                    json.getLong("revision") == snapshot.revision &&
                    json.has("deleted_at") &&
                    json.has("name") &&
                    json.getString("name").isNotBlank()
            }
        }.getOrNull()
    }

    suspend fun prepare(
        type: String,
        localKey: String,
        cloudUuid: String,
        physicalDeviceId: String
    ): StaffCatalogCandidate = db.withTransaction {
        require(type in setOf("category", "item"))
        require(CloudInboxPagePolicy.uuid(physicalDeviceId) &&
            CloudInboxPagePolicy.uuid(cloudUuid))
        require(localKey.isNotBlank() && localKey.length <= 200)

        val remoteShadow = inbox.snapshot(type,cloudUuid)
        val remote = checkedShadow(remoteShadow,type,cloudUuid)
        val deleted = remoteShadow?.deletedAtUtc != null ||
            (remote != null && !remote.isNull("deleted_at"))

        val localCategory = if (type=="category") cloud.findCategory(localKey) else null
        val itemId = if (type=="item")
            localKey.toLongOrNull()?.takeIf { it>0L } else null
        val localItem = itemId?.let { cloud.findItem(it) }
        val localExists=if(type=="item")localItem!=null else localCategory!=null
        val localName=localItem?.name ?: localCategory?.name.orEmpty()
        val remoteName=remote?.optString("name").orEmpty()
        val names=if(type=="item")catalog.allItems().map { it.name }
            else catalog.allCategories().map { it.name }
        val sameNameCount=if(remoteName.isBlank())0 else names.count {
            normalized(it)==normalized(remoteName)
        }

        var categoryVerified=true
        var categoryMatches=true
        var activeMatches=true
        if(type=="item") {
            val categoryUuid=remote?.optString("category_id").orEmpty()
            val remoteCategory=if(CloudInboxPagePolicy.uuid(categoryUuid))
                checkedShadow(inbox.snapshot("category",categoryUuid),
                    "category",categoryUuid) else null
            val remoteCategoryShadow=if(CloudInboxPagePolicy.uuid(categoryUuid))
                inbox.snapshot("category",categoryUuid) else null
            categoryVerified=remoteCategory!=null &&
                remoteCategoryShadow?.deletedAtUtc==null &&
                remoteCategory.isNull("deleted_at")
            categoryMatches=categoryVerified &&
                localItem?.category==remoteCategory?.optString("name")
            activeMatches=remote!=null &&
                remote.has("is_active") &&
                remote.opt("is_active") is Boolean &&
                localItem?.isActive==remote.getBoolean("is_active")
        }
        val forward=if(localExists) cloud.cloudId(type,localKey) else null
        val reverse=inbox.localKeyForCloudId(type,cloudUuid)
        val mappedElsewhere=forward!=null && forward!=cloudUuid
        val claimedByOther=reverse!=null && reverse!=localKey
        val pending = inbox.pendingForLocalKey(type,localKey)>0 ||
            inbox.hasPendingLocalEditForCloudId(type,cloudUuid)>0
        val decision=StaffRoomCandidateDecision.evaluate(StaffRoomEvidence(
            snapshotTrusted=remote!=null,
            remoteDeleted=deleted,
            localExists=localExists,
            localNameMatchesCloud=localName.isNotBlank() &&
                localName==remoteName &&
                (type=="item" || CloudRecordKey.category(localName)==localKey),
            localNameCandidateCount=sameNameCount,
            localCategoryMatchesCloud=categoryMatches,
            localActiveMatchesCloud=activeMatches,
            verifiedCloudCategoryPresent=categoryVerified,
            localPendingEdit=pending,
            identityMappedToOtherUuid=mappedElsewhere,
            remoteUuidMappedToOtherLocalKey=claimedByOther,
            localAlreadyLinkedToSameRemote=forward==cloudUuid &&
                reverse==localKey
        ))
        check(decision==StaffRoomCandidateStatus.READY_FOR_OWNER_REVIEW) {
            "Catalog cannot be sent for Owner review: $decision"
        }
        // Never expose a free-form room ID from a Compose text field.
        // The exact item/category must really exist in THIS phone's Room DB.
        StaffCatalogCandidatePolicy.draft(StaffCandidateInputs(
            workspaceId=CloudSyncConfig.WORKSPACE_ID,
            deviceId=physicalDeviceId,
            cloudUuid=cloudUuid,
            cloudRevision=requireNotNull(remoteShadow).revision,
            cloudWorkspaceMatches=true,
            cloudRecordDeleted=false,
            entityType=type,
            localKey=localKey,
            localName=localName,
            localCategory=localItem?.category,
            localActive=localItem?.isActive,
            localPendingEdit=false
        ))
    }
}
