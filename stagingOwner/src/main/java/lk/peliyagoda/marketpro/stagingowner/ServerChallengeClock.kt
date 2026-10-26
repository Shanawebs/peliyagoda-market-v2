package lk.peliyagoda.marketpro.stagingowner

/**
 * Anchors an untrusted-phone-clock signature to the server-issued nonce time.
 *
 * startElapsedMillis MUST be captured immediately before sending the server
 * challenge issuance request, not after receiving it. Thus elapsed includes
 * the network/request delay and conservatively shortens the remaining TTL.
 * Device wall-clock time (which can be slightly behind) is never consulted.
 *
 * PostgreSQL and the Edge verifier remain final authorities: their exact
 * 60-second expiry, live Owner AAL2 session and one-use nonce checks stay ON.
 */
internal object ServerChallengeClock {
    fun signingTimeMillis(
        issuedAtMillis: Long,
        expiresAtMillis: Long,
        startElapsedMillis: Long,
        currentElapsedMillis: Long
    ): Long {
        require(issuedAtMillis > 0 && expiresAtMillis > issuedAtMillis) {
            "P5_INVALID_TIME_WINDOW"
        }
        val ttl = expiresAtMillis - issuedAtMillis
        require(ttl in 1L..60_000L) { "P5_EXCESSIVE_TTL" }
        require(startElapsedMillis >= 0 && currentElapsedMillis >= startElapsedMillis) {
            "P5_INVALID_MONOTONIC_CLOCK"
        }
        val elapsed = currentElapsedMillis - startElapsedMillis
        require(elapsed < ttl) { "P5_CHALLENGE_ELAPSED" }
        return issuedAtMillis + elapsed
    }
}
