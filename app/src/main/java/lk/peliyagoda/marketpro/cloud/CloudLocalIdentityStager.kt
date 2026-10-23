package lk.peliyagoda.marketpro.cloud

import android.content.Context
import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.CloudIdentityEntity
import lk.peliyagoda.marketpro.data.CloudOutboxEntity
import lk.peliyagoda.marketpro.data.CloudSyncStateEntity
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LOCAL UUID STAGING ONLY — not a device enrollment, writer cutover or upload.
 *
 * Converts restored identity UUIDs to their unique existing Cloud record IDs
 * inside a single Room transaction, then quarantines the restore-generated
 * pending outbox. An AES-GCM integrity-checked snapshot is always written first.
 *
 * If a local edit occurs between Cloud audit, snapshot and commit, the exact
 * Room SHA-256 check fails and the entire transaction rolls back automatically.
 */
data class LocalIdentityStageResult(
    val mappedRecords: Int,
    val quarantinedPending: Int,
    val encryptedBackupVerified: Boolean,
    val cloudUploadLocked: Boolean = true
)

data class LocalIdentityRollbackResult(
    val restoredMappings: Int,
    val restoredPending: Int,
    val cloudUploadLocked: Boolean = true
)

/** Pure planner. Fail CLOSED if business identities, record counts or UUIDs are ambiguous. */
internal object IdentityStagePolicy {
    const val SNAPSHOT_STATE = "local_uuid_stage_snapshot"

    fun validate(
        report: CloudPreflightReport,
        current: List<CloudIdentityEntity>,
        pending: List<CloudOutboxEntity>,
        enabled: Boolean,
        enrolled: Boolean
    ) {
        require(!enabled && !enrolled && !report.deviceRegisteredAsWriter) {
            "Cloud writer activation is not allowed in the preview"
        }
        require(report.catalogAudit.fullyMatched &&
            report.financialAudit.fullyMatched &&
            report.migrationDryRun.oneToOneCandidateCoverage &&
            report.migrationDryRun.unsafeOrUnmapped == 0) {
            "Only fully reconciled, non-ambiguous records may be staged"
        }
        require(current.isNotEmpty() &&
            current.size == report.migrationCandidates.size &&
            pending.size == report.uuidAudit.pendingUploadOperations &&
            pending.size == current.size) {
            "Local UUIDs or restore queue do not match the inspected dataset"
        }
        val keyedCurrent = current.associateBy { it.entityType to it.entityKey }
        val keyedTarget = report.migrationCandidates.associateBy {
            it.entityType to it.entityKey
        }
        require(keyedCurrent.size == current.size &&
            keyedTarget.size == current.size &&
            keyedCurrent.keys == keyedTarget.keys) {
            "UUID mapping has missing/duplicate record keys"
        }
        require(report.migrationCandidates.map { it.cloudId.lowercase(Locale.ROOT) }
            .toSet().size == current.size) {
            "Server UUID targets are not unique"
        }
        require(pending.all { it.action == "upsert" &&
            it.entityType to it.entityKey in keyedCurrent.keys &&
            runCatching { UUID.fromString(it.operationId) }.isSuccess } &&
            pending.map { it.entityType to it.entityKey }.toSet() == keyedCurrent.keys) {
            "Pending operations are not an exact restored upsert queue; refusing quarantine"
        }
        for (mapping in report.migrationCandidates) {
            UUID.fromString(mapping.cloudId)
        }
    }
}

class CloudLocalIdentityStager(context: Context) {
    private val app = context.applicationContext
    private val inspector = CloudReadinessInspector(app)
    private val recovery = LocalMigrationRecovery(app)

    suspend fun stage(): LocalIdentityStageResult = withContext(Dispatchers.IO) {
        val db = MarketDatabase.get(app)
        val cloud = db.cloudDao()
        check(cloud.state(IdentityStagePolicy.SNAPSHOT_STATE).isNullOrBlank()) {
            "Identity migration already staged. Run the Cloud audit or roll back."
        }

        // Fresh authenticated remote snapshot, no service-role key and GET only.
        val checked = inspector.inspect()
        val plan = checked.migrationCandidates
        check(plan.isNotEmpty() &&
            plan.size == checked.migrationDryRun.phoneRecords &&
            checked.migrationDryRun.oneToOneCandidateCoverage &&
            checked.migrationDryRun.contentMatches) {
            "Complete one-to-one Cloud UUID mapping was not established"
        }

        // Write + decrypt/verify an immutable PRIVATE snapshot before any Room
        // identity or queue row is touched.
        val protected = recovery.prepare(checked)
        check(protected.integrityVerified && protected.snapshotFileName.isNotBlank()) {
            "Encrypted local recovery must verify before staging"
        }
        check(protected.identities == plan.size &&
            protected.preservedPending == checked.uuidAudit.pendingUploadOperations) {
            "Backup did not capture all UUID identities and pending operations"
        }

        db.withTransaction {
            check(cloud.state(IdentityStagePolicy.SNAPSHOT_STATE).isNullOrBlank()) {
                "This local migration has already been staged"
            }
            val dao = db.dao()
            val categories = dao.allCategories()
            val items = dao.allItems()
            val req = dao.allRequisitions()
            val purchases = dao.allPurchases()
            val additions = dao.allAdditions()
            val cash = dao.allCashReceived()
            val original = cloud.allIdentities()
            val queue = cloud.pending(Int.MAX_VALUE)
            val states = cloud.allStates()
            val fingerprint = LocalStateFingerprint.calculate(
                categories, items, req, purchases, additions,
                cash, original, queue, states
            )
            check(fingerprint == checked.localRecoveryFingerprint) {
                "Room changed after Cloud audit; nothing was staged"
            }
            val currentCounts = linkedMapOf(
                "categories" to categories.size,
                "items" to items.size,
                "requisitions" to req.size,
                "purchases" to purchases.size,
                "purchase_additions" to additions.size,
                "daily_cash_received" to cash.size
            )
            check(currentCounts == checked.local) {
                "Room record counts changed after Cloud audit"
            }
            IdentityStagePolicy.validate(
                checked, original, queue,
                enabled = states.any {
                    it.name == CloudSyncConfig.ENABLED && it.value == "true"
                },
                enrolled = states.any {
                    it.name == "enrolled" && it.value == "true"
                }
            )

            // Previous UUIDs + pending queue are recoverable from AES-GCM
            // snapshot. These mutations commit atomically, never separately.
            cloud.clearPending()
            cloud.clearIdentities()
            plan.forEach { mapping ->
                check(cloud.insertIdentity(mapping) > 0L) {
                    "Could not persist a unique Cloud UUID mapping"
                }
            }
            check(cloud.allIdentities().size == plan.size &&
                cloud.pendingCountNow() == 0) {
                "Failed to verify staged local UUID identities"
            }
            cloud.setState(CloudSyncStateEntity(
                IdentityStagePolicy.SNAPSHOT_STATE,
                protected.snapshotFileName
            ))
        }
        LocalIdentityStageResult(
            mappedRecords = plan.size,
            quarantinedPending = protected.preservedPending,
            encryptedBackupVerified = true
        )
    }

