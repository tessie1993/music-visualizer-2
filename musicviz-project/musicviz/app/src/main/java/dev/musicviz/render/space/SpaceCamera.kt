package dev.musicviz.render.space

import dev.musicviz.render.VisualizerRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One camera for both families: the perspective rig the rasterised and the
 * marched styles share.
 *
 * Nothing in this app has ever had a projection matrix. `HyperspaceCamera`
 * produces a position and a `mat3`, which is all a raymarcher needs, and the
 * cymatics style's only spatial transform is a 2D rotate-and-scale. The moment
 * geometry and a marched volume have to appear in the same frame - occluding
 * each other through one depth buffer - they have to agree on one projection,
 * one near/far pair and one depth encoding. That agreement is this class:
 * [proj] for the rasteriser, [basis] and [tanHalfFov] for the marcher's ray
 * generation, and [depthFromDistance] for the marcher's `gl_FragDepth`, all
 * derived from the same [constraint] so they cannot drift apart.
 *
 * ### The clock, and why it wraps the way it does
 *
 * The rig is one clock. Azimuth, elevation and dolly are all integer harmonics
 * of it, and that is not decoration: `VisualizerRenderer.TIME_WRAP_SEC` wraps
 * every clock in the app at 7100 s (the live wallpaper renders continuously,
 * so an unwrapped clock eventually loses enough float32 precision to stutter
 * and then freeze). A path built from arbitrary rates would step somewhere
 * random when that happened - the camera would jump-cut, once per session, for
 * no visible reason. Built from whole numbers of turns per wrap, every angle
 * arrives back where it started at the instant the clock does, and the jump is
 * zero to float precision. [dev.musicviz.SpaceFoundationTest] pins it, exactly
 * as `RenderClockWrapTest` pins the renderer's own.
 *
 * The harmonics are primes, so the three motions only realign at the full
 * 7100 s: the view drifts for two hours without repeating, and then rejoins
 * itself seamlessly.
 *
 * ### Allocation
 *
 * Every output is a field written in place, per the `HotPathReuseTest`
 * convention. Callers upload them and must not keep them - next frame's
 * [advance] overwrites the same arrays.
 */
internal class SpaceCamera {
    /**
     * The per-style rig. Distance and elevation are stated as RANGES rather
     * than as a value plus a swing, because what a style actually needs to
     * promise is that the camera never goes under its own floor or inside its
     * subject - and a range is checkable.
     */
    data class CameraConstraint(
        /** Closest the eye comes to the look target, in world units. */
        val minDistance: Float = 2.2f,
        val maxDistance: Float = 4.4f,
        /** Below the horizon is negative. Kept off the poles: the look-at
         *  basis degenerates within about 10 degrees of straight down. */
        val minElevationDeg: Float = -24f,
        val maxElevationDeg: Float = 38f,
        /** Vertical field of view. 55 degrees is the value the existing
         *  hyperspace marcher's ray scale works out to, so a style ported onto
         *  this camera keeps its framing. */
        val fovYDeg: Float = 55f,
        /**
         * The shared depth range. Near is as far out as it can be without
         * clipping the subject, because near is what buys depth precision:
         * at 24 bits the resolvable step at distance z is about
         * `z^2 * (1/near - 1/far) / 2^24`, which at the 0.1/60 pair here is
         * 6e-5 world units ten units out - two orders of magnitude finer than
         * anything these styles model.
         */
        val near: Float = 0.1f,
        val far: Float = 60f,
        /** Whole turns of azimuth per [VisualizerRenderer.TIME_WRAP_SEC]; 199
         *  is one revolution per 35.7 s at speed 1. */
        val orbitHarmonic: Int = 199,
        /** Elevation sweeps once per 100 s, dolly once per 165 s. Prime, and
         *  prime against the orbit, so the three never resynchronise inside a
         *  wrap. */
        val elevationHarmonic: Int = 71,
        val dollyHarmonic: Int = 43,
    )

    var constraint: CameraConstraint = CameraConstraint()

    /**
     * A -1..1 nudge along the dolly axis for the music to hold: at +1 the
     * camera sits at [CameraConstraint.minDistance] however the sweep stands,
     * at -1 at the far end. Clamped INTO the range rather than added to it, so
     * a loud passage cannot push the eye inside the subject.
     */
    var distanceBias: Float = 0f

    /**
     * Sub-pixel jitter amplitude in pixels, 0 for none. A style that dithers
     * its own march offsets or draws thin geometry gets its edges resolved by
     * moving the sample grid a fraction of a pixel per frame; the sequence is
     * low-discrepancy rather than random so a short average is even.
     */
    var jitterPixels: Float = 0f

