package dev.geode

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.geode.ui.CrystalMaterialTheme
import dev.geode.ui.GuiPrefs
import dev.geode.ui.theme.ThemePack
import dev.geode.ui.theme.ThemePackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * The colour every un-coloured `Text`, `Icon` and `IconButton` in the shell
 * falls back to.
 *
 * `MaterialTheme` does NOT provide `LocalContentColor` - it provides the
 * ColorScheme, indication, ripple, shapes, text selection and typography, and
 * nothing else. The local's library default is `Color.Black`. Normally the
 * gap is closed by a `Surface`, which derives one with
 * `contentColorFor(its container colour)`; the app shell's `Scaffold`
 * deliberately runs `containerColor = Color.Transparent` so the nebula
 * backdrop shows through the glass, and Transparent matches no colour role -
 * `contentColorFor` falls straight through to `LocalContentColor.current`.
 *
 * With nothing providing it, that was black: on a dark pack (Sugilite,
 * background #120A1A) the Player's title, the clocks, the up-next rows and
 * every transport icon button painted black on near-black. So
 * [CrystalMaterialTheme] provides it, and this holds that in place - for the
 * pack AND for the derivation the Scaffold actually performs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellContentColorTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** See CustomizeLockAffordanceTest: the bare host activity must be registered first. */
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

    /** What a composable inside the theme actually inherits. */
    private class Inherited(
        val content: Color,
        val scaffoldDerived: Color,
        val onBackground: Color,
        val background: Color,
    )

    private fun inside(
        pack: ThemePack,
        gui: GuiPrefs,
    ): Inherited {
        lateinit var seen: Inherited
        compose.setContent {
            CrystalMaterialTheme(pack = pack, gui = gui) {
                seen = read()
            }
        }
        compose.waitForIdle()
        return seen
    }

    /** The pack the app opens on, and the one the single-pack cases use. */
    private val defaultPack = ThemePackCatalog.all.first()

    @Composable
    private fun read(): Inherited =
        Inherited(
            content = LocalContentColor.current,
            // Exactly what Scaffold(containerColor = Transparent) computes for
            // its default contentColor.
            scaffoldDerived = contentColorFor(Color.Transparent),
            onBackground = MaterialTheme.colorScheme.onBackground,
            background = MaterialTheme.colorScheme.background,
        )

    @Test
    fun `material alone leaves the content colour black - the defect this guards`() {
        // Not a test of our code: a test of the assumption the fix rests on.
        // If Material ever starts providing a sane default, this fails and the
        // provider below can be reconsidered rather than cargo-culted.
        var bare: Color? = null
        compose.setContent { MaterialTheme { bare = LocalContentColor.current } }
        compose.waitForIdle()
        assertEquals("MaterialTheme's LocalContentColor default", Color.Black, bare)
    }

    @Test
    fun `the crystal theme hands every uncoloured composable the pack's own text colour`() {
        val seen = inside(defaultPack, GuiPrefs())
        assertEquals(seen.onBackground, seen.content)
        assertEquals(
            "Scaffold(containerColor = Transparent) derives its content colour from the local",
            seen.onBackground,
            seen.scaffoldDerived,
        )
    }

    @Test
    fun `the inherited colour is readable on the background it is painted on, on every pack`() {
        // The failure was black on #120A1A. Any pack where the inherited
        // writing cannot be told apart from its own background is the same
        // bug wearing different stone. One setContent (the rule allows a
        // single one per test), every pack composed side by side.
        val seen = mutableMapOf<String, Inherited>()
        compose.setContent {
            for (pack in ThemePackCatalog.all) {
                CrystalMaterialTheme(pack = pack, gui = GuiPrefs()) {
                    seen[pack.slug] = read()
                }
            }
        }
        compose.waitForIdle()
        assertEquals(ThemePackCatalog.all.size, seen.size)
        for ((slug, v) in seen) {
            val contrast = kotlin.math.abs(v.content.luminance() - v.background.luminance())
            assertTrue(
                "$slug: inherited content colour ${v.content} on background ${v.background}",
                contrast >= 0.25f,
            )
        }
    }

    @Test
    fun `a font-colour override reaches the uncoloured composables too`() {
        // onBackground is one of the roles the override repaints, so the
        // inherited colour has to follow it rather than being pinned to the
        // pack's automatic value. A DARK pack, because the light-pack gate
        // would (correctly) reject white before it ever reached the scheme.
        val white = dev.geode.ui.FontColorChoice.WHITE_ARGB
        val darkPack = ThemePackCatalog.all.first { !it.isLight }
        val seen = inside(darkPack, GuiPrefs(fontColorArgb = white))
        assertEquals(Color(white), seen.content)
    }
}
