package app.heat.core.room

import app.heat.core.crypto.Crypto
import app.heat.core.link.GroupKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A Heat room's code: twelve letters and digits, shown as `ABCD-EFGH-JKLM`.
 *
 * One code is all a person has to share. From it both ends derive, independently and identically:
 *
 *  - the **room id** the signaling server knows the room by: a one-way function of the code, stretched so that
 *    watching the server's room list tells you nothing worth guessing at;
 *  - the **group key** that authenticates and encrypts every session in the room (the same [GroupKey] that pairs a
 *    phone with its own watch), stretched the same way as the web page's room key.
 *
 * The code is the only secret, so it has to be unguessable: twelve symbols from 31 is about 59 bits, and every guess
 * costs a 150,000-round key derivation on top. It never goes to the signaling server (only the room id does), and in
 * an invite link it rides in the `#fragment`, which browsers do not send anywhere.
 *
 * The alphabet leaves out 0, O, 1, I and L so a code read aloud or off a screen is not ambiguous. It is the web
 * page's alphabet exactly, so the two share one notion of "a valid code". The derivations are byte-for-byte the
 * ones in `public/index.html` (see `HL.derive`), which the cross-language tests pin with fixed vectors.
 */
object RoomCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 12

    private const val KEY_SALT = "heat-link-v1 key"
    private const val ROOM_SALT = "heat-link-v1 room"
    private const val KEY_ROUNDS = 150_000
    private const val ROOM_ROUNDS = 30_000
    private const val ROOM_BYTES = 10

    private val rng = SecureRandom()

    /** A fresh code. Unbiased: bytes that would skew the choice among 31 symbols are rejected. */
    fun generate(): String {
        val out = StringBuilder(LENGTH)
        val buf = ByteArray(LENGTH * 2)
        while (out.length < LENGTH) {
            rng.nextBytes(buf)
            for (b in buf) {
                val v = b.toInt() and 0xFF
                if (v < 248 && out.length < LENGTH) out.append(ALPHABET[v % ALPHABET.length])
            }
        }
        return out.toString()
    }

    /** What the user typed or pasted, reduced to the code: upper case, valid symbols only, at most [LENGTH]. */
    fun normalize(text: String): String = text.uppercase().filter { it in ALPHABET }.take(LENGTH)

    /** True when [text] holds a complete code (separators and case are ignored). */
    fun isComplete(text: String): Boolean = normalize(text).length == LENGTH

    /** `ABCD-EFGH-JKLM`, or the partial code grouped the same way while it is being typed. */
    fun format(code: String): String = normalize(code).chunked(4).joinToString("-")

    /**
     * The code inside an invite link or a scanned QR: `https://host/#link=CODE`, `heat://link/CODE`, or the code
     * on its own. Null if there is no complete code to be found.
     */
    fun parseInvite(text: String): String? {
        val t = text.trim()
        val fragment = t.substringAfter("#link=", "").substringBefore('&')
        val candidate = when {
            fragment.isNotEmpty() -> fragment
            t.startsWith("heat://link/") -> t.removePrefix("heat://link/").substringBefore('?')
            else -> t
        }
        return normalize(candidate).takeIf { it.length == LENGTH }
    }

    /** The invite link to put in a QR code or share sheet. */
    fun inviteLink(code: String, origin: String): String = origin.trimEnd('/') + "/#link=" + normalize(code)

    /** The name this room has on the signaling server. Safe to show to anyone: it does not reveal the code. */
    fun roomId(code: String): String {
        val bits = pbkdf2(normalize(code), ROOM_SALT, ROOM_ROUNDS, ROOM_BYTES * 8)
        return "hl-" + Crypto.hex(bits)
    }

    /** The key every session in this room is authenticated and encrypted with. Slow on purpose (~0.1-0.3 s). */
    fun groupKey(code: String): GroupKey = GroupKey.fromBytes(pbkdf2(normalize(code), KEY_SALT, KEY_ROUNDS, 256))

    private fun pbkdf2(password: String, salt: String, rounds: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), rounds, bits)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
