package dev.geode.engine.gl

/**
 * Report builders for the two device classes the policy branches between.
 *
 * The numbers are the shapes real hardware reports, not spec minima: a current
 * flagship advertises everything and proves it, a strict-baseline device parses
 * as ES 3.0 and fails every half-float renderability probe. What no fixture
 * may do is invent a probe outcome the prober cannot measure — every field here
 * is fed by `glGetString`/`glGetIntegerv` or by an attach-render-readback loop.
 */
object ProbeFixtures {
    val PROVEN =
        FormatProbe(
            attachable = true,
            rendersExactly = true,
            blendsAdditively = true,
            filtersLinearly = true,
        )

    val UNATTACHABLE =
        FormatProbe(
            attachable = false,
            rendersExactly = false,
            blendsAdditively = false,
            filtersLinearly = true,
        )

    fun report(
        vendor: String = "Qualcomm",
        renderer: String = "Adreno (TM) 740",
        versionString: String = "OpenGL ES 3.2 V@0502.0 (GIT@09fef2b, Ie1c1d1a708)",
        extensions: Set<String> = setOf("GL_EXT_color_buffer_float", "GL_EXT_disjoint_timer_query"),
        maxTextureSize: Int = 16384,
        maxColorAttachments: Int = 8,
        maxVertexTextureImageUnits: Int = 16,
        vertexTextureFetchProven: Boolean = true,
        maxComputeWorkGroupInvocations: Int = 1024,
        maxComputeStorageBlocks: Int = 24,
        maxFragmentStorageBlocks: Int = 24,
        maxComputeImageUniforms: Int = 8,
        programBinaryFormats: Int = 1,
        timerQueryPresent: Boolean = true,
        timerQueryProven: Boolean = true,
        formats: Map<ProbedFormat, FormatProbe> = ProbedFormat.entries.associateWith { PROVEN },
    ) = GlProbeReport(
        vendor = vendor,
        renderer = renderer,
        versionString = versionString,
        extensions = extensions,
        maxTextureSize = maxTextureSize,
        maxColorAttachments = maxColorAttachments,
        maxVertexTextureImageUnits = maxVertexTextureImageUnits,
        vertexTextureFetchProven = vertexTextureFetchProven,
        maxComputeWorkGroupInvocations = maxComputeWorkGroupInvocations,
        maxComputeStorageBlocks = maxComputeStorageBlocks,
        maxFragmentStorageBlocks = maxFragmentStorageBlocks,
        maxComputeImageUniforms = maxComputeImageUniforms,
        programBinaryFormats = programBinaryFormats,
        timerQueryPresent = timerQueryPresent,
        timerQueryProven = timerQueryProven,
        formats = formats,
    )

    /**
     * A strict GLES 3.0 device: no compute, no half-float render attachments,
     * no timer queries. R16F still filters — half-float *filtering* is core in
     * ES 3.0; it is rendering to one that is the optional extension.
     */
    fun baseline() =
        report(
            vendor = "ARM",
            renderer = "Mali-G52",
            versionString = "OpenGL ES 3.0 v1.r26p0-01eac0.1a8c6a3b2f",
            extensions = emptySet(),
            maxTextureSize = 4096,
            maxColorAttachments = 4,
            maxVertexTextureImageUnits = 16,
            vertexTextureFetchProven = true,
            maxComputeWorkGroupInvocations = 0,
            maxComputeStorageBlocks = 0,
            maxFragmentStorageBlocks = 0,
            maxComputeImageUniforms = 0,
            programBinaryFormats = 0,
            timerQueryPresent = false,
            timerQueryProven = false,
            formats =
                mapOf(
                    ProbedFormat.RGBA8 to PROVEN,
                    ProbedFormat.RGBA32UI to
                        FormatProbe(
                            attachable = true,
                            rendersExactly = true,
                            blendsAdditively = false,
                            filtersLinearly = false,
                        ),
                    ProbedFormat.R16F to UNATTACHABLE,
                    ProbedFormat.RG16F to UNATTACHABLE,
                    ProbedFormat.RGBA16F to UNATTACHABLE,
                    ProbedFormat.R32F to
                        FormatProbe(
                            attachable = false,
                            rendersExactly = false,
                            blendsAdditively = false,
                            filtersLinearly = false,
                        ),
                ),
        )
}
