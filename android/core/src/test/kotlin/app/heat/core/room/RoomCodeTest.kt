package app.heat.core.room

import app.heat.core.crypto.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCodeTest {

    @Test
    fun the_derivations_match_the_web_page_byte_for_byte() {
        // Computed independently with Node's crypto (PBKDF2-HMAC-SHA256); the page's `HL.derive` must agree.
        assertEquals("hl-4688b129da8518ae58cd", RoomCode.roomId("ABCDEFGHJKMN"))
        val key = RoomCode.groupKey("ABCDEFGHJKMN")
        assertEquals("e5f1d443861b37f4", Crypto.hex(key.tag))
    }

    @Test
    fun spacing_case_and_separators_do_not_change_the_room() {
        val a = RoomCode.roomId("ABCD-EFGH-JKMN")
        assertEquals(a, RoomCode.roomId("abcd efgh jkmn"))
        assertEquals(a, RoomCode.roomId("ABCDEFGHJKMN"))
        assertEquals(RoomCode.groupKey("ABCD-EFGH-JKMN"), RoomCode.groupKey("abcdefghjkmn"))
    }

    @Test
    fun different_codes_are_different_rooms_with_different_keys() {
        assertNotEquals(RoomCode.roomId("ABCDEFGHJKMN"), RoomCode.roomId("ABCDEFGHJKMP"))
        assertNotEquals(RoomCode.groupKey("ABCDEFGHJKMN"), RoomCode.groupKey("ABCDEFGHJKMP"))
    }

    @Test
    fun the_room_id_does_not_reveal_the_key_or_the_code() {
        val id = RoomCode.roomId("ABCDEFGHJKMN")
        assertFalse(id.contains("abcdefghjkmn", ignoreCase = true))
        assertFalse(id.contains(Crypto.hex(RoomCode.groupKey("ABCDEFGHJKMN").tag)))
    }

    @Test
    fun generated_codes_are_valid_unambiguous_and_different() {
        val seen = HashSet<String>()
        repeat(200) {
            val c = RoomCode.generate()
            assertEquals(12, c.length)
            assertTrue(c.all { it in RoomCode.ALPHABET })
            assertFalse(c.any { it in "0O1IL" })
            seen += c
        }
        assertTrue("codes must not repeat", seen.size > 195)
    }

    @Test
    fun the_symbols_are_used_evenly() {
        val counts = IntArray(RoomCode.ALPHABET.length)
        repeat(3000) { RoomCode.generate().forEach { counts[RoomCode.ALPHABET.indexOf(it)]++ } }
        val mean = 3000 * 12 / RoomCode.ALPHABET.length.toDouble()
        for (c in counts) assertTrue("symbol count $c vs mean $mean", c > mean * 0.85 && c < mean * 1.15)
    }

    @Test
    fun codes_are_cleaned_up_and_formatted_as_they_are_typed() {
        assertEquals("ABCD-EFGH-JKMN", RoomCode.format("abcdefghjkmn"))
        assertEquals("ABCD-EF", RoomCode.format("abcdef"))
        assertEquals("", RoomCode.format("0O1IL"))                 // none of those symbols exist
        assertEquals("ABCDEFGHJKMN", RoomCode.normalize("ab-cd ef:gh jk_mn xyz"))
        assertTrue(RoomCode.isComplete("ABCD-EFGH-JKMN"))
        assertFalse(RoomCode.isComplete("ABCD-EFGH"))
    }

    @Test
    fun a_code_is_found_in_an_invite_link_a_scheme_url_or_alone() {
        val code = "ABCDEFGHJKMN"
        assertEquals(code, RoomCode.parseInvite("https://heat-wheat.vercel.app/#link=ABCDEFGHJKMN"))
        assertEquals(code, RoomCode.parseInvite("  https://example.org/#link=abcd-efgh-jkmn&x=1 "))
        assertEquals(code, RoomCode.parseInvite("heat://link/ABCDEFGHJKMN"))
        assertEquals(code, RoomCode.parseInvite("ABCD-EFGH-JKMN"))
        assertNull(RoomCode.parseInvite("https://example.org/#link=ABCD"))
        assertNull(RoomCode.parseInvite("hello"))
        assertEquals("https://heat-wheat.vercel.app/#link=ABCDEFGHJKMN", RoomCode.inviteLink("abcd-efgh-jkmn", "https://heat-wheat.vercel.app/"))
    }
}
