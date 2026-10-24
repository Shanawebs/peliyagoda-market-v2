package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.OtherExpenseAmounts

/**
 * Pure validation for the exact Cloud daily_other_expenses schema.
 *
 * A matching business date or amount does NOT prove UUID ownership:
 * CloudDomainMergeEngine must also check outbox, both UUID mapping directions
 * and the last server revision/signature in one Room transaction.
 */
internal object OtherExpensesShadowPolicy {
    private const val MAX_CENTS = OtherExpenseAmounts.MAX_CATEGORY_CENTS
    private const val MAX_REMARKS = OtherExpenseAmounts.MAX_REMARK_CHARS

    fun validateAmounts(
        transport: Long, labor: Long, meals: Long, others: Long, remarks: String
    ) {
        require(listOf(transport,labor,meals,others).all { it in 0L..MAX_CENTS }) {
            "Invalid daily expense amount from server"
        }
        require(remarks.length <= MAX_REMARKS &&
            remarks.none { it == '\u0000' }) {
            "Invalid daily expense remarks from server"
        }
    }

    /**
     * Length-prefixing and fixed key order prevent ambiguous concatenation
     * such as remarks "12|3" and "1|23" when hashing local checkpoints.
     */
    fun signature(
        businessDate: String, transport: Long, labor: Long,
        meals: Long, others: Long, remarks: String
    ): String {
        validateAmounts(transport,labor,meals,others,remarks)
        return listOf(businessDate,transport.toString(),labor.toString(),
            meals.toString(),others.toString(),remarks).joinToString("") {
                "${it.length}:${it}"
            }
    }
}
