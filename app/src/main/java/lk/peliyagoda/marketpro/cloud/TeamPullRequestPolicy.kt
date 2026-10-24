package lk.peliyagoda.marketpro.cloud

import java.time.OffsetDateTime
import java.util.UUID

/** Pure request/feature gate: app + server both have to be upgraded first. */
object TeamPullReadiness {
    /** Compile-time safeguards, plus the authenticated server verifies JWT and device itself. */
    fun ready(
        localPreview: Boolean,
        rpcDeployedAndAudited: Boolean,
        phoneEnrolled: Boolean,
        catalogMappingVerified: Boolean
    ): Boolean = !localPreview && rpcDeployedAndAudited &&
        phoneEnrolled && catalogMappingVerified
}

/** These are NOT credentials or evidence of server enrollment by themselves. */
data class TeamPullSession(
    val accessToken: String,
    val authenticatedUserId: String,
    val enrolledDeviceId: String,
    val workspaceId: String
) {
    override fun toString(): String =
        "TeamPullSession(user=$authenticatedUserId, device=$enrolledDeviceId, " +
            "workspace=$workspaceId, accessToken=[REDACTED])"
}

data class TeamPullPageRequest(
    val workspaceId: String,
    val deviceId: String,
    val entityType: String,
    val afterUpdatedAt: String?,
    val afterCloudId: String?,
    val pageSize: Int
)

/** Whitelist and strict nullable cursor pairing. No dynamic URL or table names. */
object TeamPullRequestPolicy {
    private val allowed = listOf(
        "category", "item", "requisition", "purchase",
        "purchase_addition", "daily_cash_received", "daily_other_expenses"
    )
    val orderedTypes: List<String> get() = allowed.toList()

    fun validate(
        session: TeamPullSession,
        request: TeamPullPageRequest,
        physicalDeviceId: String
    ) {
        require(session.accessToken.length in 20..8192) {
            "Missing or invalid transient access token"
        }
        require(CloudInboxPagePolicy.uuid(session.authenticatedUserId))
        require(CloudInboxPagePolicy.uuid(session.enrolledDeviceId))
        require(CloudInboxPagePolicy.uuid(session.workspaceId))
        require(CloudInboxPagePolicy.uuid(physicalDeviceId))
        require(session.enrolledDeviceId == physicalDeviceId) {
            "Token context does not match this physical device"
        }
        require(session.workspaceId == request.workspaceId &&
            session.enrolledDeviceId == request.deviceId
        ) { "Workspace or device mismatch" }
        require(request.entityType in allowed)
        require(request.pageSize in 1..50)
        require((request.afterUpdatedAt == null) == (request.afterCloudId == null)) {
            "Incomplete keyset cursor"
        }
        if (request.afterUpdatedAt != null) {
            require(CloudInboxPagePolicy.uuid(requireNotNull(request.afterCloudId)))
            OffsetDateTime.parse(request.afterUpdatedAt)
        }
    }
}
