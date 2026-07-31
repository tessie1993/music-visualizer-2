package dev.musicviz

import dev.musicviz.render.SwitchTiming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchTimingTest {
    private val quiet = 0f
    private val hit = SwitchTiming.STRONG_MOMENT_IMPULSE

    // ---- plain timer ----

    @Test
    fun a_plain_timer_switches_exactly_on_the_interval() {
        assertFalse(SwitchTiming.isDue(19_999, 20_000, onStrongMoment = false, beatImpulse = quiet, minDwellFloorMs = 8_000))
        assertTrue(SwitchTiming.isDue(20_000, 20_000, onStrongMoment = false, beatImpulse = quiet, minDwellFloorMs = 8_000))
    }

    @Test
    fun a_plain_timer_ignores_the_music() {
        // A huge beat one second in must not shortcut a 20 s timer.
        assertFalse(SwitchTiming.isDue(1_000, 20_000, onStrongMoment = false, beatImpulse = 1f, minDwellFloorMs = 8_000))
    }

    // ---- strong-moment mode ----

    @Test
    fun a_big_beat_switches_once_the_dwell_has_passed() {
        // interval 20 s -> dwell = max(8 s, 10 s) = 10 s.
        assertFalse(SwitchTiming.isDue(9_999, 20_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = 8_000))
        assertTrue(SwitchTiming.isDue(10_000, 20_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = 8_000))
    }

    @Test
    fun a_small_beat_never_switches_on_its_own() {
        val justUnder = SwitchTiming.STRONG_MOMENT_IMPULSE - 0.01f
        assertFalse(SwitchTiming.isDue(15_000, 20_000, onStrongMoment = true, beatImpulse = justUnder, minDwellFloorMs = 8_000))
    }

    @Test
    fun a_quiet_passage_still_rotates_at_twice_the_interval() {
        assertFalse(SwitchTiming.isDue(39_999, 20_000, onStrongMoment = true, beatImpulse = quiet, minDwellFloorMs = 8_000))
        assertTrue(SwitchTiming.isDue(40_000, 20_000, onStrongMoment = true, beatImpulse = quiet, minDwellFloorMs = 8_000))
    }

    @Test
    fun the_dwell_floor_wins_for_short_intervals() {
        // interval 5 s -> half is 2.5 s, so the caller's 8 s floor governs. A
        // hit at 3 s must not switch; one at 8 s must.
        assertFalse(SwitchTiming.isDue(3_000, 5_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = 8_000))
        assertTrue(SwitchTiming.isDue(8_000, 5_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = 8_000))
    }

    @Test
    fun the_dwell_scales_with_long_intervals_past_the_floor() {
        // interval 300 s (the slider maximum) -> dwell 150 s, not the 8 s floor.
        assertFalse(SwitchTiming.isDue(100_000, 300_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = 8_000))
        assertTrue(SwitchTiming.isDue(150_000, 300_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = 8_000))
    }

    @Test
    fun random_modes_lower_floor_switches_sooner_than_the_playlists() {
        // Same 10 s interval and the same hit: Random (6 s floor) is due, the
        // visual playlist (8 s floor) is not. Both floors beat interval/2 = 5 s.
        val args = { floor: Long -> SwitchTiming.isDue(6_000, 10_000, onStrongMoment = true, beatImpulse = hit, minDwellFloorMs = floor) }
        assertTrue(args(6_000))
        assertFalse(args(8_000))
    }

    @Test
    fun nothing_is_due_at_the_moment_of_a_switch() {
        for (interval in listOf(5_000L, 20_000L, 300_000L)) {
            for (strong in listOf(false, true)) {
                assertFalse(
                    "interval=$interval strong=$strong",
                    SwitchTiming.isDue(0, interval, strong, beatImpulse = 1f, minDwellFloorMs = 6_000),
                )
            }
        }
    }
}
