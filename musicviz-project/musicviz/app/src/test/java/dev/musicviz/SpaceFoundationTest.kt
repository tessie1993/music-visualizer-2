package dev.musicviz

import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.fluid.FluidQuality
import dev.musicviz.render.space.QualityLadder
import dev.musicviz.render.space.ResTarget
import dev.musicviz.render.space.SpaceCamera
import dev.musicviz.render.space.SpaceMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * `render/space` - the foundation both new style families stand on, and the
 * four things about it that are invisible in a code review.
 *
 * 1. **[DepthStage][dev.musicviz.render.space.DepthStage] invalidates before it
 *    unbinds.** A depth attachment that is not invalidated is written out to
 *    main memory every frame - about 10 MB at 1080x2400, 500 MB/s at 50 fps,
 *    on a part with roughly 25 GB/s in total - and nothing reports it. An
 *    invalidate issued after the unbind is worse than none: it names the
 *    attachment of whatever framebuffer is bound instead.
 * 2. **It puts back exactly what it found.** The renderer has its own target
 *    bound and its own passes to run after the scene returns; a leaked depth
 *    test rejects every one of them against a buffer that no longer exists,
 *    with no GL error to trace.
 * 3. **[ResTarget]'s scale derives from `supersampleFactor`.** Get the
 *    direction wrong and the device that throttles renders the most pixels.
 * 4. **[SpaceCamera]'s basis is orthonormal and its clock wraps continuously.**
 *    Every clock in this app wraps at `TIME_WRAP_SEC`; a rig built on
 *    arbitrary rates jump-cuts when it does.
 *
 * The GL halves are source-level, like [SceneFailureTest] and
 * [RendererWiringTest]: the suite has no GL context, and an ordering contract
 * between two `GLES30` calls is a property of the code. The maths halves are
 * pure and simply run.
 */
class SpaceFoundationTest {
    private companion object {
        /** Window sizes from the plan's budget table, with their factors. */
        val DEVICES =
            listOf(
                Triple("flagship 1080x2400", 1080 to 2400, 1.0f),
                Triple("mid-range 1080x1920", 1080 to 1920, 1.25f),
                Triple("budget 720x1520", 720 to 1520, 1.4f),
            )
    }

    private val depthStageSource: String by lazy { repoFile("src/main/java/dev/musicviz/render/space/DepthStage.kt") }
    private val resTargetSource: String by lazy { repoFile("src/main/java/dev/musicviz/render/space/ResTarget.kt") }
    private val cameraSource: String by lazy { repoFile("src/main/java/dev/musicviz/render/space/SpaceCamera.kt") }
    private val rendererSource: String by lazy { repoFile("src/main/java/dev/musicviz/render/VisualizerRenderer.kt") }

    // ---- DepthStage -----------------------------------------------------

    @Test
    fun theDepthPassInvalidatesWhileItsFramebufferIsStillBound() {
        val detach = block(depthStageSource, "fun detach() {")
        val bindOwn = detach.indexOf("glBindFramebuffer(GLES30.GL_FRAMEBUFFER, attachedFbo)")
        val invalidate = detach.indexOf("glInvalidateFramebuffer(")
        val unhook = detach.indexOf("GLES30.GL_DEPTH_ATTACHMENT")
        val restore = detach.indexOf("glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])")
        assertTrue("detach() no longer binds the FBO it attached to", bindOwn >= 0)
        assertTrue("detach() no longer invalidates the depth attachment", invalidate > bindOwn)
        assertTrue("the attachment is dropped before it is invalidated", unhook > invalidate)
        assertTrue("the invalidate happens after the framebuffer is restored", restore > invalidate)
    }

