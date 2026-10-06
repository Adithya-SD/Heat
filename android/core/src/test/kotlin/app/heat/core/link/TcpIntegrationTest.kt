package app.heat.core.link

import app.heat.core.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The whole stack on real TCP sockets, in real time: what a phone and a watch on Wi-Fi actually do. */
class TcpIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val group = GroupKey.generate()
    private val camera = DeviceId.random()
    private val viewer = DeviceId.random()

    @After
    fun tearDown() = scope.cancel()

    private class Events : PeerListener {
        val messages = CopyOnWriteArrayList<String>()
        val media = CopyOnWriteArrayList<Long>()
        override fun onMessage(data: ByteArray, offset: Int, length: Int) { messages += String(data, offset, length) }
        override fun onMedia(kind: CarrierKind, stream: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) { media += ptsUs }
    }

    private class CameraRig(val scope: CoroutineScope, group: GroupKey, camera: DeviceId, port: Int) {
        val events = Events()
        val registry = ConversationRegistry(Clock.SYSTEM, scope) { events }
        val listener = TcpListener(scope, Acceptor(camera, group, scope, handler = registry.acceptHandler()), port)
        fun start(): Boolean = listener.start()
        fun stop() { listener.stop(); registry.closeAll() }
    }

    private suspend fun <T> eventually(ms: Long = 8_000, f: () -> T?): T = withTimeout(ms) {
        var v = f()
        while (v == null) { delay(10); v = f() }
        v
    }

    private fun link(port: Int, ev: Events, key: GroupKey = group): PeerLink {
        val l = PeerLink(camera, viewer, key, scope, Clock.SYSTEM, ev)
        l.setDialer(CarrierKind.LAN, TcpDialer("127.0.0.1", port))
        return l
    }

    @Test
    fun messages_and_video_flow_over_real_sockets() = runBlocking {
        val cam = CameraRig(scope, group, camera, 0)
        assertTrue(cam.start())
        val ev = Events()
        val l = link(cam.listener.port, ev)
        l.start()
        eventually { if (l.state.value.phase == LinkState.Phase.UP) true else null }

        l.send("arm".toByteArray())
        eventually { if (cam.events.messages.contains("arm")) true else null }

        val toViewer = cam.registry.forPeer(viewer)
        toViewer.send("armed".toByteArray())
        eventually { if (ev.messages.contains("armed")) true else null }

        val frame = ByteArray(40_000) { it.toByte() }
        eventually { toViewer.sendMedia(Stream.VIDEO, MediaFlags.KEYFRAME, 42, frame, 0, frame.size).takeIf { it } }
        eventually { if (42L in ev.media) true else null }

        l.stop(); cam.stop()
    }

    @Test
    fun a_wrong_key_over_real_sockets_is_unpaired() = runBlocking {
        val cam = CameraRig(scope, group, camera, 0)
        cam.start()
        val l = link(cam.listener.port, Events(), key = GroupKey.generate())
        l.start()
        eventually { if (l.state.value.phase == LinkState.Phase.UNPAIRED) true else null }
        assertEquals(HandshakeFailure.AUTH, l.lastFailure)
        l.stop(); cam.stop()
    }

    @Test
    fun the_viewer_reconnects_when_the_camera_app_is_killed_and_restarted() = runBlocking {
        val cam1 = CameraRig(scope, group, camera, 0)
        cam1.start()
        val port = cam1.listener.port
        val ev = Events()
        val l = link(port, ev)
        l.start()
        eventually { if (l.state.value.phase == LinkState.Phase.UP) true else null }

        // The camera "dies": listener closed, every session killed, no goodbye.
        cam1.listener.stop()
        cam1.registry.all().values.forEach { c -> c.sessions.live().forEach { it.session.close() } }
        eventually { if (l.state.value.phase != LinkState.Phase.UP) true else null }

        // A command issued while the camera is gone must not be lost.
        l.send("while-you-were-out".toByteArray())

        // It comes back on the same fixed port with a fresh process state.
        val cam2 = CameraRig(scope, group, camera, port)
        assertTrue("port $port must be reusable right after a restart", cam2.start())
        eventually(15_000) { if (l.state.value.phase == LinkState.Phase.UP) true else null }
        eventually { if (cam2.events.messages.contains("while-you-were-out")) true else null }

        l.stop(); cam1.stop(); cam2.stop()
    }

    @Test
    fun closing_the_link_unblocks_a_reader_stuck_in_a_socket() = runBlocking {
        val cam = CameraRig(scope, group, camera, 0)
        cam.start()
        val l = link(cam.listener.port, Events())
        l.start()
        eventually { if (l.state.value.phase == LinkState.Phase.UP) true else null }
        val t0 = System.nanoTime()
        l.stop()
        eventually(3_000) { if (l.state.value.phase == LinkState.Phase.IDLE) true else null }
        assertTrue("stop must be prompt, took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 2_000)
        cam.stop()
    }

    @Test
    fun a_silent_tcp_stranger_is_dropped_quickly_and_does_not_block_real_viewers() = runBlocking {
        val cam = CameraRig(scope, group, camera, 0)
        cam.start()
        val strangers = (1..12).map { java.net.Socket("127.0.0.1", cam.listener.port) }
        val ev = Events()
        val l = link(cam.listener.port, ev)
        l.start()
        eventually(8_000) { if (l.state.value.phase == LinkState.Phase.UP) true else null }
        strangers.forEach { runCatching { it.close() } }
        l.stop(); cam.stop()
    }
}
