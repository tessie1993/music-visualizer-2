package dev.musicviz.render.scene

/**
 * The Customize panel's parameter tabs, as data rather than as bare strings.
 *
 * Lives next to [SceneParams] and [ParamRandomizer] because the grouping is a
 * property of the PARAMETERS, not of the screen that draws them: "Randomize
 * unlocked" rolls one tab at a time, so the randomizer has to know which tab a
 * key belongs to, and it must be the same answer the panel uses to decide
 * which controls to draw. Two lists would drift, and the drift would be
 * silent - a slider that lives in one tab and rolls with another.
 *
 * [title] IS the tab title the panel renders; `VisualsHub.CustomizePanel`
 * builds its tab row from these entries in declaration order, so the order
 * here is the order on screen.
 *
 * The GLSL tab is deliberately NOT an entry: it edits shader source, not
 * [SceneParams], so it has nothing to randomize and nothing to reset.
 */
enum class CustomizeTab(
    val title: String,
) {
    MOTION("Motion"),
    SHAPE("Shape"),
    BEHAVIOR("Behavior"),
    COLOR("Color"),
    FX("FX"),
    FLUID("Fluid"),

    /** Shown only while the CYMATICS style is active - see `CustomizeTabs.CymaticsTab`. */
    CYMATICS("Cymatics"),

    /** Shown only while the HYPERSPACE style is active, for the same reason. */
    HYPERSPACE("Hyperspace"),
}
