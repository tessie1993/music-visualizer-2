package dev.musicviz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.musicviz.render.TransitionCatalog
import dev.musicviz.render.VisualSafety
import dev.musicviz.render.VisualizerView

/**
 * Binds the ViewModel's visual state to the GL renderer. Lives at the app
 * shell level (always composed), NOT inside the now-playing screen: when
 * these effects lived only in the expanded screen, LFO/ADSR/preset/scene
 * changes made from the Visuals hub silently stopped applying whenever the
 * player was collapsed - the "reverts to the old behavior" bug class.
 */
@Composable
fun VisualizerEngineBindings(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsState()
    val lfos by viewModel.lfos.collectAsState()
    val adsrs by viewModel.adsrs.collectAsState()
    val playerPrefs by viewModel.playerPrefs.collectAsState()
    val gui by viewModel.guiPrefs.collectAsState()

    LaunchedEffect(Unit) {
        visualizerView.visualizerRenderer.onShaderError = viewModel::reportShaderError
        visualizerView.visualizerRenderer.pcmProvider = { viewModel.latestPcm() }
        viewModel.features.collect {
            // Enriched with progress/section context so the fluid spawn/catch
            // choreography can journey through the track.
            val enriched = viewModel.enrichFeatures(it)
            visualizerView.visualizerRenderer.features = enriched
            // Same frames to the live wallpaper, if one is running: it shares
            // this process but no object graph, and a second analyzer would be
            // a second answer to "was that a beat?".
            dev.musicviz.audio.AudioBus
                .publish(enriched)
        }
    }
    LaunchedEffect(viz.sceneId) {
        visualizerView.visualizerRenderer.requestedSceneId = viz.sceneId
    }
    LaunchedEffect(viz.params) {
        visualizerView.visualizerRenderer.sceneParams = viz.params
    }
    LaunchedEffect(playerPrefs.keepScreenOn) {
        visualizerView.keepScreenOn = playerPrefs.keepScreenOn
    }
    LaunchedEffect(lfos, adsrs) {
        visualizerView.visualizerRenderer.lfoEngine.configs = lfos
        visualizerView.visualizerRenderer.adsrEngine.configs = adsrs
    }
    LaunchedEffect(viz.transitionId, viz.transitionDurationSec, gui.safety) {
        // A hard CUT swaps the whole frame in one frame, so Safe visuals
        // substitutes a crossfade. The user's stored choice is untouched -
        // turning safety back off restores it.
        val id = VisualSafety.transitionId(viz.transitionId, gui.safety)
        val renderer = visualizerView.visualizerRenderer
        renderer.transitionId = id
        // Built-in styles still travel as the enum: the base shader implements
        // them, and nothing above this line has to know which family an id is.
        TransitionCatalog.builtIn(id)?.let { renderer.transitionStyle = it }
        renderer.transitionDurationMs = (viz.transitionDurationSec * 1000).toLong()
        // Link the variant NOW rather than when a switch first needs it: the
        // user has just picked it, so the compile lands during an idle moment
        // instead of as a hitch on the first frame of the transition.
        visualizerView.queueEvent { renderer.warmTransition(id) }
    }
    LaunchedEffect(gui.safety) {
        visualizerView.visualizerRenderer.safety = gui.safety
    }
    LaunchedEffect(Unit) {
        viewModel.morphFade.collect { visualizerView.visualizerRenderer.beginParamMorph(it) }
    }
    LaunchedEffect(Unit) {
        // Cold start: the style survives a restart (VizUiState is persisted)
        // but the engine's loaded .milk did not, so relaunching on the
        // milkdrop style came back to projectM's idle "M" logo. The renderer
        // re-queues its own last preset after an EGL context loss; this covers
        // the case where there is no renderer state left at all.
        viewModel.activeMilkPath.value?.let { visualizerView.visualizerRenderer.loadMilkPreset(it) }
        viewModel.vizApply.collect { apply ->
            apply.milkPath?.let {
                visualizerView.visualizerRenderer.loadMilkPreset(it)
                viewModel.noteMilkPreset(it)
            }
            apply.customShader?.let {
                visualizerView.visualizerRenderer.submitShader(apply.sceneId ?: viz.sceneId, it)
            }
        }
    }
}
