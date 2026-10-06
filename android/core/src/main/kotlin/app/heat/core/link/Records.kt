package app.heat.core.link

import app.heat.core.crypto.AeadStream
import java.io.IOException

/** Peer sent something that is not valid Heat protocol. The session must end. */
class ProtocolException(message: String) : IOException(message)

/** First byte of every decrypted record. */
internal object RecordType {
    const val READY = 0
    const val CONTROL = 1
    const val MEDIA = 2
    const val PING = 3
    const val PONG = 4
    const val BYE = 5
    const val TUNNEL = 6
}

/** Hard ceiling on one decrypted record. A 640x480 H.264 keyframe is ~100 KB; this leaves 10x headroom. */
internal const val MAX_RECORD_PLAIN = 1 shl 20

/**
 * Wire format of one record: `[u32 ciphertextLength][ciphertext+tag]`, the
 * length also bound in as AEAD associated data. The plaintext is
 * `[u8 type][payload]`. Written as a single `io.write` so a record is one
 * syscall and one TCP segment train, never interleaved with another.
 */
internal class RecordWriter(private val io: ByteDuplex, private val aead: AeadStream) {
    private var plain = ByteArray(2048)
    private var wire = ByteArray(4 + 2048 + AeadStream.TAG_BYTES)
    private val aad = ByteArray(4)

    suspend fun write(
        type: Int,
        head: ByteArray? = null, headLen: Int = 0,
        body: ByteArray? = null, bodyOffset: Int = 0, bodyLen: Int = 0
    ) {
        val plainLen = 1 + headLen + bodyLen
        if (plainLen > MAX_RECORD_PLAIN) throw ProtocolException("record too large: $plainLen")
        if (plain.size < plainLen) {
            plain = ByteArray(maxOf(plainLen, plain.size * 2))
            wire = ByteArray(4 + plain.size + AeadStream.TAG_BYTES)
        }
        plain[0] = type.toByte()
        if (headLen > 0) System.arraycopy(head!!, 0, plain, 1, headLen)
        if (bodyLen > 0) System.arraycopy(body!!, bodyOffset, plain, 1 + headLen, bodyLen)

        val cipherLen = plainLen + AeadStream.TAG_BYTES
        putInt(aad, cipherLen)
        System.arraycopy(aad, 0, wire, 0, 4)
        aead.seal(plain, 0, plainLen, aad, wire, 4)
        io.write(wire, 0, 4 + cipherLen)
    }
}

internal class RecordReader(private val io: ByteDuplex, private val aead: AeadStream) {
    private val header = ByteArray(4)
    private var wire = ByteArray(2048 + AeadStream.TAG_BYTES)

    /** Decrypted bytes of the last record: `plain[0]` is the type. Re-read this after every [read]; it can be replaced when it grows. */
    var plain = ByteArray(2048)
        private set

    /** Reads and authenticates one record. Returns the plaintext length (>= 1). */
    suspend fun read(): Int {
        io.readFully(header, 0, 4)
        val cipherLen = getInt(header)
        if (cipherLen < 1 + AeadStream.TAG_BYTES || cipherLen > MAX_RECORD_PLAIN + AeadStream.TAG_BYTES) {
            throw ProtocolException("bad record length $cipherLen")
        }
        if (wire.size < cipherLen) wire = ByteArray(maxOf(cipherLen, wire.size * 2))
        if (plain.size < cipherLen) plain = ByteArray(maxOf(cipherLen, plain.size * 2))
        io.readFully(wire, 0, cipherLen)
        return aead.open(wire, 0, cipherLen, header, plain, 0)
    }
}

internal fun putInt(out: ByteArray, value: Int, offset: Int = 0) {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
}

internal fun getInt(b: ByteArray, offset: Int = 0): Int =
    ((b[offset].toInt() and 0xFF) shl 24) or
        ((b[offset + 1].toInt() and 0xFF) shl 16) or
        ((b[offset + 2].toInt() and 0xFF) shl 8) or
        (b[offset + 3].toInt() and 0xFF)

internal fun putLong(out: ByteArray, value: Long, offset: Int = 0) {
    for (i in 0..7) out[offset + i] = (value ushr (56 - i * 8)).toByte()
}

internal fun getLong(b: ByteArray, offset: Int = 0): Long {
    var v = 0L
    for (i in 0..7) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
    return v
}
