package dev.geode.engine.gl

/**
 * How far the quality manager may trust GPU timings (§6.7: timer queries
 * where trustworthy, CPU fences otherwise).
 */
enum class TimerQuerySupport { ABSENT, UNTRUSTED, TRUSTED }

/**
 * Judgments derived from one [GlProbeReport].
 *
 * Every field obeys §6.3's rule that a GLES version alone proves nothing: a
 * capability turns on only when the version, the relevant limit *and* any
 * behavioural probe all agree. The limits are checked against the ES 3.1
 * specification floors — a "3.1" driver reporting less than a floor is not a
 * slower 3.1, it is a context whose version string cannot be trusted for that
 * capability.
 */
data class GlCapabilities(
    val version: GlVersion?,
    val computeShaders: Boolean,
    val storageBuffersInCompute: Boolean,
    val storageBuffersInFragment: Boolean,
    val imageLoadStore: Boolean,
    val vertexTextureFetch: Boolean,
    val timerQueries: TimerQuerySupport,
    val programBinaries: Boolean,
) {
    companion object {
        private val ES_3_1 = GlVersion(3, 1)

        // ES 3.1 specification minima. GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS
        // has a floor of zero, which is why fragment SSBOs stay a separate,
        // genuinely optional capability.
        private const val MIN_COMPUTE_INVOCATIONS = 128
        private const val MIN_COMPUTE_STORAGE_BLOCKS = 4
        private const val MIN_COMPUTE_IMAGE_UNIFORMS = 4

        fun derive(report: GlProbeReport): GlCapabilities {
            val version = GlVersion.parse(report.versionString)
            val es31 = version != null && version >= ES_3_1
            return GlCapabilities(
                version = version,
                computeShaders = es31 && report.maxComputeWorkGroupInvocations >= MIN_COMPUTE_INVOCATIONS,
                storageBuffersInCompute = es31 && report.maxComputeStorageBlocks >= MIN_COMPUTE_STORAGE_BLOCKS,
                storageBuffersInFragment = es31 && report.maxFragmentStorageBlocks > 0,
                imageLoadStore = es31 && report.maxComputeImageUniforms >= MIN_COMPUTE_IMAGE_UNIFORMS,
                vertexTextureFetch = report.maxVertexTextureImageUnits > 0 && report.vertexTextureFetchProven,
                timerQueries =
                    when {
                        !report.timerQueryPresent -> TimerQuerySupport.ABSENT
                        !report.timerQueryProven -> TimerQuerySupport.UNTRUSTED
                        else -> TimerQuerySupport.TRUSTED
                    },
                programBinaries = report.programBinaryFormats > 0,
            )
        }
    }
}
