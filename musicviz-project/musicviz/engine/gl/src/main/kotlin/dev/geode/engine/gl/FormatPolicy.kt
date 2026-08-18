package dev.geode.engine.gl

/**
 * How texels in a resolved format are to be read and written.
 *
 * There is deliberately no logarithmic member: §6.3 forbids log-packing any
 * field that receives additive deposits, so the encoding a policy decision can
 * express is the encoding a deposit target is allowed to have.
 */
enum class TexelEncoding {
    /** Values stored as-is; the format's own numeric range carries them. */
    LINEAR,

    /** IEEE-754 bits packed into unsigned integer channels; shaders unpack. */
    FLOAT_BITS_IN_UINT,

    /** Values divided by a documented scale into a normalized format. */
    PRE_SCALED,
}

/**
 * One policy decision: the format a resource role gets on this device, how its
 * texels are encoded, whether hardware filtering may be assumed, and why —
 * the reason feeds the debug capability screen, so a tester on an unfamiliar
 * device can read which probe made the choice.
 */
data class ResolvedFormat(
    val format: ProbedFormat,
    val encoding: TexelEncoding,
    val filterable: Boolean,
    val because: String,
)

/**
 * The per-role outcome of [FormatPolicy.resolve] for one device.
 *
 * Roles, not resources: V2-4-03's pools and V2-4-05's audio textures ask
 * "what does simulation state get here", never "is R16F supported".
 */
data class FormatPlan(
    /** Ping-pong simulation state: exact, never filtered. */
    val simulationState: ResolvedFormat,
    /** Fields sampled with bilinear filtering — velocity, height, flow. */
    val filterableField: ResolvedFormat,
    /** Additive deposit targets — trails, density, glow accumulation. */
    val linearAccumulation: ResolvedFormat,
    /** Uploaded audio data — waveform, spectrum, histories. Never rendered to. */
    val audioTexture: ResolvedFormat,
    /** Intermediate targets for linear-light blending and bloom. */
    val linearColorTarget: ResolvedFormat,
)

/**
 * §6.3 as a function. Each role walks its own ladder from the preferred
 * format down to `RGBA8`, and a rung is taken only on proven behaviour from
 * the probe report — never on the version string, never on the extension list
 * alone. The bottom rung is unconditional: `RGBA8` renderability is core, and
 * a device that fails even that still gets a named plan rather than a black
 * frame (§9.3).
 */
object FormatPolicy {
    fun resolve(report: GlProbeReport): FormatPlan =
        FormatPlan(
            simulationState = simulationState(report),
            filterableField = filterableField(report),
            linearAccumulation = linearAccumulation(report),
            audioTexture = audioTexture(report),
            linearColorTarget = linearColorTarget(report),
        )

    private fun simulationState(report: GlProbeReport): ResolvedFormat {
        val state = report.probeOf(ProbedFormat.RGBA32UI)
        if (state.renderable) {
            return ResolvedFormat(
                format = ProbedFormat.RGBA32UI,
                encoding = TexelEncoding.FLOAT_BITS_IN_UINT,
                filterable = false,
                because = "RGBA32UI proved renderable; float bits packed into uint channels",
            )
        }
        val half = report.probeOf(ProbedFormat.RGBA16F)
        if (half.renderable) {
            return ResolvedFormat(
                format = ProbedFormat.RGBA16F,
                encoding = TexelEncoding.LINEAR,
                filterable = half.filtersLinearly,
                because = "RGBA32UI failed its probe on this driver; RGBA16F proved renderable",
            )
        }
        return lastResort("neither RGBA32UI nor RGBA16F proved renderable")
    }

    private fun filterableField(report: GlProbeReport): ResolvedFormat {
        val rg = report.probeOf(ProbedFormat.RG16F)
        if (rg.renderable && rg.filtersLinearly) {
            return ResolvedFormat(
                format = ProbedFormat.RG16F,
                encoding = TexelEncoding.LINEAR,
                filterable = true,
                because = "RG16F proved renderable and filterable",
            )
        }
        val state = report.probeOf(ProbedFormat.RGBA32UI)
        if (state.renderable) {
            return ResolvedFormat(
                format = ProbedFormat.RGBA32UI,
                encoding = TexelEncoding.FLOAT_BITS_IN_UINT,
                filterable = false,
                because = "RG16F unproven; packed state with manual interpolation in the shader",
            )
        }
        return lastResort("neither RG16F nor RGBA32UI proved renderable")
    }

    private fun linearAccumulation(report: GlProbeReport): ResolvedFormat {
        val r16f = report.probeOf(ProbedFormat.R16F)
        if (r16f.renderable && r16f.blendsAdditively) {
            return ResolvedFormat(
                format = ProbedFormat.R16F,
                encoding = TexelEncoding.LINEAR,
                filterable = r16f.filtersLinearly,
                because = "R16F proved renderable and additively blendable",
            )
        }
        return ResolvedFormat(
            format = ProbedFormat.RGBA8,
            encoding = TexelEncoding.PRE_SCALED,
            filterable = true,
            because = "R16F failed the render-and-blend probe; pre-scaled RGBA8 keeps deposits linear",
        )
    }

    private fun audioTexture(report: GlProbeReport): ResolvedFormat {
        val r16f = report.probeOf(ProbedFormat.R16F)
        if (r16f.filtersLinearly) {
            return ResolvedFormat(
                format = ProbedFormat.R16F,
                encoding = TexelEncoding.LINEAR,
                filterable = true,
                because = "R16F proved filterable; audio textures are uploaded, so renderability is not required",
            )
        }
        return ResolvedFormat(
            format = ProbedFormat.RGBA8,
            encoding = TexelEncoding.PRE_SCALED,
            filterable = true,
            because = "R16F failed the filter probe despite being core; pre-scaled RGBA8 fallback",
        )
    }

    private fun linearColorTarget(report: GlProbeReport): ResolvedFormat {
        val half = report.probeOf(ProbedFormat.RGBA16F)
        if (half.renderable) {
            return ResolvedFormat(
                format = ProbedFormat.RGBA16F,
                encoding = TexelEncoding.LINEAR,
                filterable = half.filtersLinearly,
                because = "RGBA16F proved renderable; linear-light headroom available",
            )
        }
        return ResolvedFormat(
            format = ProbedFormat.RGBA8,
            encoding = TexelEncoding.LINEAR,
            filterable = true,
            because = "RGBA16F unproven; RGBA8 target with range clamped at 1.0",
        )
    }

    private fun lastResort(why: String): ResolvedFormat =
        ResolvedFormat(
            format = ProbedFormat.RGBA8,
            encoding = TexelEncoding.PRE_SCALED,
            filterable = true,
            because = "$why; RGBA8 is the core-mandated floor",
        )
}
