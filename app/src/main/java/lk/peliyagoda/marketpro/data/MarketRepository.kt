package lk.peliyagoda.marketpro.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

class MarketRepository(private val db: MarketDatabase) {
    private val dao = db.dao()
    val items: Flow<List<ItemEntity>> = dao.observeItems()
    fun requirements(date: String) = dao.observeRequisitions(date)
    fun purchases(date: String) = dao.observePurchases(date)

    suspend fun seedCatalogOnce() {
        if (dao.itemCount() != 0) return
        val vegetables = listOf(
            "Beans", "Beetroot", "Big Onion", "Brinjal", "Cabbage", "Carrot",
            "Cucumber", "Green Chilli", "Leeks", "Lettuce", "Ladies Fingers",
            "Pumpkin", "Potato", "Red Onion", "Tomato", "Capsicum", "Radish",
            "Sweet Potato", "Garlic", "Ginger", "Lime"
        )
        val fruits = listOf(
            "Apple", "Banana", "Grapes", "Guava", "Mango", "Orange",
            "Papaya", "Passion Fruit", "Pineapple", "Watermelon"
        )
        dao.insertItems(vegetables.map { ItemEntity(name = it, category = MarketConstants.VEGETABLES) }
                + fruits.map { ItemEntity(name = it, category = MarketConstants.FRUITS) })
    }

    suspend fun addItem(name: String, category: String): Boolean {
        val cleaned = name.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank() || cleaned.length > 80 || category !in MarketConstants.categories) return false
        if (dao.allItems().any { it.name.equals(cleaned, ignoreCase = true) }) return false
        return dao.insertItem(ItemEntity(name = cleaned, category = category)) > 0
    }

    suspend fun addRequirements(date: String, kitchen: String, ids: Set<Long>) {
        require(kitchen in MarketConstants.kitchens)
        require(LocalDate.parse(date) <= LocalDate.now().plusYears(10))
        val valid = dao.allItems().map { it.id }.toSet()
        db.withTransaction {
            for (id in ids) {
                if (id in valid) dao.saveRequisition(RequisitionEntity(date, kitchen, id, 0))
            }
        }
    }

    suspend fun saveRequirement(date: String, kitchen: String, id: Long, grams: Long) {
        require(grams >= 0 && grams <= 1_000_000_000)
        require(kitchen in MarketConstants.kitchens)
        dao.saveRequisition(RequisitionEntity(date, kitchen, id, grams))
    }

    suspend fun deleteRequirement(date: String, kitchen: String, id: Long) {
        dao.deleteRequisition(date, kitchen, id)
    }

    suspend fun savePurchase(date: String, id: Long, grams: Long, centsPerKg: Long) {
        require(grams in 0..1_000_000_000 && centsPerKg in 0..1_000_000_000)
        dao.savePurchase(PurchaseEntity(date, id, grams, centsPerKg))
    }

    suspend fun exportBackup(): String {
        val root = JSONObject()
        root.put("format", "peliyagoda-market-pro")
        root.put("schemaVersion", 1)
        root.put("createdAt", OffsetDateTime.now().toString())
        root.put("items", JSONArray().apply {
            dao.allItems().forEach { put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("category", it.category)
            }) }
        })
        root.put("requisitions", JSONArray().apply {
            dao.allRequisitions().forEach { put(JSONObject().apply {
                put("date", it.businessDate); put("kitchen", it.kitchen)
                put("itemId", it.itemId); put("grams", it.quantityGrams)
            }) }
        })
        root.put("purchases", JSONArray().apply {
            dao.allPurchases().forEach { put(JSONObject().apply {
                put("date", it.businessDate); put("itemId", it.itemId)
                put("grams", it.quantityGrams); put("priceCentsPerKg", it.priceCentsPerKg)
            }) }
        })
        return root.toString(2)
    }

    /** Validate *everything* before replacing records, then change the database in one transaction. */
    suspend fun restoreBackup(json: String) {
        val root = JSONObject(json)
        require(root.optString("format") == "peliyagoda-market-pro") { "Not a Market Pro backup" }
        require(root.optInt("schemaVersion") == 1) { "Unsupported backup version" }
        val itemArray = root.getJSONArray("items")
        val reqArray = root.getJSONArray("requisitions")
        val purchaseArray = root.getJSONArray("purchases")
        require(itemArray.length() <= 100_000 && reqArray.length() <= 500_000 &&
                purchaseArray.length() <= 500_000) { "Backup too large" }
        val restoredItems = ArrayList<ItemEntity>(itemArray.length())
        for (i in 0 until itemArray.length()) {
            val obj = itemArray.getJSONObject(i)
            val item = ItemEntity(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                category = obj.getString("category")
            )
            require(item.id > 0 && item.name.isNotBlank() && item.name.length <= 80)
            require(item.category in MarketConstants.categories)
            restoredItems.add(item)
        }
        require(restoredItems.map { it.id }.distinct().size == restoredItems.size)
        require(restoredItems.map { it.name.lowercase() }.distinct().size == restoredItems.size)
        val idSet = restoredItems.map { it.id }.toSet()
        fun readDate(obj: JSONObject) = obj.getString("date").also { LocalDate.parse(it) }
        val restoredRequirements = ArrayList<RequisitionEntity>(reqArray.length())
        for (i in 0 until reqArray.length()) {
            val obj = reqArray.getJSONObject(i)
            val entry = RequisitionEntity(
                businessDate = readDate(obj),
                kitchen = obj.getString("kitchen"),
                itemId = obj.getLong("itemId"),
                quantityGrams = obj.getLong("grams")
            )
            require(entry.itemId in idSet && entry.kitchen in MarketConstants.kitchens)
            require(entry.quantityGrams in 0..1_000_000_000)
            restoredRequirements.add(entry)
        }
        require(restoredRequirements.map { Triple(it.businessDate, it.kitchen, it.itemId) }
            .distinct().size == restoredRequirements.size)
        val restoredPurchases = ArrayList<PurchaseEntity>(purchaseArray.length())
        for (i in 0 until purchaseArray.length()) {
            val obj = purchaseArray.getJSONObject(i)
            val entry = PurchaseEntity(
                businessDate = readDate(obj),
                itemId = obj.getLong("itemId"),
                quantityGrams = obj.getLong("grams"),
                priceCentsPerKg = obj.getLong("priceCentsPerKg")
            )
            require(entry.itemId in idSet && entry.quantityGrams in 0..1_000_000_000)
            require(entry.priceCentsPerKg in 0..1_000_000_000)
            restoredPurchases.add(entry)
        }
        require(restoredPurchases.map { it.businessDate to it.itemId }
            .distinct().size == restoredPurchases.size)

        db.withTransaction {
            dao.clearPurchases()
            dao.clearRequisitions()
            dao.clearItems()
            dao.restoreItems(restoredItems)
            dao.restoreRequisitions(restoredRequirements)
            dao.restorePurchases(restoredPurchases)
        }
    }
}
