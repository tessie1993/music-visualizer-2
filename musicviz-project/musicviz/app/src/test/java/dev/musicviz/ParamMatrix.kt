package dev.musicviz

import dev.musicviz.render.scene.CustomizeTab
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams

/**
 * Renders `docs/PARAM_MATRIX.md` from [ParamSurface].
 *
 * The document is the same one the README has pointed at since v0.12 - what
 * every Customize parameter is wired to, and which scene families read it -
 * except that it is now derived from the sources instead of written by hand.
 * The hand-written one drifted (there is a commit whose entire subject is
 * fixing line references in it) and was eventually deleted, which left the
 * README linking to nothing and the surface unguarded.
 */
object ParamMatrix {
    fun render(): String =
        buildString {
            appendLine("# Param × Scene-Family Matrix")
            appendLine()
            appendLine("**Generated — do not edit.** `CustomizeSurfaceTest` rewrites this file from")
            appendLine("the sources whenever they drift, and fails the build until the new version is")
            appendLine("committed. To regenerate deliberately: `./gradlew :app:testDebugUnitTest`.")
            appendLine()
            appendLine("What every Customize parameter is wired to. The same test enforces the four")
            appendLine("columns: a parameter with no control, no roll (and no declared reason), no")
            appendLine("preset key or no reader fails the build.")
            appendLine()
            appendLine("A **·** in a family column means that scene class references the parameter")
            appendLine("directly (or through the property that resolves it, e.g. `palette` →")
            appendLine("`paletteBase`). A blank is not \"does nothing\": most of Shape, Color and FX")
            appendLine("reach every style through the composite pass, which is its own column.")
            appendLine()
            appendLine("## Families")
            appendLine()
            appendLine("| Family | Classes |")
            appendLine("|---|---|")
            ParamSurface.FAMILIES.forEach { (family, files) ->
                appendLine("| **$family** | ${files.joinToString(", ") { "`${it.substringAfterLast('/').removeSuffix(".kt")}`" }} |")
            }
            appendLine()
            appendLine("## Parameters")
            appendLine()
            val families = ParamSurface.FAMILIES.map { it.first }
            appendLine("| Parameter | Tab | Rolled by | Preset key | ${families.joinToString(" | ")} |")
            appendLine("|---|---|---|---|${families.joinToString("") { "---|" }}")
            val tabOf =
                CustomizeTab.entries
                    .flatMap { tab -> ParamSurface.controlsByTab.getValue(tab).map { it to tab } }
                    .groupBy({ it.first }, { it.second.title })
            for (field in ParamSurface.fields) {
                val tabs = tabOf[field].orEmpty().joinToString(" + ").ifEmpty { "—" }
                val roll = ParamSurface.rolledBy[field]?.let { "`$it`" } ?: "—"
                val key = if (field in ParamSurface.presetKeys) "`$field`" else "**missing**"
                val cells =
                    families.joinToString(" | ") {
                        if (field in ParamSurface.readersByFamily.getValue(it)) "·" else " "
                    }
                appendLine("| `$field` | $tabs | $roll | $key | $cells |")
            }
            appendLine()
            appendLine("## Never randomized")
            appendLine()
            appendLine("Declared in `ParamRandomizer.NEVER_ROLLED`; the test checks the list against")
            appendLine("the parameters no roll actually writes, in both directions.")
            appendLine()
            appendLine("| Parameter | Why |")
            appendLine("|---|---|")
            ParamRandomizer.NEVER_ROLLED.forEach { (field, why) -> appendLine("| `$field` | $why |") }
            appendLine()
            appendLine("## Rendered by nothing")
            appendLine()
            appendLine("Declared in `SceneParams.NOT_RENDERED`; every other parameter has to be read")
            appendLine("by a scene, the composite pass or the export compositor.")
            appendLine()
            appendLine("| Parameter | What it is for |")
            appendLine("|---|---|")
            SceneParams.NOT_RENDERED.forEach { (field, why) -> appendLine("| `$field` | $why |") }
            appendLine()
            appendLine("## Tabs")
            appendLine()
            appendLine("Tabs are `render.scene.CustomizeTab`: the panel builds its row from that enum")
            appendLine("and \"⚄ Randomize <tab>\" rolls exactly the keys below it.")
            appendLine()
            appendLine("| Tab | Controls | Rolled keys |")
            appendLine("|---|---|---|")
            CustomizeTab.entries.forEach { tab ->
                appendLine(
                    "| ${tab.title} | ${ParamSurface.controlsByTab.getValue(tab).size} | " +
                        "${ParamRandomizer.keysFor(tab).size} |",
                )
            }
        }
}
