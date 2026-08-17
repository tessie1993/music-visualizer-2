package dev.geode

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.geode.audio.AudioFxBand
import dev.geode.audio.AudioFxState
import dev.geode.ui.EqualizerCard
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
 * The Equalizer card's three states, and the independence of its three
 * effects.
 *
 * The card used to branch on `available` alone. That is false in two
 * completely different situations - the device refused the effect, and there
 * is no audio session to attach to yet - and the second is every cold start:
 * ExoPlayer reports session id UNSET (0) until its audio sink first
 * initialises, so `attach()` returns early, `equalizer` is null, and the card
 * declared the device unsupported and disabled its own master switch before a
 * note had ever played. `attached` is the flag that tells the two apart, and
 * it had no reader anywhere in `main/`.
 *
 * `bassAvailable` and `loudnessAvailable` had none either: the platform grants
 * the three effects separately and a device can hand out a BassBoost while
 * refusing an Equalizer, in which case the whole card - working sliders and
 * all - used to disappear behind the "not supported" note.
 *
 * These are states no shadow produces on demand, which is why the card is
 * composed over a hand-made [AudioFxState] rather than through a ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerCardStateTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

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

    private val coldStart = AudioFxState()

    private val refusedByDevice = AudioFxState(attached = true)

    private val fullChain =
        AudioFxState(
            available = true,
            attached = true,
            bassAvailable = true,
            loudnessAvailable = true,
            enabled = true,
            bands = listOf(AudioFxBand("60 Hz", 0, -1500, 1500)),
        )

    private val playSomethingFirst =
        "Play something first — the effects chain attaches to the audio session, and there " +
            "is no session until audio starts."

    private fun show(fx: AudioFxState) {
        compose.setContent { MaterialTheme { Card(fx) } }
        compose.waitForIdle()
    }

    @Composable
    private fun Card(fx: AudioFxState) {
        Column {
            EqualizerCard(fx, onEnabled = {}, onPreset = {}, onBand = { _, _ -> }, onBassBoost = {}, onLoudness = {})
        }
    }

    @Test
    fun `before anything has played the card says so, and does not blame the device`() {
        show(coldStart)
        compose.onNodeWithText(playSomethingFirst).assertExists()
        compose.onNodeWithText("Not supported on this device").assertDoesNotExist()
    }

    @Test
    fun `not supported is reserved for a session that exists and was refused`() {
        show(refusedByDevice)
        compose.onNodeWithText("Not supported on this device").assertExists()
        compose.onNodeWithText(playSomethingFirst).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun `a device that grants only a bass boost still gets its bass boost`() {
        show(AudioFxState(attached = true, bassAvailable = true, enabled = true, bassBoost = 400))
        compose.onNodeWithText("Not supported on this device").assertDoesNotExist()
        compose.onNodeWithText("This device would not grant an equalizer. What it did grant is below.").assertExists()
        compose.onNodeWithText("Bass boost  40%").assertExists()
        compose.onNodeWithText("Loudness  0 dB").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun `a loudness-only device gets the loudness slider and nothing it cannot drive`() {
        show(AudioFxState(attached = true, loudnessAvailable = true, enabled = true, loudness = 250))
        compose.onNodeWithText("Loudness  +2.5 dB").assertExists()
        compose.onNodeWithText("Bass boost  0%").assertDoesNotExist()
    }

    @Test
    fun `a full chain shows everything and the master switch is live`() {
        show(fullChain)
        compose.onNodeWithText(playSomethingFirst).assertDoesNotExist()
        compose.onNodeWithText("Not supported on this device").assertDoesNotExist()
        compose.onNodeWithText("This device would not grant an equalizer. What it did grant is below.").assertDoesNotExist()
        compose.onNodeWithText("60 Hz  0 dB").assertExists()
        compose.onNodeWithText("Bass boost  0%").assertExists()
        compose.onNodeWithText("Loudness  0 dB").assertExists()
        compose.onNode(isToggleable()).assertIsEnabled()
    }
}
