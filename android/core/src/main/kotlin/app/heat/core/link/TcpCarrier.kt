package app.heat.core.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * A TCP socket as a [ByteDuplex].
 *
 * Blocking IO on the IO dispatcher. A blocked read cannot be cancelled from
 * the outside, so cancellation is done the only reliable way: [close] shuts
 * the socket, and the read throws immediately. Everything above relies on that.
 */
class TcpDuplex(private val socket: Socket) : ByteDuplex {
    private val input: InputStream
    private val output: OutputStream

    init {
        // Our records are already whole messages: Nagle would only add latency.
        runCatching { socket.tcpNoDelay = true }
        // Cheap insurance for sockets that die without a FIN; the real liveness check is the session's ping.
        runCatching { socket.keepAlive = true }
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) { input.read(buffer, offset, length) }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) =
        withContext(Dispatchers.IO) {
            output.write(buffer, offset, length)
            output.flush()
        }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** Dials one `host:port` over TCP. The endpoint is replaceable without touching the maintainer. */
class TcpDialer(
    val host: String,
    val port: Int,
    private val connectTimeoutMs: Int = 2_500
) : Dialer {
    override val kind = CarrierKind.LAN

    override suspend fun dial(): ByteDuplex = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        TcpDuplex(socket)
    }

    override fun toString() = "TcpDialer($host:$port)"
}

/** Accepts TCP connections and hands each to the [Acceptor] for authentication. */
class TcpListener(
    private val scope: CoroutineScope,
    private val acceptor: Acceptor,
    private val requestedPort: Int = DEFAULT_PORT
) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** The port actually bound (differs from the requested one if that was 0). */
    val port: Int get() = server?.localPort ?: -1

    /** Binds and starts accepting. Returns false if the port is unavailable. */
    fun start(): Boolean {
        if (server != null) return true
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(requestedPort))
            }
        } catch (e: IOException) {
            return false
        }
        server = s
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                acceptor.accept(CarrierKind.LAN, TcpDuplex(client))
            }
        }
        return true
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
    }

    companion object {
        /** Fixed, so a remembered camera can be dialed straight away after either end restarts. */
        const val DEFAULT_PORT = 47_821

        /** This device's best address on the local network, or null when it has none. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        fun loopback(): InetAddress = InetAddress.getLoopbackAddress()
    }
}
