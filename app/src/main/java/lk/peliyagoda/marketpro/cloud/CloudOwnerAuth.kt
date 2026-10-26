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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        private val sharedAuthMutex = Mutex()

        fun normalizedEmail(input: String): String = input.trim().lowercase(Locale.ROOT)
        fun validEmail(input: String): Boolean {
            val trimmed = normalizedEmail(input)
            return trimmed.length <= 254 && Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()
        }
    }

    data class Account(val email: String, val userId: String)
    private data class AuthTokens(val email: String, val accessToken: String, val refreshToken: String)

    private val prefs = app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val authMutex = sharedAuthMutex

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

    suspend fun signIn(emailInput: String, password: String): Account = withContext(Dispatchers.IO) { authMutex.withLock {
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
    } }

    suspend fun currentAccount(): Account? = withContext(Dispatchers.IO) { authMutex.withLock {
        val old = saved() ?: return@withLock null
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
    } }

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

    /** Supabase Auth TOTP does not grant any business permission itself.
     * These calls are fully dormant in the current offline APK.
     */
    data class TotpFactor(val id:String,val friendlyName:String)
    data class TotpEnrollment(val factorId:String,val manualSecret:String) {
        // The TOTP seed may only live in Compose state until verification/cancel.
        override fun toString()="TotpEnrollment([REDACTED])"
    }

    private fun requireOwnerTotpEnabled() {
        check(OwnerTotpMfaPolicy.canCallOwnerAuth(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_OWNER_TOTP_MFA_AUTH_DEPLOYED
        )) { "Owner Authenticator MFA is disabled in this offline Security Preview" }
    }

    private fun storedVerifiedOwner(expectedUserId:String):AuthTokens {
        val tokens=saved() ?: error("Owner must log in before Authenticator verification")
        val owner=request("/auth/v1/user","GET",accessToken=tokens.accessToken)
        check(owner.getString("id")==expectedUserId &&
            normalizedEmail(owner.getString("email"))==tokens.email) {
            "Owner Auth session belongs to another account"
        }
        return tokens
    }

    /** Only factors with status=verified are allowed for subsequent MFA. */
    suspend fun verifiedOwnerTotpFactors(
        expectedUserId:String
    ):List<TotpFactor> = withContext(Dispatchers.IO) {
        authMutex.withLock {
            requireOwnerTotpEnabled()
            val old=storedVerifiedOwner(expectedUserId)
            val profile=request("/auth/v1/user","GET",accessToken=old.accessToken)
            val factors=profile.optJSONArray("factors")
                ?:return@withLock emptyList()
            val verified=mutableListOf<TotpFactor>()
            for(i in 0 until factors.length()){
                val factor=factors.optJSONObject(i)?:continue
                val id=factor.optString("id")
                if(OwnerTotpMfaPolicy.verifiedTotpFactor(
                    factor.optString("factor_type"),
                    factor.optString("status"),id
                ))verified.add(TotpFactor(id,
                    factor.optString("friendly_name","Authenticator")))
            }
            verified
        }
    }

    /** Supabase Auth returns the TOTP seed ONCE; never persist, log or copy it.
     * Show the seed only on the Owner setup screen for manual Authenticator entry.
     */
    suspend fun beginOwnerTotpEnrollment(
        expectedUserId:String
    ):TotpEnrollment = withContext(Dispatchers.IO) {
        authMutex.withLock {
            requireOwnerTotpEnabled()
            val old=storedVerifiedOwner(expectedUserId)
            val response=request("/auth/v1/factors","POST",
                JSONObject().put("factor_type","totp")
                    .put("friendly_name","Market Pro Owner")
                    .put("issuer","Market Pro"),
                accessToken=old.accessToken)
            val id=response.getString("id")
            val secret=response.getJSONObject("totp").getString("secret")
            require(OwnerTotpMfaPolicy.validFactorId(id) &&
                OwnerTotpMfaPolicy.validManualSecret(secret)) {
                "Supabase did not return a valid Owner Authenticator seed"
            }
            TotpEnrollment(id,secret)
        }
    }

    /** Cancel only a pending UNVERIFIED factor. Never delete a verified
     * Authenticator or a backup MFA factor in the ordinary setup workflow.
     */
    suspend fun cancelPendingOwnerTotpEnrollment(
        expectedUserId:String,factorId:String
    ):Boolean = withContext(Dispatchers.IO) {
        authMutex.withLock {
            requireOwnerTotpEnabled()
            require(OwnerTotpMfaPolicy.validFactorId(factorId))
            val old=storedVerifiedOwner(expectedUserId)
            val profile=request("/auth/v1/user","GET",accessToken=old.accessToken)
            val factors=profile.optJSONArray("factors")
                ?:error("Cannot verify pending Owner factor")
            var pending=false
            for(i in 0 until factors.length()) {
                val item=factors.optJSONObject(i)?:continue
                if(item.optString("id")==factorId &&
                    item.optString("factor_type")=="totp" &&
                    item.optString("status")=="unverified")pending=true
            }
            check(pending){"Only an unverified setup key may be cancelled"}
            request("/auth/v1/factors/"+factorId,"DELETE",
                accessToken=old.accessToken)
            true
        }
    }

    /** Independent Auth API challenge, never supplied/controlled by the APK. */
    suspend fun beginOwnerTotpChallenge(
        expectedUserId:String,factorId:String
    ):String = withContext(Dispatchers.IO) {
        authMutex.withLock {
            requireOwnerTotpEnabled()
            require(OwnerTotpMfaPolicy.validFactorId(factorId))
            val owner=storedVerifiedOwner(expectedUserId)
            val response=request("/auth/v1/factors/"+factorId+"/challenge",
                "POST",JSONObject(),accessToken=owner.accessToken)
            val challenge=response.getString("id")
            require(OwnerTotpMfaPolicy.validFactorId(challenge))
            challenge
        }
    }

    /** The OTP is transient. Supabase Auth verifies it, returns a NEW
     * AAL2 JWT/refresh pair and only then do we atomically replace the encrypted
     * Owner credentials. The financial SQL independently verifies fresh AMR.
     */
    suspend fun verifyOwnerTotpAndUpgradeSession(
        expectedUserId:String,factorId:String,challengeId:String,otp:String
    ):Boolean = withContext(Dispatchers.IO) {
        authMutex.withLock {
            requireOwnerTotpEnabled()
            require(OwnerTotpMfaPolicy.validFactorId(factorId) &&
                OwnerTotpMfaPolicy.validFactorId(challengeId) &&
                OwnerTotpMfaPolicy.validOtp(otp))
            val before=storedVerifiedOwner(expectedUserId)
            val response=request("/auth/v1/factors/"+factorId+"/verify",
                "POST",JSONObject()
                    .put("challenge_id",challengeId)
                    .put("code",otp),accessToken=before.accessToken)
            val newAccess=response.getString("access_token")
            val newRefresh=response.getString("refresh_token")
            require(newAccess.length in 20..8192 &&
                newRefresh.length in 10..8192)
            // Decode ONLY as a defense-in-depth LOCAL sanity check. This is
            // NOT cryptographic JWT verification. Auth /user and database
            // SQL remain the independent authoritative verification points.
            val jwtParts=newAccess.split('.')
            require(jwtParts.size==3)
            val claims=JSONObject(String(
                Base64.decode(jwtParts[1],Base64.URL_SAFE or Base64.NO_WRAP
                    or Base64.NO_PADDING),StandardCharsets.UTF_8))
            require(claims.optString("sub")==expectedUserId &&
                claims.optString("aal")=="aal2" &&
                OwnerTotpMfaPolicy.validFactorId(
                    claims.optString("session_id"))) {
                "Supabase did not return an Owner AAL2 session"
            }
            val methods=claims.optJSONArray("amr")
                ?:error("No MFA proof in upgraded JWT")
            var hasTotp=false
            for(i in 0 until methods.length()){
                val method=methods.optJSONObject(i)?:continue
                if(method.optString("method")=="totp" &&
                    method.optLong("timestamp",0)>0L)hasTotp=true
            }
            require(hasTotp){"No TOTP authentication in verified Owner session"}
            val profile=request("/auth/v1/user","GET",accessToken=newAccess)
            require(profile.getString("id")==expectedUserId &&
                normalizedEmail(profile.getString("email"))==before.email)
            require(saved()?.accessToken==before.accessToken) {
                "Owner Auth session changed during MFA; sign in again"
            }
            remember(AuthTokens(before.email,newAccess,newRefresh))
            true
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) { authMutex.withLock {
        val old = saved()
        try {
            if (old != null) request("/auth/v1/logout", "POST", accessToken = old.accessToken)
        } catch (_: Exception) {
            // Offline sign out still clears this device's encrypted credentials.
            // Remote tokens expire according to the project's Auth settings.
        } finally {
            prefs.edit().remove(KEY).commit()
        }
    } }
}
