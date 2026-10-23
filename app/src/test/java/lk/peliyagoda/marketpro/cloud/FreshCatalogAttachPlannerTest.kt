package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshCatalogAttachPlannerTest {
    private val cat = "11111111-1111-4111-8111-111111111111"
    private val item = "22222222-2222-4222-8222-222222222222"
    private val archived = "33333333-3333-4333-8333-333333333333"
    private val localCat = listOf(AttachLocalCategory("vegetables","Vegetables"))
    private val cloudCat = listOf(AttachCloudCategory(cat,"Vegetables"))
    private val localItem = listOf(AttachLocalItem("21","අමු මිරිස්","Vegetables",true))
    private val cloudItem = listOf(AttachCloudItem(item,"අමු මිරිස්",cat,true))

    @Test fun freshLocalActiveCatalogCanAttachToHistoricalCloudWithArchivedExtras() {
        val p = FreshCatalogAttachPlanner.build(
            localCat,localItem,cloudCat,
            cloudItem + AttachCloudItem(archived,"අක්‍රිය අයිතම",cat,false)
        )
        assertEquals(2,p.identities.size)
        assertEquals(1,p.protectedArchivedRemoteItems)
        assertEquals(1,p.matchedItems)
        assertEquals(item,p.identities.single { it.entityType=="item" }.cloudId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectExtraActiveRemoteItem() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            cloudItem + AttachCloudItem(archived,"Extra active",cat,true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectRenamedActiveItem() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            listOf(AttachCloudItem(item,"Other name",cat,true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectDuplicateNormalizedRemoteName() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            cloudItem + AttachCloudItem(archived," අමු  මිරිස් ",cat,false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInactiveMismatch() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            listOf(AttachCloudItem(item,"අමු මිරිස්",cat,false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectDeletedRemoteItem() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            listOf(AttachCloudItem(item,"අමු මිරිස්",cat,true,deleted=true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnrecognizedCategoryId() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            listOf(AttachCloudItem(item,"අමු මිරිස්",archived,true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectSameUuidForDifferentEntityTypes() {
        FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,
            listOf(AttachCloudItem(cat,"අමු මිරිස්",cat,true)))
    }

    @Test fun plannerHasNoServerWriteEntryPoints() {
        assertTrue(FreshCatalogAttachPlanner.build(localCat,localItem,cloudCat,cloudItem).matchedItems == 1)
    }
}
