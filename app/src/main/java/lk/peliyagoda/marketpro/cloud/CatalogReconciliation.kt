package lk.peliyagoda.marketpro.cloud

import java.util.Locale

/** A compact, non-sensitive item/category projection. No financial rows leave the cloud. */
internal data class CatalogRecord(
    val name: String,
    val category: String,
    val active: Boolean
)

internal data class CatalogAudit(
    val localActive: Int,
    val remoteActive: Int,
    val remoteArchived: Int,
    val matchedActive: Int,
    val onlyOnPhone: List<String>,
    val onlyInCloud: List<String>,
    val differentCategory: List<String>
) {
    val fullyMatched: Boolean
        get() = matchedActive == localActive &&
            matchedActive == remoteActive && differentCategory.isEmpty()
}

/**
 * Never deduce financial record identity from a similar-looking item name.
 * This ONLY determines whether active catalog names + categories are the same.
 * Immutable cloud UUID / local ID mapping is handled by a later migration.
 */
internal object CatalogReconciliation {
    private fun normalized(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun compare(phone: List<CatalogRecord>, cloud: List<CatalogRecord>): CatalogAudit {
        val local = phone.filter { it.active }
        val remote = cloud.filter { it.active }
        val key: (CatalogRecord) -> String = {
            normalized(it.name) + "|" + normalized(it.category)
        }
        val localKeys = local.map(key)
        val remoteKeys = remote.map(key)
        require(localKeys.size == localKeys.toSet().size) { "Duplicate active phone item/category" }
        require(remoteKeys.size == remoteKeys.toSet().size) { "Duplicate active cloud item/category" }

        val both = localKeys.toSet().intersect(remoteKeys.toSet())
        val missingPhone = remote.filter { key(it) !in localKeys.toSet() }
        val missingCloud = local.filter { key(it) !in remoteKeys.toSet() }
        val differingCategory = missingPhone.filter { missing ->
            missingCloud.any { normalized(it.name) == normalized(missing.name) }
        }.map { it.name }.distinct().sorted()

        return CatalogAudit(
            localActive = local.size,
            remoteActive = remote.size,
            remoteArchived = cloud.count { !it.active },
            matchedActive = both.size,
            onlyOnPhone = missingCloud.map { it.name }.distinct().sorted().take(15),
            onlyInCloud = missingPhone.map { it.name }.distinct().sorted().take(15),
            differentCategory = differingCategory.take(15)
        )
    }
}
