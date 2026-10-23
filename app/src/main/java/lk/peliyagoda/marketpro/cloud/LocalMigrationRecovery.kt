package lk.peliyagoda.marketpro.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import lk.peliyagoda.marketpro.data.MarketDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * V3.3.3 LOCAL BACKUP PREPARATION ONLY.
 *
 * Captures every Room business record, stable cloud identity, pending operation
 * and local sync flag in one Room read transaction. The file is encrypted using
 * AndroidKeyStore AES-GCM and written atomically in noBackupFilesDir.
 *
 * DOES NOT change any database row, UUID mapping, pending operation, sync flag,
 * Supabase Cloud record, device registration or Cloud Sync scheduler setting.
 *
 * This private file is not portable: Android uninstallation removes its key.
 * Keep the user's separate external JSON backup and old working app installed.
 */
data class LocalRecoveryReceipt(
    val businessRecords: Int,
    val identities: Int,
    val preservedPending: Int,
    val bytesOnDisk: Long,
    val integrityVerified: Boolean,
    val totalSavedCopies: Int
)

/** Pure gate: changed, incomplete or enrolled datasets cannot be backed up as "ready". */
internal object LocalRecoveryGate {
    fun check(
        report: CloudPreflightReport,
        actualCounts: Map<String, Int>,
        actualIdentities: Int,
        actualPending: Int,
        actualFingerprint: String,
        enabled: Boolean,
        enrolled: Boolean
    ) {
        require(!enabled && !enrolled && !report.deviceRegisteredAsWriter) {
            "Device is enrolled or sync enabled; snapshot is not a safe preview"
        }
        require(report.catalogAudit.fullyMatched && report.financialAudit.fullyMatched) {
            "Phone data does not match cloud content"
        }
        require(report.migrationDryRun.oneToOneCandidateCoverage &&
            report.migrationDryRun.contentMatches &&
            report.migrationDryRun.unsafeOrUnmapped == 0) {
            "Migration dry run has missing or ambiguous mappings"
        }
        require(report.migrationDryRun.cutoverBlocked) {
            "Preview cutover guard must remain locked"
        }
        require(report.local == actualCounts) {
            "Phone records changed since audit: rerun the cloud check"
        }
        require(report.localRecoveryFingerprint == actualFingerprint) {
            "Local record values or pending operation IDs changed; rerun Cloud Check"
        }
        require(report.uuidAudit.pendingUploadOperations == actualPending) {
            "Pending operations changed since audit: rerun the cloud check"
        }
        require(actualIdentities == report.migrationDryRun.phoneRecords) {
            "Local identity count does not match inspected records"
        }
        require(actualPending >= 0)
    }

    fun matchesPlaintext(snapshot: ByteArray, restored: ByteArray): Boolean =
        MessageDigest.isEqual(sha256(snapshot), sha256(restored)) &&
            snapshot.contentEquals(restored)

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

class LocalMigrationRecovery(private val context: Context) {
    private val app = context.applicationContext

    private data class Prepared(
        val plaintext: ByteArray,
        val totalRecords: Int,
        val identities: Int,
        val outbox: Int
    )

    private fun <T> array(rows: List<T>, make: (T) -> JSONObject): JSONArray =
        JSONArray().apply { rows.forEach { put(make(it)) } }

