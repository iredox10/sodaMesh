package com.sodamesh.mesh.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Pure-JDK crypto facade for SodaMesh.
 *
 * - Ed25519 signing / verification via [Signature] ("Ed25519").
 * - SHA-256 digests via [MessageDigest].
 * - 8-byte random peer IDs via [SecureRandom].
 * - X25519 ECDH shared secrets via [KeyAgreement] ("XDH" + X25519).
 *
 * No third-party dependencies; JDK 15+ for Ed25519, JDK 11+ for XDH/X25519.
 *
 * NOTE (X25519): on Android, "XDH"/X25519 and "Ed25519" providers are only
 * guaranteed on API 33+ (Android 13). core-mesh minSdk is 26, so callers must
 * catch [XUnavailableException] on older devices and fall back (e.g. queue via
 * [com.sodamesh.mesh.store.Outbox] until a capable path exists). This class
 * deliberately performs no Android API checks so it stays unit-testable on JVM.
 */
class CryptoManager(
    private val random: SecureRandom = SecureRandom(),
) {
    // -- Ed25519 -----------------------------------------------------------

    /** Generates a fresh Ed25519 signing key pair. */
    @Throws(XUnavailableException::class)
    fun generateEdKeyPair(): KeyPair = runAlgorithm("Ed25519") {
        // Ed25519 has fixed parameters; no initialize() call needed.
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }

    /**
     * Signs [message] with an Ed25519 [privateKey].
     * @throws java.security.InvalidKeyException if the key is not an Ed25519 private key.
     */
    @Throws(XUnavailableException::class)
    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray = runAlgorithm("Ed25519") {
        Signature.getInstance("Ed25519").apply {
            initSign(privateKey, random)
            update(message)
        }.sign()
    }

    /**
     * Verifies an Ed25519 [signature] over [message]. Returns false (rather than
     * throwing) on bad signatures / wrong keys.
     */
    @Throws(XUnavailableException::class)
    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean = runAlgorithm("Ed25519") {
        try {
            Signature.getInstance("Ed25519").apply {
                initVerify(publicKey)
                update(message)
            }.verify(signature)
        } catch (e: Exception) {
            // SignatureException / InvalidKeyException on malformed input -> invalid.
            false
        }
    }

    // -- X25519 ECDH ---------------------------------------------------------

    /** Generates a fresh X25519 key-agreement key pair. */
    @Throws(XUnavailableException::class)
    fun generateX25519KeyPair(): KeyPair = runAlgorithm("XDH") {
        KeyPairGenerator.getInstance("XDH").apply {
            initialize(NamedParameterSpec("X25519"))
        }.generateKeyPair()
    }

    /**
     * Computes the raw X25519 shared secret for our [privateKey] and the peer's
     * [peerPublicKey]. Caller is responsible for key derivation (HKDF) before use
     * as an encryption key; the raw secret must never be used directly.
     */
    @Throws(XUnavailableException::class)
    fun x25519SharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray =
        runAlgorithm("XDH") {
            KeyAgreement.getInstance("XDH").apply {
                init(privateKey)
                doPhase(peerPublicKey, true)
            }.generateSecret()
        }

    // -- Hashing / IDs / key codecs ------------------------------------------

    /** SHA-256 over the concatenation of [parts]. */
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    /** Fresh random 8-byte peer ID ([PEER_ID_BYTES]). */
    fun randomPeerId(): ByteArray = ByteArray(PEER_ID_BYTES).also(random::nextBytes)

    /** Fresh random 8-byte peer ID rendered as 16 lowercase hex chars. */
    fun randomPeerIdHex(): String = randomPeerId().toHex()

    /** PKCS#8 bytes of a private key (Ed25519 or X25519). */
    fun encodePrivateKey(key: PrivateKey): ByteArray = key.encoded

    /** X.509 bytes of a public key (Ed25519 or X25519). */
    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    /** Decodes an Ed25519 public key from its X.509 encoding. */
    @Throws(XUnavailableException::class)
    fun decodeEdPublicKey(encoded: ByteArray): PublicKey = runAlgorithm("Ed25519") {
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
    }

    /** Decodes an Ed25519 private key from its PKCS#8 encoding. */
    @Throws(XUnavailableException::class)
    fun decodeEdPrivateKey(encoded: ByteArray): PrivateKey = runAlgorithm("Ed25519") {
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))
    }

    /** Decodes an X25519 public key from its X.509 encoding. */
    @Throws(XUnavailableException::class)
    fun decodeX25519PublicKey(encoded: ByteArray): PublicKey = runAlgorithm("XDH") {
        KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(encoded))
    }

    /** Decodes an X25519 private key from its PKCS#8 encoding. */
    @Throws(XUnavailableException::class)
    fun decodeX25519PrivateKey(encoded: ByteArray): PrivateKey = runAlgorithm("XDH") {
        KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(encoded))
    }

    private inline fun <T> runAlgorithm(name: String, block: () -> T): T {
        try {
            return block()
        } catch (e: java.security.NoSuchAlgorithmException) {
            throw XUnavailableException(name, e)
        } catch (e: java.security.InvalidParameterException) {
            throw XUnavailableException(name, e)
        }
    }

    companion object {
        /** Peer ID length in bytes. */
        const val PEER_ID_BYTES = 8

        private val HEX = "0123456789abcdef".toCharArray()

        fun ByteArray.toHex(): String {
            val out = CharArray(size * 2)
            forEachIndexed { i, b ->
                out[i * 2] = HEX[(b.toInt() ushr 4) and 0x0F]
                out[i * 2 + 1] = HEX[b.toInt() and 0x0F]
            }
            return String(out)
        }
    }
}

/**
 * Thrown when the runtime has no provider for a required algorithm
 * ("Ed25519" / "XDH"). Expected on older Android devices (pre-API 33).
 */
class XUnavailableException(
    val algorithm: String,
    cause: Throwable,
) : UnsupportedOperationException("Algorithm unavailable on this runtime: $algorithm", cause)
