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

    /** Sign server-generated values only. This does NOT register a phone. */
    fun signStagingServerChallenge(c: JSONObject): String {
        val (privateKey, publicKey) = pair()
        require(c.optString("proof_version") == OwnerProofTranscript.VERSION) {
            "Wrong server challenge version"
        }
        require(c.optString("fingerprint_sha256") == fingerprint(publicKey)) {
            "Server public key does not match this phone"
        }
        require(c.optBoolean("phone_activated", true) == false) {
            "Unexpected phone activation response"
        }
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
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(material.bytes(System.currentTimeMillis()))
            sign()
        }
        return Base64.encodeToString(
            signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun fingerprint(public: PublicKey):String =
        MessageDigest.getInstance("SHA-256").digest(public.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
