package lk.peliyagoda.marketpro.util

import java.util.Locale

/**
 * Move a COMPLETED purchase behind the active purchasing work ONLY when:
 * 1) it has bought >= required and nonzero purchased quantity;
 * 2) every positive-quantity purchase lot has a nonzero market price;
 * 3) an original invoice photo was saved for this item and business date;
 * 4) at least 60 seconds passed since the most recently captured invoice.
 *
 * Photo timestamps belong to the business date and item, supplied by the UI.
 * Items in each group remain ordered alphabetically, irrespective of category.
 */
const val MARKET_PHOTO_SETTLE_DELAY_MS: Long = 60_000L

fun hasPricedCompletedPurchase(row: ConsolidatedRow): Boolean =
    row.purchasedGrams > 0L &&
        row.purchasedGrams >= row.requiredGrams &&
        (row.purchase == null ||
            row.purchase.quantityGrams <= 0L ||
            row.purchase.priceCentsPerKg > 0L) &&
        row.additions.all { it.quantityGrams <= 0L || it.priceCentsPerKg > 0L }

fun isArchivedForMarket(
    row: ConsolidatedRow,
    latestInvoiceMs: Long?,
    nowMs: Long,
    gracePeriodMs: Long = MARKET_PHOTO_SETTLE_DELAY_MS
): Boolean {
    if (!hasPricedCompletedPurchase(row)) return false
    if (latestInvoiceMs == null || latestInvoiceMs <= 0L) return false
    if (nowMs < latestInvoiceMs) return false
    return nowMs - latestInvoiceMs >= gracePeriodMs
}

fun sortedMarketPurchases(
    rows: List<ConsolidatedRow>,
    photoTimestamps: Map<Long, Long>,
    nowMs: Long
): List<ConsolidatedRow> =
    rows.sortedWith(
        compareBy<ConsolidatedRow> {
            if (isArchivedForMarket(it, photoTimestamps[it.item.id], nowMs)) 1 else 0
        }.thenBy { it.item.name.lowercase(Locale.ROOT) }
            .thenBy { it.item.id }
    )

/** Next UI-only refresh required to move a newly finished item to the bottom. */
fun nextMarketSortRefreshMs(
    rows: List<ConsolidatedRow>,
    photoTimestamps: Map<Long, Long>,
    nowMs: Long
): Long? =
    rows.asSequence()
        .filter(::hasPricedCompletedPurchase)
        .mapNotNull { photoTimestamps[it.item.id] }
        .filter { it > 0L }
        .map { it + MARKET_PHOTO_SETTLE_DELAY_MS }
        .filter { it > nowMs }
        .minOrNull()