    private suspend fun buildSnapshot(report: CloudPreflightReport): Prepared {
        val db = MarketDatabase.get(app)
        return db.withTransaction {
            val dao = db.dao()
            val cloud = db.cloudDao()
            val categories = dao.allCategories()
            val items = dao.allItems()
            val requisitions = dao.allRequisitions()
            val purchases = dao.allPurchases()
            val additions = dao.allAdditions()
            val cash = dao.allCashReceived()
            val identities = cloud.allIdentities()
            val outbox = cloud.pending(Int.MAX_VALUE)
            val states = cloud.allStates()
            val counts = linkedMapOf(
                "categories" to categories.size,
                "items" to items.size,
                "requisitions" to requisitions.size,
                "purchases" to purchases.size,
                "purchase_additions" to additions.size,
                "daily_cash_received" to cash.size
            )
            val actualFingerprint = LocalStateFingerprint.calculate(
                categories, items, requisitions, purchases, additions,
                cash, identities, outbox, states
            )
            LocalRecoveryGate.check(
                report, counts, identities.size, outbox.size,
                actualFingerprint = actualFingerprint,
                enabled = states.any { it.name == CloudSyncConfig.ENABLED && it.value == "true" },
                enrolled = states.any { it.name == "enrolled" && it.value == "true" }
            )

            // The serialized snapshot contains ALL restored rows, including
            // archived items, their locally assigned UUIDs and 330 unsent ops.
            val snapshot = JSONObject()
                .put("format", "marketpro-local-recovery-v1")
                .put("created_at_ms", System.currentTimeMillis())
                .put("package_name", app.packageName)
                .put("cloud_upload_locked", true)
                .put("categories", array(categories) {
                    JSONObject().put("name", it.name)
                })
                .put("items", array(items) {
                    JSONObject().put("id", it.id).put("name", it.name)
                        .put("category", it.category).put("is_active", it.isActive)
                })
                .put("requisitions", array(requisitions) {
                    JSONObject().put("business_date", it.businessDate)
                        .put("kitchen", it.kitchen).put("item_id", it.itemId)
                        .put("quantity_grams", it.quantityGrams)
                })
                .put("purchases", array(purchases) {
                    JSONObject().put("business_date", it.businessDate)
                        .put("item_id", it.itemId).put("quantity_grams", it.quantityGrams)
                        .put("price_cents_per_kg", it.priceCentsPerKg)
                })
                .put("purchase_additions", array(additions) {
                    JSONObject().put("id", it.id)
                        .put("business_date", it.businessDate).put("item_id", it.itemId)
                        .put("quantity_grams", it.quantityGrams)
                        .put("price_cents_per_kg", it.priceCentsPerKg)
                })
                .put("daily_cash_received", array(cash) {
                    JSONObject().put("business_date", it.businessDate)
                        .put("amount_cents", it.amountCents)
                })
                .put("cloud_entity_ids", array(identities) {
                    JSONObject().put("entity_type", it.entityType)
                        .put("entity_key", it.entityKey).put("cloud_id", it.cloudId)
                })
                .put("cloud_sync_outbox", array(outbox) {
                    JSONObject().put("entity_type", it.entityType)
                        .put("entity_key", it.entityKey).put("operation_id", it.operationId)
                        .put("action", it.action).put("changed_at_ms", it.changedAtMs)
                })
                .put("cloud_sync_state", array(states) {
                    JSONObject().put("name", it.name).put("value", it.value)
                })
            Prepared(
                plaintext = snapshot.toString().toByteArray(Charsets.UTF_8),
                totalRecords = counts.values.sum(),
                identities = identities.size,
                outbox = outbox.size
            )
        }
    }

    private fun encryptionKey(): SecretKey {
        val alias = "marketpro_expressive_v333_recovery_aes"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("marketpro-local-recovery-v1".toByteArray(Charsets.UTF_8))
        // fixed magic + 12-byte random AES-GCM IV + authenticated ciphertext
        check(cipher.iv.size == 12)
        return "MPR1".toByteArray(Charsets.US_ASCII) +
            cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(blob: ByteArray, key: SecretKey): ByteArray {
        if (blob.size < 4 + 12 + 16 ||
            !blob.copyOfRange(0, 4).contentEquals(
                "MPR1".toByteArray(Charsets.US_ASCII)
            )) throw IOException("Recovery snapshot format invalid")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(128, blob.copyOfRange(4, 16))
        )
        cipher.updateAAD("marketpro-local-recovery-v1".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob.copyOfRange(16, blob.size))
    }

    suspend fun prepare(report: CloudPreflightReport): LocalRecoveryReceipt =
        withContext(Dispatchers.IO) {
            val prepared = buildSnapshot(report)
            val folder = File(app.noBackupFilesDir, "marketpro-migration-recovery")
            check(folder.isDirectory || folder.mkdirs()) {
                "Cannot make private recovery directory"
            }
            val saved = folder.listFiles { f -> f.name.endsWith(".mpr") }
                ?.size ?: throw IOException("Cannot inspect recovery files")
            check(saved < 5) {
                "Five recovery copies already exist. Keep them safe; do not auto-delete."
            }
            val key = encryptionKey()
            val encrypted = encrypt(prepared.plaintext, key)
            val name = "local-v333-" + System.currentTimeMillis() +
                "-" + UUID.randomUUID().toString() + ".mpr"
            val target = File(folder, name)
            val temporary = File(folder, ".$name.tmp")
            try {
                FileOutputStream(temporary).use {
                    it.write(encrypted)
                    it.fd.sync()
                }
                check(temporary.renameTo(target)) {
                    "Could not atomically finalize recovery snapshot"
                }
                val recovered = decrypt(target.readBytes(), key)
                check(LocalRecoveryGate.matchesPlaintext(prepared.plaintext, recovered)) {
                    "Recovery snapshot integrity verification failed"
                }
                LocalRecoveryReceipt(
                    businessRecords = prepared.totalRecords,
                    identities = prepared.identities,
                    preservedPending = prepared.outbox,
                    bytesOnDisk = target.length(),
                    integrityVerified = true,
                    totalSavedCopies = saved + 1
                )
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                temporary.delete()
            }
        }
}