    @Test
    fun theDepthPassRestoresEveryPieceOfStateItTouched() {
        // Each pair is "what attach() reads" against "what detach() writes
        // back". Stated as a table because the failure mode of a missing pair
        // is not a crash - it is the renderer's own passes silently behaving
        // differently for the rest of the frame.
        val pairs =
            listOf(
                "GL_FRAMEBUFFER_BINDING" to "glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])",
                "GL_DEPTH_FUNC" to "glDepthFunc(prevDepthFunc[0])",
                "GL_DEPTH_WRITEMASK" to "glDepthMask(prevDepthWrite[0] != 0)",
                "GL_DEPTH_CLEAR_VALUE" to "glClearDepthf(prevDepthClear[0])",
                "GL_BLEND_SRC_RGB" to "glBlendFuncSeparate(prevBlendFunc[0]",
            )
        val attach = block(depthStageSource, "fun attach(")
        val detach = block(depthStageSource, "fun detach() {")
        for ((query, restore) in pairs) {
            assertTrue("attach() no longer snapshots $query", attach.contains(query))
            assertTrue("detach() no longer restores $query (expected `$restore`)", detach.contains(restore))
        }
        // The two enables are booleans rather than integers, so they restore
        // through a branch instead of a setter.
        assertTrue(
            "the depth test enable is not put back as found",
            detach.contains("if (prevDepthTest) GLES30.glEnable(GLES30.GL_DEPTH_TEST) else GLES30.glDisable(GLES30.GL_DEPTH_TEST)"),
        )
        assertTrue(
            "the blend enable is not put back as found",
            detach.contains("if (prevBlend) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)"),
        )
    }

    @Test
    fun theDepthPassAllocatesNothingPerFrame() {
        // attach/detach run once per frame per style. The snapshot buffers and
        // the invalidate's attachment list are fields; building either at the
        // call site is the allocation a recent commit removed from the fluid
        // scenes, reintroduced.
        for (name in listOf("fun attach(", "fun detach() {")) {
            val body = block(depthStageSource, name)
            for (alloc in listOf("IntArray(", "intArrayOf(", "FloatArray(", "floatArrayOf(")) {
                assertTrue("`$name` allocates a $alloc on the draw path", !body.contains(alloc))
            }
        }
        assertTrue(
            "the invalidate's attachment list must be a field, not a literal",
            depthStageSource.contains("private val depthAttachment = intArrayOf(GLES30.GL_DEPTH_ATTACHMENT)"),
        )
    }

    @Test
    fun aGpuThatRefusesDepthDegradesInsteadOfThrowing() {
        // The house rule, from FluidSim.kt:193-207: every scene is constructed
        // and init'ed inside onSurfaceCreated, so an exception out of any of
        // them takes the process down before the user has picked a style.
        for (token in listOf("throw ", "error(", "require(", "check(")) {
            assertTrue("DepthStage may not $token - it must go unavailable instead", !depthStageSource.contains(token))
        }
        assertTrue("a refused attachment must clear `available`", depthStageSource.contains("available = false"))
        assertTrue("a refused attachment must say why", depthStageSource.contains("onShaderError("))
        assertTrue(
            "the report must be once, not once per frame",
            depthStageSource.contains("if (reported) return"),
        )
    }

    @Test
    fun theDepthBufferIs24Bit() {
        // 16 bits is not enough for the near/far pair SpaceCamera ships: the
        // quantisation shows as stair-stepping on any surface seen at a
        // glancing angle, which is most of a displaced sheet.
        assertTrue(
            "the depth renderbuffer is no longer DEPTH_COMPONENT24",
            depthStageSource.contains("GLES30.GL_DEPTH_COMPONENT24"),
        )
    }

    // ---- ResTarget ------------------------------------------------------

    @Test
    fun theInternalScaleDividesOutTheSupersampleFactor() {
        // The property that matters is not the number, it is that the number
        // means the same thing on every device: a style asking for 0.6 gets
        // 0.6 of the DISPLAY's pixels, whatever the renderer inflated its
        // target by.
        val base = 0.6f
        for ((name, window, expectedFactor) in DEVICES) {
            val (w, h) = window
            val factor = VisualizerRenderer.supersampleFactor(w, h)
            assertEquals("$name supersample factor", expectedFactor, factor, 0f)
            val scale = ResTarget.scaleFor(base, factor)
            assertEquals("$name derived scale", base / factor, scale, 1e-6f)
            assertEquals("$name renders a different fraction of the display", base, scale * factor, 1e-5f)
        }
    }

