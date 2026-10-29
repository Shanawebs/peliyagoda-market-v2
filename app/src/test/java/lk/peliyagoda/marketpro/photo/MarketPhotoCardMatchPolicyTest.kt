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

    @Test fun iconsMatchPhotosCapturedLocallyOrRemotely() {
        assertEquals(false, MarketPhotoCardMatchPolicy.hasAnyPhoto(0, 0))
        assertEquals(true, MarketPhotoCardMatchPolicy.hasAnyPhoto(1, 0))
        assertEquals(true, MarketPhotoCardMatchPolicy.hasAnyPhoto(0, 1))
    }

    @Test fun cloudPhotoTimestampSupportsSameSortingOnTwoPhones() {
        val date = "2026-10-29"
        val remotePhoto = MarketPhotoGatewayClient.CloudPhoto(
            "p1", "a".repeat(64), 3502,
            "2026-10-29T10:23:08.618Z", "remote-a", "අඟුණ කොල"
        )
        val timestamp = requireNotNull(MarketPhotoCardMatchPolicy.latestCapturedAtMs(listOf(remotePhoto)))
        assertEquals(java.time.Instant.parse(remotePhoto.capturedAt).toEpochMilli(), timestamp)
        val row = lk.peliyagoda.marketpro.util.consolidate(
            items = listOf(lk.peliyagoda.marketpro.data.ItemEntity(4L, "අඟුණ කොල", "Vegetables")),
            requisitions = listOf(lk.peliyagoda.marketpro.data.RequisitionEntity(date,"Main",4L,10000)),
            purchases = listOf(lk.peliyagoda.marketpro.data.PurchaseEntity(date,4L,10000,20000))
        ).single()
        assertEquals(
            true, lk.peliyagoda.marketpro.util.isArchivedForMarket(row,timestamp,timestamp+60000L)
        )
        assertEquals(
            false,lk.peliyagoda.marketpro.util.isArchivedForMarket(row,timestamp,timestamp+59999L)
        )
    }

    @Test fun invalidCloudTimestampsNeverMarkCompletion() {
        val invalid = MarketPhotoGatewayClient.CloudPhoto(
            "p0","a".repeat(64),100,"malformed", "remote-x","Carrot"
        )
        assertEquals(null, MarketPhotoCardMatchPolicy.latestCapturedAtMs(listOf(invalid)))
    }
}
