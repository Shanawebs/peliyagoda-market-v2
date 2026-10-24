package lk.peliyagoda.marketpro.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Cloud rows received by a future verified download client.
 * These are SHADOW/STAGING records. They are never wired to MarketRepository's
 * visible items, purchases, requisitions, cash or local invoice photos.
 */
@Entity(tableName = "cloud_inbox_snapshots", primaryKeys = ["entityType", "cloudId"])
data class CloudInboxSnapshotEntity(
    val entityType: String,
    val cloudId: String,
    val workspaceId: String,
    val revision: Long,
    val updatedAtUtc: String,
    val deletedAtUtc: String?,
    val canonicalJson: String,
    val receivedAtMs: Long
)

/** Persisted in the same Room transaction as an entire validated page. */
@Entity(tableName = "cloud_inbox_cursors")
data class CloudInboxCursorEntity(
    @androidx.room.PrimaryKey val entityType: String,
    val lastUpdatedAtUtc: String?,
    val lastCloudId: String?,
    val lastServerToday: String?,
    val lastCheckpointMs: Long
)

@Dao
interface CloudInboxDao {
    @Query("SELECT * FROM cloud_inbox_snapshots WHERE entityType=:type AND cloudId=:cloudId LIMIT 1")
    suspend fun snapshot(type: String, cloudId: String): CloudInboxSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(snapshot: CloudInboxSnapshotEntity)

    @Query("SELECT * FROM cloud_inbox_cursors WHERE entityType=:type LIMIT 1")
    suspend fun cursor(type: String): CloudInboxCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(cursor: CloudInboxCursorEntity)

    @Query("SELECT COUNT(*) FROM cloud_inbox_snapshots")
    suspend fun snapshotCount(): Int

    @Query("SELECT COUNT(*) FROM cloud_inbox_snapshots WHERE entityType=:type")
    suspend fun snapshotCountFor(type: String): Int

    @Query("""
        SELECT COUNT(*) FROM cloud_sync_outbox AS o
        JOIN cloud_entity_ids AS i
          ON i.entityType=o.entityType AND i.entityKey=o.entityKey
        WHERE i.entityType=:type AND i.cloudId=:cloudId
    """)
    suspend fun hasPendingLocalEditForCloudId(type: String, cloudId: String): Int

    @Query("SELECT entityKey FROM cloud_entity_ids WHERE entityType=:type AND cloudId=:cloudId LIMIT 1")
    suspend fun localKeyForCloudId(type: String, cloudId: String): String?

    /** Detect pending unsent edits by logical business key, even if no Cloud UUID was bound yet. */
    @Query("SELECT COUNT(*) FROM cloud_sync_outbox WHERE entityType=:type AND entityKey=:key")
    suspend fun pendingForLocalKey(type: String, key: String): Int

    /** This DAO intentionally has NO query to update visible financial tables. */
}
