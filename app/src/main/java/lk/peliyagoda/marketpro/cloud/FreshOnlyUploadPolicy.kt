package lk.peliyagoda.marketpro.cloud

import java.util.Locale
import java.util.UUID

/**
 * V3 fresh-only transport admission policy.
 *
 * This is intentionally pure Kotlin: it DOES NOT enable a device, call an API,
 * delete legacy demo rows, modify Room, or schedule a worker. A future adapter
 * must supply VERIFIED provenance and current server state to this gate.
 *
 * Existing-workspace attach != initial import: all legacy cloud records are
 * protected; V3 may upload only explicitly created NEW business records.
 */
internal data class FreshSyncCandidate(
    val entityType: String,
    val cloudEntityId: String,
    val action: String,
    /** Stable semantic key: type|date|item-UUID|kitchen (where applicable). */
    val businessKey: String,
    /** Actual Cloud catalog-item UUID, not a device's numeric Room item ID. */
    val catalogItemCloudId: String?,
    /** Persisted by a future atomic Room journal as part of a NEW local insert. */
    val createdAfterFreshEnrollment: Boolean,
)

internal data class FreshSyncServerSnapshot(
    val ownerAuthenticated: Boolean,
    val singleWriterHandoverVerified: Boolean,
    val serverSnapshotVerified: Boolean,
    /** Original cloud records; never modify or duplicate these UUIDs. */
    val protectedLegacyEntityIds: Set<String>,
    /** Existing business keys from a protected, complete Cloud snapshot. */
    val protectedLegacyBusinessKeys: Set<String>,
    /** Canonical business key -> current server UUID, from a fresh authenticated read. */
    val serverExistingIdByBusinessKey: Map<String, String>,
    /** Catalog UUIDs already validated by read-only canonical-name matching. */
    val verifiedCatalogItemIds: Set<String>,
    /** Explicitly created new V3 row UUIDs, persisted durably on the device. */
    val freshOwnedEntityIds: Set<String>
)

internal data class FreshSyncDecision(
    val permitted: Boolean,
    val reason: String
)

/**
 * Client-side defense in depth; server RPC must ALSO reject historical writes,
 * enforce single-writer authorization and handle race conditions atomically.
 */
internal object FreshOnlyUploadPolicy {
    private val allowed = setOf(
        "purchase", "requisition", "purchase_addition", "daily_cash_received"
    )
    private fun normalizedUuid(value: String): String? =
        runCatching {
            UUID.fromString(value).toString().takeIf {
                it.equals(value, ignoreCase = true)
            }
        }.getOrNull()

    private fun normalSet(values: Set<String>): Set<String> =
        values.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }

    fun evaluate(
        change: FreshSyncCandidate,
        state: FreshSyncServerSnapshot
    ): FreshSyncDecision {
        fun reject(reason: String) = FreshSyncDecision(false, reason)
        if (!state.ownerAuthenticated || !state.singleWriterHandoverVerified ||
            !state.serverSnapshotVerified
        ) return reject("Authenticated owner, current Cloud snapshot and single writer required")

        if (change.entityType !in allowed || change.action != "upsert")
            return reject("Fresh-only mode never writes historic catalog rows or deletions")
        val entityId = normalizedUuid(change.cloudEntityId)
            ?: return reject("Invalid cloud record UUID")
        if (!change.createdAfterFreshEnrollment ||
            entityId !in normalSet(state.freshOwnedEntityIds)
        ) return reject("Record lacks durable NEW V3 origin and enrollment evidence")

        if (entityId in normalSet(state.protectedLegacyEntityIds) ||
            change.businessKey in state.protectedLegacyBusinessKeys
        ) return reject("Historic/demo Cloud data is immutable during fresh-only test")

        if (change.businessKey.isBlank() ||
            !change.businessKey.startsWith(change.entityType + "|")
        ) return reject("Missing canonical business identity")

        val itemId = change.catalogItemCloudId
        if (change.entityType == "daily_cash_received") {
            if (itemId != null) return reject("Cash record cannot reference an item")
        } else if (itemId == null ||
            normalizedUuid(itemId) !in normalSet(state.verifiedCatalogItemIds)
        ) return reject("Cloud catalog item has not been verified")

        val occupant = state.serverExistingIdByBusinessKey[change.businessKey]
        if (occupant != null && normalizedUuid(occupant) != entityId) {
            return reject("Another Cloud row already owns this date/item/kitchen")
        }
        return FreshSyncDecision(true, "Fresh V3 business change eligible for server recheck")
    }
}
