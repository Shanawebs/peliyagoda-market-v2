package lk.peliyagoda.marketpro.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudInboxSnapshotEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.util.Locale

/**
 * Bounded READ-ONLY snapshot for the Owner's future approval UI.
 * No fake Cloud data, no implicit catalog link, no outbox ACK, and NO RPC.
 */
internal class CatalogReviewReader(private val db: MarketDatabase) {
    private val inbox = db.inboxDao()
    private val cloud = db.cloudDao()
    private val market = db.dao()

    private fun clean(s: String) = s.trim().lowercase(Locale.ROOT)
    private fun isValidSnapshot(s: CloudInboxSnapshotEntity, json: JSONObject): Boolean =
        s.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
        CloudInboxPagePolicy.uuid(s.cloudId) &&
        json.optString("id") == s.cloudId &&
        json.optString("workspace_id") == s.workspaceId &&
        json.optLong("revision",-1) == s.revision &&
        s.revision > 0

    suspend fun snapshot(): List<CatalogReviewRow> = withContext(Dispatchers.IO) {
        // Bounded memory on 4GB phones; current live Cloud item count = 184.
        val categories = inbox.catalogReviewSnapshots("category",250)
        val items = inbox.catalogReviewSnapshots("item",250)
        val locals = market.allItems()
        val localCategories = market.allCategories()
        val identities = cloud.allIdentities()
        val reverse = identities.associateBy { it.cloudId }
        val forward = identities.associateBy { it.entityType to it.entityKey }
        val categoryByUuid = categories.mapNotNull { entry ->
            runCatching {
                val j = JSONObject(entry.canonicalJson)
                if (isValidSnapshot(entry,j) &&
                    entry.deletedAtUtc == null && j.isNull("deleted_at")) {
                    entry.cloudId to j.getString("name")
                } else null
            }.getOrNull()
        }.toMap()

        val result = ArrayList<CatalogReviewRow>(categories.size+items.size)
        for (s in categories+items) {
            val type = s.entityType
            if (type !in setOf("category","item")) continue
            val json = runCatching { JSONObject(s.canonicalJson) }.getOrNull()
            val trusted = json != null && isValidSnapshot(s,json)
            val name = if (trusted) json?.optString("name").orEmpty() else ""
            val remoteDeleted = s.deletedAtUtc != null ||
                json == null || !json.isNull("deleted_at")
            val cloudCatUuid = if (type=="item")
                json?.optString("category_id").orEmpty() else ""
            val remoteCategoryName = categoryByUuid[cloudCatUuid]
            val candidates = if (type=="item")
                locals.filter { clean(it.name) == clean(name) }
            else emptyList()
            val localCategoriesMatching = if (type=="category")
                localCategories.filter { clean(it.name)==clean(name) }
            else emptyList()
            val matches = if (type=="item") candidates.size else localCategoriesMatching.size
            val item = candidates.singleOrNull()
            val category = localCategoriesMatching.singleOrNull()
            val localKey = if (type=="item") item?.let { CloudRecordKey.item(it.id) }
                else category?.let { CloudRecordKey.category(it.name) }
            val fromCloud: CloudIdentityEntity? = reverse[s.cloudId]
            val fromLocal: CloudIdentityEntity? = localKey?.let { forward[type to it] }
            val categoryLinked = if (type!="item") true else {
                val categoryKey = remoteCategoryName?.let { CloudRecordKey.category(it) }
                categoryKey!=null &&
                    item?.category == remoteCategoryName &&
                    forward["category" to categoryKey]?.cloudId==cloudCatUuid &&
                    reverse[cloudCatUuid]?.entityKey==categoryKey &&
                    localCategories.any { it.name==remoteCategoryName }
            }
            val pending = localKey?.let { inbox.pendingForLocalKey(type,it) > 0 }
                ?: false
            val pendingCloud=inbox.hasPendingLocalEditForCloudId(type,s.cloudId)>0
            val forwardMismatch = fromLocal != null &&
                (fromLocal.cloudId != s.cloudId || fromLocal.entityType != type)
            val reverseMismatch = fromCloud != null &&
                (fromCloud.entityType != type ||
                    localKey == null || fromCloud.entityKey != localKey)
            val exactMapping = fromLocal?.cloudId == s.cloudId &&
                fromCloud?.entityType == type &&
                fromCloud.entityKey == localKey
            val valid = trusted && name.isNotBlank() && name.length<=250 &&
                (type=="category" || (
                    CloudInboxPagePolicy.uuid(cloudCatUuid) &&
                    json?.opt("is_active") is Boolean
                ))
            val status = CatalogReviewPolicy.status(
                CatalogReviewEvidence(
                    cloudRowTrusted=valid,
                    cloudDeleted=remoteDeleted,
                    localNameMatches=matches,
                    localCategoryMatches=type=="category" ||
                        (item != null && item.category == remoteCategoryName &&
                         item.isActive == json?.optBoolean("is_active",false)),
                    exactCloudCategoryMapped=categoryLinked,
                    pendingLocalEdit=pending || pendingCloud,
                    localKeyAlreadyBoundToOtherUuid=forwardMismatch,
                    cloudUuidAlreadyBoundToOtherLocalKey=reverseMismatch,
                    forwardAndReverseMappingAgree=exactMapping
                ),isItem=type=="item"
            )
            result.add(CatalogReviewRow(
                type=type,
                cloudName=name.takeIf { valid } ?: "Unverified Cloud Record",
                cloudCategoryName=remoteCategoryName,
                localName=if (type=="item") item?.name else category?.name,
                localCategoryName=item?.category,
                localKey=localKey,
                cloudUuid=s.cloudId,
                cloudRevision=s.revision,
                cloudDeleted=remoteDeleted,
                status=status
            ))
        }
        result.sortedWith(compareBy<CatalogReviewRow>({if(it.type=="category")0 else 1},
            {it.cloudCategoryName.orEmpty()},{it.cloudName}))
    }
}
