package lk.peliyagoda.marketpro.photo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.util.UUID

/**
 * Non-exportable P-256 photo request signer.
 * Server must independently verify JWT, active device, Owner-approved pinned key,
 * and consume unique nonces. A device ID is NEVER sufficient authorization.
 */
internal object MarketPhotoDeviceProof {
    private const val ALIAS = "marketpro_photo_p256_proof_v1"

    private fun keyPair(): KeyPair {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val private=store.getKey(ALIAS,null) as? java.security.PrivateKey
        val certificate=store.getCertificate(ALIAS)
        if(private!=null && certificate!=null)return KeyPair(certificate.publicKey,private)
        val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false).build())
        return generator.generateKeyPair()
    }

    fun publicKeySpkiBase64url():String = encode(keyPair().public.encoded)

    data class Proof(val deviceId:String,val action:String,val timestamp:Long,
                     val nonce:String,val bodySha256:String,val signature:String)

    fun sign(action:String,deviceId:String,body:ByteArray):Proof {
        require(action in setOf("upload","list","list_day","download","enroll","approve"))
        require(UUID.fromString(deviceId).toString()==deviceId)
        val timestamp=System.currentTimeMillis()/1000
        val nonce=encode(ByteArray(20).also {SecureRandom().nextBytes(it)})
        val hash=MessageDigest.getInstance("SHA-256").digest(body).joinToString("") {
            "%02x".format(it)
        }
        val canonical="MARKETPRO-PHOTO-V1\n$action\n$deviceId\n$timestamp\n$nonce\n$hash"
        val signer=Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair().private)
        signer.update(canonical.toByteArray(StandardCharsets.UTF_8))
        return Proof(deviceId,action,timestamp,nonce,hash,encode(derToP1363(signer.sign())))
    }

    private fun encode(data:ByteArray):String =
        Base64.encodeToString(data,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Convert Android DER-encoded ECDSA signature to WebCrypto P1363 r||s. */
    internal fun derToP1363(der:ByteArray):ByteArray {
        require(der.size in 68..75 && der[0]==0x30.toByte())
        var offset=1
        require((der[offset++].toInt() and 0xFF)==der.size-2)
        fun scalar():ByteArray {
            require((der[offset++].toInt() and 0xFF)==2)
            val length=der[offset++].toInt() and 0xFF
            require(length in 1..33 && offset+length<=der.size)
            val bytes=der.copyOfRange(offset,offset+length)
            offset+=length
            if(length==33)require(bytes[0].toInt()==0)
            val stripped=if(length==33)bytes.copyOfRange(1,33)else bytes
            require(stripped.size<=32)
            return ByteArray(32-stripped.size)+stripped
        }
        val r=scalar()
        val s=scalar()
        require(offset==der.size)
        return r+s
    }
}
