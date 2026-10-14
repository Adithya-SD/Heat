package app.heat.core.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFeedTest {

    private fun FrameFeed.key(pts: Long) = offer(true, pts, ByteArray(100) { 1 }, 0, 100)
    private fun FrameFeed.delta(pts: Long) = offer(false, pts, ByteArray(10) { 2 }, 0, 10)
    private fun FrameFeed.drain(): List<Long> = generateSequence { next() }.map { it.ptsUs }.toList()

    @Test
    fun nothing_is_decodable_until_a_keyframe_has_been_seen_and_the_wait_is_silent() {
        val f = FrameFeed()
        assertEquals(FrameFeed.Offer.DROPPED, f.delta(1))
        assertEquals(FrameFeed.Offer.DROPPED, f.delta(2))      // no repeated cries for a keyframe
        f.start()
        assertNull(f.next())
        assertEquals(FrameFeed.Offer.ACCEPTED, f.key(3))
        assertEquals(listOf(3L), f.drain())
    }

    @Test
    fun a_decoder_that_starts_late_replays_the_whole_group_of_pictures_in_order() {
        val f = FrameFeed()
        f.key(10); f.delta(11); f.delta(12); f.delta(13)       // arrived while the screen was still opening
        f.start()
        assertEquals(listOf(10L, 11L, 12L, 13L), f.drain())
        f.delta(14)
        assertEquals(listOf(14L), f.drain())
    }

    @Test
    fun the_replay_starts_from_the_keyframe_not_from_a_delta() {
        val f = FrameFeed()
        f.key(1); f.delta(2); f.key(3); f.delta(4)             // the first group is superseded
        f.start()
        val frames = generateSequence { f.next() }.toList()
        assertEquals(listOf(3L, 4L), frames.map { it.ptsUs })
        assertTrue(frames.first().key)
    }

    @Test
    fun a_new_keyframe_makes_the_consumer_jump_over_frames_it_never_decoded() {
        val f = FrameFeed()
        f.start()
        f.key(1); f.delta(2); f.delta(3)
        assertEquals(1L, f.next()!!.ptsUs)                      // consumer is one frame in...
        f.key(4)                                                // ...and a fresh keyframe arrives
        assertEquals(listOf(4L), f.drain())                     // stale 2 and 3 are never fed: lag resets
    }

    @Test
    fun a_consumer_that_falls_behind_is_resynced_on_the_next_keyframe_not_left_running_late() {
        val f = FrameFeed(maxLive = 5)
        f.start()
        f.key(0)
        f.drain()                                               // caught up: live
        for (i in 1L..5L) assertEquals(FrameFeed.Offer.ACCEPTED, f.delta(i))
        assertEquals(FrameFeed.Offer.NEED_KEYFRAME, f.delta(6)) // 6 behind: give up on this group
        assertEquals(0, f.backlog)
        assertEquals(FrameFeed.Offer.DROPPED, f.delta(7))       // and only ask once
        assertEquals(FrameFeed.Offer.DROPPED, f.delta(8))
        assertEquals(FrameFeed.Offer.ACCEPTED, f.key(9))
        assertEquals(listOf(9L), f.drain())
    }

    @Test
    fun the_burst_of_a_replay_is_not_mistaken_for_lag() {
        val f = FrameFeed(maxLive = 5)
        f.key(0); for (i in 1L..20L) f.delta(i)
        f.start()                                               // 21 frames waiting, far more than maxLive
        assertEquals(FrameFeed.Offer.ACCEPTED, f.delta(21))     // a live frame arrives mid-replay
        assertEquals((0L..21L).toList(), f.drain())
    }

    @Test
    fun a_group_that_never_ends_is_bounded() {
        val f = FrameFeed(maxFrames = 30)
        f.key(0)
        var asked = 0
        for (i in 1L..100L) if (f.delta(i) == FrameFeed.Offer.NEED_KEYFRAME) asked++
        assertEquals(1, asked)
        assertTrue(f.backlog < 30)
    }

    @Test
    fun the_byte_budget_bounds_memory_too() {
        val f = FrameFeed(maxBytes = 1_000)
        f.key(0)
        var lost = false
        for (i in 1L..200L) if (f.delta(i) == FrameFeed.Offer.NEED_KEYFRAME) lost = true
        assertTrue(lost)
    }

    @Test
    fun frames_are_copied_in_so_the_producer_can_reuse_its_buffer() {
        val f = FrameFeed()
        val buf = ByteArray(4) { 7 }
        f.offer(true, 1, buf, 0, 4)
        buf.fill(0)
        f.start()
        assertEquals(listOf<Byte>(7, 7, 7, 7), f.next()!!.data.toList())
    }

    @Test
    fun stopping_keeps_the_group_so_the_next_start_still_has_a_picture() {
        val f = FrameFeed()
        f.start(); f.key(1); f.delta(2); f.drain()
        f.stop()                                                // screen off
        f.delta(3); f.delta(4)
        f.start()                                               // screen back on: one replay, instant picture
        assertEquals(listOf(1L, 2L, 3L, 4L), f.drain())
        assertNotNull(f.key(5))
    }

    @Test
    fun clear_forgets_everything_and_waits_for_a_keyframe_again() {
        val f = FrameFeed()
        f.key(1); f.delta(2)
        f.clear()
        f.start()
        assertNull(f.next())
        assertEquals(FrameFeed.Offer.DROPPED, f.delta(3))
    }
}
