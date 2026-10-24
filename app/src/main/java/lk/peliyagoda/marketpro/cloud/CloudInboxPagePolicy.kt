package lk.peliyagoda.marketpro.cloud

import java.time.OffsetDateTime
import java.time.LocalDate
import java.util.UUID

/** Values trusted ONLY after server JWT + workspace/device checks. */
data class InboxWatermark(val updatedAt: String, val cloudId: String)

data class InboxCheckpoint(
    val type: String,
    val cursor: InboxWatermark?
)

/**
 * Checks stable keyset page order and checkpoint continuity before any Room
 * transaction. Never trust device time to authorize staff edits.
 */
object CloudInboxPagePolicy {
    val allowedTypes: Set<String> = setOf(
        "category", "item", "requisition", "purchase",
        "purchase_addition", "daily_cash_received"
    )

    fun uuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    private fun epoch(value: String): java.time.Instant =
        OffsetDateTime.parse(value).toInstant()

    fun validate(
        type: String,
        workspace: String,
        expectedCursor: InboxWatermark?,
        rows: List<InboxWatermark>,
        hasMore: Boolean,
        returnedCursor: InboxWatermark?,
        serverTodayColombo: String
    ) {
        require(type in allowedTypes) { "Unrecognized Cloud entity type" }
        require(uuid(workspace)) { "Invalid Cloud workspace" }
        require(rows.size <= 100) { "Cloud page exceeded size limit" }
        require(rows.isNotEmpty() || !hasMore) { "Empty page cannot report more rows" }
        require(LocalDate.parse(serverTodayColombo).toString() == serverTodayColombo)

        expectedCursor?.let {
            require(uuid(it.cloudId))
            epoch(it.updatedAt)
        }
        returnedCursor?.let {
            require(uuid(it.cloudId))
            epoch(it.updatedAt)
        }
        var previous = expectedCursor
        for (current in rows) {
            require(uuid(current.cloudId)) { "Invalid Cloud record identity" }
            val t = epoch(current.updatedAt)
            previous?.let { past ->
                val last = epoch(past.updatedAt)
                require(t > last || (t == last && current.cloudId > past.cloudId)) {
                    "Cloud rows are unordered, duplicated or behind checkpoint"
                }
            }
            previous = current
        }
        require(returnedCursor == (rows.lastOrNull() ?: expectedCursor)) {
            "Cloud checkpoint does not match last verified row"
        }
    }

    fun shouldReplaceShadowSnapshot(storedRevision: Long?, receivedRevision: Long): Boolean {
        require(receivedRevision > 0) { "Invalid server record revision" }
        require(storedRevision == null || storedRevision > 0)
        return storedRevision == null || receivedRevision > storedRevision
    }
}
