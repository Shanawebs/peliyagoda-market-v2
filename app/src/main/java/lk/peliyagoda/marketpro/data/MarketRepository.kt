package lk.peliyagoda.marketpro.data

import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.util.PurchaseEntryRules
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

class MarketRepository(private val db: MarketDatabase) {
    private val dao = db.dao()
    private val cloudDao = db.cloudDao()
    private val journal = CloudChangeJournal(cloudDao)
    val cloudPendingCount: Flow<Int> = cloudDao.observePendingCount()
    suspend fun cloudSyncEnabled(): Boolean =
        !BuildConfig.MARKETPRO_DEMO_ARCHIVE_ONLY &&
        cloudDao.state(lk.peliyagoda.marketpro.cloud.CloudSyncConfig.ENABLED) == "true"

    val items: Flow<List<ItemEntity>> = dao.observeItems()
    // Includes only current selectable master items. Historical rows still use
    // the full items flow so reports and invoice item IDs remain resolvable.
    val catalogItems: Flow<List<ItemEntity>> = dao.observeActiveItems()
    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()
    val recordedDates: Flow<List<String>> = dao.observeDatesWithData()
    fun requirements(date: String) = dao.observeRequisitions(date)
    fun purchases(date: String) = dao.observePurchases(date)
    fun additions(date: String) = dao.observeAdditions(date)
    fun purchaseHistory(itemId: Long) = dao.observePurchaseHistory(itemId)
    fun cashReceived(date: String) = dao.observeCashReceived(date)

    suspend fun saveCashReceived(date: String, amountCents: Long) {
        LocalDate.parse(date)
        require(amountCents in 0L..9_999_999_999L) { "Cash received is out of range" }
        db.withTransaction {
            dao.upsertCashReceived(DailyCashReceivedEntity(date, amountCents))
            journal.record(CloudRecordType.DAILY_CASH_RECEIVED, CloudRecordKey.cash(date))
        }
    }

    suspend fun seedCatalogOnce() {
        // Exactly once per DB/catalog release; transaction keeps the current
        // catalog intact if any insert fails. Retain all old item IDs and
        // historical kitchen/purchase/camera references.
        db.withTransaction {
            if ((dao.curatedCatalogVersion() ?: 0) >= MarketCatalog.VERSION) {
                return@withTransaction
            }
            MarketCatalog.categories.forEach { dao.insertCategory(CategoryEntity(it)) }
            val allExisting = dao.allItems()
            val byName = allExisting.associateBy { it.name.trim().lowercase() }
            dao.archiveCatalogItems()
            for ((name, category) in MarketCatalog.entries) {
                val item = byName[name.lowercase()]
                if (item != null) {
                    dao.reactivateCatalogItem(item.id, category)
                } else {
                    val result = dao.insertItem(ItemEntity(name = name, category = category))
                    check(result > 0) { "Catalog collision for $name" }
                }
            }
            dao.setCuratedCatalogVersion(CatalogStateEntity(version = MarketCatalog.VERSION))
            // A new curated catalog release must not bypass the durable journal.
            dao.allCategories().forEach {
                journal.record(CloudRecordType.CATEGORY, CloudRecordKey.category(it.name))
            }
            dao.allItems().forEach {
                journal.record(CloudRecordType.ITEM, CloudRecordKey.item(it.id))
            }
        }
    }

