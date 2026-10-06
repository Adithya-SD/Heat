package app.heat.core.testing

import app.heat.core.link.ByteDuplex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * What a test can do to the bytes one end of a [MemoryLink] writes. Every field
 * is safe to change while traffic is flowing.
 */
class Faults {
    /** Deliver at most this many bytes per chunk. 1 reproduces the worst fragmentation a network can do. */
    @Volatile var maxChunk: Int = Int.MAX_VALUE

    /** Delay each chunk by this much (virtual time under `runTest`). */
    @Volatile var latencyMs: Long = 0

    /** While true, writes block, as if the peer stopped reading and its buffers filled. */
    @Volatile var stalled: Boolean = false

    /** Writes appear to succeed but nothing arrives: a silent partition, the nastiest real-world failure. */
    @Volatile var blackhole: Boolean = false

    /** After this many total bytes written by this end, kill the link mid-write. -1 = never. */
    @Volatile var cutAfterBytes: Long = -1

    /** Flip one bit in the byte at this absolute output offset. -1 = never. */
    @Volatile var flipAtByte: Long = -1

    @Volatile var written: Long = 0
        internal set
}

/**
 * A pair of connected [ByteDuplex] ends in memory, with deterministic fault
 * injection. Used by every protocol test, and by the in-app simulator.
 */
class MemoryLink(capacityChunks: Int = 32) {
    private val aToB = Channel<ByteArray>(capacityChunks)
    private val bToA = Channel<ByteArray>(capacityChunks)

    val faultsA = Faults()
    val faultsB = Faults()

    val a: ByteDuplex = End(rx = bToA, tx = aToB, faults = faultsA)
    val b: ByteDuplex = End(rx = aToB, tx = bToA, faults = faultsB)

    /** Kill the link from both sides, as a pulled cable would. */
    fun cut() {
        a.close()
        b.close()
    }

    private inner class End(
        private val rx: Channel<ByteArray>,
        private val tx: Channel<ByteArray>,
        private val faults: Faults
    ) : ByteDuplex {
        @Volatile private var closed = false
        private var current: ByteArray? = null
        private var position = 0

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("closed")
            var chunk = current
            if (chunk == null || position >= chunk.size) {
                val received = rx.receiveCatching()
                if (closed) throw IOException("closed")
                chunk = received.getOrNull() ?: return -1
                current = chunk
                position = 0
            }
            val n = minOf(length, chunk.size - position)
            System.arraycopy(chunk, position, buffer, offset, n)
            position += n
            return n
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            var o = offset
            var left = length
            while (left > 0) {
                check()
                while (faults.stalled) {
                    delay(5)
                    check()
                }
                val n = minOf(left, faults.maxChunk)
                val chunk = buffer.copyOfRange(o, o + n)

                val start = faults.written
                val flip = faults.flipAtByte
                if (flip in start until start + n) {
                    val i = (flip - start).toInt()
                    chunk[i] = (chunk[i].toInt() xor 0x01).toByte()
                }
                faults.written = start + n

                val cut = faults.cutAfterBytes
                if (cut >= 0 && start + n > cut) {
                    val keep = (cut - start).toInt().coerceIn(0, n)
                    if (keep > 0) tx.trySend(chunk.copyOf(keep))
                    this@MemoryLink.cut()
                    throw IOException("link cut")
                }

                if (faults.latencyMs > 0) delay(faults.latencyMs)
                if (!faults.blackhole) deliver(chunk)
                o += n
                left -= n
            }
        }

        private suspend fun deliver(chunk: ByteArray) {
            // Poll rather than suspend in send(): a suspended send does not wake on
            // close(), and ByteDuplex.close() must unblock a stuck write.
            while (true) {
                check()
                val r = try {
                    tx.trySend(chunk)
                } catch (e: ClosedSendChannelException) {
                    throw IOException("peer closed", e)
                }
                if (r.isSuccess) return
                if (r.isClosed) throw IOException("peer closed")
                delay(1)
            }
        }

        private fun check() {
            if (closed) throw IOException("closed")
        }

        override fun close() {
            if (closed) return
            closed = true
            tx.close()
            rx.close()
        }
    }
}