    @Test
    fun withoutTheDivisionTheDeviceWithTheLeastHeadroomWouldGetTheMostPixels() {
        // The defect this derivation exists for, stated as the comparison that
        // used to come out backwards. A flat 0.6 applied to renderWidth is 0.6
        // of the display on the flagship and 0.84 of it on the budget part -
        // and the mid-range device, which throttles, has the largest frame of
        // the three to begin with (3.24 Mpx against 2.59).
        fun pixels(
            window: Pair<Int, Int>,
            scale: Float,
        ): Float {
            val f = VisualizerRenderer.supersampleFactor(window.first, window.second)
            return window.first * f * scale * window.second * f * scale
        }
        val flagship = DEVICES[0].second
        val budget = DEVICES[2].second
        val flatOnFlagship = pixels(flagship, 0.6f)
        val flatOnBudget = pixels(budget, 0.6f)
        assertTrue(
            "a flat scale no longer gives the budget device more of its own display than the flagship",
            flatOnBudget / (budget.first * budget.second) > flatOnFlagship / (flagship.first * flagship.second),
        )

        fun derived(window: Pair<Int, Int>): Float =
            pixels(window, ResTarget.scaleFor(0.6f, VisualizerRenderer.supersampleFactor(window.first, window.second)))

        val derivedOnFlagship = derived(flagship)
        val derivedOnBudget = derived(budget)
        assertEquals(
            "the derived scale must render the same fraction of each display",
            derivedOnFlagship / (flagship.first * flagship.second),
            derivedOnBudget / (budget.first * budget.second),
            1e-4f,
        )
    }

    @Test
    fun theRendererStillReturnsTheThreeFactorsTheBudgetsWereComputedAgainst() {
        // Every performance number in the plan is written against these three.
        assertEquals(1.0f, VisualizerRenderer.supersampleFactor(1080, 2200), 0f)
        assertEquals(1.25f, VisualizerRenderer.supersampleFactor(1080, 2199), 0f)
        assertEquals(1.25f, VisualizerRenderer.supersampleFactor(900, 1600), 0f)
        assertEquals(1.4f, VisualizerRenderer.supersampleFactor(900, 1599), 0f)
    }

    @Test
    fun theDerivedScaleStaysInsideARenderableBand() {
        assertEquals("a style asking for full size gets full size", 1f, ResTarget.scaleFor(1f, 1f), 0f)
        assertEquals("nothing may render above its own target", 1f, ResTarget.scaleFor(2f, 1f), 0f)
        assertEquals("the floor holds", ResTarget.MIN_SCALE, ResTarget.scaleFor(0.001f, 1.4f), 0f)
        // A zero or negative factor is not reachable through the renderer, but
        // a divide by it would be a NaN in a texture size.
        assertTrue("a degenerate factor must not produce a NaN scale", ResTarget.scaleFor(0.6f, 0f).isFinite())
    }

    @Test
    fun theRendererHandsEveryReducedResolutionSceneTheFactorItApplied() {
        // A scene cannot recover the factor from the size it was given: a
        // 1600px window at 1.25x and a 1400px window at 1.4x both arrive as
        // 2000 px. So the renderer pushes it - and it has to push it BEFORE
        // resize(), which is where a scene sizes its ResTarget.
        val push = rendererSource.indexOf("filterIsInstance<SupersampleAware>()")
        val resize = rendererSource.indexOf("scenes.values.forEach { it.resize(renderWidth, renderHeight) }")
        assertTrue("the renderer no longer hands scenes the supersample factor", push >= 0)
        assertTrue("the factor is pushed after resize(), too late to size anything", push < resize)
        assertTrue(
            "ResTarget must derive its scale rather than hardcode one",
            resTargetSource.contains("baseScale / supersample"),
        )
    }

