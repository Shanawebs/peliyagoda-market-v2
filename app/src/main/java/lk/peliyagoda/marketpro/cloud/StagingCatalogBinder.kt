package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.room.withTransaction
import java.time.Instant
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.DailyCashReceivedEntity
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject

/**
 * STAGING ONLY.
 *
 * Attaches a fresh local curated catalog to the already-reviewed Cloud catalog.
 * No financial record is uploaded or deleted here.
 *
 * Safety rules:
 * - phone must already be an authenticated + approved Cloud device;
 * - Cloud category/item pages are fetched through marketpro_team_pull_page;
 * - local financial tables must still be empty;
 * - active local item names/categories must match Cloud exactly;
 * - Cloud-only items are allowed only when archived/inactive;
 * - all UUID mappings are replaced in one Room transaction;
 * - initial seed outbox is cleared only after the complete catalog plan verifies.
 */
/**
 * A fresh install may contain exactly one saved Rs. 0.00 Cash Received row:
 * entering zero in the Cash UI writes a Room placeholder + durable outbox,
 * even though NO real money moved. Keep that zero row; discard only its
 * pre-activation outbox operation AFTER the catalog mapping is validated.
 *
 * Any nonzero Cash, second Cash row, purchases, requisitions, additions,
 * other expenses or unsupported finance outbox still blocks activation.
 * This policy never mutates financial rows.
 */
internal object StagingZeroCashBootstrapPolicy {
    fun permits(
        cashRows: List<DailyCashReceivedEntity>,
        pending: List<CloudOutboxEntity>
    ): Boolean {
        if (cashRows.size > 1 || cashRows.any { it.amountCents != 0L }) return false
        val zeroCashDate = cashRows.singleOrNull()?.businessDate
        return pending.all { operation ->
            when (operation.entityType) {
                "category", "item" -> true
                "daily_cash_received" ->
                    zeroCashDate != null &&
                    operation.entityKey == zeroCashDate &&
                    operation.action == "upsert"
                else -> false
            }
        }
    }
}

internal class StagingCatalogBinder(context: Context) {
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val dao = db.dao()
    private val cloud = db.cloudDao()
    private val inbox = db.inboxDao()
    private val sessionProvider = OneMarketProPullSessionProvider(app)

    data class Receipt(
        val localCategories: Int,
        val localItems: Int,
        val protectedArchivedCloudItems: Int
    )

    companion object {
        const val READY = "two_phone_sync_ready_v1"
        const val VERIFIED = "staff_outbox_verified_v1"
    }

    suspend fun isReady(): Boolean =
        cloud.state(READY) == "true" &&
            cloud.state(VERIFIED) == "true" &&
            cloud.state(CloudSyncConfig.ENABLED) == "true"

    private suspend fun stageCatalog() {
        val puller = TeamInboxDownloadCoordinator(app, sessionProvider)
        for (type in listOf("category", "item")) {
            val result = puller.stageCloudType(type, pageSize = 50, maxPages = 20)
            check(!result.hasMore) {
                "Cloud catalog download is incomplete. Try again with internet connected."
            }
        }
    }

    private suspend fun buildPlan(): CatalogAttachPlan {
        val localCategories = dao.allCategories().map {
            AttachLocalCategory(
                key = CloudRecordKey.category(it.name),
                name = it.name
            )
        }
        val localItems = dao.allItems().map {
            AttachLocalItem(
                key = CloudRecordKey.item(it.id),
                name = it.name,
                category = it.category,
                active = it.isActive
            )
        }
        check(localCategories.isNotEmpty() && localItems.isNotEmpty()) {
            "Local Master Item Catalog is empty"
        }

        val categorySnapshots = inbox.catalogReviewSnapshots("category", 1000)
        val itemSnapshots = inbox.catalogReviewSnapshots("item", 1000)
        check(categorySnapshots.isNotEmpty() && itemSnapshots.isNotEmpty()) {
            "Cloud catalog has not been downloaded"
        }
        check(categorySnapshots.size < 1000 && itemSnapshots.size < 1000) {
            "Catalog exceeds safe bootstrap limit"
        }

        val remoteCategories = categorySnapshots.map { snapshot ->
            check(snapshot.workspaceId == CloudSyncConfig.WORKSPACE_ID &&
                snapshot.deletedAtUtc == null) {
                "Cloud category is outside this workspace or archived"
            }
            val row = JSONObject(snapshot.canonicalJson)
            check(row.getString("id") == snapshot.cloudId &&
                row.getString("workspace_id") == CloudSyncConfig.WORKSPACE_ID &&
                row.getLong("revision") == snapshot.revision &&
                snapshot.revision > 0L) {
                "Untrusted Cloud category snapshot"
            }
            AttachCloudCategory(
                id = snapshot.cloudId,
                name = row.getString("name")
            )
        }

        val remoteItems = itemSnapshots.map { snapshot ->
            check(snapshot.workspaceId == CloudSyncConfig.WORKSPACE_ID) {
                "Cloud item belongs to another workspace"
            }
            val row = JSONObject(snapshot.canonicalJson)
            check(row.getString("id") == snapshot.cloudId &&
                row.getString("workspace_id") == CloudSyncConfig.WORKSPACE_ID &&
                row.getLong("revision") == snapshot.revision &&
                snapshot.revision > 0L) {
                "Untrusted Cloud item snapshot"
            }
            AttachCloudItem(
                id = snapshot.cloudId,
                name = row.getString("name"),
                categoryId = row.getString("category_id"),
                active = row.getBoolean("is_active"),
                deleted = snapshot.deletedAtUtc != null || !row.isNull("deleted_at")
            )
        }

        return FreshCatalogAttachPlanner.build(
            localCategories = localCategories,
            localItems = localItems,
            remoteCategories = remoteCategories,
            remoteItems = remoteItems
        )
    }

