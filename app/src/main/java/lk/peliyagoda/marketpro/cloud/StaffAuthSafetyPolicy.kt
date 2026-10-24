package lk.peliyagoda.marketpro.cloud

import java.util.Locale
import java.util.UUID

/**
 * Client-side identity sanity check only.
 * This does NOT grant workspace rights; the server still checks a verified
 * Auth JWT, workspace membership, device enrollment and role per request.
 */
data class StaffVerifiedIdentity(
    val userId: String,
    val email: String,
    val emailVerified: Boolean,
    val isAnonymous: Boolean
)

object StaffAuthSafetyPolicy {
    private fun uuid(input: String) = runCatching {
        UUID.fromString(input).toString().equals(input, ignoreCase = true)
    }.getOrDefault(false)

    fun normalizedEmail(input: String): String =
        input.trim().lowercase(Locale.ROOT)

    fun checkIdentity(
        identity: StaffVerifiedIdentity,
        enteredEmail: String,
        ownerUserId: String,
        previousVerifiedUserId: String? = null
    ) {
        require(uuid(identity.userId)) { "Verified Staff user UUID required" }
        require(uuid(ownerUserId) && identity.userId != ownerUserId) {
            "Owner credentials cannot be used on the team phone"
        }
        require(identity.emailVerified && !identity.isAnonymous) {
            "Verified, nonanonymous Staff Auth identity required"
        }
        require(identity.email.isNotBlank() &&
            normalizedEmail(identity.email) == normalizedEmail(enteredEmail)) {
            "Authenticated Staff email does not match"
        }
        require(previousVerifiedUserId == null ||
            identity.userId == previousVerifiedUserId) {
            "Stored Staff session changed user identity"
        }
    }

    fun allowNetwork(
        isOfflinePreview: Boolean,
        staffLoginEnabled: Boolean,
        reviewedTeamRpcDeployed: Boolean
    ): Boolean = !isOfflinePreview && staffLoginEnabled && reviewedTeamRpcDeployed
}
