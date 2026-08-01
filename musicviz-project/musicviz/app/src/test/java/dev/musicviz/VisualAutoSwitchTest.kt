package dev.musicviz

import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.ui.AutoSwitch
import dev.musicviz.ui.MilkFile
import dev.musicviz.ui.Preset
import dev.musicviz.ui.RandomVizPicker
import dev.musicviz.ui.VizPlaylistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The timing rule and the draw behind the visual playlist and random mode,
 * pinned now that both live outside the ViewModel. Previously each was written
 * inline with its own magic numbers and could only be exercised through a live
 * player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisualAutoSwitchTest {
    /** Mirrors PlayerViewModel's STRONG_MOMENT_IMPULSE. */
    private val strongImpulse = 0.6f

    // ---- AutoSwitch ----

    @Test
    fun plain_interval_switching_waits_for_the_interval() {
        assertFalse(AutoSwitch.isDue(elapsedMs = 19_999, intervalMs = 20_000))
        assertTrue(AutoSwitch.isDue(elapsedMs = 20_000, intervalMs = 20_000))
    }

    @Test
    fun a_beat_before_the_dwell_floor_does_not_switch() {
        // 20 s interval -> dwell floor is max(8000, 10000) = 10 s.
        assertFalse(
            AutoSwitch.isDueOnMusic(
                elapsedMs = 9_000,
                intervalMs = 20_000,
                beatImpulse = 0.9f,
                minDwellMs = AutoSwitch.PLAYLIST_MIN_DWELL_MS,
                impulseThreshold = strongImpulse,
            ),
        )
    }

    @Test
    fun past_the_dwell_a_strong_moment_switches_but_a_weak_one_does_not() {
        fun due(impulse: Float) =
            AutoSwitch.isDueOnMusic(
                elapsedMs = 12_000,
                intervalMs = 20_000,
                beatImpulse = impulse,
                minDwellMs = AutoSwitch.PLAYLIST_MIN_DWELL_MS,
                impulseThreshold = strongImpulse,
            )
        assertTrue(due(strongImpulse))
        assertTrue(due(0.9f))
        assertFalse(due(strongImpulse - 0.01f))
        assertFalse(due(0f))
    }

    @Test
    fun a_silent_passage_still_rotates_at_twice_the_interval() {
        assertTrue(
            AutoSwitch.isDueOnMusic(
                elapsedMs = 40_000,
                intervalMs = 20_000,
                beatImpulse = 0f,
                minDwellMs = AutoSwitch.PLAYLIST_MIN_DWELL_MS,
                impulseThreshold = strongImpulse,
            ),
        )
    }

    @Test
    fun a_short_interval_cannot_drive_the_dwell_below_the_floor() {
        // 5 s interval: half of it is 2.5 s, but the floor holds at 8 s, so a
        // beat at 3 s must not switch. This is what stops random mode
        // strobing when the slider is at its minimum.
        assertFalse(
            AutoSwitch.isDueOnMusic(
                elapsedMs = 3_000,
                intervalMs = 5_000,
                beatImpulse = 0.9f,
                minDwellMs = AutoSwitch.PLAYLIST_MIN_DWELL_MS,
                impulseThreshold = strongImpulse,
            ),
        )
    }

    @Test
    fun random_mode_is_the_more_eager_of_the_two() {
        // Both modes now gate on the same track-relative impulse, so the dwell
        // floor is the only thing that still distinguishes them.
        assertTrue(AutoSwitch.RANDOM_MIN_DWELL_MS < AutoSwitch.PLAYLIST_MIN_DWELL_MS)
    }

    // ---- RandomVizPicker ----

    private val presets =
        listOf(
            Preset("Warm", SceneIds.NEBULA, 0.6f, 0.12f, null, SceneParams.DEFAULT),
            Preset("Cold", SceneIds.FLUID, 0.6f, 0.12f, null, SceneParams.DEFAULT),
        )

    @Test
    fun the_pool_is_styles_then_presets_then_milk() {
        val pool =
            RandomVizPicker.choices(
                styles = listOf(SceneIds.NEBULA, SceneIds.FLUID),
                presets = presets,
                milkFiles = listOf(MilkFile("Geiss", "/milk/geiss.milk")),
            )
        assertEquals(5, pool.size)
        assertEquals(listOf(SceneIds.NEBULA, SceneIds.FLUID), pool.take(2).map { it.sceneId })
        assertEquals(listOf("Warm", "Cold"), pool.subList(2, 4).map { it.presetName })
        assertEquals(SceneIds.MILKDROP, pool[4].sceneId)
        assertEquals("/milk/geiss.milk", pool[4].milkPath)
    }

    @Test
    fun excluded_categories_are_simply_absent() {
        val stylesOnly = RandomVizPicker.choices(listOf(SceneIds.NEBULA), emptyList(), emptyList())
        assertEquals(1, stylesOnly.size)
        assertNull(stylesOnly.single().presetName)
        assertNull(stylesOnly.single().milkPath)
    }

    @Test
    fun an_empty_pool_picks_nothing() {
        assertNull(RandomVizPicker.pick(emptyList(), SceneIds.NEBULA, Random(1)))
    }

    @Test
    fun a_pool_of_one_returns_it_even_when_it_is_already_showing() {
        // The retry must not loop forever on a single-entry pool.
        val only = RandomVizPicker.choices(listOf(SceneIds.NEBULA), emptyList(), emptyList())
        assertEquals(SceneIds.NEBULA, RandomVizPicker.pick(only, SceneIds.NEBULA, Random(7))?.sceneId)
    }

    @Test
    fun a_preset_on_the_current_style_is_never_treated_as_a_repeat() {
        // Only a BARE style counts as "already showing" - a preset changes the
        // look even when it targets the scene already on screen.
        val pool = RandomVizPicker.choices(emptyList(), presets, emptyList())
        repeat(20) { seed ->
            val pick = RandomVizPicker.pick(pool, SceneIds.NEBULA, Random(seed))
            assertNotNull(pick)
            assertNotNull(pick!!.presetName)
        }
    }

    @Test
    fun rolling_colors_clears_both_custom_palette_slots() {
        // A custom override outranks the PALETTES lookup, so a rolled index
        // would otherwise be invisible to anyone who built their own palette.
        val rolled = RandomVizPicker.rollColors(SceneParams.DEFAULT, Random(3))
        assertTrue(rolled.palette in SceneParams.PALETTES.indices)
        assertTrue(rolled.palette2 in SceneParams.PALETTES.indices)
        assertTrue(rolled.colorShift in 0f..1f)
        assertTrue(rolled.paletteMix in 0f..0.6f)
        assertEquals(SceneParams.UNSET_OVERRIDE, rolled.paletteBaseOverride, 0f)
    }

    @Test
    fun the_draw_is_reproducible_for_a_given_seed() {
        val pool = RandomVizPicker.choices(listOf(SceneIds.NEBULA, SceneIds.FLUID), presets, emptyList())
        assertEquals(
            RandomVizPicker.pick(pool, SceneIds.BURSTS, Random(42)),
            RandomVizPicker.pick(pool, SceneIds.BURSTS, Random(42)),
        )
    }

    @Test
    fun entries_carry_a_label_for_every_category() {
        val pool =
            RandomVizPicker.choices(
                styles = listOf(SceneIds.NEBULA),
                presets = presets.take(1),
                milkFiles = listOf(MilkFile("Geiss", "/milk/geiss.milk")),
            )
        assertTrue(pool.none { it.label.isBlank() })
        assertEquals(listOf(SceneIds.NEBULA, "Warm", "Geiss"), pool.map(VizPlaylistEntry::label))
    }
}
