package dev.musicviz

import dev.musicviz.render.scene.CustomizeTab
import java.io.File

/**
 * The customization surface, read back out of the source tree.
 *
 * Every Customize parameter has to be wired through four places to work: a
 * control that writes it, a randomizer key that rolls it (or a declared reason
 * not to), a preset JSON key that persists it, and a scene that reads it. The
 * repository's history is a run of commits closing gaps in exactly that wiring
 * one at a time - dead sliders, lock chips keyed to a label nobody rendered,
 * controls shown on styles that ignore them - and the hand-written
 * `docs/PARAM_MATRIX.md` that used to track it was deleted once keeping it
 * current became a chore of its own (there is a commit whose whole subject is
 * correcting line references in it).
 *
 * So the matrix is derived instead of maintained. This object parses the four
 * sources of truth; `CustomizeSurfaceTest` turns them into build gates and
 * regenerates the document from them.
 *
 * Parsing source rather than using reflection is deliberate and follows
 * `ParamRandomizerFluidTest`: the questions here are about the CODE - which
 * composable renders a control, which lock label it carries - and none of that
 * survives into the runtime objects.
 */
object ParamSurface {
    /** Where the main sources sit, relative to the project directory. */
    private const val SOURCES = "app/src/main/java/dev/musicviz/"

    /**
     * Scene classes per family, as `docs/PARAM_MATRIX.md`'s Families table.
     * One class per family (plus the two all-styles rows): each scene is the
     * single place its family reads [SceneParams], and it hands the values on
     * to its own helpers under their own names.
     */
    val FAMILIES: List<Pair<String, List<String>>> =
        listOf(
            "Shader" to listOf("render/scene/ShaderScene.kt"),
            "Particle" to
                listOf(
                    "render/scene/ParticleSceneBase.kt",
                    "render/scene/NebulaScene.kt",
                    "render/scene/BurstScene.kt",
                    "render/scene/SwarmScene.kt",
                    "render/scene/FountainScene.kt",
                    "render/scene/OrbitScene.kt",
                ),
            "MilkDrop" to listOf("render/scene/ProjectMScene.kt"),
            "Fluid" to listOf("render/fluid/FluidScene.kt"),
            "Curl Flow" to listOf("render/fluid/CurlFlowScene.kt"),
            "Water" to listOf("render/fluid/WaterScene.kt"),
            "Cymatics" to listOf("render/scene/CymaticsScene.kt"),
            "Composite" to listOf("render/VisualizerRenderer.kt", "render/CompositeGrade.kt"),
            "Export" to listOf("export/FxCompositor.kt", "export/VideoExporter.kt"),
        )

    /**
     * Composables reached only from one tab, whose writes therefore belong to
     * that tab. The palette chips and the gradient maker are the Color tab's
     * controls; they live in their own files because they are big enough to be
     * (and because `PaletteStore` owns the override sentinel rule).
     */
    private val TAB_EXTRA_FILES: Map<CustomizeTab, List<String>> =
        mapOf(CustomizeTab.COLOR to listOf("ui/PaletteMaker.kt", "ui/PaletteStore.kt"))

    /**
     * Derived properties a scene reads INSTEAD of the field behind them, so
     * "nothing references `palette`" does not read as a dead parameter when
     * every scene resolves it through `paletteBase`/`paletteRange`.
     */
    private val ALIASES: Map<String, List<String>> =
        mapOf(
            "palette" to listOf("paletteBase", "paletteRange"),
            "palette2" to listOf("palette2Base", "palette2Range"),
            "paletteBaseOverride" to listOf("paletteBase", "usesCustomPalette"),
            "paletteRangeOverride" to listOf("paletteRange", "usesCustomPalette"),
            "palette2BaseOverride" to listOf("palette2Base", "usesCustomPalette2"),
            "palette2RangeOverride" to listOf("palette2Range", "usesCustomPalette2"),
            "bassGain" to listOf("applyBandGains"),
            "midGain" to listOf("applyBandGains"),
            "trebGain" to listOf("applyBandGains"),
        )

    /** Every [dev.musicviz.render.scene.SceneParams] field, in declaration order. */
    val fields: List<String> by lazy {
        val ctor =
            source("render/scene/SceneParams.kt")
                .substringAfter("data class SceneParams(")
                .substringBefore("\n) {")
        Regex("(?m)^\\s*val (\\w+):").findAll(ctor).map { it.groupValues[1] }.toList()
    }

