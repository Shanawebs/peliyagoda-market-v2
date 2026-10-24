package lk.peliyagoda.marketpro.data

/**
 * Daily expenses unrelated to vegetable purchase line items.
 * Money is always kept as integer cents; all four amounts are independent.
 * The business date is the single natural key. Remarks are day-specific.
 */
enum class OtherExpenseField { TRANSPORT, LABOR_CHARGE, MEALS_AND_DRINKS, OTHERS }

data class OtherExpenseAmounts(
    val transportCents: Long = 0L,
    val laborChargeCents: Long = 0L,
    val mealsDrinksCents: Long = 0L,
    val othersCents: Long = 0L,
    val remarks: String = ""
) {
    init {
        require(listOf(transportCents, laborChargeCents, mealsDrinksCents, othersCents)
            .all { it in 0L..MAX_CATEGORY_CENTS })
        require(remarks.length <= MAX_REMARK_CHARS)
    }
    val totalCents: Long
        get() = transportCents + laborChargeCents + mealsDrinksCents + othersCents

    fun withAmount(field: OtherExpenseField, cents: Long): OtherExpenseAmounts {
        require(cents in 0L..MAX_CATEGORY_CENTS)
        return when (field) {
            OtherExpenseField.TRANSPORT -> copy(transportCents = cents)
            OtherExpenseField.LABOR_CHARGE -> copy(laborChargeCents = cents)
            OtherExpenseField.MEALS_AND_DRINKS -> copy(mealsDrinksCents = cents)
            OtherExpenseField.OTHERS -> copy(othersCents = cents)
        }
    }

    companion object {
        const val MAX_CATEGORY_CENTS = 9_999_999_999L
        const val MAX_REMARK_CHARS = 500
    }
}

fun cashBalanceAfterOtherExpenses(
    cashReceivedCents: Long,
    purchaseExpensesCents: Long,
    otherExpensesCents: Long
): Long {
    require(cashReceivedCents >= 0 && purchaseExpensesCents >= 0 && otherExpensesCents >= 0)
    return cashReceivedCents - purchaseExpensesCents - otherExpensesCents
}
