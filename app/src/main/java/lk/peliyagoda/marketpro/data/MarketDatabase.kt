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

@Dao
interface MarketDao {
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE")
    fun observeItems(): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items")
    suspend fun itemCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: ItemEntity): Long

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
    entities = [ItemEntity::class, RequisitionEntity::class, PurchaseEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MarketDatabase : RoomDatabase() {
    abstract fun dao(): MarketDao

    companion object {
        @Volatile private var instance: MarketDatabase? = null
        fun get(context: Context): MarketDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MarketDatabase::class.java,
                "peliyagoda-market-pro.db"
            ).build().also { instance = it }
        }
    }
}
