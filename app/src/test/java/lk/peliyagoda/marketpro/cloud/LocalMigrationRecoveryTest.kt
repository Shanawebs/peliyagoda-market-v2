package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMigrationRecoveryTest {
    private fun counts() = linkedMapOf(
        "categories" to 3, "items" to 184, "requisitions" to 105,
        "purchases" to 26, "purchase_additions" to 10,
        "daily_cash_received" to 2
    )

    private fun report(
        fingerprint: String = "valid-digest",
        pending: Int = 330,
        mapped: Int = 330,
        writer: Boolean = false
    ): CloudPreflightReport {
        val catalog = CatalogAudit(
            171, 171, 13, 171, emptyList(), emptyList(), emptyList()
        )
        val types = listOf(
            "requisitions" to 105, "purchases" to 26,
            "purchase_additions" to 10, "daily_cash_received" to 2
        )
        val finance = FinancialAudit(types.map { (name, count) ->
            FinancialAuditSection(name, count, count, count, 0, 0)
        }, 0, 0)
        val mapping = UuidAuditResult(
            listOf(
                UuidAuditSection("category", 3, 3, 0, 3, 0, 0, 0, 0),
                UuidAuditSection("item", 184, 184, 0, 184, 0, 0, 0, 0),
                UuidAuditSection("requisition", 105, 105, 0, 105, 0, 0, 0, 0),
                UuidAuditSection("purchase", 26, 26, 0, 26, 0, 0, 0, 0),
                UuidAuditSection("purchase_addition", 10, 10, 0, 10, 0, 0, 0, 0),
                UuidAuditSection("daily_cash_received", 2, 2, 0, 2, 0, 0, 0, 0)
            ), pending, 0
        )
        return CloudPreflightReport(
            remote = counts(),
            local = counts(),
            deviceRegisteredAsWriter = writer,
            catalogAudit = catalog,
            financialAudit = finance,
            uuidAudit = mapping,
            migrationDryRun = MigrationDryRunResult(
                phoneRecords = 330,
                cloudRecords = 330,
                uniqueCandidates = mapped,
                unsafeOrUnmapped = if (mapped == 330) 0 else 330 - mapped,
                duplicateSignatures = 0,
                pendingLocalOperations = pending,
                protectedCloudArchivedItems = 13,
                contentMatches = true,
                oneToOneCandidateCoverage = mapped == 330,
                cutoverBlocked = true,
                blockers = listOf("Writer still active")
            ),
            localRecoveryFingerprint = fingerprint,
            writeBlockedReason = "Locked"
        )
    }

    @Test fun restored330RecordsCanBeProtectedWithoutWriterEnrollment() {
        LocalRecoveryGate.check(
            report(), counts(), 330, 330, "valid-digest",
            enabled = false, enrolled = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameCountsButEditedPriceBlocksStaleSnapshot() {
        LocalRecoveryGate.check(
            report(), counts(), 330, 330, "changed-financial-row",
            enabled = false, enrolled = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentPendingOperationsBlock() {
        LocalRecoveryGate.check(
            report(), counts(), 330, 329, "valid-digest",
            enabled = false, enrolled = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun neverPrepareIfOldWriterEnrollmentWasEnabled() {
        LocalRecoveryGate.check(
            report(), counts(), 330, 330, "valid-digest",
            enabled = false, enrolled = true
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun neverPrepareFromSecondWriter() {
        LocalRecoveryGate.check(
            report(writer = true), counts(), 330, 330, "valid-digest",
            enabled = false, enrolled = false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousCandidatesCannotBePresentedAsReady() {
        LocalRecoveryGate.check(
            report(mapped = 328), counts(), 330, 330, "valid-digest",
            enabled = false, enrolled = false
        )
    }

    @Test fun recoveryIntegrityDetectsAnyByteChange() {
        val original = "data:330 uuid:330 pending:330".toByteArray()
        assertTrue(LocalRecoveryGate.matchesPlaintext(original, original.copyOf()))
        val modified = original.copyOf()
        modified[8] = (modified[8].toInt() xor 1).toByte()
        assertFalse(LocalRecoveryGate.matchesPlaintext(original, modified))
    }

    @Test fun localDigestChangesWhenPendingOperationIdChanges() {
        val localItem = ItemEntity(5L, "Carrot", "Vegetables", true)
        val identity = CloudIdentityEntity(
            "item", "5", "11111111-1111-4111-8111-111111111111"
        )
        val op = CloudOutboxEntity("item", "5",
            "22222222-2222-4222-8222-222222222222", "upsert", 1234L)
        fun digest(item: ItemEntity, pending: CloudOutboxEntity): String =
            LocalStateFingerprint.calculate(
                categories = listOf(CategoryEntity("Vegetables")),
                items = listOf(item),
                requisitions = emptyList(),
                purchases = emptyList(),
                additions = emptyList(),
                cash = emptyList(),
                identities = listOf(identity),
                outbox = listOf(pending),
                states = emptyList()
            )
        assertEquals(digest(localItem, op), digest(localItem, op))
        assertTrue(digest(localItem, op) != digest(
            localItem.copy(name = "Carrot Premium"), op
        ))
        assertTrue(digest(localItem, op) != digest(
            localItem, op.copy(operationId = "33333333-3333-4333-8333-333333333333")
        ))
    }
}
