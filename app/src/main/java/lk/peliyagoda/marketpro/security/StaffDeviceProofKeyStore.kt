package lk.peliyagoda.marketpro.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Android-only, nonexportable Staff signing key.
 *
 * NOT USED BY PREVIEW UI / RPCs. Can be enabled only after a reviewed backend
 * issues short-lived challenges and verifies signatures against a previously
 * owner-approved public key. A software-backed Android Keystore key is NOT
 * silently claimed to be StrongBox/TEE hardware backed.
 */
internal class StaffDeviceProofKeyStore {
    private companion object {
        const val ALIAS = "marketpro_staff_proof_ec_p256_v374"
        const val KEY_PROVIDER = "AndroidKeyStore"
    }

    internal data class PublicKeyInfo(
        val spkiBase64Url: String,
        val sha256Fingerprint: String,
        val hardwareBacked: Boolean
    ) {
        override fun toString(): String =
            "PublicKeyInfo(fingerprint=$sha256Fingerprint, hardwareBacked=$hardwareBacked)"
    }

    internal data class SignedProof(
        val publicKey: PublicKeyInfo,
        val signatureDerBase64Url: String
    ) {
        override fun toString(): String =
            "SignedProof(publicKey=$publicKey,signature=[REDACTED])"
    }

    private fun store() = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }

    @Synchronized
    private fun existingOrCreate(): Pair<PrivateKey, java.security.PublicKey> {
        val initial = store()
        val key = initial.getKey(ALIAS,null) as? PrivateKey
        val certificate = initial.getCertificate(ALIAS)
        if (key != null && certificate != null) {
            return key to certificate.publicKey
        }
        check(key == null && certificate == null) {
            "Staff signing key is damaged; require Owner re-pairing"
        }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, KEY_PROVIDER
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        val created = generator.generateKeyPair()
        return created.private to created.public
    }

    /** No private-key bytes are ever serialized or placed in preferences. */
    fun publicInfo(): PublicKeyInfo {
        val (private, pub) = existingOrCreate()
        val keyInfo = KeyFactory.getInstance(
            private.algorithm,KEY_PROVIDER
        ).getKeySpec(private,KeyInfo::class.java)
        return PublicKeyInfo(
            Base64.getUrlEncoder().withoutPadding().encodeToString(pub.encoded),
            StaffDeviceProofPolicy.fingerprint(pub),
            keyInfo.isInsideSecureHardware
        )
    }

    /**
     * Sign a single exact purchase transaction, not the old generic v1
     * transcript. The nonexportable key is the same Owner-approved SPKI.
     * This method is never reachable while preview network flags are OFF.
     */
    /** Nonexportable Android Keystore v3 signature for 4 financial types. */
    fun signFinancial(challenge:StaffSignedFinancialChallenge):SignedProof {
        val now=System.currentTimeMillis()
        val transcript=StaffSignedFinancialProofPolicy.transcript(challenge,now)
        val (private,pub)=existingOrCreate()
        val signature=Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(transcript)
            sign()
        }
        val hardware=KeyFactory.getInstance(private.algorithm,KEY_PROVIDER)
            .getKeySpec(private,KeyInfo::class.java).isInsideSecureHardware
        return SignedProof(PublicKeyInfo(
            Base64.getUrlEncoder().withoutPadding().encodeToString(pub.encoded),
            StaffDeviceProofPolicy.fingerprint(pub),hardware
        ),Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
    }

    fun signPurchase(challenge:StaffSignedPurchaseChallenge):SignedProof {
        val now=System.currentTimeMillis()
        val bytes=StaffSignedPurchaseProofPolicy.transcript(challenge,now)
        val (private,pub)=existingOrCreate()
        val signature=Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(bytes)
            sign()
        }
        val keyInfo=KeyFactory.getInstance(private.algorithm,KEY_PROVIDER)
            .getKeySpec(private,KeyInfo::class.java)
        return SignedProof(
            PublicKeyInfo(
                Base64.getUrlEncoder().withoutPadding().encodeToString(pub.encoded),
                StaffDeviceProofPolicy.fingerprint(pub),
                keyInfo.isInsideSecureHardware
            ),
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        )
    }

    fun sign(challenge: StaffDeviceChallenge): SignedProof {
        val now = System.currentTimeMillis()
        val bytes = StaffDeviceProofPolicy.transcript(challenge,now)
        val (private,pub) = existingOrCreate()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(bytes)
            sign()
        }
        // Never log DER signature, JWT, nonce or access token.
        val hardwareBacked = KeyFactory.getInstance(
            private.algorithm,KEY_PROVIDER
        ).getKeySpec(private,KeyInfo::class.java).isInsideSecureHardware
        return SignedProof(
            PublicKeyInfo(
                Base64.getUrlEncoder().withoutPadding().encodeToString(pub.encoded),
                StaffDeviceProofPolicy.fingerprint(pub),hardwareBacked
            ),
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        )
    }
}
