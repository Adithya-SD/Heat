package app.heat.core.host

import app.heat.core.link.CarrierKind
import app.heat.core.model.StreamQuality
import app.heat.core.model.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPlanTest {
    private val wifi = StreamPlan.WIFI

    @Test
    fun bluetooth_gets_its_own_small_profile_wifi_the_wide_one() {
        assertEquals(StreamPlan.BLUETOOTH, StreamPlan.profileFor(CarrierKind.BLUETOOTH))
        assertEquals(StreamPlan.WIFI, StreamPlan.profileFor(CarrierKind.LAN))
        assertEquals(StreamPlan.WIFI, StreamPlan.profileFor(null))
        assertTrue(StreamPlan.BLUETOOTH.width < StreamPlan.WIFI.width)
    }

    @Test
    fun an_internet_viewer_is_offered_what_a_home_uplink_can_carry_not_what_a_lan_can() {
        assertEquals(StreamPlan.INTERNET_MAX_BPS, StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 0, CarrierKind.WEBRTC))
        assertTrue(StreamPlan.INTERNET_MAX_BPS < wifi.maxBps)
        assertEquals(wifi.maxBps, StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 0, CarrierKind.LAN))
        // the slowest road decides when viewers are on several
        val roads = listOf(CarrierKind.LAN, CarrierKind.WEBRTC, CarrierKind.BT_DIRECT)
        assertEquals(CarrierKind.BT_DIRECT, roads.minByOrNull { StreamPlan.carrierCap(it) })
        assertEquals(CarrierKind.WEBRTC, roads.dropLast(1).minByOrNull { StreamPlan.carrierCap(it) })
    }

    @Test
    fun the_ceiling_follows_the_most_modest_of_viewer_and_setting() {
        val max = StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 0)
        assertEquals(wifi.maxBps, max)
        assertEquals(StreamPlan.ceiling(wifi, Tier.LOW, StreamQuality.AUTO, 0, 0), StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.LOW, 0, 0))
        assertTrue(StreamPlan.ceiling(wifi, Tier.HIGH, StreamQuality.BALANCED, 0, 0) < StreamPlan.ceiling(wifi, Tier.HIGH, StreamQuality.HIGH, 0, 0))
    }

    @Test
    fun muddiness_and_heat_only_ever_lower_the_ceiling_and_never_below_the_floor() {
        val clean = StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 0)
        assertTrue(StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 60, 0) < clean)
        assertTrue(StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 2) < clean)
        assertEquals(wifi.minBps, StreamPlan.ceiling(wifi, Tier.LOW, StreamQuality.LOW, 100, 2))
    }

    @Test
    fun bluetooth_is_never_offered_more_than_it_can_carry_even_while_the_encoder_is_on_the_wide_profile() {
        val bt = StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 0, CarrierKind.BLUETOOTH)
        assertEquals(StreamPlan.BLUETOOTH.maxBps, bt)
        assertEquals(wifi.maxBps, StreamPlan.ceiling(wifi, Tier.MAX, StreamQuality.AUTO, 0, 0, CarrierKind.LAN))

        // The governor must be able to sit at a Bluetooth rate: its range is wider than the encoder profile's floor.
        val g = BitrateGovernor(StreamPlan.GOVERNOR_RANGE)
        assertTrue(g.sample(0, 0, bt, 1_000) <= bt)
        assertTrue(g.bps <= StreamPlan.BLUETOOTH.maxBps)
    }

    @Test
    fun a_slow_link_gets_fewer_keyframes() {
        assertTrue(StreamPlan.BLUETOOTH.gopSec > StreamPlan.WIFI.gopSec)
    }

    @Test
    fun the_governor_backs_off_fast_when_the_link_drops_frames() {
        val g = BitrateGovernor(wifi)
        g.reset(4_000_000)
        var t = 10_000L
        var offered = 0L; var dropped = 0L
        val before = g.bps
        repeat(3) {
            offered += 30; dropped += 15; t += 1_000
            g.sample(offered, dropped, wifi.maxBps, t)
        }
        assertTrue("${g.bps} should be well under $before", g.bps < before * 0.6)
        assertTrue(g.bps >= wifi.minBps)
    }

    @Test
    fun it_climbs_only_slowly_on_a_clean_link_and_never_past_the_ceiling() {
        val g = BitrateGovernor(wifi)
        g.reset(1_000_000)
        var t = 0L; var offered = 0L
        var last = g.bps
        var climbs = 0
        repeat(60) {
            offered += 30; t += 1_000
            val b = g.sample(offered, 0, 2_000_000, t)
            if (b > last) climbs++
            last = b
        }
        assertEquals(2_000_000, last)
        assertTrue("climbed in $climbs steps: gradual, not a jump", climbs >= 4)
    }

    @Test
    fun a_lowered_ceiling_applies_at_once() {
        val g = BitrateGovernor(wifi)
        g.reset(5_000_000)
        assertEquals(1_000_000, g.sample(0, 0, 1_000_000, 1_000))
    }

    @Test
    fun it_does_not_sawtooth_on_a_link_with_occasional_loss() {
        val g = BitrateGovernor(wifi)
        g.reset(2_000_000)
        var t = 0L; var offered = 0L; var dropped = 0L
        var last = g.bps
        var backOffs = 0
        repeat(180) { i ->
            offered += 30; t += 1_000
            if (i % 12 == 5) dropped += 5 // a burst of loss every 12 s, otherwise clean
            val b = g.sample(offered, dropped, wifi.maxBps, t)
            if (b < last) backOffs++
            last = b
        }
        // Without the soft cap this climbs to the ceiling after every loss and is cut again each time (~15 cuts in 3 minutes).
        assertTrue("back-offs=$backOffs", backOffs <= 8)
    }
}