    suspend fun isStaged(): Boolean = withContext(Dispatchers.IO) {
        !MarketDatabase.get(app).cloudDao()
            .state(IdentityStagePolicy.SNAPSHOT_STATE).isNullOrBlank()
    }

    suspend fun rollback(): LocalIdentityRollbackResult = withContext(Dispatchers.IO) {
        val db = MarketDatabase.get(app)
        val cloud = db.cloudDao()
        val name = cloud.state(IdentityStagePolicy.SNAPSHOT_STATE)
            ?.takeIf { it.isNotBlank() }
            ?: error("No locally staged identity migration to roll back")
        // Restoring is possible only from this app's decrypted + verified
        // backup, never from untrusted JSON or a filename supplied by a user.
        val saved: JSONObject = recovery.readVerifiedSnapshot(name)
        val oldIdentities = saved.getJSONArray("cloud_entity_ids")
        val oldQueue = saved.getJSONArray("cloud_sync_outbox")
        val businessFingerprint = saved.getString("business_fingerprint")
        check(oldIdentities.length() > 0 &&
            oldIdentities.length() == oldQueue.length()) {
            "Encrypted recovery snapshot does not contain a complete restore queue"
        }
        var restoredMappingCount = 0
        var restoredQueueCount = 0

        db.withTransaction {
            check(cloud.state(IdentityStagePolicy.SNAPSHOT_STATE) == name) {
                "Local migration state changed; rollback was blocked"
            }
            val dao = db.dao()
            val states = cloud.allStates()
            check(states.none { it.name in listOf("enrolled", CloudSyncConfig.ENABLED) &&
                it.value == "true" } && cloud.pendingCountNow() == 0) {
                "This device is enrolled or has new pending edits. Rollback blocked."
            }
            val actualBusinessFingerprint = LocalStateFingerprint.calculate(
                dao.allCategories(), dao.allItems(), dao.allRequisitions(),
                dao.allPurchases(), dao.allAdditions(), dao.allCashReceived(),
                emptyList(), emptyList(), emptyList()
            )
            check(actualBusinessFingerprint == businessFingerprint) {
                "Business data changed since backup. Refusing to overwrite pending state."
            }
            check(cloud.allIdentities().size == oldIdentities.length()) {
                "Staged UUID mappings changed. Rollback blocked."
            }

            cloud.clearPending()
            cloud.clearIdentities()
            for (i in 0 until oldIdentities.length()) {
                val row = oldIdentities.getJSONObject(i)
                val identity = CloudIdentityEntity(
                    row.getString("entity_type"),
                    row.getString("entity_key"),
                    row.getString("cloud_id")
                )
                UUID.fromString(identity.cloudId)
                check(cloud.insertIdentity(identity) > 0L) {
                    "Could not restore identity; transaction was rolled back"
                }
                restoredMappingCount++
            }
            for (i in 0 until oldQueue.length()) {
                val row = oldQueue.getJSONObject(i)
                val entry = CloudOutboxEntity(
                    row.getString("entity_type"),
                    row.getString("entity_key"),
                    row.getString("operation_id"),
                    row.getString("action"),
                    row.getLong("changed_at_ms")
                )
                cloud.putPending(entry)
                restoredQueueCount++
            }
            check(cloud.pendingCountNow() == oldQueue.length() &&
                cloud.allIdentities().size == oldIdentities.length()) {
                "Local rollback verification failed"
            }
            cloud.setState(CloudSyncStateEntity(
                IdentityStagePolicy.SNAPSHOT_STATE, ""
            ))
        }
        LocalIdentityRollbackResult(restoredMappingCount, restoredQueueCount)
    }
}
