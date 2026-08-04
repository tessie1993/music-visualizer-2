package dev.musicviz

import dev.musicviz.ui.PresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared file-name scheme behind presets, music playlists and palettes.
 *
 * The old sanitizer replaced every disallowed character with '_' and nothing
 * else, so distinct names collapsed onto one file: saving "月光" silently
 * destroyed "夜曲". Two properties hold it together now. A name made only of
 * safe characters keeps its exact old stem - that is what makes the on-disk
 * migration a no-op for most users - and every other name carries a stable
 * digest of the raw name, so no two names can share a file.
 */
class SafeFileNameTest {
    private val filesystemSafe = Regex("^[A-Za-z0-9 _-]+$")

    @Test
    fun a_name_of_safe_characters_is_its_own_stem() {
        assertEquals("My Preset_2-b", PresetStore.safeFileName("My Preset_2-b"))
    }

    @Test
    fun distinct_cjk_names_get_distinct_filesystem_safe_stems() {
        val a = PresetStore.safeFileName("夜曲")
        val b = PresetStore.safeFileName("月光")
        assertNotEquals(a, b)
        assertTrue(a, filesystemSafe.matches(a))
        assertTrue(b, filesystemSafe.matches(b))
    }

    @Test
    fun distinct_emoji_names_get_distinct_filesystem_safe_stems() {
        val a = PresetStore.safeFileName("🔥 Mix")
        val b = PresetStore.safeFileName("💜 Mix")
        assertNotEquals(a, b)
        assertTrue(a, filesystemSafe.matches(a))
        assertTrue(b, filesystemSafe.matches(b))
    }

    @Test
    fun the_stem_is_stable_across_calls() {
        // The digest is of the raw name, so migration and lookup always agree.
        assertEquals(PresetStore.safeFileName("夜曲"), PresetStore.safeFileName("夜曲"))
        assertEquals(PresetStore.safeFileName("🔥 Mix"), PresetStore.safeFileName("🔥 Mix"))
    }

    @Test
    fun an_unsafe_name_keeps_its_readable_part_and_appends_eight_hex_chars() {
        val stem = PresetStore.safeFileName("Live / set 1")
        assertTrue(stem, stem.startsWith("Live _ set 1-"))
        assertTrue(stem, stem.substringAfterLast('-').matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun the_milk_file_shares_the_json_stem() {
        // The .milk must sit beside its .json under one base name, hash and
        // all, or a MilkDrop preset loses its visual (see milkFileName).
        assertEquals(PresetStore.safeFileName("夜曲") + ".milk", PresetStore.milkFileName("夜曲"))
        assertEquals(PresetStore.safeFileName("夜曲") + ".milk", PresetStore.milkFileName("夜曲.milk"))
    }
}
