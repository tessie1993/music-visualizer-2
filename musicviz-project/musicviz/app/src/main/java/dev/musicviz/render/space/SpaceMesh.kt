package dev.musicviz.render.space

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The static geometry both families rasterise, as plain arrays.
 *
 * Two rules hold across every builder here, and together they are why the mesh
 * styles cost nothing per frame:
 *
 *  1. **The vertex buffer holds only the parameter-space coordinate** - `(u,v)`
 *     on a sheet, `(r, turns)` on a disc, a unit direction on a sphere. Every
 *     displacement, every height, every mode sum happens in the vertex shader
 *     from a field the style already has on the GPU. So the buffers are
 *     uploaded once in `init()` and never touched again: there is no
 *     per-frame geometry traffic anywhere in either family, and a 192x192
 *     sheet costs one `glDrawElements`.
 *  2. **Pure Kotlin, no GL.** These are arrays; uploading them is the caller's
 *     job. That split is the same one `HyperspaceMath` has against
 *     `HyperspaceScene`, and it is what lets the index topology be tested
 *     without a device.
 *
 * ### Indices are 16-bit, deliberately
 *
 * `GL_UNSIGNED_SHORT` halves the index bandwidth of the largest mesh here and
 * every builder stays inside [MAX_VERTICES] by clamping its own inputs. Note
 * that Kotlin's `Short` is signed: an index above 32767 is stored NEGATIVE and
 * is correct only because `glDrawElements` reads it back unsigned. Anything
 * that inspects [Mesh.indices] must mask with `0xFFFF`.
 */
internal object SpaceMesh {
    /** The 16-bit index ceiling. Every builder clamps rather than overflows. */
    const val MAX_VERTICES: Int = 65536

    /** Vertices per side of the largest square grid that still fits. */
    const val MAX_SIDE: Int = 256

    /** Turns per radian, for a shader reading [polar]'s second component. */
    const val TURNS_TO_RADIANS: Float = (2.0 * PI).toFloat()

    /** Past this the icosphere's vertex count leaves the 16-bit index range. */
    private const val MAX_SUBDIVISIONS: Int = 6

    enum class Primitive {
        TRIANGLES,
        TRIANGLE_STRIP,
    }

    /**
     * [vertices] is [floatsPerVertex] floats per vertex, tightly packed; the
     * meaning of those floats is stated by the builder that produced them.
     */
    class Mesh(
        val vertices: FloatArray,
        val indices: ShortArray,
        val floatsPerVertex: Int,
        val primitive: Primitive,
    ) {
        val vertexCount: Int get() = vertices.size / floatsPerVertex
        val indexCount: Int get() = indices.size
    }

    /**
     * A square sheet in `(u,v)`, both in 0..1, as one triangle strip.
     *
     * [rowWarp] is an exponent on `v` before it is stored: at 1 the rows are
     * evenly spaced, at the 1.7 the wave styles use they crowd towards `v = 0`,
     * which is where a driven edge or a rim puts the detail. It is applied here
     * rather than in the shader because it is a property of the mesh - the
     * shader would have to undo it to know where it actually is.
     */
    fun grid(
        side: Int,
        rowWarp: Float = 1f,
    ): Mesh = gridOf(side, side, rowWarp)

    /**
     * A ribbon or tube: [segments] quads along its length, [sides] around it,
     * as `(around, along)` in 0..1.
     *
     * It is a grid, because that is exactly what it is - the difference is
     * only that `u` wraps, so the seam column is DUPLICATED (there are
     * `sides + 1` columns) rather than indexed back to column 0. Sharing the
     * seam vertices would force the shader to read a wrapped `u` of both 0 and
     * 1 from one vertex, which no interpolation can do.
     */
    fun ribbon(
        segments: Int,
        sides: Int,
    ): Mesh = gridOf(sides.coerceAtLeast(2) + 1, segments.coerceAtLeast(1) + 1, 1f)

