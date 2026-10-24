package lk.peliyagoda.marketpro.cloud

import java.util.UUID

/**
 * Contract for a future Room transaction applying an authenticated server
 * snapshot/change stream. Does not perform network activity or mutate Room.
 * Server-authoritative revisions must be fetched using workspace RLS; Android
 * Realtime is only a refresh signal, never the source of authoritative values.
 */
data class TeamCloudDownloadCandidate(
    val cloudWorkspaceVerified: Boolean,
    val cloudUuid: String,
    val cloudRevision: Long,
    val localUuid: String?,
    val localRevision: Long?,
    val localHasPendingOutbox: Boolean,
    val catalogUuidMappingVerified: Boolean,
    val remoteDeleted: Boolean
)
enum class TeamCloudDownloadDecision {
    APPLY_SERVER,
    NO_CHANGE,
    REQUIRE_CONFLICT_RESOLUTION,
    REJECT_UNSAFE_SNAPSHOT
}

object TeamCloudDownloadPolicy {
    private fun validUuid(input: String?): Boolean = input != null && runCatching {
        UUID.fromString(input).toString().equals(input,true)
    }.getOrDefault(false)

    fun decide(candidate: TeamCloudDownloadCandidate): TeamCloudDownloadDecision {
        if (!candidate.cloudWorkspaceVerified ||
            !candidate.catalogUuidMappingVerified ||
            !validUuid(candidate.cloudUuid) || candidate.cloudRevision < 1L ||
            (candidate.localUuid != null &&
             (!validUuid(candidate.localUuid) ||
              !candidate.localUuid.equals(candidate.cloudUuid,true))) ||
            (candidate.localRevision != null && candidate.localRevision < 1L)) {
            return TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT
        }
        if (candidate.localHasPendingOutbox) {
            // Never clear a legitimate unsent local edit when downloading.
            return TeamCloudDownloadDecision.REQUIRE_CONFLICT_RESOLUTION
        }
        if (candidate.localRevision == null) {
            // New canonical Cloud UUID may be inserted into local Room.
            return TeamCloudDownloadDecision.APPLY_SERVER
        }
        if (candidate.cloudRevision < candidate.localRevision) {
            // Never roll local app backwards with an outdated HTTP/cache row.
            return TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT
        }
        if (candidate.cloudRevision == candidate.localRevision) {
            return TeamCloudDownloadDecision.NO_CHANGE
        }
        return TeamCloudDownloadDecision.APPLY_SERVER
    }
}
