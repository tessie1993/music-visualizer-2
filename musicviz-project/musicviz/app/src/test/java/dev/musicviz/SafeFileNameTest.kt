package dev.musicviz

import dev.musicviz.ui.PresetStore
import dev.musicviz.ui.TextureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared file-name scheme behind presets, music playlists, palettes and
 * takes - plus the texture variant of it, which follows the same collision
 * rule in a stricter, shader-identifier-safe alphabet.
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

    // Texture file names live under the same collision rule but a stricter
    // alphabet: the base doubles as a shader identifier (sampler_<basename>),
    // so no spaces, no hyphens, and no leading digit.
    private val identifierSafe = Regex("^[A-Za-z_][A-Za-z0-9_]*\\.[a-z]+$")

    @Test
    fun an_identifier_safe_texture_name_is_its_own_file_name() {
        // The identity case is what keeps every already-imported texture -
        // and every preset referencing it by this exact name - untouched.
        assertEquals("logo_2.png", TextureStore.safeTextureFileName("logo_2.png"))
    }

    @Test
    fun distinct_cjk_texture_names_get_distinct_identifier_safe_names() {
        val a = TextureStore.safeTextureFileName("夜曲.png")
        val b = TextureStore.safeTextureFileName("月光.png")
        assertNotEquals(a, b)
        assertTrue(a, identifierSafe.matches(a))
        assertTrue(b, identifierSafe.matches(b))
    }

    @Test
    fun distinct_emoji_texture_names_get_distinct_identifier_safe_names() {
        val a = TextureStore.safeTextureFileName("🔥mix.png")
        val b = TextureStore.safeTextureFileName("💜mix.png")
        assertNotEquals(a, b)
        assertTrue(a, identifierSafe.matches(a))
        assertTrue(b, identifierSafe.matches(b))
    }

    @Test
    fun the_texture_name_is_stable_across_calls() {
        assertEquals(TextureStore.safeTextureFileName("夜曲.png"), TextureStore.safeTextureFileName("夜曲.png"))
    }

    @Test
    fun a_digit_leading_texture_base_cannot_collide_with_its_prefixed_twin() {
        // "1up" used to become "t1up", the exact name of a genuine "t1up.png".
        val a = TextureStore.safeTextureFileName("1up.png")
        val b = TextureStore.safeTextureFileName("t1up.png")
        assertNotEquals(a, b)
        assertTrue(a, a.startsWith("t1up_"))
        assertTrue(a, identifierSafe.matches(a))
        assertEquals("t1up.png", b)
    }

    @Test
    fun an_altered_texture_base_appends_the_digest_with_an_underscore() {
        // '-' is legal in a file name but not in a shader identifier, so the
        // texture digest joins with '_' where safeFileName joins with '-'.
        val name = TextureStore.safeTextureFileName("night sky.png")
        assertTrue(name, name.startsWith("night_sky_"))
        assertTrue(name, name.removePrefix("night_sky_").matches(Regex("[0-9a-f]{8}\\.png")))
    }

    @Test
    fun the_milk_file_shares_the_json_stem() {
        // The .milk must sit beside its .json under one base name, hash and
        // all, or a MilkDrop preset loses its visual (see milkFileName).
        assertEquals(PresetStore.safeFileName("夜曲") + ".milk", PresetStore.milkFileName("夜曲"))
        assertEquals(PresetStore.safeFileName("夜曲") + ".milk", PresetStore.milkFileName("夜曲.milk"))
    }
}
