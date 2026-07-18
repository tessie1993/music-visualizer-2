package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.export.VideoExporter
import dev.musicviz.render.scene.BurstScene
import dev.musicviz.render.scene.FountainScene
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.NebulaScene
import dev.musicviz.render.scene.PMBridge
import dev.musicviz.render.scene.ParticleSceneBase
import dev.musicviz.render.scene.ProjectMScene
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.render.scene.ShaderScene
import dev.musicviz.render.scene.SwarmScene
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Multi-scene GL ES 3.0 renderer. Scene switching, params and shader edits
 * are queued from other threads and applied on the GL thread. All GL
 * resources are (re)created in [onSurfaceCreated] (context lost on pause).
 */
class VisualizerRenderer(private val context: Context) : GLSurfaceView.Renderer {
    companion object {
        /** Fragment-shader scenes: id -> raw resource. Order = UI order. */
        val SHADER_SCENES: Map<String, Int> =
            linkedMapOf(
                SceneIds.JULIA to R.raw.julia_frag,
                SceneIds.TUNNEL to R.raw.tunnel_frag,
                SceneIds.MANDEL to R.raw.mandel_frag,
                SceneIds.KALEIDO to R.raw.kaleido_frag,
                SceneIds.PLASMA to R.raw.plasma_frag,
                SceneIds.BARS to R.raw.bars_frag,
                SceneIds.RING to R.raw.ring_frag,
                SceneIds.SCOPE to R.raw.scope_frag,
                SceneIds.LISS to R.raw.liss_frag,
                SceneIds.WARP to R.raw.warp_frag,
                SceneIds.GRID to R.raw.grid_frag,
                SceneIds.VORONOI to R.raw.voronoi_frag,
                SceneIds.METABALLS to R.raw.metaballs_frag,
                SceneIds.RIPPLES to R.raw.ripples_frag,
            )
        val PARTICLE_SCENES: List<String> =
            listOf(SceneIds.NEBULA, SceneIds.BURSTS, SceneIds.SWARM, SceneIds.FOUNTAIN)
    }

    @Volatile
    var features: AudioFeatures = AudioFeatures.empty()

    @Volatile
    var requestedSceneId: String = SceneIds.NEBULA

    @Volatile
    var sceneParams: SceneParams = SceneParams.DEFAULT

    @Volatile
    var onShaderError: (String?) -> Unit = {}

    @Volatile
    private var pendingCustomShader: Pair<String, String>? = null

    /** Newest mono PCM window for projectM; set by the UI wiring. */
    @Volatile
    var pcmProvider: () -> FloatArray? = { null }

    val milkdropAvailable: Boolean get() = PMBridge.available

    private val scenes = LinkedHashMap<String, Scene>()
    private var activeScene: Scene? = null
    private var width = 1
    private var height = 1
    private var lastFrameMs = 0L
    private var timeSeconds = 0f
    private var fadeProgram = 0
    private var fadeVao = 0

    fun availableSceneIds(): List<String> =
        buildList {
            addAll(PARTICLE_SCENES)
            addAll(SHADER_SCENES.keys)
            if (PMBridge.available) add(SceneIds.MILKDROP)
        }

    fun submitShader(
        sceneId: String,
        fragmentSrc: String,
    ) {
        pendingCustomShader = sceneId to fragmentSrc
    }

    fun shaderSourceFor(sceneId: String): String? = SHADER_SCENES[sceneId]?.let { loadRaw(it) }

    fun loadMilkPreset(path: String) {
        (scenes[SceneIds.MILKDROP] as? ProjectMScene)?.queuePreset(path)
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        scenes.values.forEach { it.release() }
        scenes.clear()
        val particleShaders = particleShaderSources(context)
        scenes[SceneIds.NEBULA] = NebulaScene(particleShaders)
        scenes[SceneIds.BURSTS] = BurstScene(particleShaders)
        scenes[SceneIds.SWARM] = SwarmScene(particleShaders)
        scenes[SceneIds.FOUNTAIN] = FountainScene(particleShaders)
        val quadVert = loadRaw(R.raw.quad_vert)
        for ((id, res) in SHADER_SCENES) {
            scenes[id] = ShaderScene(id, quadVert, loadRaw(res)) { onShaderError(it) }
        }
        if (PMBridge.available) scenes[SceneIds.MILKDROP] = ProjectMScene { pcmProvider() }
        scenes.values.forEach { it.init() }
        activeScene = scenes[requestedSceneId] ?: scenes[SceneIds.NEBULA]

        fadeProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.fade_frag))
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        fadeVao = ids[0]
        GLES30.glClearColor(0.02f, 0.01f, 0.05f, 1f)
        lastFrameMs = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        GLES30.glViewport(0, 0, width, height)
        scenes.values.forEach { it.resize(width, height) }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
        lastFrameMs = now
        timeSeconds += dt

        pendingCustomShader?.let { (sceneId, src) ->
            pendingCustomShader = null
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        val requested = scenes[requestedSceneId]
        if (requested != null && requested !== activeScene) activeScene = requested

        val scene = activeScene ?: return
        val p = sceneParams
        if (p.trails && scene is ParticleSceneBase) {
            drawFadeQuad(1f - p.trailLength * 0.97f)
        } else {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        scene.setParams(p)
        scene.update(features, dt)
        scene.draw(timeSeconds)
    }

    private fun drawFadeQuad(alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(fadeProgram)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fadeProgram, "uFadeAlpha"), alpha.coerceIn(0.02f, 1f))
        GLES30.glBindVertexArray(fadeVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun loadRaw(resId: Int): String = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }

    /**
     * Builds fresh scene instances for the export GL context. Never reuses
     * live-context objects: GL handles are not shareable across contexts.
     */
    fun exportSceneFactory(sceneId: String): VideoExporter.SceneFactory =
        object : VideoExporter.SceneFactory {
            override fun create(): Scene {
                val exportParams = sceneParams
                val particleShaders = particleShaderSources(context)
                val quadVert = loadRaw(R.raw.quad_vert)
                val scene: Scene =
                    when {
                        sceneId == SceneIds.MILKDROP && PMBridge.available -> ProjectMScene { null }
                        sceneId == SceneIds.BURSTS -> BurstScene(particleShaders)
                        sceneId == SceneIds.SWARM -> SwarmScene(particleShaders)
                        sceneId == SceneIds.FOUNTAIN -> FountainScene(particleShaders)
                        SHADER_SCENES.containsKey(sceneId) ->
                            ShaderScene(sceneId, quadVert, loadRaw(SHADER_SCENES.getValue(sceneId)))
                        else -> NebulaScene(particleShaders)
                    }
                scene.setParams(exportParams)
                return scene
            }
        }

    private fun particleShaderSources(context: Context): ParticleSceneBase.ShaderSources =
        ParticleSceneBase.ShaderSources(loadRaw(R.raw.particle_vert), loadRaw(R.raw.particle_frag))
}
