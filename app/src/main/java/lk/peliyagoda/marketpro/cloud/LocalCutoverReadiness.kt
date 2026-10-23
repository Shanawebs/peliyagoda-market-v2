package lk.peliyagoda.marketpro.cloud

/**
 * V3.35 SOURCE-ONLY readiness gate. This never enrolls a device, schedules a
 * worker, calls Supabase, or grants permission to upload.
 *
 * Local-data parity is not server writer authorization. The old V2.31 phone
 * must drain its own queue before an separately approved atomic handover.
 */
internal data class LocalCutoverSignals(
    val staged: Boolean,
    val phoneCounts: Map<String, Int>,
    val cloudCounts: Map<String, Int>,
    val matchingUuids: Int,
    val differentUuids: Int,
    val ambiguousUuids: Int,
    val phoneUuidCount: Int,
    val cloudUuidCount: Int,
    val unsentOperations: Int,
    val catalogMatches: Boolean,
    val financialMatches: Boolean,
    val previewAlreadyWriter: Boolean
)

internal data class LocalCutoverAssessment(
    val localDataReady: Boolean,
    val blockers: List<String>
) {
    /** Never interpret a local read-only audit as server authorization. */
    val cloudUploadLocked: Boolean get() = true
    val serverHandoverRequired: Boolean get() = true
}

internal object LocalCutoverReadinessPolicy {
    fun evaluate(input: LocalCutoverSignals): LocalCutoverAssessment {
        val blockers = buildList {
            if (!input.staged) add("Encrypted, rollback-capable local UUID stage not confirmed.")
            if (input.phoneCounts.isEmpty() ||
                input.phoneCounts.keys != input.cloudCounts.keys ||
                input.phoneCounts != input.cloudCounts ||
                input.phoneCounts.values.any { it < 0 }
            ) add("Phone and Cloud record counts are not identical.")
            if (!input.catalogMatches || !input.financialMatches) {
                add("Catalog or financial fingerprints do not match.")
            }
            if (input.phoneUuidCount <= 0 ||
                input.phoneUuidCount != input.cloudUuidCount ||
                input.matchingUuids != input.phoneUuidCount ||
                input.differentUuids != 0 || input.ambiguousUuids != 0
            ) add("One or more historical Cloud UUID identities are not reconciled.")
            if (input.unsentOperations != 0) {
                add("New phone still has a pending local upload queue.")
            }
            if (input.previewAlreadyWriter) {
                add("Preview device is unexpectedly enrolled as a writer; investigate.")
            }
        }
        return LocalCutoverAssessment(blockers.isEmpty(), blockers)
    }
}
