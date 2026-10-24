package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class TeamCloudDownloadPolicyTest {
    private val id = "d1111111-1111-4111-8111-111111111111"
    private fun candidate(
        remote: Long=1, local: Long?=null, pending: Boolean=false,
        mapping: Boolean=true
    ) = TeamCloudDownloadCandidate(
        cloudWorkspaceVerified=true,
        cloudUuid=id,
        cloudRevision=remote,
        localUuid=if (local == null) null else id,
        localRevision=local,
        localHasPendingOutbox=pending,
        catalogUuidMappingVerified=mapping,
        remoteDeleted=false
    )
    @Test fun serverRowCanBeAddedToEmptyOwnerPhone() {
        assertEquals(TeamCloudDownloadDecision.APPLY_SERVER,
            TeamCloudDownloadPolicy.decide(candidate()))
    }
    @Test fun oldRevisionNotRedownloadedOrApplied() {
        assertEquals(TeamCloudDownloadDecision.NO_CHANGE,
            TeamCloudDownloadPolicy.decide(candidate(remote=2,local=2)))
        assertEquals(TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT,
            TeamCloudDownloadPolicy.decide(candidate(remote=1,local=2)))
    }
    @Test fun ownerCorrectionPropagatesToStaffWhenNoLocalPendingEdit() {
        assertEquals(TeamCloudDownloadDecision.APPLY_SERVER,
            TeamCloudDownloadPolicy.decide(candidate(remote=7,local=6)))
    }
    @Test fun networkReconnectNeverErasesPendingOfflineEdit() {
        assertEquals(TeamCloudDownloadDecision.REQUIRE_CONFLICT_RESOLUTION,
            TeamCloudDownloadPolicy.decide(candidate(remote=10,local=9,pending=true)))
    }
    @Test fun wrongWorkspaceUnknownCatalogOrRekeyCannotMutateRoom() {
        assertEquals(TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT,
            TeamCloudDownloadPolicy.decide(candidate().copy(cloudWorkspaceVerified=false)))
        assertEquals(TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT,
            TeamCloudDownloadPolicy.decide(candidate(mapping=false)))
        assertEquals(TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT,
            TeamCloudDownloadPolicy.decide(candidate(remote=2,local=1).copy(
                localUuid="33333333-3333-4333-8333-333333333333"
            )))
        assertEquals(TeamCloudDownloadDecision.REJECT_UNSAFE_SNAPSHOT,
            TeamCloudDownloadPolicy.decide(candidate(remote=0)))
    }
    @Test fun remoteTombstoneRequiresSameConflictChecks() {
        assertEquals(TeamCloudDownloadDecision.REQUIRE_CONFLICT_RESOLUTION,
            TeamCloudDownloadPolicy.decide(candidate(remote=3,local=2,pending=true)
                .copy(remoteDeleted=true)))
    }
}
