package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CategoryEntity
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.util.Locale

/**
 * V3.6.1 - SOURCE ONLY. NOT referenced from the running Activity/Worker.
 *
 * Takes a previously authenticated, workspace-verified Cloud Inbox shadow.
 * New noncolliding catalogs may be imported with exact canonical UUID mapping.
 * Existing local entries are NEVER linked by matching names alone. Their
 * historical data, category assignments and invoice photos remain untouched.
 */
internal class CloudCatalogBootstrap(private val db: MarketDatabase) {
    private val dao = db.dao()
    private val cloud = db.cloudDao()
    private val inbox = db.inboxDao()

    data class Result(
        val decision: CatalogLinkDecision,
        val localItemId: Long? = null,
        val serverRevision: Long? = null
    )

    private fun normalize(s: String): String = s.trim().lowercase(Locale.ROOT)

    private fun nameFrom(json: JSONObject): String {
        val name = json.getString("name")
        require(name.isNotBlank() && name == name.trim() && name.length <= 250) {
            "Invalid Cloud catalog name"
        }
        return name
    }

    private fun verifiedRow(
        type: String, cloudId: String, workspace: String,
        shadow: lk.peliyagoda.marketpro.data.CloudInboxSnapshotEntity
    ): JSONObject {
        val row = JSONObject(shadow.canonicalJson)
        require(type == shadow.entityType &&
            shadow.cloudId == cloudId &&
            shadow.workspaceId == workspace &&
            row.getString("id") == cloudId &&
            row.getString("workspace_id") == workspace &&
            row.getLong("revision") == shadow.revision &&
            shadow.revision > 0L &&
            CloudInboxPagePolicy.uuid(cloudId)) {
            "Unverified Cloud catalog row"
        }
        return row
    }

    private suspend fun saveCheckpoint(type: String,id: String,rev: Long): Boolean {
        val key = "catalog_pull_applied_rev|$type|$id"
        val old = cloud.state(key)
        if (old != null && (old.toLongOrNull() ?: 0L) > rev) return false
        cloud.setState(CloudSyncStateEntity(key,rev.toString()))
        return true
    }

    /**
     * Caller must first stage categories using the authenticated read API.
     * Only NEW names are inserted. A preexisting seeded category with the same
     * spelling is a REVIEW_NAME_COLLISION (not an implicit remote UUID link).
     */
    suspend fun importCategory(cloudId: String, workspace: String): Result {
        require(workspace == CloudSyncConfig.WORKSPACE_ID)
        return db.withTransaction {
            val shadow = inbox.snapshot("category",cloudId)
                ?: return@withTransaction Result(CatalogLinkDecision.REJECT_UNTRUSTED)
            val row = verifiedRow("category",cloudId,workspace,shadow)
            val name = nameFrom(row)
            val key = CloudRecordKey.category(name)
            val localCategories = dao.allCategories()
            val matching = localCategories.filter { normalize(it.name) == normalize(name) }
            val remoteToLocal = inbox.localKeyForCloudId("category",cloudId)
            val localToRemote = cloud.cloudId("category",key)
            val evidence = CatalogLinkEvidence(
                trustedWorkspace=true,
                remoteIdValid=true,
                remoteRevisionValid=shadow.revision > 0,
                remoteDeleted=shadow.deletedAtUtc != null || !row.isNull("deleted_at"),
                referencedCategoryVerified=true,
                hasPendingLocalEdit=inbox.pendingForLocalKey("category",key)>0 ||
                    inbox.hasPendingLocalEditForCloudId("category",cloudId)>0,
                normalizedNameCollision=matching.isNotEmpty(),
                localKeyForRemoteUuid=remoteToLocal,
                proposedLocalKey=key,
                remoteUuidForProposedKey=localToRemote,
                remoteUuidForProposedKeyMatchesIncoming=localToRemote == cloudId,
                mappedLocalContentsEqual=matching.size==1 && matching.single().name==name
            )
            val decision = CatalogLinkPolicy.decide(evidence)
            if (decision !in setOf(CatalogLinkDecision.IMPORT_NEW,
                    CatalogLinkDecision.VERIFIED_ALREADY_LINKED)) {
                return@withTransaction Result(decision,serverRevision=shadow.revision)
            }
            if (decision==CatalogLinkDecision.IMPORT_NEW) {
                check(dao.insertCategory(CategoryEntity(name)) > 0) {
                    "Concurrent category collision, rollback"
                }
                check(cloud.insertIdentity(CloudIdentityEntity("category",key,cloudId)) > 0L) {
                    "Cloud category already mapped, rollback"
                }
            }
            check(inbox.localKeyForCloudId("category",cloudId)==key &&
                cloud.cloudId("category",key)==cloudId)
            if (!saveCheckpoint("category",cloudId,shadow.revision)) {
                return@withTransaction Result(CatalogLinkDecision.REJECT_UNTRUSTED,
                    serverRevision=shadow.revision)
            }
            Result(decision,serverRevision=shadow.revision)
        }
    }

