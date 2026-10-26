package lk.peliyagoda.marketpro.stagingowner

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import android.util.Base64
import android.os.SystemClock
import org.json.JSONObject

/** Separate Staging-only EC P-256 key. Private key never leaves AndroidKeyStore. */
internal class OwnerKeystoreLab {
    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "marketpro_stage_owner_selftest_ec_p256_v1"
    }

    private fun keystore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun pair(): Pair<PrivateKey, PublicKey> {
        val store = keystore()
        val key = store.getKey(ALIAS, null) as? PrivateKey
        val certificate = store.getCertificate(ALIAS)
        if (key != null && certificate != null) return key to certificate.publicKey
        check(key == null && certificate == null) { "Incomplete Keystore entry" }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, PROVIDER
        )
        generator.initialize(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build())
        val created = generator.generateKeyPair()
        return created.private to created.public
    }

    data class KeySummary(val fingerprintSha256: String, val hardwareBacked: Boolean)

    fun summary(): KeySummary {
        val (private, public) = pair()
        // Hardware-backed is reported ONLY if the Android Keystore confirms it.
        val hardware = try {
            KeyFactory.getInstance(private.algorithm,PROVIDER)
                .getKeySpec(private,KeyInfo::class.java).isInsideSecureHardware
        } catch (_: Exception) {
            false
        }
        return KeySummary(fingerprint(public), hardware)
    }

    fun verifyLocalChallenge(): Boolean {
        val (private,public) = pair()
        val now = System.currentTimeMillis()
        val nonceBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonceHex = nonceBytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        // Synthetic identities. NEVER sign in, connect or enroll a Cloud account.
        val local = OwnerProofTranscript(
            workspaceId = UUID.randomUUID().toString(),
            ownerUserId = UUID.randomUUID().toString(),
            deviceId = UUID.randomUUID().toString(),
            sessionId = UUID.randomUUID().toString(),
            challengeId = UUID.randomUUID().toString(),
            nonceHex = nonceHex,
            fingerprintSha256 = fingerprint(public),
            issuedAtMillis = now,
            expiresAtMillis = now + 60_000
        )
        val payload = local.bytes(now)
        val signed = Signature.getInstance("SHA256withECDSA").run {
            initSign(private); update(payload); sign()
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(public);update(payload);verify(signed)
        } && !Signature.getInstance("SHA256withECDSA").run {
            val changed = payload.clone()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            initVerify(public);update(changed);verify(signed)
        }
    }

    /** Public key can leave the phone; the nonexportable signing key cannot. */
    fun publicSpkiBase64Url(): String {
        val (_, publicKey) = pair()
        return Base64.encodeToString(
            publicKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Safe deterministic labels. Never echo the actual server challenge,
     * nonce, fingerprint, time, phone UUID, JWT, signature or private key.
     */
    internal class ProofValidation(val safeCode: String) : IllegalArgumentException()

    /** Sign server-generated values only; real database activation is absent. */
    fun signStagingServerChallenge(c: JSONObject, issueRequestStartElapsedMs: Long): String {
        val (privateKey, publicKey) = pair()
        if (c.optString("proof_version") != OwnerProofTranscript.VERSION)
            throw ProofValidation("P5_VERSION")
        if (c.optString("fingerprint_sha256") != fingerprint(publicKey))
            throw ProofValidation("P5_KEY_FINGERPRINT")
        if (c.optBoolean("phone_activated", true) != false)
            throw ProofValidation("P5_UNEXPECTED_DEVICE_STATE")

        val material = OwnerProofTranscript(
            workspaceId = c.getString("workspace_id"),
            ownerUserId = c.getString("owner_user_id"),
            deviceId = c.getString("device_id"),
            sessionId = c.getString("session_id"),
            challengeId = c.getString("challenge_id"),
            nonceHex = c.getString("nonce_hex"),
            fingerprintSha256 = c.getString("fingerprint_sha256"),
            issuedAtMillis = c.getLong("issued_at_millis"),
            expiresAtMillis = c.getLong("expires_at_millis")
        )
        val bytes = try {
            // Android wall-clock may be behind UTC by seconds. Anchor to the
            // database-issued challenge time plus monotonic time elapsed
            // SINCE BEFORE the issuance request, including network latency.
            // Never alter the challenge timestamps signed by the Keystore.
            val serverAnchoredNow = ServerChallengeClock.signingTimeMillis(
                material.issuedAtMillis, material.expiresAtMillis,
                issueRequestStartElapsedMs, SystemClock.elapsedRealtime()
            )
            material.bytes(serverAnchoredNow)
        } catch (e: IllegalArgumentException) {
            val code = when (e.message) {
                "Invalid local test UUID" -> "P5_UUID_FORMAT"
                "Invalid local test nonce or fingerprint" -> "P5_HEX_FORMAT"
                "Invalid challenge window" -> "P5_INVALID_TIME_WINDOW"
                "Challenge is too long" -> "P5_EXCESSIVE_TTL"
                "P5_CHALLENGE_ELAPSED" -> "P5_CHALLENGE_ELAPSED"
                "P5_INVALID_MONOTONIC_CLOCK" -> "P5_INVALID_MONOTONIC_CLOCK"
                "P5_EXCESSIVE_TTL" -> "P5_EXCESSIVE_TTL"
                "P5_INVALID_TIME_WINDOW" -> "P5_INVALID_TIME_WINDOW"
                "Expired or future local challenge" -> "P5_CHALLENGE_TIME_REJECTED"
                else -> "P5_INVALID_TRANSCRIPT"
            }
            throw ProofValidation(code)
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
        return Base64.encodeToString(
            signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /** Pending-only P-256 proof: no Owner/Staff privileges. */
    fun signPendingOwnerChallenge(c:JSONObject,requestStartedElapsedMs:Long):String {
        val (privateKey,publicKey)=pair()
        if(c.optString("proof_version")!=PendingOwnerTranscript.VERSION)
            throw ProofValidation("P5_PENDING_DOMAIN")
        if(c.optString("fingerprint_sha256")!=fingerprint(publicKey))
            throw ProofValidation("P5_PENDING_KEY_MISMATCH")
        if(c.optString("registration_status")!="not_registered" ||
           c.optBoolean("device_activated",true)!=false ||
           c.optBoolean("staff_access",true)!=false)
            throw ProofValidation("P5_UNSAFE_PENDING_RECEIPT")
        val p=PendingOwnerTranscript(
            workspaceId=c.getString("workspace_id"),
            ownerUserId=c.getString("owner_user_id"),
            deviceId=c.getString("device_id"),
            sessionId=c.getString("session_id"),
            challengeId=c.getString("challenge_id"),
            nonceHex=c.getString("nonce_hex"),
            fingerprintSha256=c.getString("fingerprint_sha256"),
            issuedAtMillis=c.getLong("issued_at_millis"),
            expiresAtMillis=c.getLong("expires_at_millis")
        )
        val now=ServerChallengeClock.signingTimeMillis(
            p.issuedAtMillis,p.expiresAtMillis,
            requestStartedElapsedMs,SystemClock.elapsedRealtime()
        )
        val signed=Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(p.bytes(now))
            sign()
        }
        return Base64.encodeToString(signed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun fingerprint(public: PublicKey):String =
        MessageDigest.getInstance("SHA-256").digest(public.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
