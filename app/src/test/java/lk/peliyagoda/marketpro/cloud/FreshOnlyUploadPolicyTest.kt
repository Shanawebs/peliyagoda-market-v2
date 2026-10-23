package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshOnlyUploadPolicyTest {
    private val existingCloudId = "11111111-1111-4111-8111-111111111111"
    private val newCloudId = "22222222-2222-4222-8222-222222222222"
    private val catalogId = "33333333-3333-4333-8333-333333333333"
    private val todayKey = "purchase|2026-10-23|$catalogId"
    private val legacyKey = "purchase|2026-10-23|$catalogId"

    private fun candidate(
        entityType: String = "purchase",
        id: String = newCloudId,
        key: String = todayKey
    ) = FreshSyncCandidate(
        entityType = entityType, cloudEntityId = id, action = "upsert",
        businessKey = key, catalogItemCloudId = catalogId,
        createdAfterFreshEnrollment = true
    )

    private fun safeServer() = FreshSyncServerSnapshot(
        ownerAuthenticated = true,
        singleWriterHandoverVerified = true,
        serverSnapshotVerified = true,
        protectedLegacyEntityIds = setOf(existingCloudId),
        protectedLegacyBusinessKeys = emptySet(),
        serverExistingIdByBusinessKey = emptyMap(),
        verifiedCatalogItemIds = setOf(catalogId),
        freshOwnedEntityIds = setOf(newCloudId)
    )

    @Test fun freshPurchasePassesOnlyWhenAllEvidenceIsPresent() {
        assertTrue(FreshOnlyUploadPolicy.evaluate(candidate(), safeServer()).permitted)
    }

    @Test fun previouslyUploadedNewIdMayBeRetransmittedIdempotently() {
        val server = safeServer().copy(
            serverExistingIdByBusinessKey = mapOf(todayKey to newCloudId)
        )
        assertTrue(FreshOnlyUploadPolicy.evaluate(candidate(), server).permitted)
    }

    @Test fun legacyUuidOrHistoricalBusinessKeyNeverEligible() {
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(id = existingCloudId),
            safeServer().copy(freshOwnedEntityIds = setOf(existingCloudId))
        ).permitted)
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(),
            safeServer().copy(protectedLegacyBusinessKeys = setOf(legacyKey))
        ).permitted)
    }

    @Test fun unapprovedDeviceAndUnverifiedServerAreBlocked() {
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(), safeServer().copy(singleWriterHandoverVerified = false)
        ).permitted)
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(), safeServer().copy(serverSnapshotVerified = false)
        ).permitted)
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(), safeServer().copy(ownerAuthenticated = false)
        ).permitted)
    }

    @Test fun catalogMustBeMappedToAnExistingCloudUuid() {
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(), safeServer().copy(verifiedCatalogItemIds = emptySet())
        ).permitted)
    }

    @Test fun unexpectedCloudCollisionBlocksEvenNewLocalRow() {
        val server = safeServer().copy(
            serverExistingIdByBusinessKey = mapOf(todayKey to existingCloudId)
        )
        assertFalse(FreshOnlyUploadPolicy.evaluate(candidate(), server).permitted)
    }

    @Test fun restoredQueueAndArbitraryCloudIdAreNeverTrusted() {
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate().copy(createdAfterFreshEnrollment = false),
            safeServer()
        ).permitted)
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate().copy(cloudEntityId = "44444444-4444-4444-8444-444444444444"),
            safeServer()
        ).permitted)
    }

    @Test fun deleteCatalogAndOtherUnsupportedOperationsFailClosed() {
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate().copy(action = "delete"),safeServer()
        ).permitted)
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            candidate(entityType = "item",key = "item|test"),safeServer()
        ).permitted)
    }

    @Test fun cashHasNoCatalogItemAndRequiresFreshOrigin() {
        val key = "daily_cash_received|2026-10-23"
        val ok = candidate(entityType = "daily_cash_received",key = key)
            .copy(catalogItemCloudId = null)
        assertTrue(FreshOnlyUploadPolicy.evaluate(ok,safeServer()).permitted)
        assertFalse(FreshOnlyUploadPolicy.evaluate(
            ok.copy(catalogItemCloudId = catalogId),safeServer()
        ).permitted)
    }
}
