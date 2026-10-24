package lk.peliyagoda.marketpro.cloud

import java.util.UUID

/**
 * Server claims are verified by the backend, never derived from a 6-digit
 * device PIN, phone date, user-supplied role or editable Android preferences.
 */
enum class StaffEnrollmentState {
    NO_INVITATION,
    INVITED_NOT_ACCEPTED,
    ACCEPTED_AWAITING_OWNER_ACTIVATION,
    ENROLLED_READ_ONLY,
    ENROLLED_WRITER,
    REVOKED
}

data class StaffEnrollmentEvidence(
    val role: TeamRole,
    val sessionUserId: String,
    val invitationUserId: String,
    val physicalDeviceId: String,
    val invitationDeviceId: String,
    val ownerAccountUserId: String,
    val verifiedAuthEmail: Boolean,
    val authenticatedServerVerified: Boolean,
    val serverMembershipVerified: Boolean,
    val enrolledDeviceEnabled: Boolean,
    val serverCanWrite: Boolean,
    val serverTeamRpcDeployed: Boolean,
    val localPreview: Boolean,
    val state: StaffEnrollmentState
)

enum class StaffEnrollmentOutcome {
    NO_ACCESS,
    WAIT_FOR_INVITATION,
    WAIT_FOR_OWNER_AND_SERVER_CUTOVER,
    READ_ONLY,
    STAFF_WRITER
}

object TeamStaffEnrollmentPolicy {
    private fun validUuid(input: String) = runCatching {
        UUID.fromString(input).toString().equals(input, ignoreCase=true)
    }.getOrDefault(false)

    fun evaluate(e: StaffEnrollmentEvidence): StaffEnrollmentOutcome {
        if (e.role != TeamRole.PURCHASING_STAFF ||
            !validUuid(e.sessionUserId) || !validUuid(e.invitationUserId) ||
            !validUuid(e.physicalDeviceId) || !validUuid(e.invitationDeviceId) ||
            !validUuid(e.ownerAccountUserId) ||
            e.sessionUserId != e.invitationUserId ||
            e.physicalDeviceId != e.invitationDeviceId ||
            e.sessionUserId == e.ownerAccountUserId ||
            !e.verifiedAuthEmail || !e.authenticatedServerVerified ||
            e.state == StaffEnrollmentState.REVOKED) {
            return StaffEnrollmentOutcome.NO_ACCESS
        }
        if (e.state in setOf(
                StaffEnrollmentState.NO_INVITATION,
                StaffEnrollmentState.INVITED_NOT_ACCEPTED
            )) return StaffEnrollmentOutcome.WAIT_FOR_INVITATION
        if (e.localPreview || !e.serverTeamRpcDeployed ||
            !e.serverMembershipVerified || !e.enrolledDeviceEnabled ||
            e.state == StaffEnrollmentState.ACCEPTED_AWAITING_OWNER_ACTIVATION) {
            return StaffEnrollmentOutcome.WAIT_FOR_OWNER_AND_SERVER_CUTOVER
        }
        return if (e.state == StaffEnrollmentState.ENROLLED_WRITER && e.serverCanWrite)
            StaffEnrollmentOutcome.STAFF_WRITER
        else StaffEnrollmentOutcome.READ_ONLY
    }
}
