package lk.peliyagoda.marketpro.cloud

import java.util.UUID

/**
 * Core multi-phone permission and revision rules, independent of Android UI.
 * This client gate is defense in depth ONLY: future server-side authenticated
 * RPC must repeat all checks atomically with row changes.
 */
enum class TeamRole { OWNER, PURCHASING_STAFF, VIEWER }
enum class TeamAction {
    VIEW,
    CREATE_PURCHASE, CREATE_REQUISITION, CREATE_EXTRA_LOT,
    ENTER_CASH, ENTER_OTHER_EXPENSES,
    CORRECT_PURCHASE, CORRECT_REQUISITION, CORRECT_EXTRA_LOT,
    CORRECT_CASH, CORRECT_OTHER_EXPENSES,
    MODIFY_CATALOG, DELETE_RECORD, EXPORT_REPORT,
    REGISTER_DEVICE, REVOKE_DEVICE
}

data class TeamAccessContext(
    val role: TeamRole,
    val authenticatedUserId: String,
    val authorizedDeviceUserId: String,
    val deviceEnabled: Boolean,
    val accountMembershipVerified: Boolean,
    val ownerPinVerifiedForThisAction: Boolean = false
)

data class TeamDecision(val allowed: Boolean, val reason: String)

object TeamAccessPolicy {
    private val teamInput = setOf(
        TeamAction.CREATE_PURCHASE, TeamAction.CREATE_REQUISITION,
        TeamAction.CREATE_EXTRA_LOT, TeamAction.ENTER_CASH,
        TeamAction.ENTER_OTHER_EXPENSES
    )
    private val ownerOnly = setOf(
        TeamAction.CORRECT_PURCHASE, TeamAction.CORRECT_REQUISITION,
        TeamAction.CORRECT_EXTRA_LOT, TeamAction.CORRECT_CASH,
        TeamAction.CORRECT_OTHER_EXPENSES, TeamAction.MODIFY_CATALOG,
        TeamAction.DELETE_RECORD, TeamAction.REGISTER_DEVICE,
        TeamAction.REVOKE_DEVICE
    )
    private val pinRequired = setOf(
        TeamAction.MODIFY_CATALOG, TeamAction.DELETE_RECORD,
        TeamAction.REGISTER_DEVICE, TeamAction.REVOKE_DEVICE
    )
    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString().equals(value, true) }
            .getOrDefault(false)

    fun authorize(context: TeamAccessContext, action: TeamAction): TeamDecision {
        fun deny(reason: String) = TeamDecision(false, reason)
        if (!isUuid(context.authenticatedUserId) ||
            context.authenticatedUserId != context.authorizedDeviceUserId ||
            !context.accountMembershipVerified || !context.deviceEnabled) {
            return deny("Verified user, active registered device and workspace membership required")
        }
        if (context.role == TeamRole.VIEWER && action !in
            setOf(TeamAction.VIEW, TeamAction.EXPORT_REPORT)) {
            return deny("Viewer account cannot enter or modify market records")
        }
        if (context.role == TeamRole.PURCHASING_STAFF && action !in
            teamInput + setOf(TeamAction.VIEW, TeamAction.EXPORT_REPORT)) {
            return deny("Purchasing staff cannot correct owner-reviewed data or change security")
        }
        if (context.role == TeamRole.OWNER && action in pinRequired &&
            !context.ownerPinVerifiedForThisAction) {
            return deny("Fresh owner PIN confirmation required")
        }
        return TeamDecision(true, "Eligible for authenticated server authorization")
    }
}

/** A version check prevents late/offline staff edits overwriting owner corrections. */
data class TeamRevisionCheck(
    val creatingNew: Boolean,
    val expectedRevision: Long?,
    val serverRevision: Long?,
    val serverAlreadyHasBusinessKey: Boolean,
    val ownerReviewed: Boolean,
    val callerRole: TeamRole
)
object TeamRevisionPolicy {
    fun approve(check: TeamRevisionCheck): TeamDecision {
        if (check.creatingNew) {
            return if (check.expectedRevision == null &&
                check.serverRevision == null &&
                !check.serverAlreadyHasBusinessKey) {
                TeamDecision(true, "New stable UUID and unique business key required")
            } else TeamDecision(false, "Duplicate purchase/requisition business key")
        }
        if (check.ownerReviewed && check.callerRole != TeamRole.OWNER) {
            return TeamDecision(false, "Owner-reviewed record is locked to purchasing staff")
        }
        if (check.serverRevision == null || check.expectedRevision == null ||
            check.serverRevision < 1 || check.expectedRevision < 1 ||
            check.expectedRevision != check.serverRevision) {
            return TeamDecision(false, "Cloud revision changed; refresh before saving")
        }
        return TeamDecision(true, "Version precondition met; server must recheck atomically")
    }
}
