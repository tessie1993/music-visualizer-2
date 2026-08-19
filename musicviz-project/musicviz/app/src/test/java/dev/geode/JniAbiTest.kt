package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin side of the JNI bridge, checked against the symbols the shipped
 * library actually exports.
 *
 * ## The failure this exists to prevent, because it already happened
 *
 * A JNI symbol is named after the fully-qualified Java class that declares it.
 * `MilkdropEngine.nativeCreate` resolves to
 * `Java_dev_geode_render_scene_MilkdropEngine_nativeCreate`.
 * `libmilkdropjni.so` is a committed prebuilt binary, so renaming the Kotlin
 * package does not rename its exports — it just stops finding them.
 *
 * The rebrand from MusicViz to Geode moved the previous bridge class between
 * packages, and MilkDrop died on the spot. Every entry to the scene threw
 * `UnsatisfiedLinkError` on the GL thread, from `nativeCreate`, before any of
 * the app's own error reporting could run — so the flagship feature was
 * completely dead and nothing said why. It compiled, every unit test passed,
 * lint was clean, and the whole thing shipped.
 *
 * Nothing in a Kotlin toolchain can catch that: `external fun` promises a
 * symbol exists and is only checked when it is called, on a device, with that
 * ABI. So it is checked here instead, by reading the binary.
 *
 * The test is skipped rather than failed when the toolchain has no `nm`, since
 * a missing binutils is not a defect in this repository — CI has it.
 */
class JniAbiTest {
    private val moduleRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("module root not found from ${File("").absolutePath}")

    /** Every shipped copy of the bridge — arm64-v8a for devices, x86_64 for
     *  emulators (the CI instrumented suite loads that one). Each must export
     *  the same symbol set; a drift on either ABI is a hidden MilkDrop there. */
    private val libraries: List<File> =
        File(moduleRoot, "app/src/main/jniLibs")
            .listFiles { f: File -> f.isDirectory }
            .orEmpty()
            .map { File(it, "libmilkdropjni.so") }
            .filter { it.isFile }
            .sortedBy { it.parentFile?.name.orEmpty() }

    private val library = File(moduleRoot, "app/src/main/jniLibs/arm64-v8a/libmilkdropjni.so")

    private val bridgeSource: File =
        File(moduleRoot, "app/src/main/java/dev/geode/render/scene/MilkdropEngine.kt")

    /** `Java_<package with dots as underscores>_<Class>_<method>`. */
    private fun expectedSymbol(
        packageName: String,
        className: String,
        method: String,
    ): String = "Java_${packageName.replace(".", "_")}_${className}_$method"

    private fun exportedSymbols(of: File = library): List<String>? {
        val nm =
            listOf("/usr/bin/nm", "/usr/local/bin/nm", "nm")
                .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) }
                ?: return null
        val process =
            ProcessBuilder(nm, "-D", "--defined-only", of.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
            .lineSequence()
            .mapNotNull { line -> line.trim().split(" ").lastOrNull() }
            .filter { it.startsWith("Java_") }
            .toList()
    }

    private fun declaredPackage(): String =
        bridgeSource
            .readLines()
            .first { it.startsWith("package ") }
            .removePrefix("package ")
            .trim()

    private fun declaredNatives(): List<String> =
        Regex("""external fun (\w+)""")
            .findAll(bridgeSource.readText())
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `the bridge lives where the shipped library expects it`() {
        assertTrue(
            "MilkdropEngine.kt is not where it was left; the .so's symbols are package-specific",
            bridgeSource.isFile,
        )
        assertEquals(
            "moving MilkdropEngine breaks every native call at runtime — rebuild libmilkdropjni.so " +
                "to match (native-libs.yml), or move it back",
            "dev.geode.render.scene",
            declaredPackage(),
        )
    }

    @Test
    fun `every external fun resolves to a symbol each shipped library exports`() {
        org.junit.Assume.assumeTrue("no nm on this toolchain", exportedSymbols() != null)
        assertTrue("the prebuilt arm64 library is missing", library.isFile)
        val natives = declaredNatives()
        assertTrue("MilkdropEngine declares no external functions at all", natives.isNotEmpty())
        for (lib in libraries) {
            val exported = exportedSymbols(lib).orEmpty()
            val missing =
                natives
                    .map { expectedSymbol(declaredPackage(), "MilkdropEngine", it) }
                    .filterNot { it in exported }
            assertEquals(
                "${lib.parentFile?.name}/${lib.name}: these would throw UnsatisfiedLinkError " +
                    "on that ABI, on the GL thread, with no UI to explain it",
                emptyList<String>(),
                missing,
            )
        }
    }

    /**
     * The reverse direction is a weaker signal but a real one: a symbol the
     * library exports and nothing declares is a capability that was built and
     * then lost in a refactor.
     */
    @Test
    fun `nothing the library offers has been silently dropped`() {
        val exported = exportedSymbols()
        org.junit.Assume.assumeTrue("no nm on this toolchain", exported != null)
        val prefix = expectedSymbol(declaredPackage(), "MilkdropEngine", "")
        val declared = declaredNatives().map { expectedSymbol(declaredPackage(), "MilkdropEngine", it) }.toSet()
        val orphaned = exported!!.filter { it.startsWith(prefix) }.filterNot { it in declared }
        assertEquals("native entry points exist that no Kotlin declares", emptyList<String>(), orphaned)
    }

    /**
     * The R8 keep rule must name the real bridge class: a rule naming a class
     * that no longer exists is a silent no-op, and release builds then rename
     * `MilkdropEngine`'s package out from under the shipped `.so` — MilkDrop
     * dead in release, fine in every debug build a test ever sees. (The
     * generic native-methods keep is belt and braces, not a licence for the
     * named rule to rot.)
     */
    @Test
    fun `the R8 keep rule names the bridge class that actually exists`() {
        val rules = File(moduleRoot, "app/proguard-rules.pro").readText()
        assertTrue(
            "proguard-rules.pro must keep ${declaredPackage()}.MilkdropEngine by its real name",
            rules.contains("${declaredPackage()}.MilkdropEngine"),
        )
    }
}