    suspend fun activateFreshPhone(): Receipt {
        check(
            !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
                BuildConfig.MARKETPRO_STAGING_QA_FINANCE_RPC &&
                BuildConfig.MARKETPRO_TWO_PHONE_AUTO_SYNC &&
                BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
                BuildConfig.MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED
        ) {
            "Two-phone staging sync is not enabled in this APK"
        }

        // This also verifies current Auth + exact physical registered device.
        stageCatalog()
        val plan = buildPlan()

        db.withTransaction {
            check(!isReady()) { "Two-phone sync is already activated on this phone" }

            // Final identity activation is allowed only on a fresh staging financial store.
            check(
                dao.allRequisitions().isEmpty() &&
                    dao.allPurchases().isEmpty() &&
                    dao.allAdditions().isEmpty() &&
                    dao.allOtherExpenses().isEmpty()
            ) {
                "This staging phone has nonempty financial records. Back up records and use an audited migration before activation."
            }

            val localCategories = dao.allCategories()
            val localItems = dao.allItems()
            check(plan.identities.size == localCategories.size + localItems.size) {
                "Catalog mapping count mismatch"
            }

            val pending = cloud.pending(Int.MAX_VALUE)
            // The only permitted finance row is ONE exact-zero local cash
            // placeholder. Its zero amount and local row remain intact.
            // Its pre-activation outbox operation is discarded along with
            // pre-activation catalog journal only after this strict check.
            check(StagingZeroCashBootstrapPolicy.permits(dao.allCashReceived(),pending)) {
                "Nonzero Cash or a real financial Outbox entry exists. Do not clear data; back it up and review before Cloud activation."
            }

            // Revalidate the exact local keys inside the same transaction.
            val expectedKeys =
                localCategories.map {
                    "category" to CloudRecordKey.category(it.name)
                }.toSet() +
                    localItems.map {
                        "item" to CloudRecordKey.item(it.id)
                    }.toSet()
            check(
                expectedKeys.size == localCategories.size + localItems.size &&
                    plan.identities.map { it.entityType to it.entityKey }.toSet() == expectedKeys
            ) {
                "Local catalog changed during Cloud verification"
            }

            cloud.clearPending()
            cloud.clearIdentities()
            for (identity: CloudIdentityEntity in plan.identities) {
                check(cloud.insertIdentity(identity) > 0L) {
                    "Cloud catalog UUID collision"
                }
            }
            check(cloud.allIdentities().size == plan.identities.size) {
                "Not every local catalog row received a Cloud UUID"
            }

            cloud.setState(
                CloudSyncStateEntity(
                    FreshAttachState.PROTOCOL,
                    FreshAttachState.VERSION
                )
            )
            cloud.setState(
                CloudSyncStateEntity(
                    FreshAttachState.ATTACHED_AT,
                    Instant.now().toString()
                )
            )
            cloud.setState(CloudSyncStateEntity(VERIFIED, "true"))
            cloud.setState(CloudSyncStateEntity(READY, "true"))
            cloud.setState(CloudSyncStateEntity(CloudSyncConfig.ENABLED, "true"))
        }

        return Receipt(
            localCategories = plan.identities.count { it.entityType == "category" },
            localItems = plan.matchedItems,
            protectedArchivedCloudItems = plan.protectedArchivedRemoteItems
        )
    }
}
