package lk.peliyagoda.marketpro.util

import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketConstants
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity

/** Each item consolidates its kitchen demand and all independently priced purchase lots. */
data class ConsolidatedRow(
    val item: ItemEntity,
    val mainGrams: Long,
    val indianGrams: Long,
    val pastryGrams: Long,
    val purchase: PurchaseEntity?,
    val additions: List<PurchaseAdditionEntity> = emptyList()
) {
    val requiredGrams: Long get() = mainGrams + indianGrams + pastryGrams
    val purchasedGrams: Long get() = (purchase?.quantityGrams ?: 0L) + additions.sumOf { it.quantityGrams }
    val varianceGrams: Long get() = purchasedGrams - requiredGrams
    val additionalCents: Long get() = additions.sumOf {
        MarketMath.billCents(it.quantityGrams, it.priceCentsPerKg)
    }
    val lineCents: Long get() = (purchase?.let {
        MarketMath.billCents(it.quantityGrams, it.priceCentsPerKg)
    } ?: 0L) + additionalCents
}

fun consolidate(
    items: List<ItemEntity>,
    requisitions: List<RequisitionEntity>,
    purchases: List<PurchaseEntity>,
    additions: List<PurchaseAdditionEntity> = emptyList()
): List<ConsolidatedRow> {
    val itemById = items.associateBy { it.id }
    val reqsByItem = requisitions.groupBy { it.itemId }
    val purchasesByItem = purchases.associateBy { it.itemId }
    val additionsByItem = additions.groupBy { it.itemId }
    return (reqsByItem.keys + purchasesByItem.keys + additionsByItem.keys)
        .mapNotNull { id ->
            val item = itemById[id] ?: return@mapNotNull null
            val entries = reqsByItem[id].orEmpty()
            fun forKitchen(kitchen: String) = entries.filter { it.kitchen == kitchen }
                .sumOf { it.quantityGrams }
            ConsolidatedRow(
                item = item,
                mainGrams = forKitchen(MarketConstants.MAIN),
                indianGrams = forKitchen(MarketConstants.INDIAN),
                pastryGrams = forKitchen(MarketConstants.PASTRY),
                purchase = purchasesByItem[id],
                additions = additionsByItem[id].orEmpty()
            )
        }.sortedBy { it.item.name.lowercase() }
}

private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

fun marketCsv(date: String, rows: List<ConsolidatedRow>): String {
    val header = listOf(
        "Date", "Item", "Category", "Main Kitchen KG", "Indian Kitchen KG",
        "Pastry Kitchen KG", "Total Required KG", "Purchased KG", "Variance KG",
        "Initial Price LKR/KG", "Additional Purchase Lots", "Additional Cost LKR", "Line Total LKR"
    )
    val lines = rows.map { row ->
        listOf(
            date, row.item.name, row.item.category,
            MarketMath.editKg(row.mainGrams), MarketMath.editKg(row.indianGrams),
            MarketMath.editKg(row.pastryGrams), MarketMath.editKg(row.requiredGrams),
            MarketMath.editKg(row.purchasedGrams), MarketMath.editKg(row.varianceGrams),
            row.purchase?.let { MarketMath.editPrice(it.priceCentsPerKg) } ?: "",
            row.additions.size.toString(), MarketMath.editPrice(row.additionalCents),
            MarketMath.editPrice(row.lineCents)
        )
    }
    return (listOf(header) + lines).joinToString("\r\n") { line ->
        line.joinToString(",") { csvCell(it) }
    } + "\r\n"
}