    /**
     * A disc as `(r, turns)`, r in 0..1 and turns in 0..1, as triangles.
     *
     * Two things a plain polar grid gets wrong, both fixed here:
     *
     *  - **Ring radii are `sqrt(i / rings)`, not `i / rings`.** Ring area grows
     *    as `r dr`, so evenly spaced radii put far too many vertices near the
     *    centre and too few at the rim. The square root is the equal-area
     *    distribution: every ring band carries the same area, so vertex density
     *    is uniform per unit area, which is what a wave field needs.
     *  - **One shared centre vertex, not [sectors] of them.** A 128x256 polar
     *    grid has 256 slivers meeting at r = 0. Here the cap is a fan of
     *    [sectors] triangles onto a single vertex, and because the first real
     *    ring already sits at `sqrt(1/rings)` those triangles are not slivers.
     *
     * The plan this was built from called for a "cap quad" instead. A quad
     * cannot close a ring of N sectors without a T-junction - a crack that
     * opens the moment the vertex shader displaces the surface - so it is a fan
     * onto one vertex, which has neither problem.
     */
    fun polar(
        rings: Int,
        sectors: Int,
    ): Mesh {
        val s = sectors.coerceIn(3, 512)
        val r = rings.coerceIn(1, (MAX_VERTICES - 1) / s)
        val verts = FloatArray((1 + r * s) * 2)
        // The centre. Its angle is arbitrary and unused; the fan below reads
        // only its radius.
        verts[0] = 0f
        verts[1] = 0f
        var p = 2
        for (ring in 1..r) {
            val radius = sqrt(ring.toFloat() / r)
            for (sector in 0 until s) {
                verts[p++] = radius
                verts[p++] = sector.toFloat() / s
            }
        }
        val idx = ShortArray(3 * s + 6 * s * (r - 1))
        var n = 0
        for (sector in 0 until s) {
            val a = 1 + sector
            val b = 1 + (sector + 1) % s
            idx[n++] = 0
            idx[n++] = a.toShort()
            idx[n++] = b.toShort()
        }
        for (ring in 1 until r) {
            val inner = 1 + (ring - 1) * s
            val outer = 1 + ring * s
            for (sector in 0 until s) {
                val next = (sector + 1) % s
                val i0 = inner + sector
                val i1 = inner + next
                val o0 = outer + sector
                val o1 = outer + next
                idx[n++] = i0.toShort()
                idx[n++] = o0.toShort()
                idx[n++] = o1.toShort()
                idx[n++] = i0.toShort()
                idx[n++] = o1.toShort()
                idx[n++] = i1.toShort()
            }
        }
        return Mesh(verts, idx, 2, Primitive.TRIANGLES)
    }

    /**
     * A subdivided icosahedron, as unit direction vectors (3 floats).
     *
     * An icosphere rather than a lat-long sphere because a lat-long sphere has
     * the same pole problem [polar] fixes, in two places at once, and because
     * every triangle here is within a few percent of the same area - which is
     * what a displaced surface needs if the displacement is not to be finer
     * than the mesh in one place and coarser in another.
     *
     * [subdivisions] is clamped to 6 (40,962 vertices); 7 would be 163,842 and
     * past the 16-bit index limit.
     */
    fun icosphere(subdivisions: Int): Mesh {
        val depth = subdivisions.coerceIn(0, MAX_SUBDIVISIONS)
        // Golden-ratio rectangles: the standard construction, normalised so
        // every vertex is already a unit direction.
        val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        val base =
            floatArrayOf(
                -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t, 0f,
                0f, -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t,
                t, 0f, -1f, t, 0f, 1f, -t, 0f, -1f, -t, 0f, 1f,
            )
        val verts = ArrayList<Float>(base.size)
        for (i in base.indices step 3) {
            val inv = 1f / sqrt(base[i] * base[i] + base[i + 1] * base[i + 1] + base[i + 2] * base[i + 2])
            verts.add(base[i] * inv)
            verts.add(base[i + 1] * inv)
            verts.add(base[i + 2] * inv)
        }
        var faces =
            intArrayOf(
                0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
                1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
                3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
                4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
            )
        // Midpoints are shared between the two faces that meet on an edge, so
        // they are cached by edge key: without this the vertex count is three
        // per face per level and the surface is a shell of disconnected
        // triangles that cracks apart the moment it is displaced.
        val midpoints = HashMap<Long, Int>()
        repeat(depth) {
            val next = IntArray(faces.size * 4)
            var n = 0
            for (f in faces.indices step 3) {
                val a = faces[f]
                val b = faces[f + 1]
                val c = faces[f + 2]
                val ab = midpoint(a, b, verts, midpoints)
                val bc = midpoint(b, c, verts, midpoints)
                val ca = midpoint(c, a, verts, midpoints)
                next[n++] = a
                next[n++] = ab
                next[n++] = ca
                next[n++] = b
                next[n++] = bc
                next[n++] = ab
                next[n++] = c
                next[n++] = ca
                next[n++] = bc
                next[n++] = ab
                next[n++] = bc
                next[n++] = ca
            }
            faces = next
            midpoints.clear()
        }
        val out = verts.toFloatArray()
        val idx = ShortArray(faces.size)
        for (i in faces.indices) idx[i] = faces[i].toShort()
        return Mesh(out, idx, 3, Primitive.TRIANGLES)
    }

