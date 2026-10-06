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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class BeaconTest {
    private val group = GroupKey.generate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun freePort(): Int {
        val s = DatagramSocket(0)
        val p = s.localPort
        s.close()
        check(p > 0) { "no free port: $p" }
        return p
    }
    private val lo = InetAddress.getLoopbackAddress()

    // ── codec ────────────────────────────────────────────────────────

    @Test
    fun a_beacon_round_trips() {
        val id = DeviceId.random()
        val bytes = BeaconCodec.encode(BeaconCodec.TYPE_BEACON, group, id, 47821, "Porch cam", 7)
        val d = BeaconCodec.decode(bytes, bytes.size, group)!!
        assertEquals(id, d.id)
        assertEquals(47821, d.tcpPort)
        assertEquals("Porch cam", d.name)
        assertEquals(7L, d.seq)
    }

    @Test
    fun a_beacon_from_another_group_is_ignored() {
        val bytes = BeaconCodec.encode(BeaconCodec.TYPE_BEACON, GroupKey.generate(), DeviceId.random(), 1, "x", 1)
        assertNull(BeaconCodec.decode(bytes, bytes.size, group))
    }

    @Test
    fun a_forged_or_tampered_beacon_is_rejected() {
        val bytes = BeaconCodec.encode(BeaconCodec.TYPE_BEACON, group, DeviceId.random(), 47821, "cam", 1)
        // Redirect the camera to an attacker's port: same group tag, but the MAC no longer matches.
        val tampered = bytes.copyOf().also { it[4 + GroupKey.TAG_SIZE + DeviceId.SIZE + 1] = 99 }
        assertNull(BeaconCodec.decode(tampered, tampered.size, group))
        // An attacker who only knows the (public) tag cannot forge one.
        val forged = bytes.copyOf().also { for (i in it.size - 8 until it.size) it[i] = 0 }
        assertNull(BeaconCodec.decode(forged, forged.size, group))
    }

    @Test
    fun junk_never_throws() {
        val rnd = java.util.Random(5)
        repeat(2_000) {
            val junk = ByteArray(rnd.nextInt(80)).also(rnd::nextBytes)
            BeaconCodec.decode(junk, junk.size, group)
        }
        assertNull(BeaconCodec.decode(ByteArray(0), 0, group))
        assertNull(BeaconCodec.decode("HEATCAM|1|id|label|1.2.3.4:5:token".toByteArray(), 36, group)) // old app's format
    }

    @Test
    fun long_names_are_truncated_not_rejected() {
        val bytes = BeaconCodec.encode(BeaconCodec.TYPE_BEACON, group, DeviceId.random(), 1, "n".repeat(100), 1)
        assertEquals(24, BeaconCodec.decode(bytes, bytes.size, group)!!.name.length)
    }

    // ── live ─────────────────────────────────────────────────────────

    private fun beacon(id: DeviceId, port: Int, sendTo: List<Int>, key: GroupKey = group, expireMs: Long = 10_000) =
        Beacon(id, key, scope, Clock.SYSTEM, port, { sendTo.map { InetSocketAddress(lo, it) } }, announceMs = 100, expireMs = expireMs)

    private suspend fun <T> eventually(ms: Long = 3_000, f: () -> T?): T = withTimeout(ms) {
        var v = f()
        while (v == null) { delay(10); v = f() }
        v
    }

    @Test
    fun a_camera_is_discovered_by_its_announcement() = runBlocking {
        val camId = DeviceId.random(); val viewerId = DeviceId.random()
        val pc = freePort(); val pv = freePort()
        val cam = beacon(camId, pc, listOf(pv)); val viewer = beacon(viewerId, pv, listOf(pc))
        val ok = cam.start() && viewer.start()
        assertTrue("cam=${cam.startError} viewer=${viewer.startError}", ok)
        cam.advertise(47821, "Garage")
        val peer = eventually { viewer.peers.value[camId] }
        assertEquals("127.0.0.1", peer.host)
        assertEquals(47821, peer.tcpPort)
        assertEquals("Garage", peer.name)
        cam.stop(); viewer.stop()
    }

    @Test
    fun a_probe_gets_an_immediate_answer_without_waiting_for_the_next_announcement() = runBlocking {
        val camId = DeviceId.random(); val viewerId = DeviceId.random()
        val pc = freePort(); val pv = freePort()
        // The camera announces only every 60 s, so only a probe reply can explain a quick discovery.
        val cam = Beacon(camId, group, scope, Clock.SYSTEM, pc, { listOf(InetSocketAddress(lo, pv)) }, announceMs = 60_000)
        val viewer = beacon(viewerId, pv, listOf(pc))
        cam.start(); viewer.start()
        cam.advertise(47821, "Porch")
        delay(300)
        viewer.stop(); viewer.start()   // forget what the first announcement told us
        assertTrue(viewer.peers.value.isEmpty())
        viewer.probe()
        val peer = eventually(1_000) { viewer.peers.value[camId] }
        assertEquals("Porch", peer.name)
        cam.stop(); viewer.stop()
    }

    @Test
    fun a_stranger_camera_is_never_listed() = runBlocking {
        val pc = freePort(); val pv = freePort()
        val stranger = beacon(DeviceId.random(), pc, listOf(pv), key = GroupKey.generate())
        val viewer = beacon(DeviceId.random(), pv, listOf(pc))
        stranger.start(); viewer.start()
        stranger.advertise(47821, "Evil twin")
        delay(500)
        assertTrue(viewer.peers.value.isEmpty())
        stranger.stop(); viewer.stop()
    }

    @Test
    fun a_camera_that_goes_quiet_expires() = runBlocking {
        val camId = DeviceId.random()
        val pc = freePort(); val pv = freePort()
        val cam = beacon(camId, pc, listOf(pv)); val viewer = beacon(DeviceId.random(), pv, listOf(pc), expireMs = 600)
        cam.start(); viewer.start()
        cam.advertise(47821, "Cam")
        eventually { viewer.peers.value[camId] }
        cam.stopAdvertising()
        eventually(3_000) { if (viewer.peers.value.isEmpty()) true else null }
        cam.stop(); viewer.stop()
    }

    @Test
    fun a_device_never_lists_itself() = runBlocking {
        val id = DeviceId.random()
        val p = freePort()
        val both = beacon(id, p, listOf(p))
        both.start(); both.advertise(47821, "Me")
        delay(500)
        assertFalse(id in both.peers.value)
        both.stop()
    }

    @Test
    fun a_port_that_is_already_taken_fails_softly() {
        val taken = DatagramSocket(0)
        val b = beacon(DeviceId.random(), taken.localPort, emptyList())
        // reuseAddress may allow sharing on some OSes; either way it must not throw.
        runCatching { b.start() }.also { assertNotNull(it.getOrNull()) }
        b.stop(); taken.close()
    }
}
