package app.heat.core.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ways two devices can reach each other, best capacity first.
 * [rank] only orders preference; every carrier runs the identical session protocol.
 */
enum class CarrierKind(val rank: Int, val label: String) {
    /** TCP over the local network. Wide, low latency, not always there. */
    LAN(0, "Wi-Fi"),

    /** Wearable Data Layer channel straight to the paired phone. Always there, narrow. */
    BLUETOOTH(1, "Bluetooth"),

    /** Through another of our devices that can reach the camera when we cannot. */
    RELAY(2, "Relay")
}

/** Opens an outgoing connection to one specific peer over one carrier. */
interface Dialer {
    val kind: CarrierKind

    /** Connects and returns the raw stream, or throws [java.io.IOException]. Must give up by itself in a few seconds. */
    suspend fun dial(): ByteDuplex
}

/** Receives a fully authenticated incoming session. */
fun interface AcceptHandler {
    fun onAccepted(kind: CarrierKind, result: HandshakeResult, io: ByteDuplex)
}

/**
 * Turns raw incoming connections (from any listener: TCP, Data Layer, relay)
 * into authenticated sessions, with the limits an exposed port needs.
 *
 * A listening socket on a home network is reachable by every device on it.
 * Until the handshake completes the peer is a stranger, so: at most
 * [maxPreAuth] strangers at once, a hard deadline each, and a 256-byte cap on
 * what they may send us (enforced in [Handshake]).
 */
class Acceptor(
    private val self: DeviceId,
    private val group: GroupKey,
    private val scope: CoroutineScope,
    private val maxPreAuth: Int = 8,
    private val handler: AcceptHandler
) {
    private val preAuth = AtomicInteger()

    fun accept(kind: CarrierKind, io: ByteDuplex) {
        if (preAuth.incrementAndGet() > maxPreAuth) {
            preAuth.decrementAndGet()
            io.close()
            return
        }
        scope.launch {
            try {
                val result = Handshake.server(io, self, group)
                handler.onAccepted(kind, result, io)
            } catch (e: Exception) {
                io.close()
            } finally {
                preAuth.decrementAndGet()
            }
        }
    }
}
