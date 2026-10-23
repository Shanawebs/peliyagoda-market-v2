package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * SHA-256 over all Room content, identity mapping, and outbox; an in-place edit
 * invalidates an old cloud audit even when its row counts stay unchanged.
 */
internal object LocalStateFingerprint {
    fun calculate(
        categories: List<CategoryEntity>,
        items: List<ItemEntity>,
        requisitions: List<RequisitionEntity>,
        purchases: List<PurchaseEntity>,
        additions: List<PurchaseAdditionEntity>,
        cash: List<DailyCashReceivedEntity>,
        identities: List<CloudIdentityEntity>,
        outbox: List<CloudOutboxEntity>,
        states: List<CloudSyncStateEntity>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun part(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        fun <T> group(label: String, rows: List<T>, key: (T) -> String,
                      value: (T) -> String) {
            part(label)
            for (row in rows.sortedBy(key)) part(value(row))
            part("END:" + label)
        }
        group("categories", categories, { it.name }, { it.name })
        group("items", items, { it.id.toString().padStart(20, '0') }) {
            listOf(it.id, it.name, it.category, it.isActive).joinToString("|")
        }
        group("requisitions", requisitions, { listOf(it.businessDate, it.kitchen, it.itemId).joinToString("|") }) {
            listOf(it.businessDate, it.kitchen, it.itemId, it.quantityGrams).joinToString("|")
        }
        group("purchases", purchases, { listOf(it.businessDate, it.itemId).joinToString("|") }) {
            listOf(it.businessDate, it.itemId, it.quantityGrams, it.priceCentsPerKg).joinToString("|")
        }
        group("additions", additions, { it.id.toString().padStart(20, '0') }) {
            listOf(it.id, it.businessDate, it.itemId, it.quantityGrams, it.priceCentsPerKg).joinToString("|")
        }
        group("cash", cash, { it.businessDate }) {
            listOf(it.businessDate, it.amountCents).joinToString("|")
        }
        group("identities", identities, { listOf(it.entityType, it.entityKey).joinToString("|") }) {
            listOf(it.entityType, it.entityKey, it.cloudId).joinToString("|")
        }
        group("outbox", outbox, { listOf(it.entityType, it.entityKey).joinToString("|") }) {
            listOf(it.entityType, it.entityKey, it.operationId, it.action, it.changedAtMs).joinToString("|")
        }
        group("states", states, { it.name }) {
            listOf(it.name, it.value).joinToString("|")
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
