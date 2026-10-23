package lk.peliyagoda.marketpro.cloud

import java.util.UUID

/**
 * Read-only identity comparison. A matching business key is NOT proof that two
 * devices share an immutable Cloud UUID. Never write a candidate map to Room.
 */
internal data class UuidAuditRecord(
    val type: String,
    val semanticKey: String,
    val cloudId: String?
)

data class UuidAuditSection(
    val type: String,
    val phoneRecords: Int,
    val cloudRecords: Int,
    val matchingUuid: Int,
    val differentUuid: Int,
    val absentLocalUuid: Int,
    val phoneOnly: Int,
    val cloudOnly: Int,
    val ambiguous: Int
)

data class UuidAuditResult(
    val sections: List<UuidAuditSection>,
    val pendingUploadOperations: Int,
    val archivedRemoteRows: Int
) {
    val equalIds: Int get() = sections.sumOf { it.matchingUuid }
    val differentIds: Int get() = sections.sumOf { it.differentUuid }
    val ambiguousKeys: Int get() = sections.sumOf { it.ambiguous }
    // Even total UUID equality alone cannot authorize a second device writer.
    val isReadOnly: Boolean get() = true
}

/**
 * Unique semantic keys are only candidate links. Additional purchase lots with
 * identical date/item/qty/price are intrinsically ambiguous without persistent
 * origin UUIDs and must not be matched by arbitrary list or timestamp order.
 */
internal object UuidMappingAudit {
    val types = listOf(
        "category", "item", "requisition", "purchase",
        "purchase_addition", "daily_cash_received"
    )
    fun compare(
        phone: List<UuidAuditRecord>,
        cloud: List<UuidAuditRecord>,
        pendingOperations: Int,
        archivedRemoteRows: Int = 0
    ): UuidAuditResult {
        require(pendingOperations >= 0 && archivedRemoteRows >= 0)
        require(phone.all { it.type in types } && cloud.all { it.type in types })
        // Validate UUIDs without exposing either side in a UI, exception or log.
        fun valid(value: String?): Boolean =
            value != null && runCatching { UUID.fromString(value).toString() == value.lowercase() }
                .getOrDefault(false)
        val result = types.map { type ->
            val locals = phone.filter { it.type == type }
            val remotes = cloud.filter { it.type == type }
            val a = locals.groupBy { it.semanticKey }
            val b = remotes.groupBy { it.semanticKey }
            var same = 0
            var different = 0
            var noUuid = 0
            var onlyPhone = 0
            var onlyCloud = 0
            var ambiguous = 0
            for (key in (a.keys + b.keys).toSet()) {
                val l = a[key].orEmpty()
                val r = b[key].orEmpty()
                if (l.size > 1 || r.size > 1) {
                    ambiguous += maxOf(l.size, r.size)
                    continue
                }
                if (l.isEmpty()) { onlyCloud += r.size; continue }
                if (r.isEmpty()) { onlyPhone += l.size; continue }
                if (!valid(l[0].cloudId)) { noUuid++; continue }
                if (!valid(r[0].cloudId)) { different++; continue }
                if (l[0].cloudId.equals(r[0].cloudId, ignoreCase = true)) same++
                else different++
            }
            UuidAuditSection(type, locals.size, remotes.size, same,
                different, noUuid, onlyPhone, onlyCloud, ambiguous)
        }
        return UuidAuditResult(result, pendingOperations, archivedRemoteRows)
    }
}
