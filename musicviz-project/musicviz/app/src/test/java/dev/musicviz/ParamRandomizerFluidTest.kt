package dev.musicviz

import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Guards the "Randomize unlocked" button against two bugs that made
 * customization feel broken on the fluid styles:
 *
 *  1. The randomizer touched *no* fluid parameter at all, so a roll did
 *     nothing visible on FLUID / CURLFLOW / WATER.
 *  2. Lock keys are slider label strings. A key that does not match its label
 *     verbatim turns the lock chip into a silent no-op - the exact regression
 *     fixed once before ("Fix randomizer lock keys to match slider labels").
 *     [every_lock_key_matches_a_customize_label] parses the labels straight
 *     out of `CustomizeTabs.kt` ([ParamSurface]) so a renamed slider fails the
 *     build instead of quietly unprotecting a parameter.
 *
 * It also pins the roll inside each slider's range (a roll the user cannot
 * reproduce or undo by hand is a bug), pins the settings the randomizer must
 * never touch, and pins both halves of the custom-palette rule: a roll never
 * *invents* override hues, but rolling a slot's built-in index does clear
 * that slot's override - otherwise the roll is invisible to every user who
 * made their own palette, since an override outranks the PALETTES lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParamRandomizerFluidTest {
    private class Knob(
        val key: String,
        val read: (SceneParams) -> Any,
    )

    /**
     * Fluid-family float knobs: key, accessor, and the slider's own range.
     *
     * "Ripple strength" (Water, 0..2) and "Ripple overlay strength"
     * (all-styles overlay, 0..1) are separate keys: they used to share a
     * label, which meant one lock chip froze both params and one roll wrote
     * both - see [locking_a_fluid_key_freezes_exactly_its_parameter].
     */
    private val floatKnobs: List<Triple<String, (SceneParams) -> Float, ClosedFloatingPointRange<Float>>> =
        listOf(
            Triple("Pressure", { p: SceneParams -> p.fluidPressure }, 0f..1f),
            Triple("Fluid curl", { p: SceneParams -> p.fluidCurl }, 0f..50f),
            Triple("Motion fade", { p: SceneParams -> p.fluidVelocityDissipation }, 0f..4f),
            Triple("Fluid fade", { p: SceneParams -> p.fluidDensityDissipation }, 0f..4f),
            Triple("Chromatic aging", { p: SceneParams -> p.fluidChromaticAging }, 0f..1f),
            Triple("Stirrer speed", { p: SceneParams -> p.fluidStirrerSpeed }, 0f..2f),
            Triple("Fluid splat radius", { p: SceneParams -> p.fluidSplatRadius }, 0.02f..0.4f),
            Triple("Fluid splat force", { p: SceneParams -> p.fluidSplatForce }, 0f..3f),
            Triple("Palette cycle", { p: SceneParams -> p.fluidPaletteCycleSpeed }, 0f..2f),
            Triple("Catch pull", { p: SceneParams -> p.fluidCatchPull }, 0f..3f),
            Triple("Catch radius", { p: SceneParams -> p.fluidCatchRadius }, 0.03f..0.3f),
            Triple("Particle life (s)", { p: SceneParams -> p.fluidParticleLife }, 1f..20f),
            Triple("Particle drag", { p: SceneParams -> p.fluidParticleDrag }, 0.02f..1f),
            Triple("Particle brightness", { p: SceneParams -> p.fluidParticleBrightness }, 0f..2f),
            Triple("Fluid glow", { p: SceneParams -> p.fluidBloomIntensity }, 0.1f..2f),
            Triple("Glow threshold", { p: SceneParams -> p.fluidBloomThreshold }, 0f..1f),
            Triple("Sunrays weight", { p: SceneParams -> p.fluidSunraysWeight }, 0.3f..1f),
            Triple("Curl from mids", { p: SceneParams -> p.fluidCurlAudio }, 0f..1f),
            Triple("Glow from loudness", { p: SceneParams -> p.fluidBloomAudio }, 0f..1f),
            Triple("Fade when quiet", { p: SceneParams -> p.fluidFadeAudio }, 0f..1f),
            Triple("Radius on beat", { p: SceneParams -> p.fluidRadiusPulse }, 0f..1f),
            Triple("Flow strength", { p: SceneParams -> p.flowStrength }, 0f..1f),
            Triple("Flow force", { p: SceneParams -> p.flowForce }, 0f..3f),
            Triple("Flow curl", { p: SceneParams -> p.flowCurl }, 0f..50f),
            Triple("Wave speed", { p: SceneParams -> p.waterWaveSpeed }, 0.2f..2f),
            Triple("Damping", { p: SceneParams -> p.waterDamping }, 0.9f..0.999f),
            Triple("Depth", { p: SceneParams -> p.waterDepth }, 0f..1f),
            Triple("Specular", { p: SceneParams -> p.waterSpecular }, 0f..1f),
            Triple("Flow drift", { p: SceneParams -> p.waterFlow }, 0f..1f),
            Triple("Liquid", { p: SceneParams -> p.waterLiquid }, 0f..1f),
            Triple("Liquid flow", { p: SceneParams -> p.waterLiquidFlow }, 0f..4f),
            Triple("Liquid fade", { p: SceneParams -> p.waterLiquidFade }, 0f..2f),
            Triple("Ripple glint", { p: SceneParams -> p.rippleOverlaySpecular }, 0f..1f),
            Triple("Ripple strength", { p: SceneParams -> p.waterRippleStrength }, 0f..2f),
            Triple("Ripple overlay strength", { p: SceneParams -> p.rippleOverlayStrength }, 0f..1f),
        )

    /** Fluid-family integer knobs: key, accessor, and the slider's own range. */
    private val intKnobs: List<Triple<String, (SceneParams) -> Int, IntRange>> =
        listOf(
            Triple("Solver iterations", { p: SceneParams -> p.fluidIterations }, 8..40),
            Triple("Beat splats", { p: SceneParams -> p.fluidBeatSplats }, 0..8),
            Triple("Stirrers", { p: SceneParams -> p.fluidStirrers }, 0..4),
            Triple("Spawn points", { p: SceneParams -> p.fluidSpawnPoints }, 1..8),
            Triple("Catch points", { p: SceneParams -> p.fluidCatchPoints }, 0..4),
            Triple("Beat pattern", { p: SceneParams -> p.fluidBeatPattern }, 0..SceneParams.FLUID_PATTERNS.size - 1),
            Triple("Path", { p: SceneParams -> p.fluidSpawnPath }, 0..SceneParams.FLUID_PATHS.size - 1),
        )

    /** Fluid-family boolean knobs. */
    private val boolKnobs: List<Pair<String, (SceneParams) -> Boolean>> =
        listOf(
            Pair("Bass pump", { p: SceneParams -> p.fluidBassPump }),
            Pair("Treble sparkle", { p: SceneParams -> p.fluidSparkle }),
            Pair("Shading (embossed ink)", { p: SceneParams -> p.fluidShading }),
            Pair("Glow (fluid)", { p: SceneParams -> p.fluidBloom }),
            Pair("Sunrays", { p: SceneParams -> p.fluidSunrays }),
            Pair("Particles ride the field", { p: SceneParams -> p.flowAdvectParticles }),
        )

    private fun allKnobs(): List<Knob> =
        floatKnobs.map { Knob(it.first, it.second) } +
            intKnobs.map { Knob(it.first, it.second) } +
            boolKnobs.map { Knob(it.first, it.second) }

    @Test
    fun randomizer_covers_every_fluid_family_parameter() {
        val base = SceneParams.DEFAULT
        val rng = Random(7)
        val rolls = List(200) { ParamRandomizer.randomize(base, emptySet(), rng) }
        val never =
            allKnobs()
                .filter { knob -> rolls.none { knob.read(it) != knob.read(base) } }
                .map { it.key }
        assertEquals("fluid parameters the randomizer never moves", emptyList<String>(), never.distinct())
    }

    @Test
    fun locking_a_fluid_key_freezes_exactly_its_parameter() {
        val base = SceneParams.DEFAULT
        for (knob in allKnobs()) {
            val rng = Random(11)
            repeat(60) {
                val rolled = ParamRandomizer.randomize(base, setOf(knob.key), rng)
                assertEquals(
                    "lock on \"${knob.key}\" did not hold",
                    knob.read(base),
                    knob.read(rolled),
                )
            }
        }
    }

    @Test
    fun rolls_stay_inside_the_slider_ranges() {
        val base = SceneParams.DEFAULT
        val rng = Random(23)
        repeat(300) {
            val p = ParamRandomizer.randomize(base, emptySet(), rng)
            for ((key, get, range) in floatKnobs) {
                val v = get(p)
                assertTrue("$key rolled $v outside $range", v in range)
            }
            for ((key, get, range) in intKnobs) {
                val v = get(p)
                assertTrue("$key rolled $v outside $range", v in range)
            }
        }
    }

    /**
     * A user-made palette, applied through the override fields. The hues are
     * chosen to appear nowhere in [SceneParams.PALETTES] so "the override is
     * gone" is observable in the resolved [SceneParams.paletteBase].
     */
    private fun withCustomPalettes(): SceneParams =
        SceneParams.DEFAULT.copy(
            paletteBaseOverride = 0.123f,
            paletteRangeOverride = 0.077f,
            palette2BaseOverride = 0.311f,
            palette2RangeOverride = 0.019f,
            customPaletteId = "my-palette",
            customPalette2Id = "my-other-palette",
        )

    @Test
    fun custom_palette_overrides_are_never_given_random_values() {
        // A roll must not invent hues for a palette the user built and saved.
        // With both palette slots locked, the override has to survive intact.
        val base = withCustomPalettes()
        val rng = Random(31)
        val locked = setOf("Palette", "Palette 2")
        repeat(200) {
            val p = ParamRandomizer.randomize(base, locked, rng)
            assertEquals(base.paletteBaseOverride, p.paletteBaseOverride, 0f)
            assertEquals(base.paletteRangeOverride, p.paletteRangeOverride, 0f)
            assertEquals(base.palette2BaseOverride, p.palette2BaseOverride, 0f)
            assertEquals(base.palette2RangeOverride, p.palette2RangeOverride, 0f)
            assertEquals(base.customPaletteId, p.customPaletteId)
            assertEquals(base.customPalette2Id, p.customPalette2Id)
            assertTrue("a locked custom palette must stay in use", p.usesCustomPalette)
            assertTrue("a locked custom palette must stay in use", p.usesCustomPalette2)
        }
    }

    @Test
    fun randomizing_a_palette_clears_that_slots_custom_override() {
        // An active override outranks the PALETTES lookup, so rolling the
        // index alone would be invisible to anyone using a custom palette.
        val base = withCustomPalettes()
        val rng = Random(37)
        repeat(200) {
            val p = ParamRandomizer.randomize(base, emptySet(), rng)
            assertFalse("slot 1 override survived a palette roll", p.usesCustomPalette)
            assertFalse("slot 2 override survived a palette roll", p.usesCustomPalette2)
            assertEquals(SceneParams.UNSET_OVERRIDE, p.paletteBaseOverride, 0f)
            assertEquals(SceneParams.UNSET_OVERRIDE, p.paletteRangeOverride, 0f)
            assertEquals(SceneParams.UNSET_OVERRIDE, p.palette2BaseOverride, 0f)
            assertEquals(SceneParams.UNSET_OVERRIDE, p.palette2RangeOverride, 0f)
            assertEquals(SceneParams.NO_CUSTOM_PALETTE, p.customPaletteId)
            assertEquals(SceneParams.NO_CUSTOM_PALETTE, p.customPalette2Id)
            // The resolved hues now come from the rolled PALETTES entry.
            assertNotEquals(base.paletteBase, p.paletteBase)
            assertNotEquals(base.paletteRange, p.paletteRange)
            assertNotEquals(base.palette2Base, p.palette2Base)
            assertNotEquals(base.palette2Range, p.palette2Range)
            assertEquals(SceneParams.PALETTES[p.palette].second, p.paletteBase, 0f)
            assertEquals(SceneParams.PALETTES[p.palette].third, p.paletteRange, 0f)
        }
    }

    @Test
    fun structural_and_performance_settings_are_never_rolled() {
        val base =
            SceneParams.DEFAULT.copy(
                fluidQuality = 4,
                fluidAutoQuality = false,
                fluidParticlesEnabled = false,
                fluidDyeEnabled = false,
                flowEnabled = true,
                rippleOverlayEnabled = true,
                fluidSpawnProgress = 0.25f,
                paramFadeSec = 2.5f,
            )
        val rng = Random(41)
        repeat(200) {
            val p = ParamRandomizer.randomize(base, emptySet(), rng)
            assertEquals(base.fluidQuality, p.fluidQuality)
            assertEquals(base.fluidAutoQuality, p.fluidAutoQuality)
            assertEquals(base.fluidParticlesEnabled, p.fluidParticlesEnabled)
            assertEquals(base.fluidDyeEnabled, p.fluidDyeEnabled)
            assertEquals(base.flowEnabled, p.flowEnabled)
            assertEquals(base.rippleOverlayEnabled, p.rippleOverlayEnabled)
            assertEquals(base.fluidSpawnProgress, p.fluidSpawnProgress, 0f)
            assertEquals(base.paramFadeSec, p.paramFadeSec, 0f)
        }
    }

    @Test
    fun lock_keys_are_unique() {
        // A key used twice would let one r() call silently undo another's lock.
        val dupes =
            ParamRandomizer.KEYS
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
        assertEquals("duplicate randomizer lock keys", emptySet<String>(), dupes)
    }

    @Test
    fun every_lock_key_matches_a_customize_label() {
        val labels = ParamSurface.allLockableLabels
        assertTrue("parsed too few Customize labels ($labels)", labels.size > 40)
        val missing = ParamRandomizer.KEYS.filter { it !in labels }
        assertEquals("randomizer lock keys with no matching Customize label", emptyList<String>(), missing)
    }

    /**
     * Every key the randomizer rolls must be lockable. The chip selectors
     * (Palette, Palette 2, Particle shape, Beat pattern, Path) rendered a
     * plain label with no lock chip, so those five params were rolled on every
     * press with no way to hold them - a user could not keep the palette they
     * had just chosen. They now render `LockableChipLabel`, which is why
     * [ParamSurface.allLockableLabels] counts them as lockable controls.
     */
    @Test
    fun chip_selector_keys_are_lockable_too() {
        val labels = ParamSurface.allLockableLabels
        for (key in listOf("Palette", "Palette 2", "Particle shape", "Beat pattern", "Path")) {
            assertTrue("chip selector \"$key\" renders no lock chip", key in labels)
            assertTrue("\"$key\" is not rolled at all", key in ParamRandomizer.KEYS)
        }
    }

    @Test
    fun every_fluid_knob_key_is_actually_honoured_by_the_randomizer() {
        val keys = ParamRandomizer.KEYS.toSet()
        val unknown = allKnobs().map { it.key }.distinct().filter { it !in keys }
        assertEquals("test knobs naming a key the randomizer does not roll", emptyList<String>(), unknown)
    }
}
