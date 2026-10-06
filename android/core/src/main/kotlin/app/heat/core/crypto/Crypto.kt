package app.heat.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The only place in Heat that touches javax.crypto.
 *
 * Everything here is a thin, boring wrapper over primitives the platform
 * already ships (HMAC-SHA256, HKDF built from it, AES-256-GCM). No custom
 * constructions: the protocol above composes these in the textbook way, and
 * keeping the surface this small is what makes it reviewable.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also(rng::nextBytes)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** Constant-time comparison. Never use `==` on MACs. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ── HKDF (RFC 5869) ──────────────────────────────────────────────

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "bad HKDF length $length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, out, produced, n)
            produced += n
            counter++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // ── Encoding helpers ─────────────────────────────────────────────

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) {
            val hi = Character.digit(text[it * 2], 16)
            val lo = Character.digit(text[it * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** Thrown when a ciphertext fails authentication. The session must die. */
class AuthenticationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One direction of an AES-256-GCM record stream.
 *
 * The nonce is `prefix(4) || counter(8)`; the counter is implicit (both ends
 * count records on a reliable, ordered byte stream), so it costs no wire
 * bytes and a replayed, reordered or dropped record fails authentication
 * instead of being silently accepted. One instance per direction, used by one
 * coroutine at a time.
 */
class AeadStream(key: ByteArray, private val noncePrefix: ByteArray) {
    init {
        require(key.size == 32) { "key must be 32 bytes" }
        require(noncePrefix.size == 4) { "nonce prefix must be 4 bytes" }
    }

    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12).also { System.arraycopy(noncePrefix, 0, it, 0, 4) }
    private var counter = 0L

    /** Records processed so far, i.e. the counter the next record will use. */
    val records: Long get() = counter

    private fun nextNonce(): ByteArray {
        val c = counter++
        for (i in 0..7) nonce[4 + i] = (c ushr (56 - i * 8)).toByte()
        return nonce
    }

    /** Encrypts [length] bytes at [offset]; writes ciphertext+tag to [out]. Returns bytes written. */
    fun seal(
        plain: ByteArray, offset: Int, length: Int,
        aad: ByteArray, out: ByteArray, outOffset: Int
    ): Int {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nextNonce()))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain, offset, length, out, outOffset)
    }

    /** Decrypts and verifies. Returns plaintext length. */
    fun open(
        cipherText: ByteArray, offset: Int, length: Int,
        aad: ByteArray, out: ByteArray, outOffset: Int
    ): Int {
        try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nextNonce()))
            cipher.updateAAD(aad)
            return cipher.doFinal(cipherText, offset, length, out, outOffset)
        } catch (e: java.security.GeneralSecurityException) {
            throw AuthenticationException("record failed authentication", e)
        }
    }

    companion object {
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
