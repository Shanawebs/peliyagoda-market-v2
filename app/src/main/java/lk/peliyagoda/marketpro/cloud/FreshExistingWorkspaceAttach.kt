package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.room.withTransaction
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudChangeJournal
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.CloudRecordKey
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Existing Supabase catalog ATTACH ONLY for an empty-financial standalone V3 sandbox.
 * No historical Cloud records are changed. All network calls are GET, and all
 * local mapping changes occur in ONE Room transaction after full revalidation.
 * A server-authorized writer registration must already exist before activation.
 */
internal object FreshAttachState {
    const val PROTOCOL = "fresh_attachment_protocol"
    const val VERSION = "v1"
    const val ATTACHED_AT = "fresh_attachment_utc"
    fun originKey(type: String, entityKey: String) = "fresh_origin:$type:$entityKey"
}

internal class FreshCatalogReader {
    private val origin = BuildConfig.MARKETPRO_SUPABASE_URL
    private val workspace = CloudSyncConfig.WORKSPACE_ID

    private fun getRows(token: String, table: String, projection: String): JSONArray {
        check(table == "categories" || table == "items")
        val endpoint = "/rest/v1/$table?select=$projection&workspace_id=eq.$workspace&limit=500"
        val url = URL(origin + endpoint)
        require(url.protocol == "https" && url.host == BuildConfig.MARKETPRO_SUPABASE_HOST)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Prefer", "count=exact")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Cloud catalog read failed; check owner login")
            }
            val total = CloudReadinessRules.countFromContentRange(
                connection.getHeaderField("Content-Range")
            )
            require(total in 1..500) { "Unexpected catalog size; refusing partial attach" }
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            val body = reader.use { it.readText().take(262145) }
            require(body.length <= 262144) { "Cloud catalog too large" }
            val rows = JSONArray(body)
            require(rows.length() == total) { "Cloud catalog is incomplete" }
            return rows
        } finally {
            connection.disconnect()
        }
    }

    fun categories(token: String): List<AttachCloudCategory> =
        getRows(token, "categories", "id,name").let { rows ->
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                AttachCloudCategory(row.getString("id"), row.getString("name"))
            }
        }

    fun items(token: String): List<AttachCloudItem> =
        getRows(token, "items", "id,name,category_id,is_active,deleted_at").let { rows ->
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                AttachCloudItem(
                    id = row.getString("id"), name = row.getString("name"),
                    categoryId = row.getString("category_id"),
                    active = row.getBoolean("is_active"),
                    deleted = !row.isNull("deleted_at")
                )
            }
        }
}

internal data class FreshAttachReceipt(
    val linkedActiveItems: Int,
    val remoteArchivedItemsPreserved: Int,
    val cloudWriteEnabled: Boolean
)

private data class LocalAttachSnapshot(
    val categories: List<AttachLocalCategory>,
    val items: List<AttachLocalItem>,
    val identityKeys: Set<Pair<String, String>>,
    val fingerprint: String
)

internal class FreshExistingWorkspaceAttach(context: Context) {
    private val app = context.applicationContext
    private val db = MarketDatabase.get(app)
    private val dao = db.dao()
    private val cloud = db.cloudDao()
    private val api = FreshCatalogReader()

