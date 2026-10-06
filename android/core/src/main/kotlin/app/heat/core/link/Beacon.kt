package app.heat.core.link

import app.heat.core.crypto.Crypto
import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

/** Where a camera can be reached on the local network, as learned from its beacon. */
data class DiscoveredPeer(
    val id: DeviceId,
    val host: String,
    val tcpPort: Int,
    val name: String,
    val seenMs: Long
)

/**
 * The wire format of a discovery packet. Authenticated, not secret: anything may
 * *hear* a beacon, but only a holder of the group key can *forge* one, so a
 * stranger cannot plant a fake camera in the list or point a viewer at a decoy.
 *
 * ```
 * 'H' 'B' | ver u8 | type u8 | groupTag[8] | deviceId[16] | tcpPort u16 | nameLen u8 | name | seq u32 | mac[8]
 * ```
 * The MAC is HMAC-SHA256 under a key derived from the group key, truncated to 8 bytes.
 */
object BeaconCodec {
    const val TYPE_BEACON = 1
    const val TYPE_PROBE = 2
    private const val VERSION = 1
    private const val MAC = 8
    private const val FIXED = 2 + 1 + 1 + GroupKey.TAG_SIZE + DeviceId.SIZE + 2 + 1
    const val MAX_NAME = 24

    class Decoded(val type: Int, val id: DeviceId, val tcpPort: Int, val name: String, val seq: Long)

    fun encode(type: Int, group: GroupKey, id: DeviceId, tcpPort: Int, name: String, seq: Long): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NAME) it.copyOf(MAX_NAME) else it }
        val body = ByteArray(FIXED + nameBytes.size + 4)
        body[0] = 'H'.code.toByte()
        body[1] = 'B'.code.toByte()
        body[2] = VERSION.toByte()
        body[3] = type.toByte()
        System.arraycopy(group.tag, 0, body, 4, GroupKey.TAG_SIZE)
        id.copyInto(body, 4 + GroupKey.TAG_SIZE)
        var o = 4 + GroupKey.TAG_SIZE + DeviceId.SIZE
        body[o++] = (tcpPort ushr 8).toByte()
        body[o++] = tcpPort.toByte()
        body[o++] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, body, o, nameBytes.size)
        o += nameBytes.size
        putInt(body, seq.toInt(), o)
        val mac = Crypto.hmac(group.beaconKey, body).copyOf(MAC)
        return body + mac
    }

    /** Null for anything that is not a valid beacon of this group. Total: never throws on junk. */
    fun decode(data: ByteArray, length: Int, group: GroupKey): Decoded? {
        if (length < FIXED + 4 + MAC || length > 256) return null
        if (data[0] != 'H'.code.toByte() || data[1] != 'B'.code.toByte() || data[2].toInt() != VERSION) return null
        for (i in 0 until GroupKey.TAG_SIZE) if (data[4 + i] != group.tag[i]) return null
        val nameLen = data[FIXED - 1].toInt() and 0xFF
        if (nameLen > MAX_NAME || length != FIXED + nameLen + 4 + MAC) return null

        val bodyLen = length - MAC
        val expected = Crypto.hmac(group.beaconKey, data.copyOf(bodyLen)).copyOf(MAC)
        if (!Crypto.equal(expected, data.copyOfRange(bodyLen, length))) return null

        val idOffset = 4 + GroupKey.TAG_SIZE
        val portOffset = idOffset + DeviceId.SIZE
        return Decoded(
            type = data[3].toInt() and 0xFF,
            id = DeviceId.fromBytes(data, idOffset),
            tcpPort = ((data[portOffset].toInt() and 0xFF) shl 8) or (data[portOffset + 1].toInt() and 0xFF),
            name = String(data, FIXED, nameLen, Charsets.UTF_8),
            seq = getInt(data, FIXED + nameLen).toLong() and 0xFFFFFFFFL
        )
    }
}

/**
 * Finds cameras on the local network, and lets this device be found.
 *
 * Plain UDP broadcast, because on a home router it is the discovery mechanism
 * that actually works (mDNS is unreliable on Android and Wear and silently
 * fails on segmented networks). Two refinements over a bare announce loop:
 *
 *  - **Probe.** A viewer that has just started broadcasts a PROBE; every
 *    camera that hears it answers *immediately* with a unicast beacon. A camera
 *    is found in milliseconds, not after waiting out an announce interval.
 *  - **Authentication.** See [BeaconCodec]. Strangers' packets are ignored.
 *
 * Wi-Fi power-save drops inbound broadcast while the screen is off, which is
 * the normal state of a camera; on Android the platform layer holds a
 * multicast lock for as long as this is running.
 */
