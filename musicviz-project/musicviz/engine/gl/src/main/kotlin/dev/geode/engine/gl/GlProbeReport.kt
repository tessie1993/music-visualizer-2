package dev.geode.engine.gl

/**
 * The texture formats the engine makes decisions about, by their sized
 * internal-format names.
 *
 * The set is what MASTER_PLAN §6.3 branches between — `RGBA32UI` packed state,
 * verified FP16, `RGBA8` fallbacks — plus `RG16F` for two-channel velocity
 * fields and `R32F` because its two optional behaviours (rendering, linear
 * filtering) are classic driver lies worth recording.
 */
enum class ProbedFormat { RGBA8, R16F, RG16F, RGBA16F, R32F, RGBA32UI }

/**
 * What one format was *measured* to do on one context. Every field is proven
 * behaviour, never an advertisement: `attachable` means the FBO reported
 * complete, `rendersExactly` means a draw and readback returned the expected
 * texels, `blendsAdditively` means GL_ONE/GL_ONE accumulation read back
 * correctly, `filtersLinearly` means a magnified sample between two texels
 * interpolated. A format a driver advertises but fails to prove is treated as
 * unsupported — that asymmetry is the point of probing.
 */
data class FormatProbe(
    val attachable: Boolean,
    val rendersExactly: Boolean,
    val blendsAdditively: Boolean,
    val filtersLinearly: Boolean,
) {
    val renderable: Boolean get() = attachable && rendersExactly
}

/**
 * The raw facts one probe pass reads off a live GL context: identity strings,
 * limits, the extension set, and per-format behavioural outcomes.
 *
 * This type is deliberately judgment-free. Judgments — "is compute usable",
 * "which accumulation format" — live in [GlCapabilities] and [FormatPolicy],
 * derived fresh on every load, so a better rule next month applies to facts
 * cached today. A format absent from [formats] was not probed and counts as
 * unproven.
 *
 * Filled on-device by the V2-4-01b prober; every field maps to a
 * `glGetString`/`glGetIntegerv` read or an attach-render-readback loop.
 */
data class GlProbeReport(
    val vendor: String,
    val renderer: String,
    val versionString: String,
    val extensions: Set<String>,
    val maxTextureSize: Int,
    val maxColorAttachments: Int,
    val maxVertexTextureImageUnits: Int,
    /** A vertex shader actually fetched a texel and the fetch reached the screen. */
    val vertexTextureFetchProven: Boolean,
    val maxComputeWorkGroupInvocations: Int,
    val maxComputeStorageBlocks: Int,
    val maxFragmentStorageBlocks: Int,
    val maxComputeImageUniforms: Int,
    val programBinaryFormats: Int,
    /** GL_EXT_disjoint_timer_query is in the extension set. */
    val timerQueryPresent: Boolean,
    /** Queries returned nonzero, monotonic timings with the disjoint flag honoured. */
    val timerQueryProven: Boolean,
    val formats: Map<ProbedFormat, FormatProbe>,
) {
    fun probeOf(format: ProbedFormat): FormatProbe = formats[format] ?: UNPROVEN

    private companion object {
        val UNPROVEN =
            FormatProbe(
                attachable = false,
                rendersExactly = false,
                blendsAdditively = false,
                filtersLinearly = false,
            )
    }
}
