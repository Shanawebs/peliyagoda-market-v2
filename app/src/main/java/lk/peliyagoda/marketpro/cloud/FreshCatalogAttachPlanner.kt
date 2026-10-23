package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import java.util.Locale
import java.util.UUID

/**
 * Deterministic catalog-only attachment. It is impossible for this planner to
 * create/overwrite Cloud rows: remote UUIDs are read-only inputs.
 *
 * A fresh V3 sandbox may have 171 active items, while the historical cloud
 * has the same 171 active names plus 13 separately archived demo items.
 * Archive-only extras are intentionally preserved, never imported or deleted.
 */
internal data class AttachLocalCategory(val key: String, val name: String)
internal data class AttachLocalItem(
    val key: String,
    val name: String,
    val category: String,
    val active: Boolean
)
internal data class AttachCloudCategory(val id: String, val name: String)
internal data class AttachCloudItem(
    val id: String,
    val name: String,
    val categoryId: String,
    val active: Boolean,
    val deleted: Boolean = false
)
internal data class CatalogAttachPlan(
    val identities: List<CloudIdentityEntity>,
    val protectedArchivedRemoteItems: Int,
    val matchedItems: Int
)

internal object FreshCatalogAttachPlanner {
    private fun norm(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun validUuid(text: String): Boolean =
        runCatching { UUID.fromString(text).toString().equals(text, true) }
            .getOrDefault(false)

    fun build(
        localCategories: List<AttachLocalCategory>,
        localItems: List<AttachLocalItem>,
        remoteCategories: List<AttachCloudCategory>,
        remoteItems: List<AttachCloudItem>
    ): CatalogAttachPlan {
        require(localCategories.isNotEmpty() && localItems.isNotEmpty())
        require(localCategories.size <= 1000 && localItems.size <= 1000)
        require(remoteCategories.size <= 1000 && remoteItems.size <= 1000)
        require(localCategories.all { it.key.isNotEmpty() && it.name.isNotBlank() })
        require(localItems.all { it.key.isNotEmpty() && it.name.isNotBlank() })

        val lc = localCategories.groupBy { norm(it.name) }
        val rc = remoteCategories.groupBy { norm(it.name) }
        require(lc.values.all { it.size == 1 } && rc.values.all { it.size == 1 })
        require(lc.keys == rc.keys) { "Remote category names do not match fresh phone" }

        val remoteIdSet = (remoteCategories.map { it.id } + remoteItems.map { it.id })
        require(remoteIdSet.all(::validUuid) && remoteIdSet.map { it.lowercase(Locale.ROOT) }.toSet().size == remoteIdSet.size)
        val catNames = remoteCategories.associate { it.id to norm(it.name) }
        require(remoteItems.all { it.categoryId in catNames })

        val li = localItems.groupBy { norm(it.name) }
        val ri = remoteItems.groupBy { norm(it.name) }
        require(li.values.all { it.size == 1 } && ri.values.all { it.size == 1 }) {
            "Ambiguous item names cannot be bound to Cloud UUIDs"
        }
        require(li.keys.all { it in ri }) { "A phone catalog item is absent from Cloud" }
        require(ri.filterKeys { it !in li }.values.flatten().all { !it.active }) {
            "Cloud has active items absent from fresh phone catalog"
        }

        val result = mutableListOf<CloudIdentityEntity>()
        for (row in localCategories) {
            val remote = rc.getValue(norm(row.name)).single()
            result.add(CloudIdentityEntity("category", row.key, remote.id))
        }
        for (row in localItems) {
            val remote = ri.getValue(norm(row.name)).single()
            require(remote.active == row.active && !remote.deleted) {
                "Phone/Cloud item active or deletion status differs"
            }
            require(catNames[remote.categoryId] == norm(row.category)) {
                "Phone/Cloud category differs for an item"
            }
            result.add(CloudIdentityEntity("item", row.key, remote.id))
        }
        require(result.map { it.entityType to it.entityKey }.toSet().size == result.size)
        require(result.map { it.cloudId.lowercase(Locale.ROOT) }.toSet().size == result.size)
        return CatalogAttachPlan(
            identities = result,
            protectedArchivedRemoteItems = ri.filterKeys { it !in li }
                .values.flatten().count { !it.active },
            matchedItems = localItems.size
        )
    }
}
