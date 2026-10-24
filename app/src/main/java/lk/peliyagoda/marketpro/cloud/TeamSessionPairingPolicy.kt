package lk.peliyagoda.marketpro.cloud

import java.util.UUID

/**
 * Pure device-pairing preflight. Success here NEVER enrolls a device or
 * grants a workspace role: only authenticated server RPCs may do so.
 */
internal object TeamSessionPairingPolicy {
    private val oneTimeCode = Regex("[0-9a-f]{64}")

    fun validUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    fun deploymentReady(
        offlinePreview: Boolean,
        staffLoginEnabled: Boolean,
        teamPullDeployed: Boolean,
        pairingRpcDeployed: Boolean
    ): Boolean = !offlinePreview && staffLoginEnabled &&
        teamPullDeployed && pairingRpcDeployed

    fun validateStaffClaim(
        workspaceId: String,
        sessionUserId: String,
        ownerUserId: String,
        registeredLocalDeviceId: String,
        requestedDeviceId: String,
        code: String
    ) {
        require(validUuid(workspaceId) && validUuid(sessionUserId) &&
            validUuid(ownerUserId) && validUuid(registeredLocalDeviceId) &&
            validUuid(requestedDeviceId)) { "Valid identity required" }
        require(sessionUserId != ownerUserId) {
            "Owner login cannot be used to pair the Purchasing Team phone"
        }
        require(registeredLocalDeviceId == requestedDeviceId) {
            "Pairing is tied to this device, not a copied Phone ID"
        }
        require(oneTimeCode.matches(code)) {
            "A fresh 64-character one-time Owner invitation is required"
        }
    }

    fun validateOwnerInvite(
        workspaceId: String,
        ownerUserId: String,
        authenticatedOwnerId: String,
        installedOwnerDeviceId: String,
        requestOwnerDeviceId: String,
        staffUserId: String,
        staffDeviceId: String,
        label: String
    ) {
        require(listOf(workspaceId,ownerUserId,authenticatedOwnerId,
            installedOwnerDeviceId,requestOwnerDeviceId,staffUserId,
            staffDeviceId).all(::validUuid)) { "Valid device and user IDs required" }
        require(ownerUserId == authenticatedOwnerId &&
            installedOwnerDeviceId == requestOwnerDeviceId) {
            "Only the verified Owner device may issue Staff invitations"
        }
        require(staffUserId != ownerUserId &&
            staffDeviceId != installedOwnerDeviceId) {
            "Staff must use separate identity and phone"
        }
        require(label.trim().length in 1..100 &&
            label.trim() == label && label.none { it.isISOControl() }) {
            "A safe 1–100 character device label is required"
        }
    }

    fun validateClaimResult(
        accepted: Boolean,
        status: String,
        returnedWorkspaceId: String,
        expectedWorkspaceId: String
    ) {
        require(accepted &&
            status == "awaiting_owner_activation_and_server_cutover" &&
            returnedWorkspaceId == expectedWorkspaceId) {
            "Server did not confirm a pending Owner-approved invitation"
        }
    }

    fun validateGrantResult(
        accepted: Boolean,
        serverDeviceId: String,
        expectedDeviceId: String
    ) {
        require(accepted && serverDeviceId == expectedDeviceId) {
            "Server did not approve the exact requested phone"
        }
    }
}

/**
 * One-time invitation must never appear in exceptions, debug strings,
 * analytics, application logs or local preferences. Display to Owner only
 * while the secure pairing dialog is open, then discard from UI state.
 */
internal class OneTimeOwnerInvitation private constructor(
    val code: String,
    val expiresAt: String
) {
    override fun toString(): String = "OneTimeOwnerInvitation([REDACTED])"

    companion object {
        fun validated(code: String, expiresAt: String): OneTimeOwnerInvitation {
            require(code.matches(Regex("[0-9a-f]{64}")))
            require(expiresAt.isNotBlank() && expiresAt.length <= 80)
            return OneTimeOwnerInvitation(code, expiresAt)
        }
    }
}