    /**
     * Import a NEW item ONLY after its category was already mapped from an
     * exact Cloud UUID and the local category name matches its shadow record.
     * Existing local item names force a deliberate owner reconciliation.
     */
    suspend fun importItem(cloudId: String, workspace: String): Result {
        require(workspace == CloudSyncConfig.WORKSPACE_ID)
        return db.withTransaction {
            val shadow = inbox.snapshot("item",cloudId)
                ?: return@withTransaction Result(CatalogLinkDecision.REJECT_UNTRUSTED)
            val row = verifiedRow("item",cloudId,workspace,shadow)
            val name = nameFrom(row)
            val categoryUuid = row.getString("category_id")
            require(CloudInboxPagePolicy.uuid(categoryUuid))
            val categoryShadow=inbox.snapshot("category",categoryUuid)
            val categoryName = if (categoryShadow != null &&
                categoryShadow.deletedAtUtc == null &&
                categoryShadow.workspaceId == workspace &&
                categoryShadow.cloudId == categoryUuid) {
                val categoryRow=verifiedRow("category",categoryUuid,workspace,categoryShadow)
                nameFrom(categoryRow)
            } else null
            val categoryLocalKey = inbox.localKeyForCloudId("category",categoryUuid)
            val linkedCategory = categoryName != null &&
                categoryLocalKey == CloudRecordKey.category(categoryName) &&
                cloud.cloudId("category",requireNotNull(categoryLocalKey)) == categoryUuid &&
                dao.allCategories().any { it.name == categoryName }
            val active = row.getBoolean("is_active")
            val items = dao.allItems()
            val matches = items.filter { normalize(it.name) == normalize(name) }
            val mappedKey = inbox.localKeyForCloudId("item",cloudId)
            val mappedId = mappedKey?.toLongOrNull()?.takeIf { it > 0L }
            val mappedItem = mappedId?.let { id -> items.firstOrNull { it.id == id } }
            val localToRemote = mappedKey?.let { cloud.cloudId("item",it) }
            val evidence = CatalogLinkEvidence(
                trustedWorkspace=true,
                remoteIdValid=true,
                remoteRevisionValid=shadow.revision > 0,
                remoteDeleted=shadow.deletedAtUtc != null || !row.isNull("deleted_at"),
                referencedCategoryVerified=linkedCategory,
                hasPendingLocalEdit=(mappedKey != null &&
                    inbox.pendingForLocalKey("item",mappedKey)>0) ||
                    inbox.hasPendingLocalEditForCloudId("item",cloudId)>0,
                normalizedNameCollision=matches.isNotEmpty(),
                localKeyForRemoteUuid=mappedKey,
                proposedLocalKey=mappedKey,
                remoteUuidForProposedKey=localToRemote,
                remoteUuidForProposedKeyMatchesIncoming=localToRemote == cloudId,
                mappedLocalContentsEqual=mappedItem?.name==name &&
                    mappedItem.category==categoryName &&
                    mappedItem.isActive==active
            )
            val decision = CatalogLinkPolicy.decide(evidence)
            if (decision !in setOf(CatalogLinkDecision.IMPORT_NEW,
                    CatalogLinkDecision.VERIFIED_ALREADY_LINKED)) {
                return@withTransaction Result(decision,serverRevision=shadow.revision)
            }
            var resultId = mappedId
            if (decision==CatalogLinkDecision.IMPORT_NEW) {
                resultId = dao.insertItem(ItemEntity(
                    name=name,category=requireNotNull(categoryName),isActive=active))
                check(resultId > 0L) { "Concurrent item-name collision, rollback" }
                val key = CloudRecordKey.item(resultId)
                check(cloud.insertIdentity(CloudIdentityEntity("item",key,cloudId))>0L) {
                    "Cloud item UUID already mapped to different local row"
                }
            }
            val id=requireNotNull(resultId)
            val key=CloudRecordKey.item(id)
            check(inbox.localKeyForCloudId("item",cloudId)==key &&
                cloud.cloudId("item",key)==cloudId)
            if (!saveCheckpoint("item",cloudId,shadow.revision)) {
                return@withTransaction Result(CatalogLinkDecision.REJECT_UNTRUSTED,
                    serverRevision=shadow.revision)
            }
            Result(decision,localItemId=id,serverRevision=shadow.revision)
        }
    }
}