    // ---- SpaceCamera ----------------------------------------------------

    @Test
    fun theBasisStaysOrthonormalThroughAWholeSweep() {
        val cam = SpaceCamera()
        var t = 0f
        var checked = 0
        while (t < 400f) {
            cam.advance(1f / 50f, 3f, 1080, 2400)
            t += 1f / 50f
            val b = cam.basis
            assertEquals("right is not unit at t=$t", 1f, length(b, 0), 1e-6f)
            assertEquals("up is not unit at t=$t", 1f, length(b, 3), 1e-6f)
            assertEquals("forward is not unit at t=$t", 1f, length(b, 6), 1e-6f)
            assertEquals("right . up at t=$t", 0f, dot(b, 0, 3), 1e-6f)
            assertEquals("right . forward at t=$t", 0f, dot(b, 0, 6), 1e-6f)
            assertEquals("up . forward at t=$t", 0f, dot(b, 3, 6), 1e-6f)
            // right x up = -forward: the camera looks down its own -Z, the
            // convention every projection matrix in GL is written for.
            assertEquals("handedness flipped at t=$t", -b[6], b[1] * b[5] - b[2] * b[4], 1e-6f)
            assertEquals("handedness flipped at t=$t", -b[7], b[2] * b[3] - b[0] * b[5], 1e-6f)
            assertEquals("handedness flipped at t=$t", -b[8], b[0] * b[4] - b[1] * b[3], 1e-6f)
            checked++
        }
        assertTrue("the sweep must actually turn the camera", checked > 10_000)
    }

    @Test
    fun theRigClockWrapsWithoutMovingTheCamera() {
        // The reason the rig is built from integer harmonics of the wrap. Step
        // the clock to just short of TIME_WRAP_SEC, then across it, and
        // compare the movement to an ordinary step taken mid-run: a rig built
        // on arbitrary rates would jump by an entire orbit here.
        val cam = SpaceCamera()
        val dt = 1f / 50f
        cam.advance(VisualizerRenderer.TIME_WRAP_SEC - 4f * dt, 1f, 1080, 2400)
        repeat(3) { cam.advance(dt, 1f, 1080, 2400) }
        val before = cam.position.copyOf()
        val basisBefore = cam.basis.copyOf()
        val clockBefore = cam.clock
        cam.advance(dt, 1f, 1080, 2400)
        assertTrue("the clock did not wrap - the test is not testing the wrap", cam.clock < clockBefore)
        val jump = distance(before, cam.position)
        val basisJump = maxDelta(basisBefore, cam.basis)

        val reference = SpaceCamera()
        reference.advance(0.5f * VisualizerRenderer.TIME_WRAP_SEC, 1f, 1080, 2400)
        val refBefore = reference.position.copyOf()
        val refBasis = reference.basis.copyOf()
        reference.advance(dt, 1f, 1080, 2400)
        val refJump = distance(refBefore, reference.position)
        val refBasisJump = maxDelta(refBasis, reference.basis)

        // "Continuous" here means the step across the wrap is an ordinary
        // step, not that it is zero: the camera is still moving.
        assertEquals("the camera jumped across the clock wrap", refJump, jump, 1e-3f)
        assertEquals("the basis jumped across the clock wrap", refBasisJump, basisJump, 1e-3f)
    }