    /**
     * [count] unit quads, as `(cornerX, cornerY, quadIndex)` with the corners
     * at +/-0.5 - for the styles that draw a set of axis-aligned planes and
     * place each one from its index in the vertex shader.
     *
     * Separate vertices per quad rather than one shared unit quad drawn
     * instanced: the consumers sort their planes back-to-front on the CPU every
     * frame, and a sort is a permutation of the INDEX buffer, which is free.
     * Instancing would make the draw order the instance order, which is the
     * one thing a back-to-front blend cannot have.
     */
    fun quadSet(count: Int): Mesh {
        val n = count.coerceIn(1, MAX_VERTICES / 4)
        val verts = FloatArray(n * 4 * 3)
        val idx = ShortArray(n * 6)
        var p = 0
        var q = 0
        for (i in 0 until n) {
            val base = i * 4
            val id = i.toFloat()
            for (corner in 0 until 4) {
                verts[p++] = if (corner and 1 == 0) -0.5f else 0.5f
                verts[p++] = if (corner and 2 == 0) -0.5f else 0.5f
                verts[p++] = id
            }
            idx[q++] = base.toShort()
            idx[q++] = (base + 1).toShort()
            idx[q++] = (base + 2).toShort()
            idx[q++] = (base + 2).toShort()
            idx[q++] = (base + 1).toShort()
            idx[q++] = (base + 3).toShort()
        }
        return Mesh(verts, idx, 3, Primitive.TRIANGLES)
    }

    /**
     * [cols] x [rows] vertices in `(u,v)`, one triangle strip, rows joined by
     * degenerate triangles.
     *
     * The joins are two repeated indices - the strip's last vertex, then the
     * next row's first - which draws two zero-area triangles the rasteriser
     * discards. Two rather than one because the count must stay EVEN: a
     * triangle strip alternates its winding, so an odd number of filler
     * indices would leave every row after the first facing backwards.
     */
    private fun gridOf(
        cols: Int,
        rows: Int,
        rowWarp: Float,
    ): Mesh {
        val c = cols.coerceIn(2, MAX_SIDE)
        val r = rows.coerceIn(2, MAX_VERTICES / c)
        val verts = FloatArray(c * r * 2)
        var p = 0
        for (row in 0 until r) {
            val v = row.toFloat() / (r - 1)
            val warped = if (rowWarp == 1f) v else v.pow(rowWarp)
            for (col in 0 until c) {
                verts[p++] = col.toFloat() / (c - 1)
                verts[p++] = warped
            }
        }
        val idx = ShortArray((r - 1) * 2 * c + (r - 2).coerceAtLeast(0) * 2)
        var n = 0
        for (row in 0 until r - 1) {
            if (row > 0) {
                idx[n] = idx[n - 1]
                n++
                idx[n] = ((row + 1) * c).toShort()
                n++
            }
            // The far row first, so the first triangle of every strip is
            // counter-clockwise in parameter space and GL's default front face
            // means what it says.
            for (col in 0 until c) {
                idx[n++] = ((row + 1) * c + col).toShort()
                idx[n++] = (row * c + col).toShort()
            }
        }
        return Mesh(verts, idx, 2, Primitive.TRIANGLE_STRIP)
    }

    /** The shared midpoint of edge (a,b), created and normalised once. */
    private fun midpoint(
        a: Int,
        b: Int,
        verts: ArrayList<Float>,
        cache: HashMap<Long, Int>,
    ): Int {
        val key = if (a < b) a.toLong() * MAX_VERTICES + b else b.toLong() * MAX_VERTICES + a
        cache[key]?.let { return it }
        val ax = verts[a * 3]
        val ay = verts[a * 3 + 1]
        val az = verts[a * 3 + 2]
        val bx = verts[b * 3]
        val by = verts[b * 3 + 1]
        val bz = verts[b * 3 + 2]
        var mx = 0.5f * (ax + bx)
        var my = 0.5f * (ay + by)
        var mz = 0.5f * (az + bz)
        val inv = 1f / sqrt(mx * mx + my * my + mz * mz)
        mx *= inv
        my *= inv
        mz *= inv
        val index = verts.size / 3
        verts.add(mx)
        verts.add(my)
        verts.add(mz)
        cache[key] = index
        return index
    }
}