    /** Keys `PresetStore.toJson` writes; the preset document's vocabulary. */
    val presetKeys: Set<String> by lazy {
        Regex("\\.put\\(\"(\\w+)\"").findAll(source("ui/PresetStore.kt")).map { it.groupValues[1] }.toSet()
    }

    /** Each tab's composable body, sliced out of `CustomizeTabs.kt`. */
    val tabBodies: Map<CustomizeTab, String> by lazy {
        val src = source("ui/CustomizeTabs.kt")
        val bounds =
            Regex("(?m)^(?:internal |private |)fun (\\w+)\\(")
                .findAll(src)
                .map { it.groupValues[1] to it.range.first }
                .toList()
        val bodies =
            bounds
                .mapIndexed { i, (name, start) ->
                    name to src.substring(start, bounds.getOrNull(i + 1)?.second ?: src.length)
                }.toMap()
        CustomizeTab.entries.associateWith { tab ->
            val composable = if (tab == CustomizeTab.FX) "FxTab" else "${tab.title}Tab"
            bodies[composable] ?: error("no $composable in CustomizeTabs.kt")
        }
    }

    /** Fields each tab's controls write, including its own extra composables. */
    val controlsByTab: Map<CustomizeTab, Set<String>> by lazy {
        CustomizeTab.entries.associateWith { tab ->
            val text = tabBodies.getValue(tab) + TAB_EXTRA_FILES[tab].orEmpty().joinToString("\n") { source(it) }
            assignedFields(text)
        }
    }

    /** Every field reachable from some Customize control. */
    val controlledFields: Set<String> by lazy { controlsByTab.values.flatten().toSet() }

    /** Labels of the controls in [text] that render a lock chip. */
    fun lockableLabels(text: String): Set<String> =
        Regex("(?:LabeledSlider|LabeledIntSlider|CheckRow|LockableChipLabel)\\(\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()

    /** Labels of every lockable control in the panel, whatever tab it is on. */
    val allLockableLabels: Set<String> by lazy { lockableLabels(source("ui/CustomizeTabs.kt")) }

    /** Field -> the lock key whose roll writes it, from `ParamRandomizer`. */
    val rolledBy: Map<String, String> by lazy {
        val src = source("render/scene/ParamRandomizer.kt")
        val blocks = Regex("r\\(\"([^\"]+)\"\\)").findAll(src).toList()
        buildMap {
            blocks.forEachIndexed { i, m ->
                val body = src.substring(m.range.last, blocks.getOrNull(i + 1)?.range?.first ?: src.length)
                assignedFields(body).forEach { put(it, m.groupValues[1]) }
            }
        }
    }

    /** Family -> the fields its scene classes reference (aliases resolved). */
    val readersByFamily: Map<String, Set<String>> by lazy {
        FAMILIES.associate { (family, files) ->
            val text = files.joinToString("\n") { source(it) }
            family to
                fields
                    .filter { field ->
                        (listOf(field) + ALIASES[field].orEmpty()).any { Regex("\\b$it\\b").containsMatchIn(text) }
                    }.toSet()
        }
    }

    /**
     * Names assigned in a `copy(...)`-style call in [text] that are real
     * parameters. Intersecting with [fields] is what makes a regex enough
     * here: a local `val on = chance(...)` cannot collide with a parameter
     * name, so the only matches left are genuine writes.
     */
    private fun assignedFields(text: String): Set<String> =
        Regex("[(,]\\s*(\\w+)\\s*=[^=]")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it in fields }
            .toSet()

    /**
     * The project directory - the one holding `app/` and `docs/`, found by
     * walking up from the working directory (which is the app module when
     * Gradle runs the tests, and the project when an IDE does). Anchored on a
     * file rather than on a directory name so it cannot land on `app/`, whose
     * own `src/main/...` would otherwise match and put the generated matrix in
     * `app/docs/` instead of the `docs/` the README links to.
     */
    val moduleRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, SOURCES + "render/scene/SceneParams.kt").isFile }
            ?: error("musicviz project root not found from ${File("").absolutePath}")
    }

    /** A main source file, by its path under `dev/musicviz/`. */
    fun source(relative: String): String =
        File(moduleRoot, SOURCES + relative)
            .takeIf { it.isFile }
            ?.readText()
            ?: error("$relative not found under ${moduleRoot.absolutePath}")
}
