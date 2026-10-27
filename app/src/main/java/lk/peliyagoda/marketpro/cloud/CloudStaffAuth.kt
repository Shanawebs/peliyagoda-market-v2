package lk.peliyagoda.marketpro.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Patterns
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
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

/**
 * Future Purchasing Staff Auth adapter. NEVER wired from today's offline
 * preview. Uses separate Keystore alias and preferences from CloudOwnerAuth.
 *
 * Signing in proves identity, NOT workspace role/device authorization.
 * Only audited Supabase team RPCs may grant actual read/write permissions.
 */
internal class CloudStaffAuth(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("marketpro_cloud_staff_session_v1",
        Context.MODE_PRIVATE)
    private val mutex = Mutex()

    companion object {
        private const val KEY = "encrypted_staff_tokens_v1"
        private const val ALIAS = "marketpro_staff_aes256_gcm_v1"
        private val HOST = BuildConfig.MARKETPRO_SUPABASE_HOST
        private const val RESPONSE_BYTES = 32768
    }

    private data class Session(
        val email: String,
        val userId: String,
        val accessToken: String,
        val refreshToken: String
    ) {
        override fun toString(): String =
            "StaffEncryptedSession(userId=$userId, email=$email, tokens=[REDACTED])"
    }
    private class InvalidAuth : IOException("Staff sign-in expired or was rejected")

    private fun requireActivatedAuth() {
        check(StaffAuthSafetyPolicy.allowIdentityLogin(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED
        )) { "Team Login unavailable until enabled in staging" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun unseal(encoded: String): String {
        val bytes = Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size > 28)
        val iv = bytes.copyOfRange(0,12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),
            StandardCharsets.UTF_8)
    }

    private fun readStored(): Session? {
        val encrypted = prefs.getString(KEY,null) ?: return null
        return try {
            val json = JSONObject(unseal(encrypted))
            Session(json.getString("email"),json.getString("user_id"),
                json.getString("access_token"),json.getString("refresh_token"))
        } catch (_: Exception) {
            // Reinstall/restore with lost Keystore key must not produce a bogus
            // authenticated staff state. Owner's independent session is untouched.
            prefs.edit().remove(KEY).commit()
            null
        }
    }

    private fun store(session: Session) {
        val plaintext = JSONObject().put("email",session.email)
            .put("user_id",session.userId)
            .put("access_token",session.accessToken)
            .put("refresh_token",session.refreshToken)
            .toString()
        check(prefs.edit().putString(KEY,seal(plaintext)).commit()) {
            "Encrypted staff credentials could not be stored"
        }
    }

    /** Static-origin HTTPS; no redirects, bearer in header, no tokens in URLs/logs. */
    private fun request(
        route: String,
        method: String,
        payload: JSONObject? = null,
        bearer: String? = null
    ): JSONObject {
        requireActivatedAuth()
        require(route in setOf("/auth/v1/token?grant_type=password",
            "/auth/v1/token?grant_type=refresh_token",
            "/auth/v1/user","/auth/v1/logout"))
        require(method == "GET" || method == "POST")
        val base = URL(BuildConfig.MARKETPRO_SUPABASE_URL)
        require(base.protocol == "https" && base.host == HOST &&
            (base.port == -1 || base.port == 443) &&
            (base.path.isEmpty() || base.path == "/"))
        val url = URL(base,route)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod=method
            doOutput=payload != null
            instanceFollowRedirects=false
            connectTimeout=12000
            readTimeout=15000
            setRequestProperty("Accept","application/json")
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            if (bearer != null) setRequestProperty("Authorization","Bearer $bearer")
            if (payload != null) setRequestProperty("Content-Type",
                "application/json; charset=utf-8")
        }
        try {
            if (payload != null) {
                val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 8192)
                connection.outputStream.use { it.write(bytes) }
            }
            val status=connection.responseCode
            if (status==401 || status==403) throw InvalidAuth()
            if (status !in 200..299) throw IOException(
                if (status in 400..499) "Staff Auth request was rejected (HTTP $status)"
                else "Staff Auth temporarily unavailable (HTTP $status)"
            )
            val result=ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buf=ByteArray(4096)
                while (true) {
                    val read=stream.read(buf)
                    if (read<0) break
                    if (result.size()+read>RESPONSE_BYTES)
                        throw IOException("Staff Auth response too large")
                    result.write(buf,0,read)
                }
            }
            return if (result.size()==0) JSONObject()
                else JSONObject(result.toString(StandardCharsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }

    private fun validateProfile(
        profile: JSONObject, enteredEmail: String, oldUserId: String? = null
    ): StaffVerifiedIdentity {
        val identity = StaffVerifiedIdentity(
            userId=profile.getString("id"),
            email=profile.getString("email"),
            emailVerified=profile.optString("email_confirmed_at").isNotBlank() &&
                profile.optString("email_confirmed_at") != "null",
            isAnonymous=profile.optBoolean("is_anonymous",false)
        )
        StaffAuthSafetyPolicy.checkIdentity(
            identity,enteredEmail,CloudSyncConfig.OWNER_USER_ID,oldUserId
        )
        return identity
    }

    /** Server RLS returns self's role only. Identity login confers NO Cloud writes. */
    private fun verifiedEditorMembership(bearer:String,userId:String):Boolean {
        require(java.util.UUID.fromString(userId).toString()==userId)
        val url=URL(BuildConfig.MARKETPRO_SUPABASE_URL +
            "/rest/v1/workspace_members?select=role" +
            "&workspace_id=eq.${CloudSyncConfig.WORKSPACE_ID}" +
            "&user_id=eq.$userId&limit=2")
        require(url.protocol=="https" &&
            url.host==BuildConfig.MARKETPRO_SUPABASE_HOST)
        val connection=(url.openConnection() as HttpURLConnection).apply {
            requestMethod="GET"
            instanceFollowRedirects=false
            connectTimeout=12000
            readTimeout=15000
            setRequestProperty("apikey",BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization","Bearer $bearer")
            setRequestProperty("Accept","application/json")
        }
        return try {
            if(connection.responseCode!=200) return false
            val response=connection.inputStream.bufferedReader().use { it.readText().take(8192) }
            val roles=JSONArray(response)
            roles.length()==1 && roles.getJSONObject(0).optString("role")=="editor"
        } finally {
            connection.disconnect()
        }
    }

    /** Never called from UI or WorkManager until explicitly enabled. */
    suspend fun signInStaff(emailInput: String,password: String): StaffVerifiedIdentity =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                requireActivatedAuth()
                val email=StaffAuthSafetyPolicy.normalizedEmail(emailInput)
                require(email.length <= 254 && Patterns.EMAIL_ADDRESS.matcher(email).matches())
                require(password.length >= 1 && password.length <= 1024)
                val previous=readStored()
                val response=request("/auth/v1/token?grant_type=password","POST",
                    JSONObject().put("email",email).put("password",password))
                val access=response.getString("access_token")
                val refresh=response.getString("refresh_token")
                val profile=request("/auth/v1/user","GET",bearer=access)
                val identity=validateProfile(profile,email,previous?.userId)
                if (BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
                    check(verifiedEditorMembership(access,identity.userId)) {
                        "This account is not an approved Team workspace member"
                    }
                store(Session(email,identity.userId,access,refresh))
                if (BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
                    MarketProActiveRole.activateTeam(app,identity.userId)
                identity
            }
        }

    /**
     * Refreshes expired session under one mutex. A verified *same* JWT subject
     * is required after refresh. Server separately checks workspace/device.
     */
    suspend fun verifiedPullSession(): TeamPullSession {
        check(BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED) {
            "Team cloud downloads are not released yet"
        }
        return verifiedPhoneSession()
    }

    /** Safe identity-only verification for pairing; does NOT enable Cloud Pull. */
    suspend fun verifiedPhoneSession(): TeamPullSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireActivatedAuth()
            val old=readStored() ?: error("Sign in with the dedicated Staff account")
            var active=old
            val profile=try {
                request("/auth/v1/user","GET",bearer=old.accessToken)
            } catch (_: InvalidAuth) {
                val refreshed=try {
                    request("/auth/v1/token?grant_type=refresh_token","POST",
                        JSONObject().put("refresh_token",old.refreshToken))
                } catch (e:InvalidAuth) {
                    prefs.edit().remove(KEY).commit()
                    throw e
                }
                active=old.copy(
                    accessToken=refreshed.getString("access_token"),
                    refreshToken=refreshed.getString("refresh_token")
                )
                request("/auth/v1/user","GET",bearer=active.accessToken)
            }
            validateProfile(profile,old.email,old.userId)
            if (active != old) store(active)
            TeamPullSession(
                accessToken=active.accessToken,
                authenticatedUserId=old.userId,
                enrolledDeviceId=CloudDeviceIdentity.get(app),
                workspaceId=CloudSyncConfig.WORKSPACE_ID
            )
        }
    }

    suspend fun signOutStaff() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old=readStored()
            // Remove locally first even when offline. No chance to reuse stale
            // staff session on this phone; remote token expiry is independent.
            prefs.edit().remove(KEY).commit()
            if (BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE)
                MarketProActiveRole.clearIfActive(app,"team")
            if (old != null && StaffAuthSafetyPolicy.allowNetwork(
                BuildConfig.MARKETPRO_LOCAL_PREVIEW,
                BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
                BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED
            )) {
                try {
                    request("/auth/v1/logout","POST",bearer=old.accessToken)
                } catch (_: Exception) {
                    // Server-side device revocation must still be enforced by
                    // every RPC; this local signout is not a global revoke.
                }
            }
        }
    }
}

/** Explicit future adapter. It is NOT instantiated by the running app. */
internal class VerifiedStaffPullSessionProvider(context: Context) :
    TeamPullSessionProvider {
    private val staffAuth=CloudStaffAuth(context)
    override suspend fun currentSession(): TeamPullSession =
        staffAuth.verifiedPullSession()
}
