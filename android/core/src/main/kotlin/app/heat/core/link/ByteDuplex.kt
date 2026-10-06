package app.heat.core.link

import java.io.EOFException
import java.io.IOException

/**
 * Anything that can carry an ordered, reliable stream of bytes both ways: a TCP
 * socket, a Wear Data Layer channel, an in-memory pipe in a test, or a tunnel
 * through another device.
 *
 * This is the *only* thing the session layer knows about the outside world.
 * Everything above it (handshake, encryption, framing, liveness, prioritised
 * media) is written once and behaves identically on every carrier, which is
 * what removes the "which link is this message on" class of bug entirely.
 *
 * Contract:
 *  - [read] returns at least one byte, or -1 at end of stream, or throws [IOException].
 *  - [write] writes everything or throws [IOException].
 *  - [close] is idempotent, never blocks, and must unblock any pending
 *    [read] or [write] (by making it throw).
 */
interface ByteDuplex {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

/** Reads exactly [length] bytes or throws [EOFException]. */
suspend fun ByteDuplex.readFully(buffer: ByteArray, offset: Int, length: Int) {
    var got = 0
    while (got < length) {
        val n = read(buffer, offset + got, length - got)
        if (n < 0) throw EOFException("stream ended after $got of $length bytes")
        got += n
    }
}