    suspend fun addCategory(name: String): Boolean {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > 40 || clean.equals("All", true)) return false
        return db.withTransaction {
            if (dao.allCategories().any { it.name.equals(clean, true) }) false
            else {
                val created = dao.insertCategory(CategoryEntity(clean)) > 0
                if (created) journal.record(CloudRecordType.CATEGORY, CloudRecordKey.category(clean))
                created
            }
        }
    }

    suspend fun addItem(name: String, category: String): Boolean {
        val cleaned = name.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank() || cleaned.length > 80 || dao.allCategories().none { it.name == category }) return false
        return db.withTransaction {
            val old = dao.allItems().firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
            val created = when {
                old == null -> dao.insertItem(ItemEntity(name = cleaned, category = category)) > 0
                !old.isActive -> dao.reactivateCatalogItem(old.id, category) == 1
                else -> false
            }
            if (created) {
                val latest = dao.allItems().first { it.name.equals(cleaned, ignoreCase = true) }
                journal.record(CloudRecordType.ITEM, CloudRecordKey.item(latest.id))
            }
            created
        }
    }

    /** Rename without changing the item ID or any linked requisition/purchase records. */
    suspend fun renameItem(id: Long, name: String): Boolean {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || clean.length > 80) return false
        return db.withTransaction {
            val existing = dao.allItems()
            if (existing.none { it.id == id } ||
                existing.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) {
                false
            } else {
                val renamed = dao.renameItem(id, clean) == 1
                if (renamed) journal.record(CloudRecordType.ITEM, CloudRecordKey.item(id))
                renamed
            }
        }
    }

    /**
     * Non-destructive master item removal for future selections.
     * Existing requisitions, purchase lots, invoice photos and item ID are retained.
     * Reject incorrect approval codes before opening the write transaction.
     */
    suspend fun archiveItem(id: Long, approvalCode: String): Boolean {
        if (!lk.peliyagoda.marketpro.security.CatalogArchiveAuthorization.valid(approvalCode)) {
            return false
        }
        return db.withTransaction {
            val archived = dao.archiveItem(id) == 1
            if (archived) {
                journal.record(CloudRecordType.ITEM, CloudRecordKey.item(id))
            }
            archived
        }
    }

    suspend fun addRequirements(date: String, kitchen: String, ids: Set<Long>) {
        require(kitchen in MarketConstants.kitchens)
        require(LocalDate.parse(date) <= LocalDate.now().plusYears(10))
        val valid = dao.allItems().filter { it.isActive }.map { it.id }.toSet()
        db.withTransaction {
            for (id in ids) {
                if (id in valid) {
                    dao.saveRequisition(RequisitionEntity(date, kitchen, id, 0))
                    journal.record(
                        CloudRecordType.REQUISITION,
                        CloudRecordKey.requisition(date, kitchen, id)
                    )
                }
            }
        }
    }

    suspend fun saveRequirement(date: String, kitchen: String, id: Long, grams: Long) {
        require(grams >= 0 && grams <= 1_000_000_000)
        require(kitchen in MarketConstants.kitchens)
        db.withTransaction {
            dao.saveRequisition(RequisitionEntity(date, kitchen, id, grams))
            journal.record(CloudRecordType.REQUISITION, CloudRecordKey.requisition(date, kitchen, id))
        }
    }

    suspend fun deleteRequirement(date: String, kitchen: String, id: Long) {
        db.withTransaction {
            dao.deleteRequisition(date, kitchen, id)
            journal.record(
                CloudRecordType.REQUISITION, CloudRecordKey.requisition(date, kitchen, id),
                CloudAction.DELETE
            )
        }
    }

    suspend fun savePurchase(date: String, id: Long, grams: Long, centsPerKg: Long) {
        require(grams in 0..1_000_000_000 && centsPerKg in 0..1_000_000_000)
        db.withTransaction {
            if (grams == 0L && centsPerKg == 0L) {
                // Empty purchase is a real delete, not a completed transaction.
                dao.deletePurchase(date, id)
                journal.record(
                    CloudRecordType.PURCHASE, CloudRecordKey.purchase(date, id),
                    CloudAction.DELETE
                )
            } else {
                dao.savePurchase(PurchaseEntity(date, id, grams, centsPerKg))
                journal.record(CloudRecordType.PURCHASE, CloudRecordKey.purchase(date, id))
            }
        }
    }

    suspend fun addPurchaseAddition(date: String, id: Long, grams: Long, centsPerKg: Long) {
        require(grams in 1..1_000_000_000 && centsPerKg in 1..1_000_000_000)
        // The UI check also runs here in one transaction to guard against a
        // stale screen, rapid edits, and multiple extra-lot taps.
        db.withTransaction {
            require(dao.allItems().any { it.id == id })
            val initial = dao.purchaseForItem(date, id)
            val required = dao.requirementGramsForItem(date, id)
            val totalBought = (initial?.quantityGrams ?: 0L) +
                dao.additionalGramsForItem(date, id)
            check(PurchaseEntryRules.canAddAnotherPurchase(
                initial?.quantityGrams, initial?.priceCentsPerKg, required, totalBought
            )) {
                "Enter the initial purchased quantity and market price first. " +
                    "An additional purchase is allowed only for a shortage."
            }
            val lotId = dao.insertAddition(PurchaseAdditionEntity(
                businessDate = date, itemId = id,
                quantityGrams = grams, priceCentsPerKg = centsPerKg
            ))
            journal.record(CloudRecordType.PURCHASE_ADDITION, CloudRecordKey.addition(lotId))
        }
    }

    /** Preserve lot IDs: records remain individually priced and editable. */
    suspend fun editPurchaseAddition(date: String, itemId: Long, lotId: Long, grams: Long, centsPerKg: Long) {
        require(grams in 0..1_000_000_000 && centsPerKg in 0..1_000_000_000)
        db.withTransaction {
            check(dao.updateAddition(lotId, date, itemId, grams, centsPerKg) == 1) {
                "Additional purchase no longer exists"
            }
            journal.record(CloudRecordType.PURCHASE_ADDITION, CloudRecordKey.addition(lotId))
        }
    }

    suspend fun exportBackup(): String {
        val root = JSONObject()
        root.put("format", "peliyagoda-market-pro")
        root.put("schemaVersion", 4)
        root.put("createdAt", OffsetDateTime.now().toString())
        root.put("cashReceived", JSONArray().apply {
            dao.allCashReceived().forEach {
                put(JSONObject().apply {
                    put("date", it.businessDate)
                    put("amountCents", it.amountCents)
                })
            }
        })
        root.put("categories", JSONArray().apply {
            dao.allCategories().forEach { put(it.name) }
        })
        root.put("additions", JSONArray().apply {
            dao.allAdditions().forEach { put(JSONObject().apply {
                put("id", it.id); put("date", it.businessDate); put("itemId", it.itemId)
                put("grams", it.quantityGrams); put("priceCentsPerKg", it.priceCentsPerKg)
            }) }
        })
        root.put("items", JSONArray().apply {
            dao.allItems().forEach { put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("category", it.category)
                put("active", it.isActive)
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
        // This isolated demo APK is never enrolled. PIN-gated restore replaces
        // ONLY its own local Room data and cannot mutate Supabase or V3.3.7.
        // Never replace data if this app is actually enrolled, even in archive mode.
        // A user-approved full cloud rebootstrap protocol is required after enrollment.
        // Do NOT silently rewrite remote UUID identities when importing a backup.
        check(cloudDao.state("enrolled") != "true") {
            "Cloud-linked backup restore needs a verified disconnect/rebootstrap."
        }
        val root = JSONObject(json)
        require(root.optString("format") == "peliyagoda-market-pro") { "Not a Market Pro backup" }
        val backupVersion = root.optInt("schemaVersion")
        require(backupVersion in 1..4) { "Unsupported backup version" }
        val itemArray = root.getJSONArray("items")
        val reqArray = root.getJSONArray("requisitions")
        val purchaseArray = root.getJSONArray("purchases")
        val additionsArray = if (backupVersion >= 2) root.getJSONArray("additions") else JSONArray()
        val cashArray = if (backupVersion >= 3) root.getJSONArray("cashReceived") else JSONArray()
        require(itemArray.length() <= 100_000 && reqArray.length() <= 500_000 &&
                purchaseArray.length() <= 500_000 && additionsArray.length() <= 500_000 &&
                cashArray.length() <= 100_000) { "Backup too large" }
        val restoredItems = ArrayList<ItemEntity>(itemArray.length())
        for (i in 0 until itemArray.length()) {
            val obj = itemArray.getJSONObject(i)
            val item = ItemEntity(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                category = obj.getString("category"),
                isActive = if (backupVersion >= 4) obj.optBoolean("active", true) else true
            )
            require(item.id > 0 && item.name.isNotBlank() && item.name.length <= 80)
            require(item.category.isNotBlank() && item.category.length <= 40)
            restoredItems.add(item)
        }
        require(restoredItems.map { it.id }.distinct().size == restoredItems.size)
        require(restoredItems.map { it.name.lowercase() }.distinct().size == restoredItems.size)
        val categoryNames = linkedSetOf(MarketConstants.VEGETABLES, MarketConstants.FRUITS)
        if (backupVersion >= 2) {
            val array = root.getJSONArray("categories")
            require(array.length() <= 1000)
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val name = array.getString(i)
                require(name.isNotBlank() && name.length <= 40)
                require(seen.add(name.lowercase())) { "Repeated category" }
                if (!categoryNames.any { it.equals(name, true) }) categoryNames.add(name)
            }
        }
        restoredItems.forEach { item ->
            if (!categoryNames.any { it.equals(item.category, true) }) categoryNames.add(item.category)
        }
        require(categoryNames.size <= 1000)
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

        val restoredAdditions = ArrayList<PurchaseAdditionEntity>()
        for (i in 0 until additionsArray.length()) {
            val obj = additionsArray.getJSONObject(i)
            val lot = PurchaseAdditionEntity(
                id = obj.getLong("id"), businessDate = readDate(obj),
                itemId = obj.getLong("itemId"), quantityGrams = obj.getLong("grams"),
                priceCentsPerKg = obj.getLong("priceCentsPerKg")
            )
            require(lot.id > 0 && lot.itemId in idSet &&
                lot.quantityGrams in 0..1_000_000_000 &&
                lot.priceCentsPerKg in 0..1_000_000_000)
            restoredAdditions.add(lot)
        }
        require(restoredAdditions.map { it.id }.distinct().size == restoredAdditions.size)

        val restoredCash = ArrayList<DailyCashReceivedEntity>(cashArray.length())
        for (i in 0 until cashArray.length()) {
            val obj = cashArray.getJSONObject(i)
            val cash = DailyCashReceivedEntity(
                businessDate = readDate(obj),
                amountCents = obj.getLong("amountCents")
            )
            require(cash.amountCents in 0L..9_999_999_999L)
            restoredCash.add(cash)
        }
        require(restoredCash.map { it.businessDate }.distinct().size == restoredCash.size)

        db.withTransaction {
            // Re-check under lock before performing any destructive local restore.
            check(cloudDao.state("enrolled") != "true") {
                "Cannot replace data while Cloud Sync enrollment is active."
            }
            dao.clearCashReceived()
            dao.clearAdditions()
            dao.clearPurchases()
            dao.clearRequisitions()
            dao.clearItems()
            dao.clearCategories()
            dao.restoreCategories(categoryNames.map { CategoryEntity(it) })
            dao.restoreItems(restoredItems)
            dao.restoreRequisitions(restoredRequirements)
            dao.restorePurchases(restoredPurchases)
            dao.restoreAdditions(restoredAdditions)
            dao.restoreCashReceived(restoredCash)
            // A legacy backup must be upgraded to the new curated catalog.
            // An actual V4 backup retains the owner's saved catalog state.
            dao.setCuratedCatalogVersion(
                CatalogStateEntity(version = if (backupVersion < 4) 0 else MarketCatalog.VERSION)
            )
            // Replace journal and opaque IDs only for an UNENROLLED development database.
            // Tombstones for an enrolled workspace may never be silently discarded.
            cloudDao.clearPending()
            cloudDao.clearIdentities()
            dao.allCategories().forEach {
                journal.record(CloudRecordType.CATEGORY, CloudRecordKey.category(it.name))
            }
            dao.allItems().forEach {
                journal.record(CloudRecordType.ITEM, CloudRecordKey.item(it.id))
            }
            restoredRequirements.forEach {
                journal.record(
                    CloudRecordType.REQUISITION,
                    CloudRecordKey.requisition(it.businessDate, it.kitchen, it.itemId)
                )
            }
            restoredPurchases.forEach {
                journal.record(CloudRecordType.PURCHASE, CloudRecordKey.purchase(it.businessDate, it.itemId))
            }
            restoredAdditions.forEach {
                journal.record(CloudRecordType.PURCHASE_ADDITION, CloudRecordKey.addition(it.id))
            }
            restoredCash.forEach {
                journal.record(CloudRecordType.DAILY_CASH_RECEIVED, CloudRecordKey.cash(it.businessDate))
            }
        }
        if (backupVersion < 4) seedCatalogOnce()
    }
}