    @Test
    fun theRigNeverLeavesItsConstraint() {
        // The constraint is a promise about where the eye can be, so it is
        // structural: the sweep is built inside the range rather than clamped
        // into it afterwards.
        val c = SpaceCamera.CameraConstraint(minDistance = 1.5f, maxDistance = 6f, minElevationDeg = -20f, maxElevationDeg = 40f)
        val cam = SpaceCamera().apply { constraint = c }
        var minSeen = Float.MAX_VALUE
        var maxSeen = 0f
        repeat(20_000) { step ->
            cam.distanceBias = kotlin.math.sin(step * 0.013f)
            cam.advance(1f / 50f, 1.7f, 1080, 2400)
            val d = length(cam.position, 0)
            minSeen = minOf(minSeen, d)
            maxSeen = maxOf(maxSeen, d)
            assertTrue(
                "the eye reached $d, outside ${c.minDistance}..${c.maxDistance}",
                d >= c.minDistance - 1e-3f && d <= c.maxDistance + 1e-3f,
            )
            val elevationDeg = Math.toDegrees(kotlin.math.asin((cam.position[1] / d).toDouble())).toFloat()
            assertTrue(
                "elevation reached $elevationDeg deg",
                elevationDeg >= c.minElevationDeg - 1e-2f && elevationDeg <= c.maxElevationDeg + 1e-2f,
            )
        }
        // And the range is actually used - a rig that never leaves the middle
        // would pass every assertion above.
        assertTrue("the dolly never approached its near limit", minSeen < c.minDistance + 0.1f)
        assertTrue("the dolly never approached its far limit", maxSeen > c.maxDistance - 0.1f)
    }

    @Test
    fun aSaturatedDollyBiasParksTheEyeOnTheLimit() {
        val c = SpaceCamera.CameraConstraint(minDistance = 2f, maxDistance = 5f)
        val cam = SpaceCamera().apply { constraint = c }
        cam.distanceBias = 1f
        cam.advance(11.3f, 1f, 1080, 2400)
        assertEquals("bias +1 must pull the eye all the way in", c.minDistance, length(cam.position, 0), 1e-4f)
        cam.distanceBias = -1f
        cam.advance(0.02f, 1f, 1080, 2400)
        assertEquals("bias -1 must push it all the way out", c.maxDistance, length(cam.position, 0), 1e-4f)
        // Out of range is a caller bug, not a licence to leave the range.
        cam.distanceBias = 40f
        cam.advance(0.02f, 1f, 1080, 2400)
        assertEquals("an out-of-range bias must clamp", c.minDistance, length(cam.position, 0), 1e-4f)
    }