    /** World position of the eye. */
    val position: FloatArray = FloatArray(3)

    /**
     * Camera -> world basis as a column-major `mat3` (right, up, forward) -
     * the same layout and the same meaning as `HyperspaceCamera.basis`, so a
     * marcher moved onto this camera needs no change to its ray generation.
     */
    val basis: FloatArray = FloatArray(9)

    /** Column-major, GL order. [viewProj] is `proj * view`. */
    val view: FloatArray = FloatArray(16)
    val proj: FloatArray = FloatArray(16)
    val viewProj: FloatArray = FloatArray(16)
    val invViewProj: FloatArray = FloatArray(16)

    /** This frame's jitter, in pixels; already folded into [proj]. */
    val jitter: FloatArray = FloatArray(2)

    /**
     * `(A, B)` in `windowDepth = 0.5 * (A - B / t) + 0.5`, for a marcher that
     * writes `gl_FragDepth` from a ray parameter. Uploaded as one `vec2` so
     * the shader cannot derive it from a different near/far than [proj] used;
     * [depthFromDistance] is the same arithmetic on the CPU.
     */
    val depthCoeff: FloatArray = FloatArray(2)

    /** `tan(fovY/2)`: the marcher's ray scale at the image plane. */
    var tanHalfFov: Float = 0f
        private set

    /** Seconds on the rig clock, wrapped. Exposed for a style whose own
     *  animation has to stay in phase with the camera's. */
    var clock: Float = 0f
        private set

    private val target = FloatArray(3)
    private var jitterIndex = 0
    private var aspect = 1f
    private var viewportW = 1
    private var viewportH = 1

    /** Where the rig orbits. Defaults to the world origin. */
    fun lookAt(
        x: Float,
        y: Float,
        z: Float,
    ) {
        target[0] = x
        target[1] = y
        target[2] = z
    }

    fun reset() {
        clock = 0f
        jitterIndex = 0
        distanceBias = 0f
    }

    /**
     * Advances the rig and rebuilds every matrix.
     *
     * @param speed multiplier on the clock; 0 parks the camera without
     *   resetting it, which is what a "still" style wants.
     * @param viewportWidth pixels being rendered at THIS frame - [ResTarget]'s
     *   reduced size when it is in reduced mode, not the scene's full size, or
     *   the jitter lands on the wrong grid.
     */
    fun advance(
        dt: Float,
        speed: Float,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        clock = (clock + dt * max(speed, 0f)) % VisualizerRenderer.TIME_WRAP_SEC
        viewportW = max(viewportWidth, 1)
        viewportH = max(viewportHeight, 1)
        aspect = viewportW.toFloat() / viewportH

        val c = constraint
        val azimuth = TAU * phase(c.orbitHarmonic)
        // sin of a whole-turn phase, so the sweep is continuous across the
        // wrap for the same reason the azimuth is.
        val elevSweep = sin(TAU * phase(c.elevationHarmonic))
        val dollySweep = sin(TAU * phase(c.dollyHarmonic))

        val elevation = lerpRange(c.minElevationDeg, c.maxElevationDeg, elevSweep) * DEG_TO_RAD
        // The bias BENDS the sweep towards one end of the range instead of
        // being added to it: at |bias| = 1 the sweep is fully overridden and
        // the eye sits exactly on the limit.
        val bias = distanceBias.coerceIn(-1f, 1f)
        val dolly = dollySweep * (1f - abs(bias)) - bias
        val distance = lerpRange(c.minDistance, c.maxDistance, dolly)

        val ce = cos(elevation)
        position[0] = target[0] + distance * ce * cos(azimuth)
        position[1] = target[1] + distance * sin(elevation)
        position[2] = target[2] + distance * ce * sin(azimuth)

        buildBasis()
        buildView()
        buildProj(c)
        multiply(proj, view, viewProj)
        invert(viewProj, invViewProj)
    }

    /**
     * Window-space depth (0 at the near plane, 1 at the far plane) for a point
     * [distance] world units in front of the eye along the view axis. A marched
     * style writes this into `gl_FragDepth` and is then occluded by, and
     * occludes, rasterised geometry through the same [DepthStage] buffer.
     */
    fun depthFromDistance(distance: Float): Float {
        val t = max(distance, constraint.near)
        return (0.5f * (depthCoeff[0] - depthCoeff[1] / t) + 0.5f).coerceIn(0f, 1f)
    }

