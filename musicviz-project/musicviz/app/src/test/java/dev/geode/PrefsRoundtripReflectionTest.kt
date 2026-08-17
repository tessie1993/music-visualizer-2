package dev.geode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.geode.analysis.BeatTuning
import dev.geode.data.ExportDefaults
import dev.geode.data.ExportPrefsStore
import dev.geode.data.PlayerPrefs
import dev.geode.data.PlayerPrefsStore
import dev.geode.ui.AutoVisualsPrefsStore
import dev.geode.ui.GuiPrefs
import dev.geode.ui.ThemeStore
import dev.geode.ui.VizPlaylistEntry
import dev.geode.ui.VizUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The [PresetRoundtripTest] mechanism applied to the four prefs stores that
 * did not have it: build a snapshot where EVERY persisted field differs from
 * its default - via reflection, so fields added later are covered without
 * anyone editing this file - save it, load it through a FRESH store, and
 * compare field by field.
 *
 * What this catches that a hand-written "save these eleven values" test
 * cannot: a field added to the data class and to `save()` but not `load()`
 * (or vice versa) still passes a hand test built before it existed, because
 * both sides of the comparison hold the default. Here the mutated value
 * makes the missing key visible the day it is introduced.
 *
 * Fields that intentionally do NOT round-trip live in per-store exemption
 * maps with the reason, and the test asserts they come back as DEFAULTS -
 * so an exemption cannot silently hide a half-persisted field either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsRoundtripReflectionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPrefs() {
        for (name in listOf("geode-prefs", "geode-player", "geode-viz")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    // ------------------------------------------------------------ GuiPrefs

    /** Intentionally session-only / legacy fields, with the documented why. */
    private val guiExempt =
        mapOf(
            "micReactive" to
                "per-session by design: an app that opens the microphone at launch " +
                "because of a switch left on weeks ago is not consented to (field docs)",
            "whiteFont" to
                "legacy migration switch: saveGui resolves it into fontColorArgb and " +
                "retires the old key; loadGui never reads it back",
        )

    /** In-range non-default values for fields loadGui coerces or specializes. */
    private val guiOverrides =
        mapOf(
            "presetMirrorUri" to "content://geode/test-mirror",
            "fontColorArgb" to 0xFF336699.toInt(),
            // within SIGMA_MIN..SIGMA_MAX
            "beatSensitivity" to 4f,
            // within INTERVAL_MS_MIN..INTERVAL_MS_MAX
            "beatMinIntervalMs" to 500f,
            // within TEXT_SCALE_MIN..TEXT_SCALE_MAX
            "textScale" to 1.1f,
            // within 1..DEFAULT_STROBE_HZ
            "maxFlashHz" to 2f,
            // within 0..1
            "maxFlashDepth" to 0.5f,
            // within 0.2..2
            "touchSmearStrength" to 1.5f,
            // Exempt fields stay at their defaults so they cannot interact
            // with the fields under test (whiteFont writes fontColorArgb).
            "micReactive" to false,
            "whiteFont" to false,
        )

    @Test
    fun every_gui_pref_field_survives_saveGui_loadGui() {
        // Sanity-pin the override bounds against their real constants, so a
        // widened default cannot quietly turn an override into a no-op.
        assertTrue(BeatTuning.SENSITIVITY_DEFAULT != 4f && 4f in BeatTuning.SENSITIVITY_MIN..BeatTuning.SENSITIVITY_MAX)
        assertTrue(
            BeatTuning.INTERVAL_MS_DEFAULT != 500f &&
                500f in BeatTuning.INTERVAL_MS_MIN..BeatTuning.INTERVAL_MS_MAX,
        )

        val mutated = mutatedInstance(GuiPrefs::class, GuiPrefs(), guiOverrides)
        ThemeStore(context).saveGui(mutated)
        val loaded = ThemeStore(context).loadGui()
        assertFieldParity(GuiPrefs::class, mutated, loaded, GuiPrefs(), guiExempt)
    }

    // ---------------------------------------------------------- PlayerPrefs

    private val playerOverrides =
        mapOf(
            // load coerces into 0..2
            "repeatMode" to 2,
            // 0.5..2
            "speed" to 1.5f,
            // -6..6
            "pitchSemitones" to -3.5f,
            // >= 0
            "sleepTimerMinutes" to 45,
            // 0..MAX_FADE_MS
            "fadeMs" to 1250,
        )

    @Test
    fun every_player_pref_field_survives_save_load() {
        val mutated = mutatedInstance(PlayerPrefs::class, PlayerPrefs(), playerOverrides)
        PlayerPrefsStore(context).save(mutated)
        val loaded = PlayerPrefsStore(context).load()
        assertFieldParity(PlayerPrefs::class, mutated, loaded, PlayerPrefs(), emptyMap())
    }

    // -------------------------------------------------------- ExportDefaults

    private val exportOverrides =
        mapOf(
            // the store snaps anything that is not 30 back to 60
            "fps" to 30,
        )

    @Test
    fun every_export_default_survives_save_load() {
        val mutated = mutatedInstance(ExportDefaults::class, ExportDefaults(), exportOverrides)
        ExportPrefsStore(context).save(mutated)
        val loaded = ExportPrefsStore(context).load()
        assertFieldParity(ExportDefaults::class, mutated, loaded, ExportDefaults(), emptyMap())
    }

    // ------------------------------------------------------ AutoVisualsPrefs

    /**
     * AutoVisuals persists a SUBSET of [VizUiState] (there is deliberately no
     * parallel data class - the store's own docs). The persisted set is read
     * out of `save()`'s source, so a knob added there is covered here the
     * same day, and `applyTo` must restore exactly that set.
     */
    @Test
    fun auto_visuals_save_and_applyTo_cover_the_same_fields_and_roundtrip() {
        val src = stripComments(source("ui/AutoVisualsPrefsStore.kt"))
        val stateProps = VizUiState::class.memberProperties.map { it.name }.toSet()

        val saveBody = src.substringAfter("fun save(").substringBefore("companion object")
        val saved =
            Regex("""state\.(\w+)""")
                .findAll(saveBody)
                .map { it.groupValues[1] }
                .filter { it in stateProps }
                .toSet()
        assertTrue("no persisted fields parsed out of AutoVisualsPrefs.save()", saved.size >= 8)

        val applyBody = src.substringAfter("fun applyTo(").substringBefore("fun save(")
        val restored =
            Regex("""\b(\w+)\s*=\s*(prefs\.|entries)""")
                .findAll(applyBody)
                .map { it.groupValues[1] }
                .filter { it in stateProps }
                .toSet()
        assertEquals(
            "AutoVisualsPrefs.save() and applyTo() cover different field sets - a knob " +
                "persisted but never restored (or restored but never saved) is a setting " +
                "that silently resets",
            saved.sorted(),
            restored.sorted(),
        )

        // Runtime roundtrip of exactly the persisted set, every value
        // non-default. The playlist must be non-empty or the store's own
        // enabled-but-inert backstop clears vizPlaylistEnabled by design.
        val entries =
            listOf(
                VizPlaylistEntry(sceneId = "fluid", presetName = "Dusk", milkPath = null, label = "Dusk"),
                VizPlaylistEntry(sceneId = "milkdrop", presetName = null, milkPath = "/x/y.milk", label = "y"),
            )
        val defaults = VizUiState()
        val ctor = VizUiState::class.primaryConstructor!!
        val byName = VizUiState::class.memberProperties.associateBy { it.name }
        val args =
            ctor.parameters
                .filter { it.name in saved }
                .associateWith { param ->
                    when (val current = byName.getValue(param.name!!).get(defaults)) {
                        is Boolean -> !current
                        is Int -> current + 3 // stays inside INTERVAL_SEC for both intervals
                        is List<*> -> entries
                        else -> error("unhandled persisted VizUiState field '${param.name}': $current")
                    }
                }
        val mutated = ctor.callBy(args)

        AutoVisualsPrefsStore(context).save(mutated)
        val loaded = AutoVisualsPrefsStore(context).applyTo(VizUiState())
        val failures = mutableListOf<String>()
        for (name in saved) {
            val want = byName.getValue(name).get(mutated)
            val got = byName.getValue(name).get(loaded)
            if (want != got) failures += "$name: saved=$want loaded=$got"
        }
        assertEquals("AutoVisuals knobs dropped on the floor: $failures", 0, failures.size)
    }

    // ---------------------------------------------------------------- engine

    /**
     * Builds an instance where every constructor parameter differs from its
     * default: [overrides] first (bounded/enum/nullable fields), then the
     * generic mutation by type. Fails loudly on a type it cannot mutate, so
     * a new field of a new shape extends this file instead of dodging it.
     */
    private fun <T : Any> mutatedInstance(
        klass: KClass<T>,
        defaults: T,
        overrides: Map<String, Any?>,
    ): T {
        val ctor = klass.primaryConstructor!!
        val byName = klass.memberProperties.associateBy { it.name }
        val args =
            ctor.parameters.associateWith { param ->
                val name = param.name!!
                if (name in overrides) {
                    overrides.getValue(name)
                } else {
                    when (val current = byName.getValue(name).get(defaults)) {
                        is Boolean -> !current
                        is Float -> current + 0.137f
                        is Int -> current + 3
                        is String -> current + "_x"
                        is Enum<*> -> current.javaClass.enumConstants.first { it != current }
                        null -> error("field '$name' defaults to null - add an override with a real value")
                        else -> error("unhandled field type for '$name': $current - extend the mutator")
                    }
                }
            }
        return ctor.callBy(args)
    }

    /** Field-by-field comparison, with exempt fields pinned to their defaults. */
    private fun <T : Any> assertFieldParity(
        klass: KClass<T>,
        mutated: T,
        loaded: T,
        defaults: T,
        exempt: Map<String, String>,
    ) {
        val failures = mutableListOf<String>()
        for (prop in klass.memberProperties) {
            // Derived (non-constructor) properties recompute from the fields
            // under test; comparing them double-counts any failure.
            if (klass.primaryConstructor!!.parameters.none { it.name == prop.name }) continue
            val want = if (prop.name in exempt) prop.get(defaults) else prop.get(mutated)
            val got = prop.get(loaded)
            val ok =
                when (want) {
                    is Float -> got is Float && kotlin.math.abs(want - got) < 1e-4f
                    else -> want == got
                }
            if (!ok) {
                val label = if (prop.name in exempt) "exempt field failed to reload as DEFAULT" else "dropped"
                failures += "${prop.name} ($label): saved=$want loaded=$got"
            }
        }
        assertEquals("${klass.simpleName} fields lost by the store: $failures", 0, failures.size)
        val staleExemptions = exempt.keys.filter { name -> klass.memberProperties.none { it.name == name } }
        assertEquals("stale exemptions for ${klass.simpleName}: remove them", emptyList<String>(), staleExemptions)
    }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("src/main/java/dev/geode/", "app/src/main/java/dev/geode/")) {
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        org.junit.Assert.fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
