package dev.musicviz

import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
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
 *     out of `CustomizeDialog.kt` so a renamed slider fails the build instead
 *     of quietly unprotecting a parameter.
 *
 * It also pins the roll inside each slider's range (a roll the user cannot
 * reproduce or undo by hand is a bug) and pins the fields the randomizer must
 * never touch, most importantly the custom-palette overrides.
 */
class ParamRandomizerFluidTest {
    private class Knob(
        val key: String,
        val read: (SceneParams) -> Any,
    )

    /**
     * Fluid-family float knobs: key, accessor, and the slider's own range.
     *
     * "Ripple strength" appears twice on purpose: it is one label in front of
     * two sliders (the Water section and the all-styles ripple overlay), so a
     * single lock covers both while each still rolls inside its own range.
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
            Triple("Ripple glint", { p: SceneParams -> p.rippleOverlaySpecular }, 0f..1f),
            Triple("Ripple strength", { p: SceneParams -> p.waterRippleStrength }, 0f..2f),
            Triple("Ripple strength", { p: SceneParams -> p.rippleOverlayStrength }, 0f..1f),
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

    @Test
    fun custom_palette_overrides_and_structural_toggles_are_never_rolled() {
        // A user who built and saved a custom palette must keep it: an
        // override is active at >= 0f, UNSET_OVERRIDE (-1f) means "off".
        val base =
            SceneParams.DEFAULT.copy(
                paletteBaseOverride = 0.42f,
                paletteRangeOverride = 0.17f,
                palette2BaseOverride = 0.61f,
                palette2RangeOverride = 0.09f,
                customPaletteId = "my-palette",
                customPalette2Id = "my-other-palette",
                fluidQuality = 4,
                fluidAutoQuality = false,
                fluidParticlesEnabled = false,
                fluidDyeEnabled = false,
                flowEnabled = true,
                rippleOverlayEnabled = true,
                fluidSpawnProgress = 0.25f,
                paramFadeSec = 2.5f,
            )
        val rng = Random(31)
        repeat(200) {
            val p = ParamRandomizer.randomize(base, emptySet(), rng)
            assertEquals(base.paletteBaseOverride, p.paletteBaseOverride, 0f)
            assertEquals(base.paletteRangeOverride, p.paletteRangeOverride, 0f)
            assertEquals(base.palette2BaseOverride, p.palette2BaseOverride, 0f)
            assertEquals(base.palette2RangeOverride, p.palette2RangeOverride, 0f)
            assertEquals(base.customPaletteId, p.customPaletteId)
            assertEquals(base.customPalette2Id, p.customPalette2Id)
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
        val dupes = ParamRandomizer.KEYS.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate randomizer lock keys", emptySet<String>(), dupes)
    }

    @Test
    fun every_lock_key_matches_a_customize_label() {
        val labels = lockableLabels()
        assertTrue("parsed too few Customize labels ($labels)", labels.size > 40)
        val missing =
            ParamRandomizer.KEYS.filter {
                it !in labels && it !in ParamRandomizer.KEYS_WITHOUT_LOCK_CHIP
            }
        assertEquals("randomizer lock keys with no matching Customize label", emptyList<String>(), missing)
    }

    @Test
    fun every_fluid_knob_key_is_actually_honoured_by_the_randomizer() {
        val keys = ParamRandomizer.KEYS.toSet()
        val unknown = allKnobs().map { it.key }.distinct().filter { it !in keys }
        assertEquals("test knobs naming a key the randomizer does not roll", emptyList<String>(), unknown)
    }

    /** Labels of every Customize control that renders a lock chip. */
    private fun lockableLabels(): Set<String> {
        val regex = Regex("(?:LabeledSlider|LabeledIntSlider|CheckRow)\\(\\s*\"([^\"]+)\"")
        return regex
            .findAll(customizeDialogSource())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun customizeDialogSource(): String {
        val relatives =
            listOf(
                "src/main/java/dev/musicviz/ui/CustomizeDialog.kt",
                "app/src/main/java/dev/musicviz/ui/CustomizeDialog.kt",
            )
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (rel in relatives) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("CustomizeDialog.kt not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