class Beacon(
    private val self: DeviceId,
    private val group: GroupKey,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val port: Int = DEFAULT_PORT,
    /** Where to send. Defaults to the limited and subnet broadcast addresses; tests pass loopback. */
    private val targets: () -> List<InetSocketAddress> = { broadcastTargets(DEFAULT_PORT) },
    private val announceMs: Long = ANNOUNCE_MS,
    private val expireMs: Long = EXPIRE_MS
) {
    private val _peers = MutableStateFlow<Map<DeviceId, DiscoveredPeer>>(emptyMap())

    /** Cameras heard from recently, never including this device. */
    val peers: StateFlow<Map<DeviceId, DiscoveredPeer>> = _peers.asStateFlow()

    @Volatile private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var announceJob: Job? = null
    private var reapJob: Job? = null
    private val seq = AtomicInteger()

    @Volatile private var advertisedPort = 0
    @Volatile private var advertisedName = ""
    @Volatile private var advertising = false

    /** Why [start] failed, for diagnostics. */
    @Volatile var startError: Throwable? = null
        private set

    /** Binds the socket and starts listening. Returns false if the port is unavailable (then discovery is just off). */
    fun start(): Boolean {
        if (socket != null) return true
        val s = try {
            // Not apply{}: inside it, a bare `port` is the socket's own (unconnected) getPort() = -1.
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.broadcast = true
            sock.bind(InetSocketAddress(port))
            sock
        } catch (e: Exception) {
            startError = e
            return false
        }
        socket = s

        listenJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(512)
            while (isActive && !s.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: Exception) {
                    if (s.isClosed) break
                    continue
                }
                onPacket(packet)
            }
        }

        reapJob = scope.launch {
            while (isActive) {
                delay(expireMs / 2)
                val cutoff = clock.nowMs() - expireMs
                _peers.update { m -> m.filterValues { it.seenMs > cutoff } }
            }
        }
        return true
    }

    /** Camera role: announce [tcpPort] and [name] until [stopAdvertising]. Calling again just updates them. */
    fun advertise(tcpPort: Int, name: String) {
        advertisedPort = tcpPort
        advertisedName = name
        if (advertising) return
        advertising = true
        announceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendNow(BeaconCodec.TYPE_BEACON, advertisedPort, advertisedName)
                delay(announceMs)
            }
        }
    }

    fun stopAdvertising() {
        advertising = false
        announceJob?.cancel()
        announceJob = null
    }

    /** Viewer role: "who is there?" Cameras answer at once. Call on start, on foreground, after Wi-Fi returns. */
    fun probe() {
        scope.launch(Dispatchers.IO) { sendNow(BeaconCodec.TYPE_PROBE, 0, "") }
    }

    fun stop() {
        stopAdvertising()
        listenJob?.cancel()
        reapJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        _peers.value = emptyMap()
    }

    private fun sendNow(type: Int, tcpPort: Int, name: String) {
        val s = socket ?: return
        val bytes = BeaconCodec.encode(type, group, self, tcpPort, name, seq.incrementAndGet().toLong())
        for (target in targets()) {
            runCatching { s.send(DatagramPacket(bytes, bytes.size, target)) }
        }
    }

    private fun onPacket(packet: DatagramPacket) {
        val d = BeaconCodec.decode(packet.data, packet.length, group) ?: return
        if (d.id == self) return // our own broadcast, echoed back

        when (d.type) {
            BeaconCodec.TYPE_BEACON -> {
                if (d.tcpPort == 0) return
                val host = packet.address.hostAddress ?: return
                val peer = DiscoveredPeer(d.id, host, d.tcpPort, d.name, clock.nowMs())
                _peers.update { it + (d.id to peer) }
            }

            BeaconCodec.TYPE_PROBE -> {
                if (!advertising) return
                // Answer the prober directly, right now.
                val s = socket ?: return
                val reply = BeaconCodec.encode(
                    BeaconCodec.TYPE_BEACON, group, self, advertisedPort, advertisedName, seq.incrementAndGet().toLong()
                )
                runCatching { s.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
            }
        }
    }

    companion object {
        /** Unprivileged, unlikely to collide. Same as the earlier app so the two can coexist on one network. */
        const val DEFAULT_PORT = 47_820
        const val ANNOUNCE_MS = 3_000L
        const val EXPIRE_MS = 10_000L

        /** Limited broadcast plus each interface's own subnet broadcast: some routers drop one but not the other. */
        fun broadcastTargets(port: Int): List<InetSocketAddress> {
            val out = LinkedHashSet<InetSocketAddress>()
            out += InetSocketAddress(InetAddress.getByName("255.255.255.255"), port)
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val b = ia.broadcast
                        if (b is Inet4Address) out += InetSocketAddress(b, port)
                    }
                }
            }
            return out.toList()
        }
    }
}
