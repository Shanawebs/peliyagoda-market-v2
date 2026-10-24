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

    /** Read-only, bounded input for future Owner mapping review UI. */
    @Query("""
        SELECT * FROM cloud_inbox_snapshots
        WHERE entityType=:type ORDER BY cloudId ASC LIMIT :limit
    """)
    suspend fun catalogReviewSnapshots(type: String, limit: Int): List<CloudInboxSnapshotEntity>

    @Query("""
        SELECT COUNT(*) FROM cloud_sync_outbox AS o
        JOIN cloud_entity_ids AS i
          ON i.entityType=o.entityType AND i.entityKey=o.entityKey
        WHERE i.entityType=:type AND i.cloudId=:cloudId
    """)
    suspend fun hasPendingLocalEditForCloudId(type: String, cloudId: String): Int

    @Query("SELECT entityKey FROM cloud_entity_ids WHERE entityType=:type AND cloudId=:cloudId LIMIT 1")
    suspend fun localKeyForCloudId(type: String, cloudId: String): String?

    /** Read bounded, deterministic merge candidates without altering the cursor.
     * UUID order is independent of server's (updated_at,id) pull watermark.
     * Re-reading already-current rows is safe because the revision policy
     * prevents re-application and keeps pending local changes untouched.
     */
    @Query("""
        SELECT * FROM cloud_inbox_snapshots
        WHERE entityType=:type AND cloudId > :afterId
        ORDER BY cloudId ASC LIMIT :limit
    """)
    suspend fun financialMergeCandidates(
        type: String, afterId: String, limit: Int
    ): List<CloudInboxSnapshotEntity>

    /**
     * Prevent duplicate lot imports against an old Owner/Staff phone:
     * any historical local + lot without an exact Cloud UUID mapping blocks
     * auto-import of another lot on that same day/item.
     */
    @Query("""
        SELECT COUNT(*) FROM purchase_additions AS p
        LEFT JOIN cloud_entity_ids AS c
          ON c.entityType='purchase_addition' AND
             c.entityKey=CAST(p.id AS TEXT)
        WHERE p.businessDate=:date AND p.itemId=:itemId AND c.cloudId IS NULL
    """)
    suspend fun unmappedAdditionCount(date: String, itemId: Long): Int

    /** Detect pending unsent edits by logical business key, even if no Cloud UUID was bound yet. */
    @Query("SELECT COUNT(*) FROM cloud_sync_outbox WHERE entityType=:type AND entityKey=:key")
    suspend fun pendingForLocalKey(type: String, key: String): Int

    /** This DAO intentionally has NO query to update visible financial tables. */
}
