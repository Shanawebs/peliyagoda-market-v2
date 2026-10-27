package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySingletonIdentityPolicyTest {
    private val oldId = "11111111-1111-4111-8111-111111111111"
    private val remoteId = "22222222-2222-4222-8222-222222222222"

    private fun input(type: String = "daily_cash_received") =
        DailySingletonIdentityEvidence(
            type = type,
            localRowExists = true,
            hasPendingLocalEdit = true,
            remoteTombstone = false,
            localKey = "2026-10-27",
            mappedCloudId = oldId,
            remoteCloudId = remoteId,
            reverseLocalKey = null,
            provisionalOriginMatches = true,
            oldMappedPullRevision = null,
            oldMappedAckRevision = null
        )

    @Test fun cashMayAdoptCanonicalSameDayUuidOnlyWhileOldUuidIsLocalOnly() {
        assertTrue(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(input()))
    }

    @Test fun otherExpensesUsesTheSameStrictSingletonRule() {
        assertTrue(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input("daily_other_expenses")
        ))
    }

    @Test fun purchaseIdentityCanNeverBeReboundByThisPolicy() {
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input("purchase")
        ))
    }

    @Test fun acknowledgedOrPreviouslyPulledUuidCanNeverBeRebound() {
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input().copy(oldMappedAckRevision = 1)
        ))
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input().copy(oldMappedPullRevision = 1)
        ))
    }

    @Test fun pendingEditAndProvisionalOriginAreBothMandatory() {
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input().copy(hasPendingLocalEdit = false)
        ))
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input().copy(provisionalOriginMatches = false)
        ))
    }

    @Test fun tombstoneOrRemoteIdentityCollisionNeverRebinds() {
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input().copy(remoteTombstone = true)
        ))
        assertFalse(DailySingletonIdentityPolicy.canAdoptCanonicalRemote(
            input().copy(reverseLocalKey = "2026-10-26")
        ))
    }
}
