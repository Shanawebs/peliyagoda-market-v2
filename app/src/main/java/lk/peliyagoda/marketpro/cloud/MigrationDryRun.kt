package lk.peliyagoda.marketpro.cloud

/**
 * Cloud migration READ-ONLY dry run.
 *
 * "Candidate" means unique business-record fingerprint + existing remote UUID
 * was found in the audit, NOT that we may overwrite local UUIDs or clear outbox.
 *
 * Never infer cutover permission from matching counts or candidate identities:
 * the old V2.31 cloud writer may still change records, pending edits may be
 * newer than the restored JSON, and there is no transactionally frozen backup.
 */
data class MigrationDryRunResult(
    val phoneRecords: Int,
    val cloudRecords: Int,
    val uniqueCandidates: Int,
    val unsafeOrUnmapped: Int,
    val duplicateSignatures: Int,
    val pendingLocalOperations: Int,
    val protectedCloudArchivedItems: Int,
    val contentMatches: Boolean,
    val oneToOneCandidateCoverage: Boolean,
    val cutoverBlocked: Boolean,
    val blockers: List<String>
)

/** Pure policy evaluator. No network, Room writes, token, UUID publication or side effects. */
internal object MigrationDryRun {
    fun evaluate(
        identities: UuidAuditResult,
        catalog: CatalogAudit,
        finance: FinancialAudit
    ): MigrationDryRunResult {
        val phoneTotal = identities.sections.sumOf { it.phoneRecords }
        val cloudTotal = identities.sections.sumOf { it.cloudRecords }
        val candidates = identities.sections.sumOf {
            it.matchingUuid + it.differentUuid
        }
        val ambiguous = identities.sections.sumOf { it.ambiguous }
        val failedRecords = identities.sections.sumOf {
            it.absentLocalUuid + it.phoneOnly + it.cloudOnly + it.ambiguous
        }
        val fullyCovered = phoneTotal == cloudTotal &&
            candidates == phoneTotal &&
            failedRecords == 0 &&
            identities.sections.all { it.phoneRecords == it.cloudRecords }

        val matchingContents = catalog.fullyMatched && finance.fullyMatched
        val issues = buildList {
            if (!catalog.fullyMatched) add(
                "Phone and Cloud item/category data differ; do not migrate identities."
            )
            if (!finance.fullyMatched) add(
                "Phone and Cloud financial fingerprints differ; review transactions."
            )
            if (!fullyCovered) add(
                "Some UUID candidates are missing, duplicated, ambiguous or unmatched."
            )
            if (identities.pendingUploadOperations > 0) add(
                "Restored local outbox has ${identities.pendingUploadOperations} " +
                    "unsent operations; their intent must be audited before quarantine."
            )
            if (identities.archivedRemoteRows > 0) add(
                "Cloud has archived financial tombstones; preserve history and revisions."
            )
            // Not proven by a client-only GET audit. These release gates must
            // be verified in a separate, explicitly owner-approved migration.
            add("A transactionally consistent Cloud backup/revision snapshot is not verified.")
            add("Original writer has not been retired and new writer is not authorized.")
            add("There is no approved transactional Room UUID rebind/rollback yet.")
        }
        return MigrationDryRunResult(
            phoneRecords = phoneTotal,
            cloudRecords = cloudTotal,
            uniqueCandidates = candidates,
            unsafeOrUnmapped = failedRecords,
            duplicateSignatures = ambiguous,
            pendingLocalOperations = identities.pendingUploadOperations,
            protectedCloudArchivedItems = catalog.remoteArchived,
            contentMatches = matchingContents,
            oneToOneCandidateCoverage = fullyCovered,
            cutoverBlocked = true,
            blockers = issues
        )
    }
}
