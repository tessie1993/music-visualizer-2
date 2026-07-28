package dev.musicviz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    LaunchedEffect(Unit) {
        visualizerView.visualizerRenderer.onShaderError = viewModel::reportShaderError
        visualizerView.visualizerRenderer.pcmProvider = { viewModel.latestPcm() }
        viewModel.features.collect {
            // Enriched with progress/section context so the fluid spawn/catch
            // choreography can journey through the track.
            visualizerView.visualizerRenderer.features = viewModel.enrichFeatures(it)
        }
    }
    LaunchedEffect(viz.sceneId) {
        visualizerView.visualizerRenderer.requestedSceneId = viz.sceneId
    }
    LaunchedEffect(viz.params) {
        visualizerView.visualizerRenderer.sceneParams = viz.params
    }
    LaunchedEffect(lfos, adsrs) {
        visualizerView.visualizerRenderer.lfoEngine.configs = lfos
        visualizerView.visualizerRenderer.adsrEngine.configs = adsrs
    }
    LaunchedEffect(viz.transitionStyle, viz.transitionDurationSec) {
        visualizerView.visualizerRenderer.transitionStyle = viz.transitionStyle
        visualizerView.visualizerRenderer.transitionDurationMs = (viz.transitionDurationSec * 1000).toLong()
    }
    LaunchedEffect(Unit) {
        viewModel.morphFade.collect { visualizerView.visualizerRenderer.beginParamMorph(it) }
    }
    LaunchedEffect(Unit) {
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
