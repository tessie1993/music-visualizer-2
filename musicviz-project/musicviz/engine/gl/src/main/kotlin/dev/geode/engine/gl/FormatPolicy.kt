package dev.geode.engine.gl

enum class TexelEncoding {
    LINEAR,

    FLOAT_BITS_IN_UINT,

    PRE_SCALED,
}

data class ResolvedFormat(
    val format: ProbedFormat,
    val encoding: TexelEncoding,
    val filterable: Boolean,
    val because: String,
)

data class FormatPlan(
    val simulationState: ResolvedFormat,
    val filterableField: ResolvedFormat,
    val linearAccumulation: ResolvedFormat,
    val audioTexture: ResolvedFormat,
    val linearColorTarget: ResolvedFormat,
)

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
