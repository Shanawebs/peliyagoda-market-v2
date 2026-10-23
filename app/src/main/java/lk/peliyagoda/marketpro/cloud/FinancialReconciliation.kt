package lk.peliyagoda.marketpro.cloud

import java.util.Locale

/**
 * Read-only financial record comparison, not a UUID migration.
 * Compares a MULTISET of historical rows by (date, normalized catalog identity,
 * kitchen, quantity grams, price cents/kg, or daily cash cents). This detects
 * amount/date/quantity mismatches even when record COUNTS are identical.
 *
 * No local or remote database writes, no cloud writer registration, no RPCs.
 */
internal data class FinancialFingerprint(
    val kind: String,
    val signature: String
)

data class FinancialAuditSection(
    val type: String,
    val phoneCount: Int,
    val cloudCount: Int,
    val matchedRows: Int,
    val phoneOnly: Int,
    val cloudOnly: Int
) {
    val matched: Boolean
        get() = phoneOnly == 0 && cloudOnly == 0 &&
            phoneCount == cloudCount && matchedRows == phoneCount
}

data class FinancialAudit(
    val sections: List<FinancialAuditSection>,
    val unmappedPhoneItems: Int,
    val unmappedCloudItems: Int
) {
    val fullyMatched: Boolean
        get() = sections.isNotEmpty() &&
            sections.all { it.matched } &&
            unmappedPhoneItems == 0 && unmappedCloudItems == 0
}

internal object FinancialReconciliation {
    private val types = listOf(
        "requisitions", "purchases", "purchase_additions", "daily_cash_received"
    )

    fun itemKey(name: String, category: String): String =
        name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) +
            "\u001f" + category.trim().lowercase(Locale.ROOT)

    fun row(
        type: String,
        date: String,
        item: String = "",
        kitchen: String = "",
        grams: Long = 0L,
        priceCentsPerKg: Long = 0L,
        cashCents: Long = 0L
    ): FinancialFingerprint {
        require(type in types) { "Unsupported financial type" }
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid business date" }
        require(grams >= 0 && priceCentsPerKg >= 0 && cashCents >= 0) {
            "Invalid negative quantity or money"
        }
        val signature = when (type) {
            "requisitions" -> listOf(date, kitchen, item, grams.toString())
            "purchases", "purchase_additions" ->
                listOf(date, item, grams.toString(), priceCentsPerKg.toString())
            else -> listOf(date, cashCents.toString())
        }.joinToString("\u001e")
        return FinancialFingerprint(type, signature)
    }

    fun compare(
        phone: List<FinancialFingerprint>,
        cloud: List<FinancialFingerprint>,
        missingPhoneItems: Int = 0,
        missingCloudItems: Int = 0
    ): FinancialAudit {
        fun frequency(entries: List<FinancialFingerprint>): Map<String, Int> =
            entries.groupingBy { it.signature }.eachCount()
        val sections = types.map { type ->
            val a = phone.filter { it.kind == type }
            val b = cloud.filter { it.kind == type }
            val af = frequency(a)
            val bf = frequency(b)
            val matching = (af.keys + bf.keys).toSet().sumOf { key ->
                minOf(af[key] ?: 0, bf[key] ?: 0)
            }
            FinancialAuditSection(
                type, a.size, b.size, matching,
                a.size - matching, b.size - matching
            )
        }
        return FinancialAudit(sections, missingPhoneItems, missingCloudItems)
    }
}