    @Test
    fun theMarchersDepthAgreesWithTheRasterisersProjection() {
        // The whole reason there is one camera: a marched style writes
        // gl_FragDepth from a ray parameter and a rasterised style writes it
        // from the projection, and they have to land in the same depth buffer.
        val cam = SpaceCamera()
        cam.advance(3.7f, 1f, 1080, 2400)
        val mvp = cam.viewProj
        for (t in listOf(0.2f, 1f, 2.5f, 7f, 20f, 59f)) {
            val x = cam.position[0] + cam.basis[6] * t
            val y = cam.position[1] + cam.basis[7] * t
            val z = cam.position[2] + cam.basis[8] * t
            val clipZ = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]
            val clipW = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
            val expected = 0.5f * (clipZ / clipW) + 0.5f
            assertEquals("depth at $t units disagrees with the projection", expected, cam.depthFromDistance(t), 1e-4f)
        }
        assertEquals("the near plane must map to 0", 0f, cam.depthFromDistance(cam.constraint.near), 1e-5f)
        assertEquals("the far plane must map to 1", 1f, cam.depthFromDistance(cam.constraint.far), 1e-5f)
    }

    @Test
    fun theInverseViewProjectionIsOne() {
        val cam = SpaceCamera()
        for (step in 1..40) {
            cam.advance(step * 0.37f, 1f, 1440, 2960)
            for (col in 0 until 4) {
                for (row in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 4) sum += cam.viewProj[k * 4 + row] * cam.invViewProj[col * 4 + k]
                    val expected = if (col == row) 1f else 0f
                    assertEquals("(viewProj * invViewProj)[$col][$row] at step $step", expected, sum, 2e-4f)
                }
            }
        }
    }

    @Test
    fun theCameraHandsBackItsOwnArraysEveryFrame() {
        // Stated as a contract, like LfoEngine.tick's: these are uploaded and
        // must not be kept. A camera that allocated four matrices a frame
        // would allocate 200 a second on the draw path.
        val cam = SpaceCamera()
        cam.advance(0.02f, 1f, 1080, 2400)
        val first = listOf(cam.view, cam.proj, cam.viewProj, cam.invViewProj, cam.basis, cam.position)
        cam.advance(0.02f, 1f, 1080, 2400)
        val second = listOf(cam.view, cam.proj, cam.viewProj, cam.invViewProj, cam.basis, cam.position)
        for (i in first.indices) assertSame("output $i is a fresh array each frame", first[i], second[i])
        val advance = block(cameraSource, "fun advance(")
        for (alloc in listOf("FloatArray(", "floatArrayOf(", "arrayOf(")) {
            assertTrue("advance() allocates a $alloc", !advance.contains(alloc))
        }
    }

    @Test
    fun theSubPixelJitterStaysSubPixelAndAveragesOut() {
        val cam = SpaceCamera().apply { jitterPixels = 1f }
        var sumX = 0f
        var sumY = 0f
        val n = 1024
        repeat(n) {
            cam.advance(1f / 50f, 1f, 1080, 2400)
            assertTrue("jitter x left the pixel: ${cam.jitter[0]}", abs(cam.jitter[0]) <= 0.5f)
            assertTrue("jitter y left the pixel: ${cam.jitter[1]}", abs(cam.jitter[1]) <= 0.5f)
            sumX += cam.jitter[0]
            sumY += cam.jitter[1]
        }
        // A low-discrepancy sequence is worth having over a random one exactly
        // here: over one period the mean offset is a hundredth of a pixel, so
        // a short average is not biased in any direction.
        assertTrue("the jitter is biased in x: ${sumX / n}", abs(sumX / n) < 0.01f)
        assertTrue("the jitter is biased in y: ${sumY / n}", abs(sumY / n) < 0.01f)
        val still = SpaceCamera()
        still.advance(1f / 50f, 1f, 1080, 2400)
        assertEquals("jitter must be off by default", 0f, still.jitter[0], 0f)
        assertEquals("jitter must be off by default", 0f, still.jitter[1], 0f)
    }

    // ---- QualityLadder --------------------------------------------------

    @Test
    fun theLadderIsTheSameControlAsTheFluidFamilys() {
        assertEquals("tier counts have diverged", FluidQuality.TIERS.size, QualityLadder.TIERS.size)
        assertEquals(
            "the labels have diverged - one Quality setting, two meanings",
            FluidQuality.TIERS.map { it.label },
            QualityLadder.TIERS.map { it.label },
        )
        for (user in -2..6) {
            for (down in 0..5) {
                assertEquals(
                    "the latch law has diverged at user=$user down=$down",
                    FluidQuality.effectiveIndex(user, down),
                    QualityLadder.effectiveIndex(user, down),
                )
            }
        }
    }

    @Test
    fun theLadderOnlyEverGetsCheaper() {
        for (i in 1 until QualityLadder.TIERS.size) {
            val hi = QualityLadder.TIERS[i - 1]
            val lo = QualityLadder.TIERS[i]
            assertTrue("${lo.label} has a larger mesh than ${hi.label}", lo.meshSide <= hi.meshSide)
            assertTrue("${lo.label} marches further than ${hi.label}", lo.marchSteps <= hi.marchSteps)
            assertTrue("${lo.label} has more grains than ${hi.label}", lo.grainCount <= hi.grainCount)
            assertTrue("${lo.label} has more slices than ${hi.label}", lo.atlasSlices <= hi.atlasSlices)
            assertTrue("${lo.label} renders larger than ${hi.label}", lo.resScale <= hi.resScale)
        }
        for (t in QualityLadder.TIERS) {
            // Over-relaxation needs the miss-detection of enhanced sphere
            // tracing, which no style here ships; above 1 the march steps
            // through thin geometry instead of onto it.
            assertTrue("${t.label} over-relaxes the march at ${t.marchRelaxation}", t.marchRelaxation <= 1f)
            assertTrue("${t.label} asks for a mesh SpaceMesh cannot index", t.meshSide <= SpaceMesh.MAX_SIDE)
        }
    }

    @Test
    fun theTierCapsTheScaleAndTheSupersampleCorrectionStillApplies() {
        val medium = QualityLadder.TIERS.indexOfFirst { it.label == "Medium" }
        // A style asking for more than the tier allows is capped...
        assertEquals(
            "Medium no longer caps at its own resScale",
            ResTarget.scaleFor(QualityLadder.tier(medium).resScale, 1.25f),
            QualityLadder.resTargetScale(0.95f, medium, 1.25f),
            0f,
        )
        // ...and one asking for less keeps what it asked for, at every tier,
        // because a volumetric at 0.2 chose that as its design.
        for (i in QualityLadder.TIERS.indices) {
            assertEquals(
                "tier $i moved a style that was already below the cap",
                ResTarget.scaleFor(0.2f, 1f),
                QualityLadder.resTargetScale(0.2f, i, 1f),
                0f,
            )
        }
    }

    // ---- SpaceMesh ------------------------------------------------------

    @Test
    fun everyMeshIndexesInsideItself() {
        val meshes =
            mapOf(
                "grid" to SpaceMesh.grid(192, 1.7f),
                "grid at the clamp" to SpaceMesh.grid(4000),
                "ribbon" to SpaceMesh.ribbon(120, 12),
                "polar" to SpaceMesh.polar(96, 256),
                "icosphere" to SpaceMesh.icosphere(4),
                "icosphere past the clamp" to SpaceMesh.icosphere(9),
                "quads" to SpaceMesh.quadSet(42),
                "quads past the clamp" to SpaceMesh.quadSet(999_999),
            )
        for ((name, mesh) in meshes) {
            assertTrue("$name has no vertices", mesh.vertexCount > 0)
            assertTrue("$name exceeds the 16-bit index range", mesh.vertexCount <= SpaceMesh.MAX_VERTICES)
            assertEquals("$name has a ragged vertex array", 0, mesh.vertices.size % mesh.floatsPerVertex)
            for (i in mesh.indices.indices) {
                // Unsigned: Kotlin's Short is signed and glDrawElements is not.
                val v = mesh.indices[i].toInt() and 0xFFFF
                assertTrue("$name index $i is $v, past its ${mesh.vertexCount} vertices", v < mesh.vertexCount)
            }
            for (f in mesh.vertices) assertTrue("$name has a non-finite vertex", f.isFinite())
        }
    }

    @Test
    fun theGridStripJoinsRowsWithoutFlippingThem() {
        val side = 16
        val mesh = SpaceMesh.grid(side)
        assertEquals(SpaceMesh.Primitive.TRIANGLE_STRIP, mesh.primitive)
        assertEquals("vertex count", side * side, mesh.vertexCount)
        // Two filler indices per row join, and two rather than one because a
        // strip alternates winding: an odd number would leave every row after
        // the first facing backwards.
        assertEquals("index count", (side - 1) * 2 * side + (side - 2) * 2, mesh.indexCount)
        var degenerate = 0
        for (i in 0..mesh.indexCount - 3) {
            val a = mesh.indices[i].toInt() and 0xFFFF
            val b = mesh.indices[i + 1].toInt() and 0xFFFF
            val c = mesh.indices[i + 2].toInt() and 0xFFFF
            if (a == b || b == c || a == c) degenerate++
        }
        // Exactly four zero-area triangles per join - the two filler indices
        // sit in four overlapping windows - and no others anywhere.
        assertEquals("the strip's degenerate triangles are not where the joins are", 4 * (side - 2), degenerate)
    }

    @Test
    fun theRowWarpCrowdsTheRowsWithoutReorderingThem() {
        val side = 33
        val flat = SpaceMesh.grid(side, 1f)
        val warped = SpaceMesh.grid(side, 1.7f)
        var previous = -1f
        for (row in 0 until side) {
            val v = warped.vertices[(row * side) * 2 + 1]
            assertTrue("row $row went backwards", v > previous)
            previous = v
            assertTrue("row $row left 0..1", v in 0f..1f)
            // The warp only ever pulls rows towards v = 0 - towards the driven
            // edge, where the detail is.
            assertTrue("row $row moved the wrong way", v <= flat.vertices[(row * side) * 2 + 1] + 1e-6f)
        }
        assertEquals("the ends must be pinned", 0f, warped.vertices[1], 0f)
        assertEquals("the ends must be pinned", 1f, warped.vertices[(side * side - 1) * 2 + 1], 1e-6f)
    }

    @Test
    fun thePolarDiscIsEqualAreaAndHasOneCentre() {
        val rings = 64
        val sectors = 128
        val mesh = SpaceMesh.polar(rings, sectors)
        assertEquals("one shared centre vertex plus the rings", 1 + rings * sectors, mesh.vertexCount)
        // r_i = sqrt(i/rings), so r^2 is linear in i: every ring band carries
        // the same area and the vertex density is uniform per unit area. A
        // plain i/rings would put far too many vertices at the centre.
        for (ring in 1..rings) {
            val r = mesh.vertices[(1 + (ring - 1) * sectors) * 2]
            assertEquals("ring $ring is not equal-area", ring.toFloat() / rings, r * r, 1e-5f)
        }
        var centreUses = 0
        for (i in mesh.indices.indices) if ((mesh.indices[i].toInt() and 0xFFFF) == 0) centreUses++
        assertEquals("the cap is not a fan onto one vertex", sectors, centreUses)
        // The cap fan is not made of slivers: the first ring is a real
        // distance out, which is the other half of the fix.
        assertTrue("the first ring collapsed onto the centre", mesh.vertices[2] > 0.1f)
    }

    @Test
    fun theIcosphereSubdividesOntoTheSphereAndSharesItsMidpoints() {
        // 12, 42, 162, 642, 2562... - each level adds one vertex per EDGE, and
        // that count is only right if the two faces meeting on an edge share
        // the midpoint they create. Unshared, the surface is a shell of loose
        // triangles that cracks open the moment a vertex shader displaces it.
        val expected = listOf(12, 42, 162, 642, 2562, 10242, 40962)
        for (level in expected.indices) {
            val mesh = SpaceMesh.icosphere(level)
            assertEquals("level $level vertex count", expected[level], mesh.vertexCount)
            assertEquals("level $level face count", 20 * (1 shl (2 * level)) * 3, mesh.indexCount)
            for (v in 0 until mesh.vertexCount) {
                val x = mesh.vertices[v * 3]
                val y = mesh.vertices[v * 3 + 1]
                val z = mesh.vertices[v * 3 + 2]
                assertEquals("level $level vertex $v is not on the unit sphere", 1f, sqrt(x * x + y * y + z * z), 1e-6f)
            }
        }
        assertEquals("subdivision must clamp at the index limit", expected.last(), SpaceMesh.icosphere(7).vertexCount)
    }

    // ---- helpers --------------------------------------------------------

    private fun length(
        v: FloatArray,
        at: Int,
    ): Float = sqrt(v[at] * v[at] + v[at + 1] * v[at + 1] + v[at + 2] * v[at + 2])

    private fun dot(
        v: FloatArray,
        a: Int,
        b: Int,
    ): Float = v[a] * v[b] + v[a + 1] * v[b + 1] + v[a + 2] * v[b + 2]

    private fun distance(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var sum = 0f
        for (i in a.indices) sum += (a[i] - b[i]) * (a[i] - b[i])
        return sqrt(sum)
    }

    private fun maxDelta(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var m = 0f
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    /** The braced body that follows [header], header included. */
    private fun block(
        source: String,
        header: String,
    ): String {
        val start = source.indexOf(header)
        if (start < 0) {
            fail("`$header` is gone")
            error("unreachable")
        }
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        while (i < source.length) {
            if (source[i] == '{') depth++
            if (source[i] == '}') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        return source.substring(open, minOf(i + 1, source.length))
    }

    /** Resolves a file under `app/`, whichever directory tests run from. */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
