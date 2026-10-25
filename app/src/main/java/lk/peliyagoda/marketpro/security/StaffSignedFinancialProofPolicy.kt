package lk.peliyagoda.marketpro.security

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Exact AndroidKeystore ECDSA P-256 V3 16-line contract. Never use v1 generic
 * "proof verified" response as a business mutation permission.
 */
internal data class StaffSignedFinancialChallenge(
    val deviceChallenge:StaffDeviceChallenge,
    val financialType:String,
    val operationId:String,
    val entityId:String,
    val financialAction:String,
    val expectedRevision:Long?,
    val operationSha256:String,
    val proofVersion:String
) {
    override fun toString()="StaffSignedFinancialChallenge([REDACTED],type=$financialType)"
}

internal object StaffSignedFinancialProofPolicy {
    const val VERSION="marketpro-device-proof-financial-v3"
    val types=setOf(
        "requisition","purchase_addition","daily_cash_received","daily_other_expenses"
    )
    private fun uuid(v:String)=runCatching {
        UUID.fromString(v).toString().equals(v,true)
    }.getOrDefault(false)

    fun transcript(c:StaffSignedFinancialChallenge,nowMillis:Long):ByteArray {
        val b=c.deviceChallenge
        require(StaffDeviceProofPolicy.valid(b,nowMillis) && b.purpose=="write" &&
            c.proofVersion==VERSION && c.financialType in types &&
            uuid(c.operationId) && uuid(c.entityId) &&
            Regex("[0-9a-f]{64}").matches(c.operationSha256) &&
            ((c.financialAction=="create" && c.expectedRevision==null) ||
             (c.financialAction=="correct" &&
              c.expectedRevision!=null && c.expectedRevision>0L))) {
            "Untrusted signed financial operation"
        }
        val parent=String(StaffDeviceProofPolicy.transcript(b,nowMillis),
            StandardCharsets.US_ASCII).split("\n")
        require(parent.size==10)
        return (listOf(VERSION)+parent.drop(1)+listOf(
            c.financialType,c.operationId,c.entityId,c.financialAction,
            c.expectedRevision?.toString()?:"null",c.operationSha256
        )).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
    }
}
