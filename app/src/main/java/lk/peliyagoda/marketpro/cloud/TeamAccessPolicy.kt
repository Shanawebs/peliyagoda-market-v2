package lk.peliyagoda.marketpro.cloud

import java.time.LocalDate
import java.util.UUID

/**
 * Future two-phone access policy.
 *
 * Staff can create and correct any number of times on the SAME SRI LANKA
 * business day, with no owner review. All earlier days are read-only to staff.
 * Only the owner can edit previous dates.
 *
 * Defense in depth: [serverBusinessToday] and [dateVerifiedByServer] must come
 * from a trusted authenticated backend response. In production the Supabase
 * RPC MUST recompute its own Asia/Colombo date and repeat this check during
 * the database transaction. Never trust a phone clock or client-supplied flag.
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
    val ownerPinVerifiedForThisAction: Boolean = false,
    /** Business date YYYY-MM-DD of the entry being created/edited. */
    val businessDate: String? = null,
    /** Today in Asia/Colombo, obtained from authenticated server. */
    val serverBusinessToday: String? = null,
    val dateVerifiedByServer: Boolean = false
)

data class TeamDecision(val allowed: Boolean, val reason: String)

object TeamAccessPolicy {
    private val dailyCreate = setOf(
        TeamAction.CREATE_PURCHASE, TeamAction.CREATE_REQUISITION,
        TeamAction.CREATE_EXTRA_LOT, TeamAction.ENTER_CASH,
        TeamAction.ENTER_OTHER_EXPENSES
    )
    private val dailyCorrect = setOf(
        TeamAction.CORRECT_PURCHASE, TeamAction.CORRECT_REQUISITION,
        TeamAction.CORRECT_EXTRA_LOT, TeamAction.CORRECT_CASH,
        TeamAction.CORRECT_OTHER_EXPENSES
    )
    private val dailyWrite = dailyCreate + dailyCorrect
    // Device administration and destructive/archive actions remain Owner-only.
    // Normal catalog edits are available to BOTH roles in the same APK.
    private val pinRequired = setOf(
        TeamAction.DELETE_RECORD,
        TeamAction.REGISTER_DEVICE, TeamAction.REVOKE_DEVICE
    )
    private val staffAllowed = dailyWrite + setOf(
        TeamAction.VIEW, TeamAction.EXPORT_REPORT, TeamAction.MODIFY_CATALOG
    )

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString().equals(value, true) }
            .getOrDefault(false)

    private fun isoDateOrNull(value: String?): LocalDate? {
        if (value == null || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
            return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }

    fun authorize(context: TeamAccessContext, action: TeamAction): TeamDecision {
        fun deny(reason: String) = TeamDecision(false, reason)
        if (!isUuid(context.authenticatedUserId) ||
            context.authenticatedUserId != context.authorizedDeviceUserId ||
            !context.accountMembershipVerified || !context.deviceEnabled) {
            return deny("Verified user, active registered device and workspace membership required")
        }
        if (context.role == TeamRole.VIEWER && action !in
            setOf(TeamAction.VIEW, TeamAction.EXPORT_REPORT)) {
            return deny("Viewer account cannot modify market records")
        }
        if (context.role == TeamRole.PURCHASING_STAFF && action !in staffAllowed) {
            return deny("Team can edit today's data, but cannot administer phones or archive protected records")
        }
        if (action in dailyWrite) {
            val today = isoDateOrNull(context.serverBusinessToday)
            val target = isoDateOrNull(context.businessDate)
            if (!context.dateVerifiedByServer || today == null || target == null) {
                return deny("Authenticated Sri Lanka server business date required")
            }
            if (target.isAfter(today)) {
                return deny("Future business dates are not editable")
            }
            if (context.role == TeamRole.PURCHASING_STAFF && target != today) {
                return deny("Previous days are read-only for purchasing staff; owner edit only")
            }
            // Owner may edit any valid earlier date and today's entries.
            // Team may edit today's entries repeatedly without owner approval.
        }
        if (action in pinRequired &&
            (context.role != TeamRole.OWNER || !context.ownerPinVerifiedForThisAction)) {
            return deny("Fresh Owner PIN verification required")
        }
        return TeamDecision(true, "Eligible for authenticated server authorization")
    }
}

/** Revisions remain mandatory; unlimited same-day edits != last-write-wins. */
data class TeamRevisionCheck(
    val creatingNew: Boolean,
    val expectedRevision: Long?,
    val serverRevision: Long?,
    val serverAlreadyHasBusinessKey: Boolean,
    val callerRole: TeamRole
)

object TeamRevisionPolicy {
    fun approve(check: TeamRevisionCheck): TeamDecision {
        if (check.callerRole == TeamRole.VIEWER) {
            return TeamDecision(false, "Viewer must never write")
        }
        if (check.creatingNew) {
            return if (check.expectedRevision == null &&
                check.serverRevision == null &&
                !check.serverAlreadyHasBusinessKey) {
                TeamDecision(true, "New UUID and unique business key; server must validate date")
            } else TeamDecision(false, "Duplicate business key or non-new record")
        }
        if (check.serverRevision == null || check.expectedRevision == null ||
            check.serverRevision < 1 || check.expectedRevision < 1 ||
            check.expectedRevision != check.serverRevision) {
            return TeamDecision(false, "Cloud revision changed; reload before editing again")
        }
        return TeamDecision(true, "Version matches; server must recheck date and revision atomically")
    }
}