    /** Whole-turn phase in [0,1) for an integer harmonic of the wrap.
     *
     *  In double because the whole point is the wrap: `harmonic * clock` at
     *  clock near 7100 is a five-digit number whose float32 ULP is 1e-3 of a
     *  turn, and taking the fractional part there would put a visible step in
     *  the orbit at the one moment this is all designed to be smooth. */
    private fun phase(harmonic: Int): Float = ((harmonic * clock.toDouble() / VisualizerRenderer.TIME_WRAP_SEC) % 1.0).toFloat()

    /** Look at [target]: forward, then right, then a re-derived up. */
    private fun buildBasis() {
        var fx = target[0] - position[0]
        var fy = target[1] - position[1]
        var fz = target[2] - position[2]
        val fl = 1f / max(sqrt(fx * fx + fy * fy + fz * fz), 1e-5f)
        fx *= fl
        fy *= fl
        fz *= fl
        // World up, unless the view is nearly vertical - there the cross
        // product degenerates and the frame would flip. The elevation range in
        // CameraConstraint keeps a style off this branch; it exists because a
        // style may drive lookAt() anywhere.
        val upIsY = abs(fy) < 0.985f
        val ux = if (upIsY) 0f else 1f
        val uy = if (upIsY) 1f else 0f
        // right = cross(forward, up), with up.z = 0 in both branches.
        var rx = -fz * uy
        var ry = fz * ux
        var rz = fx * uy - fy * ux
        val rl = 1f / max(sqrt(rx * rx + ry * ry + rz * rz), 1e-5f)
        rx *= rl
        ry *= rl
        rz *= rl
        // up = cross(right, forward); orthonormal by construction rather than
        // by assumption, which is what keeps the basis good to 1e-6 at every
        // orientation instead of only away from the poles.
        val vx = ry * fz - rz * fy
        val vy = rz * fx - rx * fz
        val vz = rx * fy - ry * fx
        basis[0] = rx
        basis[1] = ry
        basis[2] = rz
        basis[3] = vx
        basis[4] = vy
        basis[5] = vz
        basis[6] = fx
        basis[7] = fy
        basis[8] = fz
    }

    /** The inverse of the camera's world transform, column-major. */
    private fun buildView() {
        val rx = basis[0]
        val ry = basis[1]
        val rz = basis[2]
        val ux = basis[3]
        val uy = basis[4]
        val uz = basis[5]
        val fx = basis[6]
        val fy = basis[7]
        val fz = basis[8]
        val ex = position[0]
        val ey = position[1]
        val ez = position[2]
        view[0] = rx
        view[1] = ux
        view[2] = -fx
        view[3] = 0f
        view[4] = ry
        view[5] = uy
        view[6] = -fy
        view[7] = 0f
        view[8] = rz
        view[9] = uz
        view[10] = -fz
        view[11] = 0f
        view[12] = -(rx * ex + ry * ey + rz * ez)
        view[13] = -(ux * ex + uy * ey + uz * ez)
        view[14] = fx * ex + fy * ey + fz * ez
        view[15] = 1f
    }

    private fun buildProj(c: CameraConstraint) {
        val near = max(c.near, 1e-3f)
        val far = max(c.far, near * 2f)
        tanHalfFov = tan(0.5f * c.fovYDeg.coerceIn(20f, 120f) * DEG_TO_RAD)
        val cot = 1f / tanHalfFov
        proj.fill(0f)
        proj[0] = cot / max(aspect, 1e-3f)
        proj[5] = cot
        proj[10] = (far + near) / (near - far)
        proj[11] = -1f
        proj[14] = 2f * far * near / (near - far)
        depthCoeff[0] = (far + near) / (far - near)
        depthCoeff[1] = 2f * far * near / (far - near)
        if (jitterPixels > 0f) {
            // R2, the two-dimensional low-discrepancy sequence generated by
            // the plastic number: successive frames land far apart on the
            // sub-pixel square, so a two-frame average is already close to
            // even, where a random offset would clump.
            jitterIndex = (jitterIndex + 1) and (JITTER_PERIOD - 1)
            val n = jitterIndex.toFloat()
            jitter[0] = (frac(0.5f + R2_ALPHA1 * n) - 0.5f) * jitterPixels
            jitter[1] = (frac(0.5f + R2_ALPHA2 * n) - 0.5f) * jitterPixels
            // A shear on the projection's third column, which is a translation
            // in NDC of `2 * pixels / viewport`: shifting the vertices instead
            // would move the geometry relative to the lights, rather than the
            // sample grid relative to the geometry.
            proj[8] = 2f * jitter[0] / viewportW
            proj[9] = 2f * jitter[1] / viewportH
        } else {
            jitter[0] = 0f
            jitter[1] = 0f
        }
    }

