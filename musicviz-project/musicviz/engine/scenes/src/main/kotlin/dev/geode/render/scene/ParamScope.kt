package dev.geode.render.scene

/**
 * Which styles a Customize parameter can actually move.
 *
 * WHY (R4, no dead controls): a control that does nothing for the style on screen has to be
 * HIDDEN, not greyed out with an apology under it. This is the one place that answers "is this
 * parameter live right now?", and every surface that shows, rolls or modulates a parameter asks
 * it: the Customize tabs, the modulation target picker and the randomizer. Keeping the answer in
 * one enum is what stops the panel drifting back into three different half-truths.
 *
 * [UNIVERSAL] is not a shrug — it means the composite pass applies the parameter to the finished
 * frame for every style (see `composite_frag.glsl` and `CompositeGrade.gateFor`), so it really
 * does reach all of them.
 */
enum class ParamScope {
    /** Applied to the final frame by the composite pass, so every style honours it. */
    UNIVERSAL,

    /** The scene clock. MilkDrop presets pace themselves and Beam draws the signal directly. */
    SCENE_CLOCK,

    /** Scales the band/level envelopes a scene reads. MilkDrop takes raw PCM instead. */
    AUDIO_DRIVE,

    BASS_BAND,

    MID_BAND,

    TREBLE_BAND,

    /** Lives in the shader-scene fragment programs (morph, dual palette, colour map, duotone). */
    SHADER_LOOK,

    /** The endless-dive phase, integrated by the shader scenes and MilkDrop. */
    ENDLESS_ZOOM,

    /** An in-scene force, not a screen effect. */
    TURBULENCE,

    /** Thins Fluid's particle population. */
    DYE_DENSITY,

    /** Whether a persistent scene keeps its previous frame at all. */
    TRAIL_TOGGLE,

    /** How long a persistent scene's afterimage lasts. */
    TRAIL_LENGTH,

    /** Echo zoom/warp, applied by `TrailPass` to the styles that persist their target. */
    TRAIL_ECHO,

    /** Point-sprite look, for the styles that draw particles as sprites. */
    PARTICLE_SPRITE,

    MILKDROP,

    /** The Fluid solver itself. */
    FLUID_SIM,

    /** Splat emitters: Fluid and Water. */
    EMITTERS,

    /** Spawn/catch choreography: Fluid, Curl Flow and Water. */
    JOURNEY,

    WATER,

    CYMATICS,

    HYPERSPACE,

    BEAM,

    /** The standalone FlowField sim. Fluid drives the field from its own solver instead. */
    FLOW_FIELD_SIM,

    /** The shared ripple overlay. Water's own surface already refracts, so it stays off there. */
    RIPPLE_OVERLAY,
    ;

    fun appliesTo(sceneId: String): Boolean = appliesTo(SceneCapabilities.kindOf(sceneId))

    fun appliesTo(kind: SceneKind): Boolean =
        when (this) {
            UNIVERSAL -> true
            SCENE_CLOCK -> kind != SceneKind.MILKDROP && kind != SceneKind.BEAM
            AUDIO_DRIVE -> kind != SceneKind.MILKDROP
            BASS_BAND -> kind in BASS_READERS
            MID_BAND -> kind in MID_READERS
            TREBLE_BAND -> kind in TREBLE_READERS
            SHADER_LOOK -> kind == SceneKind.SHADER
            ENDLESS_ZOOM -> kind == SceneKind.SHADER || kind == SceneKind.MILKDROP
            TURBULENCE -> kind in TURBULENCE_READERS
            DYE_DENSITY -> kind == SceneKind.FLUID
            TRAIL_TOGGLE -> kind == SceneKind.SILK || kind == SceneKind.CURL_FLOW
            TRAIL_LENGTH -> kind == SceneKind.SILK || kind == SceneKind.CURL_FLOW || kind == SceneKind.BEAM
            TRAIL_ECHO -> kind == SceneKind.CURL_FLOW || kind == SceneKind.BEAM
            PARTICLE_SPRITE -> kind == SceneKind.FLUID || kind == SceneKind.CURL_FLOW
            MILKDROP -> kind == SceneKind.MILKDROP
            FLUID_SIM -> kind == SceneKind.FLUID
            EMITTERS -> kind == SceneKind.FLUID || kind == SceneKind.WATER
            JOURNEY -> kind == SceneKind.FLUID || kind == SceneKind.CURL_FLOW || kind == SceneKind.WATER
            WATER -> kind == SceneKind.WATER
            CYMATICS -> kind == SceneKind.CYMATICS
            HYPERSPACE -> kind == SceneKind.HYPERSPACE
            BEAM -> kind == SceneKind.BEAM
            FLOW_FIELD_SIM -> kind != SceneKind.FLUID
            RIPPLE_OVERLAY -> kind != SceneKind.WATER
        }

    private companion object {
        // Per-band gain only reaches a style that reads that band's envelope. Beam and MilkDrop
        // consume raw PCM and never look at bass/mid/treble at all.
        val BASS_READERS =
            setOf(
                SceneKind.SHADER,
                SceneKind.SILK,
                SceneKind.MYCELIUM,
                SceneKind.ACID,
                SceneKind.FLUID,
                SceneKind.WATER,
                SceneKind.HYPERSPACE,
            )

        val MID_READERS =
            setOf(
                SceneKind.SHADER,
                SceneKind.SILK,
                SceneKind.ACID,
                SceneKind.FLUID,
                SceneKind.CURL_FLOW,
                SceneKind.WATER,
                SceneKind.HYPERSPACE,
            )

        val TREBLE_READERS =
            setOf(
                SceneKind.SHADER,
                SceneKind.SILK,
                SceneKind.LIFE,
                SceneKind.MYCELIUM,
                SceneKind.ACID,
                SceneKind.FLUID,
                SceneKind.CURL_FLOW,
                SceneKind.WATER,
                SceneKind.CYMATICS,
                SceneKind.HYPERSPACE,
            )

        val TURBULENCE_READERS =
            setOf(
                SceneKind.SHADER,
                SceneKind.MYCELIUM,
                SceneKind.ACID,
                SceneKind.CURL_FLOW,
            )
    }
}
