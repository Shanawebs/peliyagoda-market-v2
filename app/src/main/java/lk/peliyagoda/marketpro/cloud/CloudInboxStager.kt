package lk.peliyagoda.marketpro.cloud

import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CloudInboxCursorEntity
import lk.peliyagoda.marketpro.data.CloudInboxSnapshotEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.LocalDate

/**
 * V3.5.5 Phase A: verified-page → local SHADOW tables only.
 *
 * No network call and NO live Domain/MarketDao write.
 * Never call on the UI thread. The caller will eventually be a server-
 * authenticated download transport, after staged RPC rollout.
 */
class CloudInboxStager(private val db: MarketDatabase) {

    data class StageResult(
        val recordsStaged: Int,
        val recordsAlreadyCurrent: Int,
        val recordsWithPendingLocalEdit: Int,
        val nextCursor: InboxWatermark?,
        val hasMore: Boolean
    )

    private val dao = db.inboxDao()

    private val allowedColumns = mapOf(
        "category" to setOf("id","workspace_id","name","revision","deleted_at","created_at","updated_at"),
        "item" to setOf("id","workspace_id","category_id","name","is_active","revision",
            "deleted_at","created_at","updated_at"),
        "requisition" to setOf("id","workspace_id","business_date","kitchen","item_id",
            "quantity_grams","revision","deleted_at","created_at","updated_at"),
        "purchase" to setOf("id","workspace_id","business_date","item_id","quantity_grams",
            "price_cents_per_kg","revision","deleted_at","created_at","updated_at"),
        "purchase_addition" to setOf("id","workspace_id","business_date","item_id",
            "quantity_grams","price_cents_per_kg","revision","deleted_at",
            "created_at","updated_at"),
        "daily_cash_received" to setOf("id","workspace_id","business_date",
            "amount_cents","revision","deleted_at","created_at","updated_at")
    )

    /**
     * Receives the JSON returned by the proposed authenticated
     * public.marketpro_team_pull_page RPC. A malformed or cross-workspace
     * result throws BEFORE any database mutation.
     */
    suspend fun stagePage(
        trustedWorkspaceId: String,
        requestedType: String,
        expectedCursor: InboxWatermark?,
        rawPage: String,
        receivedAtMs: Long
    ): StageResult {
        require(receivedAtMs > 0L)
        require(CloudInboxPagePolicy.uuid(trustedWorkspaceId))
        require(rawPage.length in 2..262144) { "Oversized or empty Cloud page" }
        val page = JSONObject(rawPage)
        require(page.getString("entity_type") == requestedType)
        val array = page.getJSONArray("rows")
        require(array.length() <= 100)
        val hasMore = page.getBoolean("has_more")
        val today = page.getString("server_today_colombo")
        require(LocalDate.parse(today).toString() == today)
        val nextAt = page.optString("next_updated_at").takeIf { it.isNotBlank() && it != "null" }
        val nextId = page.optString("next_id").takeIf { it.isNotBlank() && it != "null" }
        require((nextAt == null) == (nextId == null))
        val returnedCursor = if (nextAt != null && nextId != null)
            InboxWatermark(nextAt,nextId) else null

        data class Verified(val cloudId:String, val rev:Long, val updated:String,
                            val deleted:String?,val payload:String)
        val verified = ArrayList<Verified>(array.length())
        val watermarks = ArrayList<InboxWatermark>(array.length())
        val keys = allowedColumns[requestedType]
            ?: throw IllegalArgumentException("Unsupported Cloud record type")
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val names = row.keys().asSequence().toSet()
            require(names.all { it in keys }) { "Unknown or unsafe Cloud column" }
            require(row.getString("workspace_id") == trustedWorkspaceId) {
                "Cloud row belongs to another workspace"
            }
            val id = row.getString("id")
            require(CloudInboxPagePolicy.uuid(id))
            val revision = row.getLong("revision")
            require(revision > 0L)
            val updated = row.getString("updated_at")
            OffsetDateTime.parse(updated)
            val deleted = if (row.isNull("deleted_at")) null else
                row.getString("deleted_at").also { OffsetDateTime.parse(it) }
            if (requestedType in setOf(
                "purchase","purchase_addition","requisition","daily_cash_received"
            )) {
                val date = row.getString("business_date")
                require(LocalDate.parse(date).toString() == date)
            }
            if (requestedType in setOf("purchase","purchase_addition","requisition","item")) {
                require(CloudInboxPagePolicy.uuid(row.getString(
                    if (requestedType == "item") "category_id" else "item_id"
                )))
            }
            val canonical = row.toString()
            require(canonical.length <= 4096) { "Oversized Cloud record" }
            verified.add(Verified(id,revision,updated,deleted,canonical))
            watermarks.add(InboxWatermark(updated,id))
        }

        CloudInboxPagePolicy.validate(
            requestedType,trustedWorkspaceId,expectedCursor,
            watermarks,hasMore,returnedCursor,today
        )

        return db.withTransaction {
            val stored = dao.cursor(requestedType)
            val actualCursor = if (stored?.lastUpdatedAtUtc != null &&
                stored.lastCloudId != null)
                InboxWatermark(stored.lastUpdatedAtUtc,stored.lastCloudId)
                else null
            // Concurrent WorkManager runs or restart must never skip a page.
            require(actualCursor == expectedCursor) { "Cloud cursor changed; restart pull" }

            var staged = 0
            var skipped = 0
            var conflicts = 0
            for (entry in verified) {
                if (dao.hasPendingLocalEditForCloudId(requestedType,entry.cloudId) > 0) {
                    conflicts++
                    // We may stage the server shadow for future conflict display,
                    // but never write into the user's local domain record.
                }
                val previous = dao.snapshot(requestedType,entry.cloudId)
                if (CloudInboxPagePolicy.shouldReplaceShadowSnapshot(
                        previous?.revision,entry.rev)) {
                    dao.saveSnapshot(
                        CloudInboxSnapshotEntity(
                            entityType=requestedType,
                            cloudId=entry.cloudId,
                            workspaceId=trustedWorkspaceId,
                            revision=entry.rev,
                            updatedAtUtc=entry.updated,
                            deletedAtUtc=entry.deleted,
                            canonicalJson=entry.payload,
                            receivedAtMs=receivedAtMs
                        )
                    )
                    staged++
                } else {
                    skipped++
                }
            }
            dao.saveCursor(
                CloudInboxCursorEntity(
                    entityType=requestedType,
                    lastUpdatedAtUtc=returnedCursor?.updatedAt,
                    lastCloudId=returnedCursor?.cloudId,
                    lastServerToday=today,
                    lastCheckpointMs=receivedAtMs
                )
            )
            StageResult(staged,skipped,conflicts,returnedCursor,hasMore)
        }
    }
}