    private suspend fun localSnapshot(): LocalAttachSnapshot = db.withTransaction {
        require(cloud.state(CloudSyncConfig.ENABLED) != "true") { "Fresh attach already activated" }
        require(cloud.state(FreshAttachState.PROTOCOL).isNullOrBlank()) {
            "Fresh attach already recorded"
        }
        require(cloud.state(IdentityStagePolicy.SNAPSHOT_STATE).isNullOrBlank()) {
            "Restored UUID stage cannot be used for a fresh-only sandbox"
        }
        val cats = dao.allCategories()
        val items = dao.allItems()
        val req = dao.allRequisitions()
        val purchases = dao.allPurchases()
        val additions = dao.allAdditions()
        val cash = dao.allCashReceived()
        check(req.isEmpty() && purchases.isEmpty() &&
            additions.isEmpty() && cash.isEmpty()) {
            "A fresh attach requires NO local financial demo rows"
        }
        val identities = cloud.allIdentities()
        val pending = cloud.pending(Int.MAX_VALUE)
        val states = cloud.allStates()
        val expected = cats.map { "category" to CloudRecordKey.category(it.name) }.toSet() +
            items.map { "item" to CloudRecordKey.item(it.id) }.toSet()
        require(expected.size == cats.size + items.size &&
            identities.map { it.entityType to it.entityKey }.toSet() == expected &&
            pending.map { it.entityType to it.entityKey }.toSet() == expected &&
            identities.size == expected.size && pending.size == expected.size &&
            pending.all { it.action == "upsert" && it.entityType in setOf("item","category") }
        ) { "Catalog seed journal not clean or not complete" }

        val fingerprint = LocalStateFingerprint.calculate(
            cats,items,req,purchases,additions,cash,identities,pending,states
        )
        LocalAttachSnapshot(
            categories = cats.map { AttachLocalCategory(
                CloudRecordKey.category(it.name),it.name
            ) },
            items = items.map { AttachLocalItem(
                CloudRecordKey.item(it.id),it.name,it.category,it.isActive
            ) },
            identityKeys = expected,
            fingerprint = fingerprint
        )
    }

    suspend fun activate(): FreshAttachReceipt = withContext(Dispatchers.IO) {
        // Remote authorization + catalog GETs happen outside Room transaction.
        val token = CloudOwnerAuth(app).accessTokenForOwner(CloudSyncConfig.OWNER_USER_ID)
        val device = CloudDeviceIdentity.get(app)
        UUID.fromString(device)
        CloudRestClient().verifyPrimaryOwner(token,device)
        val before = localSnapshot()
        val remoteCats = api.categories(token)
        val remoteItems = api.items(token)
        val plan = FreshCatalogAttachPlanner.build(
            before.categories,before.items,remoteCats,remoteItems
        )

        db.withTransaction {
            val nowCats = dao.allCategories()
            val nowItems = dao.allItems()
            val req = dao.allRequisitions()
            val purchases = dao.allPurchases()
            val additions = dao.allAdditions()
            val cash = dao.allCashReceived()
            val beforeIds = cloud.allIdentities()
            val beforeQueue = cloud.pending(Int.MAX_VALUE)
            val states = cloud.allStates()
            require(
                LocalStateFingerprint.calculate(
                    nowCats,nowItems,req,purchases,additions,cash,
                    beforeIds,beforeQueue,states
                ) == before.fingerprint
            ) { "Phone changed during catalog audit; nothing activated" }
            check(cloud.state(CloudSyncConfig.ENABLED) != "true" &&
                cloud.state(FreshAttachState.PROTOCOL).isNullOrBlank())
            check(beforeIds.map { it.entityType to it.entityKey }.toSet() == before.identityKeys)
            check(beforeQueue.map { it.entityType to it.entityKey }.toSet() == before.identityKeys)

            // Only a pristine initial CATALOG journal is cleared. No financial
            // data, historic Cloud UUIDs or photos are touched by this operation.
            cloud.clearPending()
            cloud.clearIdentities()
            for (mapping in plan.identities) {
                check(cloud.insertIdentity(mapping) > 0L)
            }
            check(cloud.allIdentities().size == plan.identities.size &&
                cloud.pendingCountNow() == 0)
            cloud.setState(CloudSyncStateEntity(FreshAttachState.PROTOCOL, FreshAttachState.VERSION))
            cloud.setState(CloudSyncStateEntity(FreshAttachState.ATTACHED_AT, Instant.now().toString()))
            cloud.setState(CloudSyncStateEntity(CloudSyncConfig.ENABLED, "true"))
        }
        CloudSyncScheduler.schedulePeriodic(app)
        FreshAttachReceipt(plan.matchedItems,plan.protectedArchivedRemoteItemsPreserved(),true)
    }
}

private fun CatalogAttachPlan.protectedArchivedRemoteItemsPreserved() =
    protectedArchivedRemoteItems
