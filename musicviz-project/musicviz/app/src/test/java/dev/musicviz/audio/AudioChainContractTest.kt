package dev.musicviz.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Two standing rules about the audio sink, guarded before the DSP chain exists.
 *
 * **1. Never enable media3's float OUTPUT.** It reads like the obvious way to
 * get a float DSP pipeline. It is the opposite: `DefaultAudioSink`'s own javadoc
 * on `setEnableFloatOutput` says *"Audio processing (for example, speed
 * adjustment) will not be available when float output is in use."* Float output
 * does not give us a float chain - it removes the chain, and the
 * `TeeAudioProcessor` that feeds every visual in the app goes with it. The
 * failure is silent: no crash, no log, just a visualizer that never moves and an
 * equalizer that does nothing. A float chain is built by having the *stages*
 * work in float internally, not by asking the sink for float output.
 *
 * **2. The analysis tap stays first.** `FeatureExtractor.reset` and
 * `AnalysisEngine.reset` both state that live playback must reproduce what the
 * cached and exported render produce from the same file - and the offline path
 * decodes the file with no user EQ in it. Put user-tunable DSP upstream of the
 * tap and live visuals diverge from every exported video, differently for every
 * user and every preset, with no test that can pin it. The loudness seek bar,
 * drawn from the offline RMS curve, would disagree too. If output metering is
 * ever wanted, it gets a second tap that feeds meters only - never
 * `AudioFeatures`.
 */
class AudioChainContractTest {
    private val factory: String by lazy {
        repoFile("src/main/java/dev/musicviz/audio/TapRenderersFactory.kt")
    }

    @Test
    fun `float output is never enabled`() {
        for (setter in listOf("setEnableFloatOutput", "setEnableAudioFloatOutput")) {
            assertFalse(
                "$setter(true) disables the sink's whole audio-processing pipeline, including the " +
                    "TeeAudioProcessor the visualizer reads. Work in float INSIDE the stages instead.",
                Regex("""$setter\s*\(\s*true\s*\)""").containsMatchIn(factory),
            )
        }
    }

    @Test
    fun `the visualizer tap is the first processor in the chain`() {
        // Whether the array form or an explicit chain is in use, the tap must
        // be the first element: everything after it is audio the analysis must
        // not see.
        val order =
            Regex("""(TeeAudioProcessor|MvzDspChain|setAudioProcessorChain|setAudioProcessors)""")
                .findAll(factory)
                .map { it.value }
                .toList()
        assertTrue("no audio processor wiring found in TapRenderersFactory", order.isNotEmpty())
        val tapAt = factory.indexOf("TeeAudioProcessor(")
        assertTrue("TapRenderersFactory no longer installs the visualizer tap", tapAt >= 0)
        // Any DSP stage names must appear after the tap's construction.
        for (stage in DSP_STAGE_NAMES) {
            val at = factory.indexOf("$stage(")
            if (at >= 0) {
                assertTrue(
                    "$stage is wired before the visualizer tap - analysis would see processed audio, " +
                        "breaking live/export feature parity",
                    at > tapAt,
                )
            }
        }
    }

    private companion object {
        /**
         * Stage names the chain is expected to grow. Listed here so that adding
         * one upstream of the tap fails this test rather than silently changing
         * what the visuals analyse.
         */
        val DSP_STAGE_NAMES =
            listOf(
                "GainProcessor",
                "EqProcessor",
                "ConvolutionProcessor",
                "CrossfeedProcessor",
                "StereoMatrixProcessor",
                "DynamicsProcessor",
                "DitherProcessor",
            )
    }

    /** Resolves a path under `app/`, whichever directory the tests run from. */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
