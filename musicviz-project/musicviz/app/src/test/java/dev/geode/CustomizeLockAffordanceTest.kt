package dev.geode

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import dev.geode.analysis.IntelligenceMode
import dev.geode.render.scene.HyperspaceMath
import dev.geode.render.scene.ParamRandomizer
import dev.geode.render.scene.SceneParams
import dev.geode.render.scene.VisualStyleCatalog
import dev.geode.ui.BehaviorTab
import dev.geode.ui.ColorTab
import dev.geode.ui.CymaticsTab
import dev.geode.ui.FluidTab
import dev.geode.ui.FxTab
import dev.geode.ui.HyperspaceTab
import dev.geode.ui.MotionTab
import dev.geode.ui.ShapeTab
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lock affordance and the honest-chip contracts, checked against the tabs
 * as ACTUALLY COMPOSED - not against the source text.
 *
 * `ParamRandomizerFluidTest.every_lock_key_matches_a_customize_label` parses
 * labels back out of `CustomizeTabs.kt`, and that regex counted `CheckRow` as
 * a lockable shape for as long as `CheckRow` rendered no lock at all: fifteen
 * randomizer keys were toggles a user could never hold against "Randomize
 * unlocked", and the contract test passed throughout, because presence in the
 * source is not the affordance on screen. So this class composes the real
 * tabs under Robolectric (the `AppSmokeTest` pattern) and asserts on the
 * semantics tree the user actually gets:
 *
 *  - every randomizer key labelling a `CheckRow` renders the shared LockChip;
 *  - controls whose labels no roll writes render NO chip (a lock there would
 *    persist a key nothing honours);
 *  - the Cymatics Geometry / Hyperspace Fractal chips give way to a hint when
 *    the active substyle pins the value in `VisualStyleCatalog` (the chips
 *    were pure no-ops there), and stay for null / unknown ids;
 *  - the Hyperspace Act chips are live in Hold alone, and say so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomizeLockAffordanceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The host is a bare [ComponentActivity] (the tabs need no app plumbing),
     * which the app manifest does not declare - Robolectric's ActivityScenario
     * refuses undeclared activities. Registering it in the shadow
     * PackageManager must happen BEFORE the compose rule's scenario launches,
     * i.e. outside the rule, hence the chain rather than an @Before.
     */
    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(
                TestRule { base, _ ->
                    object : Statement() {
                        override fun evaluate() {
                            val app = ApplicationProvider.getApplicationContext<Application>()
                            shadowOf(app.packageManager)
                                .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                            base.evaluate()
                        }
                    }
                },
            ).around(compose)

    /** `CheckRow` labels as written in the panel source (the lock-key set). */
    private fun checkRowLabels(): Set<String> =
        Regex("CheckRow\\(\\s*\"([^\"]+)\"")
            .findAll(ParamSurface.source("ui/CustomizeTabs.kt"))
            .map { it.groupValues[1] }
            .toSet()

    /** Params with every conditional CheckRow's gate open. */
    private val gatesOpen = SceneParams.DEFAULT.copy(flowEnabled = true)

    @Test
    fun every_checkrow_randomizer_key_renders_a_lock_affordance() {
        val keys = checkRowLabels().intersect(ParamRandomizer.LOCKABLE_LABELS)
        // The audit counted fifteen; fewer parsed means the reader broke, not
        // that the affordance question went away.
        assertTrue("expected >= 15 CheckRow randomizer keys, parsed $keys", keys.size >= 15)
        compose.setContent {
            MaterialTheme {
                Column {
                    // Every gate that hides a CheckRow key is forced open:
                    // beam + shader-look + point-sprite on Shape, shader-look
                    // on Color, FLUID (and with it the emitter sections) on
                    // Fluid, flowEnabled for "Particles ride the field".
                    MotionTab(gatesOpen) {}
                    ShapeTab(
                        gatesOpen,
                        {},
                        isShaderLookScene = true,
                        isPointSpriteScene = true,
                        isBeamScene = true,
                    )
                    BehaviorTab(
                        gatesOpen,
                        {},
                        attack = 0.2f,
                        decay = 0.1f,
                        onReactivityChange = { _, _ -> },
                        intelligenceMode = IntelligenceMode.entries.first(),
                        onIntelligenceModeChange = {},
                    )
                    ColorTab(gatesOpen, {}, isShaderLookScene = true)
                    FluidTab(gatesOpen, {}, isFluidScene = true)
                }
            }
        }
        keys.forEach { key ->
            compose.onAllNodesWithContentDescription("Lock $key").onFirst().assertExists()
        }
    }

    @Test
    fun controls_without_randomizer_keys_render_no_lock_chip() {
        compose.setContent {
            MaterialTheme {
                Column {
                    BehaviorTab(
                        SceneParams.DEFAULT,
                        {},
                        attack = 0.2f,
                        decay = 0.1f,
                        onReactivityChange = { _, _ -> },
                        intelligenceMode = IntelligenceMode.entries.first(),
                        onIntelligenceModeChange = {},
                    )
                    FxTab(SceneParams.DEFAULT, {}, lfos = emptyList(), onLfoChange = { _, _ -> })
                    HyperspaceTab(SceneParams.DEFAULT) {}
                }
            }
        }
        // Labels no roll ever writes: a chip here would persist a key nothing
        // honours. "Journey" and "Detail" are the NEVER_ROLLED cases, the
        // rest are controls outside SceneParams entirely.
        listOf("Fade time (s)", "Reactivity attack", "Reactivity decay", "Journey", "Detail").forEach { label ->
            compose.onAllNodesWithContentDescription("Lock $label").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("$label locked").assertCountEquals(0)
        }
        // And a rolled key composed right beside them still carries its chip.
        compose.onAllNodesWithContentDescription("Lock Act").onFirst().assertExists()
    }

    @Test
    fun cymatics_geometry_chips_become_a_hint_when_the_style_pins_geometry() {
        val pinned = VisualStyleCatalog.cymatics.firstOrNull { it.geometryOverride != null }
        assumeTrue("no cymatics substyle pins geometry (catalog mid-rework?)", pinned != null)
        val name = pinned!!.geometryOverride?.let { SceneParams.CYMATICS_GEOMETRIES.getOrNull(it) }
        assumeTrue("geometryOverride outside CYMATICS_GEOMETRIES", name != null)
        compose.setContent {
            MaterialTheme { CymaticsTab(SceneParams.DEFAULT, activeSceneId = pinned.id) {} }
        }
        compose.onAllNodesWithText("Geometry set by this style: $name.").onFirst().assertExists()
        // The chips are genuinely gone, not disabled: no geometry name
        // renders as its own (chip) text node.
        SceneParams.CYMATICS_GEOMETRIES.forEach { geometry ->
            compose.onAllNodesWithText(geometry).assertCountEquals(0)
        }
    }

    @Test
    fun cymatics_geometry_chips_stay_for_null_and_unknown_ids() {
        compose.setContent {
            MaterialTheme {
                Column {
                    CymaticsTab(SceneParams.DEFAULT) {}
                    CymaticsTab(SceneParams.DEFAULT, activeSceneId = "not-a-style") {}
                }
            }
        }
        // Both fall back to the full chip row: one geometry chip per tab.
        compose.onAllNodesWithText(SceneParams.CYMATICS_GEOMETRIES.first()).assertCountEquals(2)
    }

    @Test
    fun hyperspace_fractal_chips_become_a_hint_when_the_style_forces_species() {
        val forced = VisualStyleCatalog.hyperspace.firstOrNull { it.forcedSpecies != null }
        assumeTrue("no hyperspace substyle forces a species (catalog mid-rework?)", forced != null)
        val name = forced!!.forcedSpecies?.let { SceneParams.HYPERSPACE_SPECIES.getOrNull(it) }
        assumeTrue("forcedSpecies outside HYPERSPACE_SPECIES", name != null)
        compose.setContent {
            MaterialTheme { HyperspaceTab(SceneParams.DEFAULT, activeSceneId = forced.id) {} }
        }
        compose.onAllNodesWithText("Fractal set by this style: $name.").onFirst().assertExists()
        SceneParams.HYPERSPACE_SPECIES.forEach { species ->
            compose.onAllNodesWithText(species).assertCountEquals(0)
        }
    }

    @Test
    fun hyperspace_fractal_chips_stay_for_null_and_unknown_ids() {
        compose.setContent {
            MaterialTheme {
                Column {
                    HyperspaceTab(SceneParams.DEFAULT) {}
                    HyperspaceTab(SceneParams.DEFAULT, activeSceneId = "not-a-style") {}
                }
            }
        }
        compose.onAllNodesWithText(SceneParams.HYPERSPACE_SPECIES.first()).assertCountEquals(2)
    }

    @Test
    fun act_chips_are_inert_outside_hold_and_say_why() {
        compose.setContent {
            MaterialTheme {
                HyperspaceTab(SceneParams.DEFAULT.copy(hyperJourney = HyperspaceMath.JOURNEY_MUSIC)) {}
            }
        }
        HyperspaceMath.ACT_NAMES.forEach { act ->
            compose.onAllNodesWithText(act).onFirst().assertIsNotEnabled()
        }
        compose
            .onAllNodesWithText("Act is live on Hold only", substring = true)
            .onFirst()
            .assertExists()
    }

    @Test
    fun act_chips_are_live_on_hold() {
        compose.setContent {
            MaterialTheme {
                HyperspaceTab(SceneParams.DEFAULT.copy(hyperJourney = HyperspaceMath.JOURNEY_HOLD)) {}
            }
        }
        HyperspaceMath.ACT_NAMES.forEach { act ->
            compose.onAllNodesWithText(act).onFirst().assertIsEnabled()
        }
        compose.onAllNodesWithText("Act is live on Hold only", substring = true).assertCountEquals(0)
    }

    /**
     * The host side of the forced-selector hints: `CustomizePanel` must hand
     * the two substyle tabs the LIVE scene id, or the tab-side logic above
     * never sees a pinned style and the dead chips come back. Pinned at the
     * source level (the ParamSurface idiom) because composing CustomizePanel
     * needs the whole PlayerViewModel + GL view stack.
     */
    @Test
    fun customize_panel_hands_the_substyle_tabs_the_live_scene_id() {
        val src = ParamSurface.source("ui/VisualsHub.kt")
        listOf("CymaticsTab", "HyperspaceTab").forEach { tab ->
            assertTrue(
                "$tab is called without activeSceneId = viz.sceneId in CustomizePanel",
                Regex("$tab\\(\\s*p,\\s*activeSceneId = viz\\.sceneId").containsMatchIn(src),
            )
        }
    }

    /**
     * The Customize tab strip's reset must key on tab IDENTITY: keying on the
     * list's SIZE let a same-size swap (a Cymatics style straight to a
     * Hyperspace style trades CYMATICS for HYPERSPACE at the same position)
     * keep the stale index and land the user on a tab they never chose.
     * Source-pinned for the same reason as above.
     */
    @Test
    fun customize_tab_reset_is_keyed_on_tab_identity_not_size() {
        val src = ParamSurface.source("ui/VisualsHub.kt")
        assertTrue(
            "the reset effect keys on the size again - same-size tab swaps keep a stale index",
            !src.contains("LaunchedEffect(tabs.size)"),
        )
        assertTrue(
            "the reset effect no longer keys on the tab titles (identity)",
            src.contains("LaunchedEffect(titles)"),
        )
    }

    @Test
    fun motion_tab_names_the_styles_that_ignore_speed_and_endless_zoom() {
        compose.setContent { MaterialTheme { MotionTab(SceneParams.DEFAULT) {} } }
        // The Turbulence/Density idiom: a hint that names the styles a
        // control does not reach, on the two Motion controls with family gaps.
        compose.onAllNodesWithText("so those two ignore it", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("Dive speed setting the rate", substring = true).onFirst().assertExists()
    }
}
