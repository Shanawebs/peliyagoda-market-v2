package lk.peliyagoda.marketpro.security

import java.nio.charset.StandardCharsets

/**
 * Exact Android transcript for the independently audited Edge/Pg17 v2
 * purchase challenge. NO reusable "proof_verified_once" tickets.
 *
 * SHA256 is returned by PostgreSQL over canonical JSONB; Android MUST sign
 * those exact bytes rather than guess the server JSONB serialization.
 */
internal data class StaffSignedPurchaseChallenge(
    val deviceChallenge: StaffDeviceChallenge,
    val operationId: String,
    val entityId: String,
    val financialAction: String,
    val expectedRevision: Long?,
    val operationSha256: String,
    val proofVersion: String
) {
    override fun toString(): String =
        "StaffSignedPurchaseChallenge([REDACTED],action=$financialAction)"
}

internal object StaffSignedPurchaseProofPolicy {
    private const val VERSION = "marketpro-device-proof-purchase-v2"
    private val shaPattern=Regex("[0-9a-f]{64}")

    fun transcript(c:StaffSignedPurchaseChallenge,nowMillis:Long):ByteArray {
        val b=c.deviceChallenge
        require(StaffDeviceProofPolicy.valid(b,nowMillis) && b.purpose=="write" &&
            c.proofVersion==VERSION &&
            TeamUuidCheck.valid(c.operationId) && TeamUuidCheck.valid(c.entityId) &&
            shaPattern.matches(c.operationSha256) &&
            ((c.financialAction=="create" && c.expectedRevision==null) ||
             (c.financialAction=="correct" &&
              c.expectedRevision!=null && c.expectedRevision>0L))) {
            "Untrusted signed purchase context"
        }
        val previous=String(StaffDeviceProofPolicy.transcript(b,nowMillis),
            StandardCharsets.US_ASCII).split("\n")
        require(previous.size==10)
        return (listOf(VERSION)+previous.drop(1)+listOf(
            c.operationId,c.entityId,c.financialAction,
            c.expectedRevision?.toString()?:"null",c.operationSha256
        )).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
    }

    /** Pure UUID validation does not depend on Android context, time or locale. */
    private object TeamUuidCheck {
        fun valid(value:String):Boolean=runCatching {
            java.util.UUID.fromString(value).toString().equals(value,ignoreCase=true)
        }.getOrDefault(false)
    }
}
