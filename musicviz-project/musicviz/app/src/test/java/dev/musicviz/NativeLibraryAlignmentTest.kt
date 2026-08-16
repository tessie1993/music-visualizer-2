package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 15 introduced devices with 16 KB memory pages, and an app targeting
 * SDK 35+ whose shared libraries are laid out for 4 KB pages will not load on
 * them. `targetSdk` here is 36.
 *
 * The repository already knows this - `.github/workflows/native-libs.yml` is
 * called "Rebuild native libs (16 KB aligned)" and verifies alignment on what
 * it produces. What nothing checked is the binaries actually in the tree, and
 * that is the gap this closes: a workflow that verifies its own output says
 * nothing about whether its output was ever committed.
 *
 * The alignment is read out of the ELF program headers rather than trusted
 * from a build flag, because the flag is on the machine that built it and the
 * header is on the artifact that ships.
 */
class NativeLibraryAlignmentTest {
    /** Google's requirement for apps on 16 KB-page devices. */
    private val required = 16384L

    /**
     * Libraries known to be 4 KB aligned at the time of writing, with no
     * rebuild yet run.
     *
     * This set must shrink to empty, and a passing build is not evidence that
     * it has - it is evidence that nothing NEW broke. Rebuilding is the
     * `native-libs.yml` workflow's job: NDK r28 plus
     * `-Wl,-z,max-page-size=16384`, which cannot be done from a unit test.
     * When a rebuild lands, this set empties and the assertion below starts
     * proving the real thing.
     */
    private val knownUnaligned = setOf("libprojectM-4.so", "libprojectmjni.so")

    private val jniLibs = File(ParamSurface.moduleRoot, "app/src/main/jniLibs")

    private fun shippedLibraries(): List<File> =
        jniLibs
            .walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .sortedBy { it.name }
            .toList()

    /**
     * The largest `p_align` over the ELF64 `PT_LOAD` segments - the page size
     * the loader must be able to give this library.
     */
    private fun loadAlignment(so: File): Long =
        RandomAccessFile(so, "r").use { file ->
            val header = ByteArray(0x40).also { file.readFully(it) }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
                "${so.name} is not an ELF file"
            }
            require(header[4].toInt() == 2) { "${so.name} is not ELF64" }
            val phoff = buf.getLong(0x20)
            val phentsize = buf.getShort(0x36).toInt()
            val phnum = buf.getShort(0x38).toInt()
            (0 until phnum).maxOf { i ->
                val entry = ByteArray(phentsize)
                file.seek(phoff + i.toLong() * phentsize)
                file.readFully(entry)
                val ph = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
                if (ph.getInt(0) == PT_LOAD) ph.getLong(0x30) else 0L
            }
        }

    @Test
    fun `the shipped libraries are readable ELF64 objects`() {
        val libraries = shippedLibraries()
        assertTrue("no native libraries found under $jniLibs", libraries.isNotEmpty())
        libraries.forEach { assertTrue("${it.name} reports no LOAD segment", loadAlignment(it) > 0) }
    }

    @Test
    fun `the alignment reader agrees with a known-good header`() {
        // Positive control. Without it, a reader that returned 4096 for
        // everything would pass the assertion below for the wrong reason and
        // keep passing after a real rebuild fixed the libraries.
        val source = shippedLibraries().first()
        val patched = File.createTempFile("aligned", ".so").apply { deleteOnExit() }
        source.copyTo(patched, overwrite = true)
        RandomAccessFile(patched, "rw").use { file ->
            val header = ByteArray(0x40).also { file.readFully(it) }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val phoff = buf.getLong(0x20)
            val phentsize = buf.getShort(0x36).toInt()
            val phnum = buf.getShort(0x38).toInt()
            for (i in 0 until phnum) {
                val at = phoff + i.toLong() * phentsize
                file.seek(at)
                val entry = ByteArray(phentsize).also { file.readFully(it) }
                if (ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt(0) != PT_LOAD) continue
                file.seek(at + 0x30)
                file.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(required).array())
            }
        }
        assertEquals("the reader must see a 16 KB header as 16 KB", required, loadAlignment(patched))
    }

    @Test
    fun `no library is misaligned beyond the ones already known to be`() {
        val misaligned =
            shippedLibraries()
                .filter { loadAlignment(it) < required }
                .map { it.name }
                .toSet()
        assertEquals(
            "a native library that is not 16 KB aligned will not load on an Android 15+ " +
                "16 KB-page device, and this app targets SDK 36. Rebuild through " +
                ".github/workflows/native-libs.yml and update knownUnaligned.",
            knownUnaligned,
            misaligned,
        )
    }

    private companion object {
        /** `PT_LOAD`; the only segment type whose alignment the loader honours. */
        const val PT_LOAD = 1
    }
}
