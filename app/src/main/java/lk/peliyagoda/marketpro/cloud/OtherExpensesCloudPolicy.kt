package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.data.OtherExpenseAmounts
import java.time.LocalDate
import java.util.UUID

/**
 * Review-only bridge between Room daily_other_expenses and the new server RPC.
 * NOT wired to WorkManager; no Staff upload until Auth/device proofs and the
 * revision-aware cloud inbox are deployed and authenticated.
 */
internal data class OtherExpensesCloudDraft(
    val workspaceId: String,
    val deviceId: String,
    val operationId: String,
    val cloudId: String,
    val businessDate: String,
    val amounts: OtherExpenseAmounts,
    val action: ExpenseCloudAction,
    val expectedRevision: Long?
)

internal enum class ExpenseCloudAction { CREATE, CORRECT }

internal object OtherExpensesCloudPolicy {
    fun networkReady(
        localPreview: Boolean,
        ownerOrStaffSignedIn: Boolean,
        serverRpcAudited: Boolean,
        verifiedDevice: Boolean,
        revisionInboxReady: Boolean
    ): Boolean = !localPreview && ownerOrStaffSignedIn && serverRpcAudited &&
        verifiedDevice && revisionInboxReady

    fun validate(
        draft: OtherExpensesCloudDraft,
        access: TeamAccessContext,
        serverRevision: Long?,
        businessKeyAlreadyExists: Boolean
    ): Map<String, Any> {
        require(listOf(draft.workspaceId,draft.deviceId,
            draft.operationId,draft.cloudId).all { value ->
                runCatching { UUID.fromString(value).toString()
                    .equals(value,ignoreCase = true) }.getOrDefault(false)
        }) { "Authenticated Cloud record identifiers required" }
        require(runCatching { draft.businessDate == LocalDate.parse(draft.businessDate).toString() }.getOrDefault(false)) {
            "Invalid expense business date"
        }
        require(access.businessDate == draft.businessDate)
        val operation = if (draft.action==ExpenseCloudAction.CREATE)
            TeamAction.ENTER_OTHER_EXPENSES
        else TeamAction.CORRECT_OTHER_EXPENSES
        val accessResult=TeamAccessPolicy.authorize(access,operation)
        require(accessResult.allowed) {
            "Authenticated role and Sri Lanka server date required"
        }
        val revisionResult=TeamRevisionPolicy.approve(TeamRevisionCheck(
            creatingNew = draft.action==ExpenseCloudAction.CREATE,
            expectedRevision=draft.expectedRevision,
            serverRevision=serverRevision,
            serverAlreadyHasBusinessKey=businessKeyAlreadyExists,
            callerRole=access.role
        ))
        require(revisionResult.allowed) {
            "Cloud revision or expense identity changed"
        }

        // Mirrors the exact six-column server whitelist; never take photos,
        // Android file paths or arbitrary user JSON fields.
        return linkedMapOf(
            "business_date" to draft.businessDate,
            "transport_cents" to draft.amounts.transportCents,
            "labor_charge_cents" to draft.amounts.laborChargeCents,
            "meals_drinks_cents" to draft.amounts.mealsDrinksCents,
            "others_cents" to draft.amounts.othersCents,
            "remarks" to draft.amounts.remarks
        )
    }
}
