package lk.peliyagoda.marketpro.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketPhotoCardMatchPolicyTest {
    private fun photo(id: String, remoteId: String, name: String) =
        MarketPhotoGatewayClient.CloudPhoto(
            id, "a".repeat(64), 100, "2026-10-29T08:00:00Z", remoteId, name
        )

    @Test fun canonicalIdWinsOverSameNameDecoys() {
        val found = MarketPhotoCardMatchPolicy.match(
            listOf(photo("p1", "remote-a", "Carrot"), photo("p2", "remote-b", "Carrot")),
            "remote-b", "Carrot")
        assertEquals(PhotoCardMatchStatus.CANONICAL_ID, found.status)
        assertEquals(listOf("p2"), found.photos.map { it.id })
    }

    @Test fun crossPhoneWithoutUuidCanViewUniqueExactName() {
        val found = MarketPhotoCardMatchPolicy.match(
            listOf(photo("p1", "remote-a", "අල")), null, "අල")
        assertEquals(PhotoCardMatchStatus.UNIQUE_NAME, found.status)
        assertEquals(1, found.photos.size)
    }

    @Test fun ambiguousCloudNamesNeverCrossAssociate() {
        val found = MarketPhotoCardMatchPolicy.match(
            listOf(photo("p1", "remote-a", "Carrot"), photo("p2", "remote-b", "Carrot")),
            null, "Carrot")
        assertEquals(PhotoCardMatchStatus.AMBIGUOUS, found.status)
        assertTrue(found.photos.isEmpty())
    }

    @Test fun wrongItemNameNeverShowsAnotherItem() {
        val found = MarketPhotoCardMatchPolicy.match(
            listOf(photo("p1", "remote-a", "Carrot")), null, "Onion")
        assertEquals(PhotoCardMatchStatus.NONE, found.status)
        assertTrue(found.photos.isEmpty())
    }
}
