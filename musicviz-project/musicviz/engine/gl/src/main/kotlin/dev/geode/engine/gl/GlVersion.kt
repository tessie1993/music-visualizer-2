package dev.geode.engine.gl

/**
 * The ES version buried in a `GL_VERSION` string.
 *
 * The string is prose — "OpenGL ES 3.2 V@0502.0 (GIT@…)" on Adreno,
 * "OpenGL ES 3.2 v1.r32p1-…" on Mali, "OpenGL ES 3.1 (4.5.0 NVIDIA …)" in the
 * emulator — and only the leading "OpenGL ES <major>.<minor>" is contractual.
 * A string without that shape parses to null, and null enables nothing:
 * capability derivation treats an unreadable version like a version too low.
 */
data class GlVersion(val major: Int, val minor: Int) : Comparable<GlVersion> {
    override fun compareTo(other: GlVersion): Int = compareValuesBy(this, other, GlVersion::major, GlVersion::minor)

    override fun toString(): String = "$major.$minor"

    companion object {
        private val ES_VERSION = Regex("""^OpenGL ES(?:-C[ML])? (\d+)\.(\d+)""")

        fun parse(versionString: String): GlVersion? =
            ES_VERSION.find(versionString)?.let { match ->
                GlVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }
    }
}
