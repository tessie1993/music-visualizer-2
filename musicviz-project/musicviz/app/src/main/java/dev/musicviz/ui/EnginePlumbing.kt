package dev.musicviz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.musicviz.render.BlendMode
import dev.musicviz.render.TransitionCatalog
import dev.musicviz.render.VisualSafety
import dev.musicviz.render.VisualizerView
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the Layers controls have chosen: the second style rendered under the
 * active one, how strongly it shows, and which blend function combines the
 * two. Defaults mirror the renderer's own (`VisualizerRenderer.layerMix` /
 * `layerBlend`), with the layer off.
 */
data class LayersUiState(
    val enabled: Boolean = false,
    val sceneId: String? = null,
    val mix: Float = 0.5f,
    val blend: BlendMode = BlendMode.SCREEN,
)

/**
 * The Layers feature's state bus, between the Customize panel and
 * [VisualizerEngineBindings].
 *
 * The renderer's layer fields are deliberately renderer state rather than
 * `SceneParams` entries (see `VisualizerRenderer.layerSceneId`: they say which
 * scenes are ON SCREEN, not how one scene looks), so they have no ViewModel
 * flow to ride and no preset key to persist under. This object is the missing
 * plumbing: the FX tab's Layers controls write [state], the always-composed
 * bindings below push it to the renderer - the same shape as every ViewModel
 * binding here, and for the same reason the bindings live at the shell: a
 * layer set up in the Visuals hub must keep applying after the panel unmounts.
 *
 * A process-wide object (the `AudioBus` shape) rather than remembered
 * composable state, so reopening the panel shows the layer that is actually
 * running instead of a fresh default.
 */
object LayersBus {
    /** Written by the FX tab's Layers controls, applied by the bindings. */
    val state = MutableStateFlow(LayersUiState())

    /**
     * What the layer picker may offer. Published by the bindings because the
     * list is the renderer's (`VisualizerRenderer.availableSceneIds` -
     * MilkDrop's presence is a device property, not a constant) and the
     * Customize tabs hold no renderer reference.
     */
    val availableScenes = MutableStateFlow<List<String>>(emptyList())

    /**
     * The ACTIVE scene id, mirrored here so the picker can exclude it: the
     * renderer ignores a layer naming the active scene (a style blended with
     * itself is just that style at a different exposure).
     */
    val activeSceneId = MutableStateFlow<String?>(null)
}

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
    val layers by LayersBus.state.collectAsState()

    LaunchedEffect(Unit) {
        visualizerView.visualizerRenderer.onShaderError = viewModel::reportShaderError
        visualizerView.visualizerRenderer.pcmProvider = { viewModel.latestPcm() }
        LayersBus.availableScenes.value = visualizerView.visualizerRenderer.availableSceneIds()
        viewModel.features.collect {
            // Enriched with progress/section context so the fluid spawn/catch
            // choreography can journey through the track.
            val enriched = viewModel.enrichFeatures(it)
            visualizerView.visualizerRenderer.features = enriched
        }
    }
    LaunchedEffect(viz.sceneId) {
        visualizerView.visualizerRenderer.requestedSceneId = viz.sceneId
        LayersBus.activeSceneId.value = viz.sceneId
    }
    LaunchedEffect(layers) {
        val renderer = visualizerView.visualizerRenderer
        // null when off - not the id at mix 0 - because a set layerSceneId
        // renders a whole second scene per frame whatever the mix says.
        renderer.layerSceneId = if (layers.enabled) layers.sceneId else null
        // Raw values on purpose: the renderer bounds the mix at upload
        // (VisualSafety.layerMix), so a Safe-visuals toggle re-bounds a live
        // layer without anyone having to rewrite these fields.
        renderer.layerMix = layers.mix
        renderer.layerBlend = layers.blend
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
