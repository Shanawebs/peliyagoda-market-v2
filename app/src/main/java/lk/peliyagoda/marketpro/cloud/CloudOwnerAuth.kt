package lk.peliyagoda.marketpro.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Patterns
import lk.peliyagoda.marketpro.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This is a DEV-only sign-in client. No business records or photos are sent.
 * Supabase Auth is separate from cloud writer authorization: a login ALONE
 * never authorizes a device/workspace to upload business information.
 */
class CloudOwnerAuth(private val app: Context) {
    companion object {
        val PROJECT_URL = BuildConfig.MARKETPRO_SUPABASE_URL
        private const val PREFS = "marketpro_cloud_owner_auth"
        private const val KEY = "encrypted_session"
        private const val ALIAS = "marketpro_cloud_owner_auth_aes_v1"

        fun normalizedEmail(input: String): String = input.trim().lowercase(Locale.ROOT)
        fun validEmail(input: String): Boolean {
            val trimmed = normalizedEmail(input)
            return trimmed.length <= 254 && Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
        }
    }

    data class Account(val email: String, val userId: String)
    private data class AuthTokens(val email: String, val accessToken: String, val refreshToken: String)

    private val prefs = app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun aesKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** IV is random, never reused; access and refresh JWTs never enter plain preferences. */
    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val combined = cipher.iv + cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun unseal(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        require(data.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), StandardCharsets.UTF_8)
    }

    private fun remember(tokens: AuthTokens) {
        val body = JSONObject().put("email", tokens.email)
            .put("access_token", tokens.accessToken).put("refresh_token", tokens.refreshToken)
        check(prefs.edit().putString(KEY, seal(body.toString())).commit()) {
            "Secure auth session could not be saved"
        }
    }

    private fun saved(): AuthTokens? {
        val ciphertext = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val obj = JSONObject(unseal(ciphertext))
            AuthTokens(obj.getString("email"), obj.getString("access_token"), obj.getString("refresh_token"))
        }.getOrNull()
    }

    private fun request(
        path: String, method: String, json: JSONObject? = null, accessToken: String? = null
    ): JSONObject {
        val destination = URL(PROJECT_URL + path)
        require(destination.protocol == "https" && destination.host.endsWith(".supabase.co"))
        val connection = (destination.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (json != null) connection.outputStream.use {
                it.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                reader.readText().take(32768)
            } ?: ""
            if (status !in 200..299) {
                // Never surface service error payloads: they may contain identity details.
                throw IOException(if (status in 400..499) {
                    "Authentication was not accepted. Check your email verification and credentials (HTTP $status)."
                } else {
                    "Supabase temporarily unavailable (HTTP $status)."
                })
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun createAccount(emailInput: String, password: String) = withContext(Dispatchers.IO) {
        val email = normalizedEmail(emailInput)
        require(validEmail(email) && password.length >= 12) {
            "Enter a valid email and a password of at least 12 characters."
        }
        // Hosted Supabase normally emails a confirmation link. Password stays on phone.
        request("/auth/v1/signup", "POST", JSONObject().put("email", email).put("password", password))
        Unit
    }

    suspend fun signIn(emailInput: String, password: String): Account = withContext(Dispatchers.IO) {
        val email = normalizedEmail(emailInput)
        require(validEmail(email) && password.isNotBlank()) { "Enter your email and password." }
        val response = request(
            "/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email).put("password", password)
        )
        val tokens = AuthTokens(email, response.getString("access_token"), response.getString("refresh_token"))
        // Revalidate JWT with Supabase before storing any session on device.
        val profile = request("/auth/v1/user", "GET", accessToken = tokens.accessToken)
        check(normalizedEmail(profile.getString("email")) == email) { "Account email mismatch." }
        remember(tokens)
        Account(email, profile.getString("id"))
    }

    suspend fun currentAccount(): Account? = withContext(Dispatchers.IO) {
        val old = saved() ?: return@withContext null
        val first = runCatching { request("/auth/v1/user", "GET", accessToken = old.accessToken) }
        var profile = first.getOrNull()
        if (profile == null) {
            val refreshed = request(
                "/auth/v1/token?grant_type=refresh_token", "POST",
                JSONObject().put("refresh_token", old.refreshToken)
            )
            val tokens = AuthTokens(
                old.email,
                refreshed.getString("access_token"),
                refreshed.getString("refresh_token")
            )
            profile = request("/auth/v1/user", "GET", accessToken = tokens.accessToken)
            remember(tokens)
        }
        val account = Account(
            email = normalizedEmail(profile.getString("email")),
            userId = profile.getString("id")
        )
        check(account.email == old.email) { "Saved account did not match authenticated identity." }
        account
    }

    /**
     * Return only a validated Supabase Auth token for the one enrolled DEV owner.
     * The token is transient and never added to job input data, logs or plain files.
     */
    suspend fun accessTokenForOwner(expectedUserId: String): String = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: error("Sign in to your Cloud Owner account first")
        check(account.userId == expectedUserId) {
            "Signed-in account is not the enrolled Market Pro owner"
        }
        saved()?.accessToken ?: error("Encrypted Cloud Owner session unavailable")
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val old = saved()
        try {
            if (old != null) request("/auth/v1/logout", "POST", accessToken = old.accessToken)
        } catch (_: Exception) {
            // Offline sign out still clears this device's encrypted credentials.
            // Remote tokens expire according to the project's Auth settings.
        } finally {
            prefs.edit().remove(KEY).commit()
        }
    }
}
