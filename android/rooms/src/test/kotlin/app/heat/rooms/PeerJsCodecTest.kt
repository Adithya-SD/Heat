package app.heat.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerJsCodecTest {

    @Test
    fun a_message_from_the_server_is_read() {
        val m = PeerJsCodec.decode("""{"type":"OFFER","src":"abc123","dst":"hl-1","payload":{"sdp":{"type":"offer","sdp":"v=0\r\n"},"type":"data","connectionId":"dc_42","label":"dc_42","serialization":"raw"}}""")!!
        assertEquals("OFFER", m.type)
        assertEquals("abc123", m.src)
        assertEquals("dc_42", m.connectionId)
        assertEquals("v=0\r\n", PeerJsCodec.sdpOf(m.payload))
    }

    @Test
    fun garbage_and_unknown_shapes_are_ignored_not_fatal() {
        assertNull(PeerJsCodec.decode("not json"))
        assertNull(PeerJsCodec.decode("""{"no":"type"}"""))
        val m = PeerJsCodec.decode("""{"type":"HEARTBEAT"}""")!!
        assertNull(m.src); assertNull(m.payload); assertNull(m.connectionId)
    }

    @Test
    fun an_offer_has_the_shape_the_page_expects_of_a_raw_data_connection() {
        val text = PeerJsCodec.encode("OFFER", "hl-room", PeerJsCodec.offer("v=0", "dc_7"))
        val m = PeerJsCodec.decode(text)!!
        assertEquals("OFFER", m.type)
        assertEquals("hl-room", m.dst)
        val p = m.payload!!
        assertEquals("data", p["type"].toString().trim('"'))
        assertEquals("raw", p["serialization"].toString().trim('"'))
        assertEquals("dc_7", p["label"].toString().trim('"'))
        assertEquals("true", p["reliable"].toString())
        assertEquals("v=0", PeerJsCodec.sdpOf(p))
    }

    @Test
    fun candidates_round_trip_with_their_media_line() {
        val payload = PeerJsCodec.candidate("candidate:1 1 UDP 1 10.0.0.2 5000 typ host", "0", 0, "dc_9")
        val c = PeerJsCodec.candidateOf(PeerJsCodec.decode(PeerJsCodec.encode("CANDIDATE", "x", payload))!!.payload)!!
        assertEquals("candidate:1 1 UDP 1 10.0.0.2 5000 typ host", c.candidate)
        assertEquals("0", c.sdpMid)
        assertEquals(0, c.sdpMLineIndex)
        assertNull(PeerJsCodec.candidateOf(null))
    }

    @Test
    fun the_signaling_url_matches_what_the_page_connects_to() {
        assertEquals("wss://0.peerjs.com:443/peerjs?key=peerjs&id=hl-abc&token=t0k", PeerServer().url("hl-abc", "t0k"))
        assertEquals("ws://localhost:9000/myapp/peerjs?key=k&id=i&token=t", PeerServer("localhost", 9000, "/myapp", false, "k").url("i", "t"))
    }

    @Test
    fun random_ids_are_peerjs_safe_and_different() {
        val ids = (1..50).map { randomId() }.toSet()
        assertEquals(50, ids.size)
        assertTrue(ids.all { it.length == 16 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } })
        assertNotNull(ids.first())
    }
}
