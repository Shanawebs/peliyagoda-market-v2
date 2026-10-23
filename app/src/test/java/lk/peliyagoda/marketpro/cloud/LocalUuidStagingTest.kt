package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUuidStagingTest {
    private val old1 = "11111111-1111-4111-8111-111111111111"
    private val old2 = "22222222-2222-4222-8222-222222222222"
    private val new1 = "33333333-3333-4333-8333-333333333333"
    private val new2 = "44444444-4444-4444-8444-444444444444"
    private val op1 = "55555555-5555-4555-8555-555555555555"
    private val op2 = "66666666-6666-4666-8666-666666666666"

    private val previous = listOf(
        CloudIdentityEntity("category", "vegetables", old1),
        CloudIdentityEntity("item", "1", old2)
    )
    private val next = listOf(
        CloudIdentityEntity("category", "vegetables", new1),
        CloudIdentityEntity("item", "1", new2)
    )
    private val pending = listOf(
        CloudOutboxEntity("category", "vegetables", op1, "upsert", 1234L),
        CloudOutboxEntity("item", "1", op2, "upsert", 1234L)
    )

    private fun report(
        candidates: List<CloudIdentityEntity> = next,
        readerAuthorized: Boolean = true
    ) = CloudPreflightReport(
        remote = mapOf("categories" to 1, "items" to 1),
        local = mapOf("categories" to 1, "items" to 1),
        deviceRegisteredAsWriter = !readerAuthorized,
        catalogAudit = CatalogAudit(1,1,0,1, emptyList(),emptyList(),emptyList()),
        financialAudit = FinancialAudit(
            sections = listOf(FinancialAuditSection("requisitions",0,0,0,0,0)),
            unmappedPhoneItems = 0, unmappedCloudItems = 0
        ),
        uuidAudit = UuidAuditResult(
            sections = listOf(UuidAuditSection("item",1,1,0,1,0,0,0,0)),
            pendingUploadOperations = 2, archivedRemoteRows = 0
        ),
        migrationDryRun = MigrationDryRunResult(
            phoneRecords = 2, cloudRecords = 2, uniqueCandidates = 2,
            unsafeOrUnmapped = 0, duplicateSignatures = 0,
            pendingLocalOperations = 2, protectedCloudArchivedItems = 0,
            contentMatches = true, oneToOneCandidateCoverage = true,
            cutoverBlocked = true, blockers = listOf("Old writer remains active")
        ),
        localRecoveryFingerprint = "sha256-content",
        writeBlockedReason = "No Upload",
        migrationCandidates = candidates
    )

    @Test fun originalJsonRestorationWithTwoCandidatesIsLocallyStageable() {
        IdentityStagePolicy.validate(report(), previous, pending, false, false)
        assertEquals(IdentityStagePolicy.SNAPSHOT_STATE, "local_uuid_stage_snapshot")
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCloudTargetUuidMustFailClosed() {
        IdentityStagePolicy.validate(
            report(candidates = listOf(
                CloudIdentityEntity("category", "vegetables", new1),
                CloudIdentityEntity("item", "1", new1)
            )), previous, pending, false, false
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pendingUnrecordedNewEditBlocksQuarantine() {
        val newEdit = pending.mapIndexed { index, row ->
            if (index == 1) row.copy(action = "delete") else row
        }
        IdentityStagePolicy.validate(report(), previous, newEdit, false, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pendingQueueMustCoverEveryRestoredRecordExactly() {
        IdentityStagePolicy.validate(report(), previous, pending.take(1), false, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongLocalRecordKeyBlocksMapping() {
        val missing = listOf(previous[0],previous[1].copy(entityKey = "9"))
        IdentityStagePolicy.validate(report(), missing, pending, false, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun originalWriterCannotBeReboundByThisPreview() {
        IdentityStagePolicy.validate(report(readerAuthorized = false), previous, pending, false, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noStageWhileLocalCloudSyncIsEnabled() {
        IdentityStagePolicy.validate(report(), previous, pending, true, false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun noStageWithDeviceEnrollment() {
        IdentityStagePolicy.validate(report(), previous, pending, false, true)
    }

    @Test fun resultNeverClaimsServerEnrollment() {
        val receipt = LocalIdentityStageResult(2,2,encryptedBackupVerified=true)
        assertTrue(receipt.cloudUploadLocked)
        assertTrue(LocalIdentityRollbackResult(2,2).cloudUploadLocked)
    }
}
