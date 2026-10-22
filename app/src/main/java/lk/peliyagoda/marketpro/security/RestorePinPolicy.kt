package lk.peliyagoda.marketpro.security

/**
 * UI PIN policy only. A six-digit PIN reduces accidental or casual restore misuse,
 * but is not a substitute for a signed, authenticated administrator backend.
 */
object RestorePinPolicy {
    const val MAX_FAILURES = 3
    const val LOCK_MILLIS = 5 * 60 * 1000L

    fun validPin(value: String): Boolean =
        value.length == 6 && value.all { it in '0'..'9' }

    fun shouldLock(failures: Int): Boolean = failures >= MAX_FAILURES

    fun remainingSeconds(lockedUntilMillis: Long, nowMillis: Long): Long {
        val delta = lockedUntilMillis - nowMillis
        return if (delta <= 0L) 0L else (delta + 999L) / 1000L
    }
}
