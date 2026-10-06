package app.heat.core.link

import app.heat.core.crypto.Crypto
import java.util.Base64

/** A device's stable 128-bit identity. Random, generated once, persisted. */
class DeviceId(bytes: ByteArray) {
    private val raw: ByteArray = bytes.copyOf()

    init {
        require(raw.size == SIZE) { "device id must be $SIZE bytes" }
    }

    val bytes: ByteArray get() = raw.copyOf()
    val hex: String by lazy(LazyThreadSafetyMode.PUBLICATION) { Crypto.hex(raw) }

    /** First 8 hex characters. For logs and UI only, never for comparison. */
    val short: String get() = hex.substring(0, 8)

    internal fun copyInto(dst: ByteArray, offset: Int) = System.arraycopy(raw, 0, dst, offset, SIZE)

    override fun equals(other: Any?): Boolean = other is DeviceId && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String = "DeviceId($short)"

    companion object {
        const val SIZE = 16
        fun random() = DeviceId(Crypto.random(SIZE))
        fun fromHex(text: String): DeviceId? = runCatching { DeviceId(Crypto.fromHex(text)) }.getOrNull()
        fun fromBytes(bytes: ByteArray, offset: Int = 0) = DeviceId(bytes.copyOfRange(offset, offset + SIZE))
    }
}

/**
 * The secret every device in one household shares.
 *
 * It never travels in clear and is never used directly: the handshake derives
 * a fresh pair of record keys from it for every session, so capturing traffic
 * (or even one session's keys) reveals nothing reusable. Possession of the key
 * is what "paired" means; there is no token on the wire to sniff.
 */
class GroupKey private constructor(private val raw: ByteArray) {

    /** Public 8-byte label. Lets a device ignore strangers' beacons cheaply; reveals nothing about the key. */
    val tag: ByteArray = Crypto.sha256("heat/1 tag".toByteArray(), raw).copyOf(TAG_SIZE)

    internal val authKey: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Crypto.hkdf(raw, ByteArray(0), "heat/1 auth".toByteArray(), 32)
    }

    internal val beaconKey: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Crypto.hkdf(raw, ByteArray(0), "heat/1 beacon".toByteArray(), 32)
    }

    internal fun extract(salt: ByteArray): ByteArray = Crypto.hkdfExtract(salt, raw)

    /** URL-safe text form, for QR codes and secure storage. Treat as a password. */
    fun export(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    override fun equals(other: Any?): Boolean = other is GroupKey && Crypto.equal(raw, other.raw)
    override fun hashCode(): Int = tag.contentHashCode()
    override fun toString(): String = "GroupKey(${Crypto.hex(tag)})"

    companion object {
        const val SIZE = 32
        const val TAG_SIZE = 8

        fun generate() = GroupKey(Crypto.random(SIZE))

        fun parse(text: String): GroupKey? = runCatching {
            val bytes = Base64.getUrlDecoder().decode(text.trim())
            if (bytes.size == SIZE) GroupKey(bytes) else null
        }.getOrNull()

        fun fromBytes(bytes: ByteArray): GroupKey {
            require(bytes.size == SIZE) { "group key must be $SIZE bytes" }
            return GroupKey(bytes.copyOf())
        }
    }
}
