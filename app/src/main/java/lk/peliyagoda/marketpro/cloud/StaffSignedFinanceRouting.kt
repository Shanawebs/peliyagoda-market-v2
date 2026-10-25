package lk.peliyagoda.marketpro.cloud

/**
 * The Staff finance journal contains EXACTLY five allowlisted financial
 * entities. Every upload is cryptographically signed on the physical Staff
 * AndroidKeystore; no Staff request is sent through the legacy Owner writer.
 */
internal object StaffSignedFinanceRoutingPolicy {
    enum class Channel { PURCHASE_V2, FINANCIAL_V3 }
    private val all = setOf(
        "purchase", "requisition", "purchase_addition",
        "daily_cash_received", "daily_other_expenses"
    )

    fun channel(entityType:String):Channel = when(entityType) {
        "purchase" -> Channel.PURCHASE_V2
        "requisition","purchase_addition","daily_cash_received",
        "daily_other_expenses" -> Channel.FINANCIAL_V3
        else -> throw IllegalArgumentException(
            "Only five proof-bound financial entities may be uploaded"
        )
    }

    fun allTypes():Set<String> = all

    /**
     * This is a *release* prerequisite check, not a local authorization.
     * Staff JWT, session, lease, Owner approved key and Colombo business
     * date are independently rechecked inside every server transaction.
     */
    fun ready(
        preview:Boolean, staffLogin:Boolean, staffPull:Boolean,
        pairing:Boolean, ownerMfa:Boolean, keyEnrollment:Boolean,
        writerApproval:Boolean, financeRpc:Boolean,
        serverProof:Boolean, autoUpload:Boolean
    ):Boolean =
        !preview && staffLogin && staffPull && pairing &&
        ownerMfa && keyEnrollment && writerApproval &&
        financeRpc && serverProof && autoUpload
}

internal class SignedAllStaffFinancialWriteTransport(
    private val purchase:StaffProofBoundWriteTransport =
        ProofBoundStaffPurchaseHttpTransport(),
    private val otherFinance:StaffProofBoundWriteTransport =
        ProofBoundStaffFinancialHttpTransport()
):StaffProofBoundWriteTransport {
    override suspend fun submit(
        session:TeamPullSession,
        plan:StaffOutboxUploadPolicy.Plan,
        payload:org.json.JSONObject
    ):StaffServerWriteReceipt {
        // Exact routing, no fallback from signed endpoint failure to Owner
        // legacy CloudSyncEngine or unsecured direct PostgREST.
        return when(StaffSignedFinanceRoutingPolicy.channel(plan.entityType)) {
            StaffSignedFinanceRoutingPolicy.Channel.PURCHASE_V2 ->
                purchase.submit(session,plan,payload)
            StaffSignedFinanceRoutingPolicy.Channel.FINANCIAL_V3 ->
                otherFinance.submit(session,plan,payload)
        }
    }
}
