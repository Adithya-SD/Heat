package app.heat.core.link

import app.heat.core.host.CameraBackend
import app.heat.core.host.CameraClient
import app.heat.core.host.CameraHost
import app.heat.core.host.VideoSink
import app.heat.core.model.CameraConfig
import app.heat.core.model.Tier
import app.heat.core.room.RoomCode
import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The browser's implementation of the session protocol (public/index.html, `HL`) against this one, over real
 * sockets. Run as a child Node process; skipped if Node is not installed. If these pass, a web page and the phone
 * app can talk: same handshake, same records, same reliable control, same media framing.
 */
class JsInteropTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val script = File("../../tests/hl-interop.js").canonicalFile
    private val code = "ABCDEFGHJKMN"
    private val procs = ArrayList<Process>()

    @After
    fun tearDown() { procs.forEach { it.destroyForcibly() }; scope.cancel() }

    private fun node(vararg args: String): Process {
        val p = ProcessBuilder(listOf("node", script.path) + args).redirectErrorStream(true).start()
        procs += p
        return p
    }

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "-v").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false) && script.exists()

    /** Reads the child's JSON lines on a thread so the test can assert on what it has said so far. */
    private class Lines(p: Process) {
        val all = CopyOnWriteArrayList<String>()
        init { Thread { p.inputStream.bufferedReader().useLines { it.forEach(all::add) } }.apply { isDaemon = true }.start() }
        fun find(ev: String): String? = all.firstOrNull { it.contains("\"ev\":\"$ev\"") }
        fun count(ev: String): Int = all.count { it.contains("\"ev\":\"$ev\"") }
    }

    private suspend fun <T> eventually(ms: Long = 20_000, f: () -> T?): T = withTimeout(ms) {
        var v = f()
        while (v == null) { delay(20); v = f() }
        v
    }

    private class FakeBackend(val host: () -> CameraHost?, val scope: CoroutineScope) : CameraBackend {
        val keyframes = AtomicInteger()
        val configs = CopyOnWriteArrayList<CameraConfig>()
        val armed = CopyOnWriteArrayList<Boolean>()
        override fun onConfig(new: CameraConfig, old: CameraConfig?) { configs += new }
        override fun onArmed(armed: Boolean) { this.armed += armed }
        override fun onStreaming(video: Boolean, audio: Boolean, tier: Tier) = Unit
        override fun requestKeyframe() { keyframes.incrementAndGet() }
        override fun snap() = Unit
        override fun identify() = Unit
    }

    private fun stacks(): String = Thread.getAllStackTraces().entries
        .filter { (t, st) -> st.any { it.className.startsWith("app.heat") } }
        .joinToString("\n", prefix = "\n") { (t, st) -> "${t.state} ${t.name}: " + st.take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } }

    @Test
    fun a_browser_can_watch_and_control_the_real_camera_host() = runBlocking {
        assumeTrue("Node is not installed; skipping the browser interop test", nodeAvailable())
        val camera = DeviceId.random()
        val group = RoomCode.groupKey(code)
        lateinit var host: CameraHost
        val backend = FakeBackend({ host }, scope)
        host = CameraHost({ "Phone" }, scope, Clock.SYSTEM, CameraConfig(), 0, backend)
        host.start()
        val listener = TcpListener(scope, Acceptor(camera, group, scope, handler = host.acceptHandler()), 0)
        assertTrue(listener.start())

        val lines = Lines(node("viewer", listener.port.toString(), code))
        // Play the encoder: a keyframe whenever one is asked for, deltas in between.
        val feeder = scope.launchFeeder(host, backend)
        try {
            eventually { lines.find("done") ?: lines.find("timeout")?.also { error("browser side gave up: ${lines.all}; host saw: ${host.lastClose}" + stacks()) } }
            assertTrue("hello from the camera: ${lines.all}", lines.find("hello")!!.contains("\"role\":\"CAMERA\""))
            assertTrue(lines.count("video") >= 4)
            assertEquals("the first picture the browser gets is a keyframe", true, lines.find("video")!!.contains("\"key\":1"))
            // The browser's settings change reached the camera, was acknowledged, and its arm request was honoured.
            assertTrue(lines.all.any { it.contains("\"ev\":\"cfg\"") && it.contains("\"ackSeq\":1") && it.contains("\"sensitivity\":77") })
            assertEquals(77, host.config.value.detect.sensitivity)
            assertTrue(host.armed.value)
        } finally {
            feeder.cancel()
            listener.stop()
            host.close()
        }
    }

    @Test
    fun the_real_phone_client_can_watch_and_control_a_browser_camera() = runBlocking {
        assumeTrue("Node is not installed; skipping the browser interop test", nodeAvailable())
        val cameraId = DeviceId.random()
        val lines = Lines(node("camera", code, cameraId.hex))
        val port = eventually { lines.find("listening") }.substringAfter("\"port\":").substringBefore('}').toInt()

        val client = CameraClient(cameraId, "Phone", DeviceId.random(), RoomCode.groupKey(code), scope, Clock.SYSTEM)
        val frames = AtomicInteger()
        var sawKey = false
        client.videoSink = VideoSink { flags, _, _, _, _ -> if (flags and MediaFlags.KEYFRAME != 0) sawKey = true; frames.incrementAndGet() }
        client.link.setDialer(CarrierKind.LAN, TcpDialer("127.0.0.1", port))
        client.start()
        try {
            eventually { if (client.hello.value?.name == "Web camera") true else null }
            eventually { client.config.value?.takeIf { it.label == "Web camera" } }
            eventually { client.status.value?.stream?.width?.takeIf { it == 640 } }
            client.watch(video = true)
            eventually { if (frames.get() >= 4) true else null }
            assertTrue("the browser camera's first frame is a keyframe", sawKey)

            client.edit { it.copy(detect = it.detect.copy(sensitivity = 90)) }
            eventually { if (client.config.value?.detect?.sensitivity == 90) true else null }
            client.arm(true)
            eventually { if (client.status.value?.armed == true) true else null }
            assertEquals(LinkState.Phase.UP, client.linkState.value.phase)
        } finally {
            client.stop()
        }
    }

    @Test
    fun a_browser_with_the_wrong_code_is_refused_by_the_real_camera() = runBlocking {
        assumeTrue("Node is not installed; skipping the browser interop test", nodeAvailable())
        val camera = DeviceId.random()
        lateinit var host: CameraHost
        host = CameraHost({ "Phone" }, scope, Clock.SYSTEM, CameraConfig(), 0, FakeBackend({ host }, scope))
        host.start()
        val listener = TcpListener(scope, Acceptor(camera, RoomCode.groupKey("ABCDEFGHJKMP"), scope, handler = host.acceptHandler()), 0)
        assertTrue(listener.start())
        val lines = Lines(node("viewer", listener.port.toString(), code))       // holds a different code
        try {
            eventually { lines.find("error") ?: lines.find("closed") ?: lines.find("timeout") }
            assertTrue("no picture for a stranger: ${lines.all}", lines.count("video") == 0)
            assertEquals(0, host.viewerCount.value)
        } finally {
            listener.stop(); host.close()
        }
    }

    // A tiny encoder stand-in: keyframes on request (and every second), deltas in between.
    private fun CoroutineScope.launchFeeder(host: CameraHost, backend: FakeBackend) = launch(Dispatchers.IO) {
        var n = 0
        var lastKeys = 0
        while (true) {
            val wanted = backend.keyframes.get()
            val key = wanted != lastKeys || n % 30 == 0
            lastKeys = wanted
            val data = ByteArray(if (key) 20_000 else 1_500) { (n and 0xFF).toByte() }
            host.sendVideo(if (key) MediaFlags.KEYFRAME else 0, n * 33_333L, data, 0, data.size)
            n++
            delay(33)
        }
    }
}
