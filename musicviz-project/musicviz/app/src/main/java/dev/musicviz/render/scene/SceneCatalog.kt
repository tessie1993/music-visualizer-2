package dev.musicviz.render.scene

/**
 * The one place that says which styles exist and which of them a random pick
 * may land on.
 *
 * This used to be spelled out three times — the renderer's `availableSceneIds`,
 * the Styles tab, and the ViewModel's random-mode pool — and the three had
 * already drifted: the random pool was built from the particle and shader
 * scenes alone, so Fluid, Curl Flow and Water could never be picked even with
 * "include styles" on. They were reachable only indirectly, through the
 * built-in presets that happen to use them.
 *
 * The particle and shader id lists still live on `VisualizerRenderer` (they
 * carry its shader resource ids), so they are passed in rather than owned
 * here; folding them in belongs to the wider scene-registry step.
 */
object SceneCatalog {
    /**
     * Styles backed by their own simulation rather than a shader program or a
     * particle system. Grouped because the UI, the renderer and the exporter
     * all gate on the family as a whole.
     */
    val FLUID_FAMILY: List<String> = listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER)

    /** Every style the user can choose, in Styles-tab order. */
    fun selectableStyles(
        particle: List<String>,
        shader: Collection<String>,
        milkdropAvailable: Boolean,
    ): List<String> =
        buildList {
            addAll(particle)
            addAll(shader)
            if (milkdropAvailable) add(SceneIds.MILKDROP)
            addAll(FLUID_FAMILY)
        }

    /**
     * Styles a random/auto pick may land on: [selectableStyles] without
     * MilkDrop, which needs a `.milk` file to show anything and is therefore
     * offered through its own "include MilkDrop" toggle instead.
     */
    fun randomStyles(
        particle: List<String>,
        shader: Collection<String>,
    ): List<String> = selectableStyles(particle, shader, milkdropAvailable = false)
}
