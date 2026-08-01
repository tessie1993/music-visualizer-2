package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Barred spiral: a disc under DIFFERENTIAL rotation, with the arms drawn by a
 * density wave rather than by the stars themselves.
 *
 * This is the structural opposite of [OrbitScene], which is why both earn a
 * place. Orbits gives every particle its own radius and its own constant
 * angular speed, so the rings stay rings forever. Here the angular speed is a
 * flat-ish rotation curve - w(r) = base / (r + CORE) - so the inner disc laps
 * the outer disc and any pattern made of stars would smear out within a few
 * seconds. Real galaxies have the same problem (the winding dilemma), and the
 * real answer is Lin-Shu density wave theory: the arms are a standing pattern
 * the stars orbit THROUGH, not a set of stars. So the arms live in a separate
 * slowly-rotating logarithmic-spiral phase term that only brightens and swells
 * the stars passing through it, and the disc can shear freely underneath.
 *
 * Music mapping: bass swells the disc and lights the core, mids drive the
 * rotation rate, treble sparkles the rim (where the stars are youngest and
 * bluest), and a beat pushes the density wave forward so the arms visibly
 * sweep. Turbulence widens the arms into a flocculent disc; Speed scales the
 * whole rotation; Endless zoom drifts stars outward and recycles them into the
 * bulge, so the camera reads as falling into the core.
 */
class GalaxyScene(
    shaders: ShaderSources,
    count: Int = 3200,
) : ParticleSceneBase(SceneIds.GALAXY, count, shaders) {
    private companion object {
        /** Softening radius of the rotation curve; below it the disc is solid-body. */
        const val CORE = 0.22f

        /** Arms in the density wave. Two is what a grand-design spiral has. */
        const val ARMS = 2f

        /** Winding tightness of the logarithmic spiral, in radians per ln(r). */
        const val PITCH = 3.1f
    }

    private val random = Random(19)
    private val angle = FloatArray(count) { random.nextFloat() * 2f * PI.toFloat() }

    // sqrt-distributed radii: uniform in AREA, so the disc does not pile up in
    // the middle the way a uniform radius roll does.
    private val radius = FloatArray(count) { 0.06f + sqrt(random.nextFloat()) * 1.02f }
    private val band = IntArray(count) { random.nextInt(64) }
    private val bob = FloatArray(count) { random.nextFloat() * 2f * PI.toFloat() }

    /** Phase of the density wave itself - slow, and the arms belong to it. */
    private var wavePhase = 0f
    private var prevBeat = false

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        val bands = features.bands
        val n = bands.size
        val drive = p.audioDrive
        val swell = 1f + (features.bass * drive).coerceIn(0f, 1.5f) * 0.18f
        // The pattern rotates far slower than the inner disc - that separation
        // IS the density wave. A beat nudges it so the arms sweep on the hit.
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        wavePhase += (0.05f + features.mid * drive * 0.12f) * p.speed * dt
        if (beatEdge) wavePhase += 0.05f * p.beatResponse * features.beatImpulse
        // Turbulence blurs the arms out into a flocculent disc.
        val armSharp = 5.5f / (1f + p.turbulence.coerceIn(0f, 2f) * 2.2f)

        for (i in 0 until count) {
            if (p.endlessZoom) {
                radius[i] *= 1f + p.endlessZoomSpeed * 1.2f * dt
                if (radius[i] > 1.45f) radius[i] = 0.06f + random.nextFloat() * 0.05f
            }
            val r = radius[i]
            val e = (bands[band[i] % n] * drive).coerceIn(0f, 1.5f)
            // Flat-ish rotation curve: solid-body inside CORE, ~1/r outside.
            val omega = (0.9f / (r + CORE)) * p.speed * (0.75f + features.mid * drive * 0.5f)
            angle[i] += omega * dt
            // Distance to the nearest arm crest, in radians of pattern phase.
            val armPhase = (angle[i] - wavePhase * 2f * PI.toFloat()) * ARMS + PITCH * ln(r + CORE) * ARMS
            val toCrest = cos(armPhase)
            // exp-shaped crest: sharp bright arms, dark inter-arm gaps.
            val arm = kotlin.math.exp(-(1f - toCrest) * armSharp)
            // Stars ride slightly INTO the crest, which is what makes the arms
            // look like they have thickness rather than being painted on.
            val pull = arm * 0.05f * (1f + p.turbulence)
            val rr = (r * swell + pull) * (1f + 0.03f * sin(angle[i] * 2f + bob[i]))
            val ca = cos(angle[i])
            val sa = sin(angle[i])

            // Bulge: everything inside CORE is old, red and bright.
            val bulge = kotlin.math.exp(-r * 4.5f)
            // Young blue stars sit in the arms at large radius; treble sparkles them.
            val young = arm * (0.35f + features.treble * drive * 0.9f) * (r / 1.1f)
            val energy = (0.10f + bulge * (0.5f + features.bass * drive * 0.7f) + young + e * 0.45f)

            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = ca * rr
            vertexData[o + 1] = sa * rr * 0.82f
            vertexData[o + 2] = 2f + bulge * 9f + arm * 8f + e * 7f
            // Hue walks outward: hot core, cool rim, arms pushed further still.
            vertexData[o + 3] = (0.06f + r * 0.55f + arm * 0.18f).coerceIn(0f, 1f)
            vertexData[o + 4] = energy.coerceIn(0f, 1f)
            // Orbital tangent, so the shear itself is visible as streak length:
            // inner stars smear, rim stars stay round.
            vertexData[o + VELOCITY_OFFSET] = -sa * rr * omega
            vertexData[o + VELOCITY_OFFSET + 1] = ca * rr * 0.82f * omega
        }
    }

    /** Exposed for the headless test of the winding/shear property. */
    internal fun angularRate(
        r: Float,
        speed: Float,
    ): Float = (0.9f / (abs(r) + CORE)) * speed * 0.75f
}
