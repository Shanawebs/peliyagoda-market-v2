package lk.peliyagoda.marketpro.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The owner establishes the six-digit PIN locally on first use.
 *
 * The PIN is never hard-coded, committed to GitHub, or stored as plain text.
 * Verification uses a non-exportable Android Keystore HMAC key, a random salt,
 * constant-time comparison, and persisted rate limiting. Lockout is a deterrent
 * to casual use, not a defense against a rooted or compromised phone.
 *
 * No automatic reset: uninstalling/clearing app storage also removes local
 * records. Always export a JSON backup before making device changes.
 */
class RestorePinGuard(context: Context) {
    sealed interface Check {
        data object Accepted : Check
        data class Rejected(val attemptsRemaining: Int) : Check
        data class Locked(val secondsRemaining: Long) : Check
        data object NotConfigured : Check
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        "restore_pin_guard_v1", Context.MODE_PRIVATE
    )

    fun isConfigured(): Boolean = prefs.contains(PIN_TAG)

    @Synchronized
    fun establish(pin: String, confirmation: String): Boolean {
        if (isConfigured() || pin != confirmation || !RestorePinPolicy.validPin(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verifier = tag(salt, pin)
        return prefs.edit()
            .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PIN_TAG, Base64.encodeToString(verifier, Base64.NO_WRAP))
            .putInt(FAILED_ATTEMPTS, 0)
            .putLong(LOCKED_UNTIL, 0L)
            .commit()
    }

    @Synchronized
    fun verify(pin: String): Check {
        if (!isConfigured()) return Check.NotConfigured
        val now = System.currentTimeMillis()
        val lockUntil = prefs.getLong(LOCKED_UNTIL, 0L)
        val remaining = RestorePinPolicy.remainingSeconds(lockUntil, now)
        if (remaining > 0L) return Check.Locked(remaining)

        val saltText = prefs.getString(PIN_SALT, null) ?: return Check.Rejected(0)
        val tagText = prefs.getString(PIN_TAG, null) ?: return Check.NotConfigured
        val salt = Base64.decode(saltText, Base64.DEFAULT)
        val expected = Base64.decode(tagText, Base64.DEFAULT)

        if (RestorePinPolicy.validPin(pin) &&
            MessageDigest.isEqual(expected, tag(salt, pin))) {
            prefs.edit().putInt(FAILED_ATTEMPTS, 0).putLong(LOCKED_UNTIL, 0L).commit()
            return Check.Accepted
        }

        val priorFailures =
            if (lockUntil != 0L && now >= lockUntil) 0
            else prefs.getInt(FAILED_ATTEMPTS, 0)
        val failures = priorFailures + 1
        if (RestorePinPolicy.shouldLock(failures)) {
            prefs.edit()
                .putInt(FAILED_ATTEMPTS, 0)
                .putLong(LOCKED_UNTIL, now + RestorePinPolicy.LOCK_MILLIS)
                .commit()
            return Check.Locked(
                RestorePinPolicy.remainingSeconds(now + RestorePinPolicy.LOCK_MILLIS, now)
            )
        }
        prefs.edit().putInt(FAILED_ATTEMPTS, failures).putLong(LOCKED_UNTIL, 0L).commit()
        return Check.Rejected(RestorePinPolicy.MAX_FAILURES - failures)
    }

    private fun tag(salt: ByteArray, pin: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key())
        mac.update(salt)
        return mac.doFinal(pin.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val saved = store.getKey(KEY_ALIAS, null)
        if (saved != null) return saved as SecretKey

        // Fail closed if preference data exists but the Keystore key vanished.
        check(!isConfigured()) { "Restore PIN key is unavailable. Do not clear app data without a backup." }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).setDigests(KeyProperties.DIGEST_SHA256).build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "marketpro_restore_pin_hmac_v1"
        const val PIN_SALT = "salt"
        const val PIN_TAG = "verifier"
        const val FAILED_ATTEMPTS = "failed_attempts"
        const val LOCKED_UNTIL = "locked_until"
    }
}
