package lk.peliyagoda.marketpro.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Cloud V1 deliberately contains NO invoice/photo entity and NO file/Storage upload.
 * This is only an offline durable journal; network sync requires verified enrollment.
 */
enum class CloudRecordType(val wireName: String) {
    CATEGORY("category"),
    ITEM("item"),
    REQUISITION("requisition"),
    PURCHASE("purchase"),
    PURCHASE_ADDITION("purchase_addition"),
    DAILY_CASH_RECEIVED("daily_cash_received")
}

enum class CloudAction { UPSERT, DELETE }

/** Local logical keys are device-scoped; never treat Room autoincrement IDs as cloud IDs. */
object CloudRecordKey {
    fun category(name: String): String {
        val clean = name.trim().lowercase(Locale.ROOT)
        require(clean.isNotEmpty())
        return clean
    }
    fun item(id: Long): String {
        require(id > 0L)
        return id.toString()
    }
    fun requisition(date: String, kitchen: String, itemId: Long): String {
        LocalDate.parse(date)
        require(kitchen in MarketConstants.kitchens)
        return "${date}|${kitchen}|${item(itemId)}"
    }
    fun purchase(date: String, itemId: Long): String {
        LocalDate.parse(date)
        return "${date}|${item(itemId)}"
    }
    fun addition(lotId: Long): String = item(lotId)
    fun cash(date: String): String = LocalDate.parse(date).toString()
}

/**
 * Latest non-acknowledged change per local logical key. Replacing a pending edit
 * generates a new operationId, so an ACK for an older in-flight upload can never
 * erase the newer edit. The sender must persist ACK only after server commit.
 */
@Entity(
    tableName = "cloud_sync_outbox",
    primaryKeys = ["entityType", "entityKey"],
    indices = [Index(value = ["changedAtMs"])]
)
data class CloudOutboxEntity(
    val entityType: String,
    val entityKey: String,
    val operationId: String,
    val action: String,
    val changedAtMs: Long
)

/** Persistent stable UUID mapping; deleted records retain identity as tombstones. */
@Entity(
    tableName = "cloud_entity_ids",
    primaryKeys = ["entityType", "entityKey"],
    indices = [Index(value = ["cloudId"], unique = true)]
)
data class CloudIdentityEntity(
    val entityType: String,
    val entityKey: String,
    val cloudId: String
)

@Entity(tableName = "cloud_sync_state")
data class CloudSyncStateEntity(@PrimaryKey val name: String, val value: String)

@Dao
interface CloudSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPending(entry: CloudOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentity(entry: CloudIdentityEntity): Long

    @Query("SELECT cloudId FROM cloud_entity_ids WHERE entityType=:type AND entityKey=:key LIMIT 1")
    suspend fun cloudId(type: String, key: String): String?

    @Query("SELECT COUNT(*) FROM cloud_sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cloud_sync_outbox")
    suspend fun pendingCountNow(): Int

    /** Strict dependency ordering: category → item → financial rows. */
    @Query("""
        SELECT * FROM cloud_sync_outbox
        ORDER BY CASE entityType
          WHEN 'category' THEN 0 WHEN 'item' THEN 1 ELSE 2 END,
          changedAtMs,entityType,entityKey LIMIT :limit
    """)
    suspend fun pending(limit: Int): List<CloudOutboxEntity>

    @Query("SELECT * FROM categories WHERE lower(trim(name)) = :key LIMIT 1")
    suspend fun findCategory(key: String): CategoryEntity?

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun findItem(id: Long): ItemEntity?

    @Query("SELECT * FROM requisitions WHERE businessDate = :date AND kitchen = :kitchen AND itemId = :itemId LIMIT 1")
    suspend fun findRequisition(date: String, kitchen: String, itemId: Long): RequisitionEntity?

    @Query("SELECT * FROM purchases WHERE businessDate = :date AND itemId = :itemId LIMIT 1")
    suspend fun findPurchase(date: String, itemId: Long): PurchaseEntity?

    @Query("SELECT * FROM purchase_additions WHERE id = :id LIMIT 1")
    suspend fun findAddition(id: Long): PurchaseAdditionEntity?

    @Query("SELECT * FROM daily_cash_received WHERE businessDate = :date LIMIT 1")
    suspend fun findCash(date: String): DailyCashReceivedEntity?

    @Query("SELECT * FROM cloud_sync_state WHERE name = :name LIMIT 1")
    fun observeState(name: String): Flow<CloudSyncStateEntity?>

    /** Conditional ACK: never delete a newer unsent update. */
    @Query("DELETE FROM cloud_sync_outbox WHERE entityType=:type AND entityKey=:key AND operationId=:operationId")
    suspend fun acknowledge(type: String, key: String, operationId: String): Int

    @Query("SELECT value FROM cloud_sync_state WHERE name=:name LIMIT 1")
    suspend fun state(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setState(entry: CloudSyncStateEntity)

    @Query("DELETE FROM cloud_sync_outbox")
    suspend fun clearPending()

    @Query("DELETE FROM cloud_entity_ids")
    suspend fun clearIdentities()
}

/**
 * Call this within the SAME Room transaction as the actual item/financial mutation.
 * This class never connects to a network or reads invoice photos.
 */
class CloudChangeJournal(private val dao: CloudSyncDao) {
    suspend fun record(
        type: CloudRecordType,
        key: String,
        action: CloudAction = CloudAction.UPSERT
    ) {
        require(key.isNotBlank())
        val wireType = type.wireName
        dao.insertIdentity(CloudIdentityEntity(wireType, key, UUID.randomUUID().toString()))
        dao.putPending(
            CloudOutboxEntity(
                entityType = wireType,
                entityKey = key,
                operationId = UUID.randomUUID().toString(),
                action = action.name.lowercase(Locale.ROOT),
                changedAtMs = System.currentTimeMillis()
            )
        )
    }
}
