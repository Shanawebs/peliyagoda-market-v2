package lk.peliyagoda.marketpro.util

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity

/** A selected kitchen item and its editable required quantity, without changing Room data. */
data class KitchenRequirementRow(
    val item: ItemEntity,
    val requirement: RequisitionEntity
)

/** Used only to present requisitions in category sections. */
data class KitchenCategoryGroup(
    val category: String,
    val rows: List<KitchenRequirementRow>
) {
    val itemCount: Int get() = rows.size
    val subtotalGrams: Long get() = rows.sumOf { it.requirement.quantityGrams }
}

/**
 * Returns the selected kitchen's requirements grouped by their master-item
 * categories. Both category names and item names sort A-Z; ties use stable IDs.
 * Only existing master items can be rendered, matching the old card behavior.
 * The calculation is read-only and never modifies autosaved requisition data.
 */
fun groupKitchenRequirements(
    items: List<ItemEntity>,
    requirements: List<RequisitionEntity>,
    kitchen: String
): List<KitchenCategoryGroup> {
    val byId = items.associateBy { it.id }
    return requirements.asSequence()
        .filter { it.kitchen == kitchen }
        .mapNotNull { req ->
            byId[req.itemId]?.let { item -> KitchenRequirementRow(item, req) }
        }
        .groupBy { it.item.category }
        .entries
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
        .map { (category, unsorted) ->
            KitchenCategoryGroup(
                category = category,
                rows = unsorted.sortedWith(
                    compareBy<KitchenRequirementRow>(String.CASE_INSENSITIVE_ORDER) {
                        it.item.name
                    }.thenBy { it.item.id }
                )
            )
        }
}
