package lk.peliyagoda.marketpro.util

/** Shared rules for allowing a second purchase after an initial priced purchase. */
object PurchaseEntryRules {
    const val MAX_VALUE = 1_000_000_000L

    fun canAddAnotherPurchase(
        initialQtyGrams: Long?,
        initialPriceCents: Long?,
        requiredGrams: Long,
        totalPurchasedGrams: Long
    ): Boolean {
        return initialQtyGrams != null && initialQtyGrams in 1..MAX_VALUE &&
            initialPriceCents != null && initialPriceCents in 1..MAX_VALUE &&
            requiredGrams > totalPurchasedGrams
    }

    /**
     * Blank field means zero stored in Room, never an invalid editor state.
     * A temporary incomplete decimal (e.g. "1.") stays a draft until valid.
     */
    fun blankQuantity(text: String): Long? = MarketMath.parseBlankGrams(text)
    fun blankUnitPrice(text: String): Long? = MarketMath.parseBlankPriceCents(text)
}
