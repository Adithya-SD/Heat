package app.heat.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.heat.core.link.Acceptor
import app.heat.core.link.ByteDuplex
import app.heat.core.link.CarrierKind
import app.heat.core.link.Dialer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Wire constants of the direct Bluetooth carrier. Part of the protocol: never change them. */
object RfcommNames {
    val SERVICE_UUID: UUID = UUID.fromString("0b3f6a20-7a4e-4c53-9a1d-5ea7c0de4854")
    const val SERVICE_NAME = "Heat"
}

/** Android 12 made talking to a paired Bluetooth device a runtime permission; before that it was granted at install. */
fun hasBluetoothConnect(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/**
 * Opens a Bluetooth (RFCOMM) socket straight to the paired phone.
 *
 * This is the fast road over Bluetooth. The Wearable Data Layer is convenient, but every message crosses Play
 * services on both devices first, which costs seconds to start (its first call alone took 1.5 to 5 s on a
 * watch) and a few hundred milliseconds per message. A plain socket to a device we are already bonded with has
 * neither cost: it connects in tens to hundreds of milliseconds and carries a message in tens.
 *
 * The session protocol on top is identical: the link is authenticated and encrypted by our own handshake, so the
 * socket is opened in the insecure (no link-level pairing step) mode: nothing the Bluetooth layer adds is relied on.
 *
 * Which bonded device is the phone is assumed, then remembered: the one that worked last time is tried first
 * ([Hints]); otherwise phones before anything else.
 */
@SuppressLint("MissingPermission")
class RfcommDialer(context: Context, private val hints: Hints) : Dialer {
    override val kind = CarrierKind.BT_DIRECT
    private val appContext = context.applicationContext

    @Volatile private var failuresInARow = 0

    override suspend fun dial(): ByteDuplex {
        if (!hasBluetoothConnect(appContext)) throw IOException("Bluetooth permission not granted")
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")

        val bonded = adapter.bondedDevices.orEmpty().toList()
        val hinted = hints.btAddress?.let { a -> bonded.firstOrNull { it.address == a } }
        val phones = bonded.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        // Once the phone's address is known it is the only device worth dialing: every other paired device (headphones, a
        // car) hangs for the full connect timeout before failing, so trying them after a refused dial turned one failed
        // attempt into eight seconds. The others are only a fallback when there is no known phone, or it keeps failing.
        val others = (phones + bonded).distinctBy { it.address }.filter { it.address != hinted?.address }
        val order = when {
            hinted == null -> others.take(MAX_TRIES)
            failuresInARow >= FALLBACK_AFTER -> (listOf(hinted) + others).take(MAX_TRIES)
            else -> listOf(hinted)
        }
        if (order.isEmpty()) throw IOException("no paired devices")

        var failure: IOException? = null
        for (device in order) {
            try {
                return connect(adapter, device).also { failuresInARow = 0 }
            } catch (e: IOException) {
                failure = e
            }
        }
        failuresInARow++
        throw failure ?: IOException("no paired phone answered")
    }

    private suspend fun connect(adapter: android.bluetooth.BluetoothAdapter, device: BluetoothDevice): ByteDuplex =
        withContext(Dispatchers.IO) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { adapter.cancelDiscovery() }                 // a running scan makes connecting several times slower
            val socket = device.createInsecureRfcommSocketToServiceRecord(RfcommNames.SERVICE_UUID)
            var ok = false
            try {
                // connect() cannot be cancelled from the outside; closing the socket is the only way to cut it short.
                coroutineScope {
                    val watchdog = launch(Dispatchers.Default) {
                        delay(CONNECT_TIMEOUT_MS)
                        runCatching { socket.close() }
                    }
                    try {
                        socket.connect()
                    } finally {
                        watchdog.cancel()
                    }
                }
                ok = true
                hints.btAddress = device.address
                Log.i(TAG, "direct Bluetooth connected in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                StreamDuplex(socket.inputStream, socket.outputStream, label = "rfcomm") { socket.close() }
            } finally {
                if (!ok) runCatching { socket.close() }
            }
        }

    private companion object {
        const val TAG = "HeatRfcomm"
        const val MAX_TRIES = 3
        const val FALLBACK_AFTER = 4
        const val CONNECT_TIMEOUT_MS = 4_000L
    }
}

/**
 * The phone's end: listens for the watch on the same service id and hands every connection to the [Acceptor]
 * for authentication. Self-healing: if Bluetooth is switched off and on, or the permission is granted late, the
 * listener simply starts when it can.
 */
@SuppressLint("MissingPermission")
class RfcommListener(context: Context, private val scope: CoroutineScope, private val acceptor: Acceptor) {
    private val appContext = context.applicationContext
    private var job: Job? = null
    @Volatile private var server: BluetoothServerSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
    }

    private suspend fun loop() {
        var backoff = 500L
        while (scope.isActive) {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled || !hasBluetoothConnect(appContext)) {
                delay(5_000)
                continue
            }
            val s = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord(RfcommNames.SERVICE_NAME, RfcommNames.SERVICE_UUID)
            } catch (e: IOException) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(10_000)
                continue
            }
            server = s
            backoff = 500
            Log.i(TAG, "listening for the watch over Bluetooth")
            try {
                while (scope.isActive) {
                    val socket = s.accept()                       // blocks until a watch connects
                    Log.i(TAG, "watch connected over Bluetooth")
                    acceptor.accept(CarrierKind.BT_DIRECT, StreamDuplex(socket.inputStream, socket.outputStream, label = "rfcomm") { socket.close() })
                }
            } catch (e: IOException) {
                // The server socket closed (Bluetooth toggled, or stop()): go round and listen again if still wanted.
            } finally {
                runCatching { s.close() }
                server = null
            }
            delay(300)
        }
    }

    private companion object {
        const val TAG = "HeatRfcomm"
    }
}
