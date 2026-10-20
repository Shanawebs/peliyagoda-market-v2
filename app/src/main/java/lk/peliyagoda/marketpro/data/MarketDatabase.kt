package lk.peliyagoda.marketpro.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object MarketConstants {
    const val VEGETABLES = "Vegetables"
    const val FRUITS = "Fruits"
    const val MAIN = "Main Kitchen"
    const val INDIAN = "Indian Kitchen"
    const val PASTRY = "Pastry Kitchen"
    val kitchens = listOf(MAIN, INDIAN, PASTRY)
    val categories = listOf(VEGETABLES, FRUITS)
}

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val name: String)

@Entity(tableName = "purchase_additions", indices = [Index("businessDate"), Index("itemId")])
data class PurchaseAdditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val businessDate: String,
    val itemId: Long,
    val quantityGrams: Long,
    val priceCentsPerKg: Long
)

@Entity(tableName = "items", indices = [Index(value = ["name"], unique = true)])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String
)

@Entity(tableName = "requisitions", primaryKeys = ["businessDate", "kitchen", "itemId"],
    indices = [Index("itemId")])
data class RequisitionEntity(
    val businessDate: String,
    val kitchen: String,
    val itemId: Long,
    val quantityGrams: Long
)

@Entity(tableName = "purchases", primaryKeys = ["businessDate", "itemId"],
    indices = [Index("itemId")])
data class PurchaseEntity(
    val businessDate: String,
    val itemId: Long,
    val quantityGrams: Long,
    val priceCentsPerKg: Long
)

/** One original purchase or an additional purchase at its own actual unit rate. */
data class PurchaseHistoryLot(
    val businessDate: String,
    val quantityGrams: Long,
    val priceCentsPerKg: Long,
    val lotSequence: Long
)

@Dao
interface MarketDao {
    @Query("""
        SELECT businessDate, quantityGrams, priceCentsPerKg, 0 AS lotSequence
        FROM purchases
        WHERE itemId = :itemId AND quantityGrams > 0
        UNION ALL
        SELECT businessDate, quantityGrams, priceCentsPerKg, id AS lotSequence
        FROM purchase_additions
        WHERE itemId = :itemId AND quantityGrams > 0
        ORDER BY businessDate ASC, lotSequence ASC
    """)
    fun observePurchaseHistory(itemId: Long): Flow<List<PurchaseHistoryLot>>

    /**
     * Dates with actual entered quantities/prices. Observable across ALL days,
     * not just the currently selected day. No schema migration needed.
     */
    @Query("""
        SELECT businessDate FROM requisitions WHERE quantityGrams > 0
        UNION
        SELECT businessDate FROM purchases WHERE quantityGrams > 0 OR priceCentsPerKg > 0
        UNION
        SELECT businessDate FROM purchase_additions WHERE quantityGrams > 0
        ORDER BY businessDate
    """)
    fun observeDatesWithData(): Flow<List<String>>

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE")
    suspend fun allCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreCategories(entries: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("SELECT * FROM purchase_additions WHERE businessDate = :date ORDER BY id")
    fun observeAdditions(date: String): Flow<List<PurchaseAdditionEntity>>

    @Query("SELECT * FROM purchases WHERE businessDate = :date AND itemId = :itemId LIMIT 1")
    suspend fun purchaseForItem(date: String, itemId: Long): PurchaseEntity?

    @Query("SELECT COALESCE(SUM(quantityGrams), 0) FROM requisitions WHERE businessDate = :date AND itemId = :itemId")
    suspend fun requirementGramsForItem(date: String, itemId: Long): Long

    @Query("SELECT COALESCE(SUM(quantityGrams), 0) FROM purchase_additions WHERE businessDate = :date AND itemId = :itemId")
    suspend fun additionalGramsForItem(date: String, itemId: Long): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAddition(entry: PurchaseAdditionEntity): Long

    @Query("UPDATE purchase_additions SET quantityGrams = :grams, priceCentsPerKg = :priceCents WHERE id = :lotId AND businessDate = :date AND itemId = :itemId")
    suspend fun updateAddition(lotId: Long, date: String, itemId: Long, grams: Long, priceCents: Long): Int

    @Query("SELECT * FROM purchase_additions ORDER BY id")
    suspend fun allAdditions(): List<PurchaseAdditionEntity>

    @Query("DELETE FROM purchase_additions")
    suspend fun clearAdditions()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreAdditions(entries: List<PurchaseAdditionEntity>)

    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE")
    fun observeItems(): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items")
    suspend fun itemCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: ItemEntity): Long

    @Query("UPDATE items SET name = :name WHERE id = :id")
    suspend fun renameItem(id: Long, name: String): Int

    @Query("SELECT * FROM requisitions WHERE businessDate = :date")
    fun observeRequisitions(date: String): Flow<List<RequisitionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRequisition(entry: RequisitionEntity)

    @Query("DELETE FROM requisitions WHERE businessDate = :date AND kitchen = :kitchen AND itemId = :itemId")
    suspend fun deleteRequisition(date: String, kitchen: String, itemId: Long)

    @Query("SELECT * FROM purchases WHERE businessDate = :date")
    fun observePurchases(date: String): Flow<List<PurchaseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePurchase(entry: PurchaseEntity)

    @Query("DELETE FROM purchases WHERE businessDate = :date AND itemId = :itemId")
    suspend fun deletePurchase(date: String, itemId: Long)

    @Query("SELECT * FROM items ORDER BY id")
    suspend fun allItems(): List<ItemEntity>

    @Query("SELECT * FROM requisitions ORDER BY businessDate, kitchen, itemId")
    suspend fun allRequisitions(): List<RequisitionEntity>

    @Query("SELECT * FROM purchases ORDER BY businessDate, itemId")
    suspend fun allPurchases(): List<PurchaseEntity>

    @Query("DELETE FROM purchases")
    suspend fun clearPurchases()

    @Query("DELETE FROM requisitions")
    suspend fun clearRequisitions()

    @Query("DELETE FROM items")
    suspend fun clearItems()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restoreRequisitions(entries: List<RequisitionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restorePurchases(entries: List<PurchaseEntity>)
}

@Database(
    entities = [
        ItemEntity::class, RequisitionEntity::class, PurchaseEntity::class,
        CategoryEntity::class, PurchaseAdditionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MarketDatabase : RoomDatabase() {
    abstract fun dao(): MarketDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
                db.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('Vegetables'), ('Fruits')")
                db.execSQL("INSERT OR IGNORE INTO categories(name) SELECT DISTINCT category FROM items")
                db.execSQL("CREATE TABLE IF NOT EXISTS `purchase_additions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `businessDate` TEXT NOT NULL, `itemId` INTEGER NOT NULL, `quantityGrams` INTEGER NOT NULL, `priceCentsPerKg` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_additions_businessDate` ON `purchase_additions` (`businessDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchase_additions_itemId` ON `purchase_additions` (`itemId`)")
            }
        }

        @Volatile private var instance: MarketDatabase? = null
        fun get(context: Context): MarketDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MarketDatabase::class.java,
                "peliyagoda-market-pro.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
