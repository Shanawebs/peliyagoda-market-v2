package lk.peliyagoda.marketpro.cloud

/**
 * Pure, testable authorization boundary for the NEXT-stage (still disabled)
 * device-bound review transport. App checks do not replace the server's JWT,
 * role, device enrollment, revocation and Cloud revision enforcement.
 */
internal enum class CatalogReviewAction {
    STAFF_SUBMIT, OWNER_LIST, OWNER_APPROVE
}
internal enum class CatalogReviewActorRole { STAFF, OWNER }

internal data class CatalogReviewNetworkSession(
    val token: String,
    val userUuid: String,
    val deviceUuid: String,
    val workspaceUuid: String,
    val role: CatalogReviewActorRole
) {
    // Kotlin data classes otherwise print all constructor properties by default.
    override fun toString() =
        "CatalogReviewNetworkSession(user=$userUuid, device=$deviceUuid, role=$role, token=[REDACTED])"
}

internal object CatalogReviewNetworkPolicy {
    fun canEnable(
        offlinePreview: Boolean,
        teamRpcDeployed: Boolean,
        staffLoginEnabled: Boolean,
        candidateRpcReviewedAndDeployed: Boolean
    ): Boolean = !offlinePreview && teamRpcDeployed &&
        staffLoginEnabled && candidateRpcReviewedAndDeployed

    fun validate(
        session: CatalogReviewNetworkSession,
        actualDeviceUuid: String,
        expectedWorkspaceUuid: String,
        action: CatalogReviewAction
    ) {
        require(session.token.length in 20..8192 &&
            session.token.none { it == '\n' || it == '\r' }) {
            "Invalid transient session token"
        }
        require(CloudInboxPagePolicy.uuid(session.userUuid) &&
            CloudInboxPagePolicy.uuid(session.deviceUuid) &&
            CloudInboxPagePolicy.uuid(session.workspaceUuid) &&
            CloudInboxPagePolicy.uuid(actualDeviceUuid) &&
            CloudInboxPagePolicy.uuid(expectedWorkspaceUuid))
        require(session.deviceUuid == actualDeviceUuid &&
            session.workspaceUuid == expectedWorkspaceUuid)
        require(
            if (action == CatalogReviewAction.STAFF_SUBMIT)
                session.role == CatalogReviewActorRole.STAFF
            else session.role == CatalogReviewActorRole.OWNER
        ) { "Incorrect role for Catalog Review action" }
    }

    fun validCursor(cursor: String?) {
        require(cursor == null || CloudInboxPagePolicy.uuid(cursor)) {
            "Untrusted Owner Review cursor UUID"
        }
    }

    fun validPageSize(limit: Int) {
        require(limit in 1..50)
    }
}