    private companion object {
        const val TAU: Float = (2.0 * PI).toFloat()
        const val DEG_TO_RAD: Float = (PI / 180.0).toFloat()

        /** 1/plastic-number and its square: the R2 sequence's two increments. */
        const val R2_ALPHA1: Float = 0.7548777f
        const val R2_ALPHA2: Float = 0.56984025f

        /** Power of two so the index wraps with a mask and the products stay
         *  small enough that `frac` keeps its precision. */
        const val JITTER_PERIOD: Int = 1024

        fun frac(x: Float): Float = x - floor(x)

        /** [t] in -1..1 across the range. */
        fun lerpRange(
            lo: Float,
            hi: Float,
            t: Float,
        ): Float {
            val mid = 0.5f * (lo + hi)
            val half = 0.5f * (hi - lo)
            return mid + half * t.coerceIn(-1f, 1f)
        }

        /** `out = a * b`, all column-major. */
        fun multiply(
            a: FloatArray,
            b: FloatArray,
            out: FloatArray,
        ) {
            for (col in 0 until 4) {
                val b0 = b[col * 4]
                val b1 = b[col * 4 + 1]
                val b2 = b[col * 4 + 2]
                val b3 = b[col * 4 + 3]
                for (row in 0 until 4) {
                    out[col * 4 + row] = a[row] * b0 + a[4 + row] * b1 + a[8 + row] * b2 + a[12 + row] * b3
                }
            }
        }

        /**
         * Inverse by the adjugate over six pairs of 2x2 minors - textbook
         * Cramer, written out rather than looped because a loop over cofactors
         * is both slower and harder to check than the closed form.
         *
         * The indices are POSITIONAL: the algorithm is stated for row-major
         * and applied here to a column-major array, which is the same array
         * read as the transpose. `inverse(M^T) = inverse(M)^T`, so the answer
         * comes back out in the layout it went in. This is worth stating
         * because getting it wrong produces a matrix that looks plausible and
         * is the inverse of the wrong thing.
         *
         * A singular matrix leaves [out] as the identity: wrong, but finite. A
         * NaN in the inverse view-projection reaches every world-space
         * reconstruction in the frame and turns it black.
         */
        fun invert(
            m: FloatArray,
            out: FloatArray,
        ) {
            val s0 = m[0] * m[5] - m[4] * m[1]
            val s1 = m[0] * m[6] - m[4] * m[2]
            val s2 = m[0] * m[7] - m[4] * m[3]
            val s3 = m[1] * m[6] - m[5] * m[2]
            val s4 = m[1] * m[7] - m[5] * m[3]
            val s5 = m[2] * m[7] - m[6] * m[3]
            val c5 = m[10] * m[15] - m[14] * m[11]
            val c4 = m[9] * m[15] - m[13] * m[11]
            val c3 = m[9] * m[14] - m[13] * m[10]
            val c2 = m[8] * m[15] - m[12] * m[11]
            val c1 = m[8] * m[14] - m[12] * m[10]
            val c0 = m[8] * m[13] - m[12] * m[9]
            val det = s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0
            if (abs(det) < 1e-12f) {
                out.fill(0f)
                out[0] = 1f
                out[5] = 1f
                out[10] = 1f
                out[15] = 1f
                return
            }
            val d = 1f / det
            out[0] = (m[5] * c5 - m[6] * c4 + m[7] * c3) * d
            out[1] = (-m[1] * c5 + m[2] * c4 - m[3] * c3) * d
            out[2] = (m[13] * s5 - m[14] * s4 + m[15] * s3) * d
            out[3] = (-m[9] * s5 + m[10] * s4 - m[11] * s3) * d
            out[4] = (-m[4] * c5 + m[6] * c2 - m[7] * c1) * d
            out[5] = (m[0] * c5 - m[2] * c2 + m[3] * c1) * d
            out[6] = (-m[12] * s5 + m[14] * s2 - m[15] * s1) * d
            out[7] = (m[8] * s5 - m[10] * s2 + m[11] * s1) * d
            out[8] = (m[4] * c4 - m[5] * c2 + m[7] * c0) * d
            out[9] = (-m[0] * c4 + m[1] * c2 - m[3] * c0) * d
            out[10] = (m[12] * s4 - m[13] * s2 + m[15] * s0) * d
            out[11] = (-m[8] * s4 + m[9] * s2 - m[11] * s0) * d
            out[12] = (-m[4] * c3 + m[5] * c1 - m[6] * c0) * d
            out[13] = (m[0] * c3 - m[1] * c1 + m[2] * c0) * d
            out[14] = (-m[12] * s3 + m[13] * s1 - m[14] * s0) * d
            out[15] = (m[8] * s3 - m[9] * s1 + m[10] * s0) * d
        }
    }
}
