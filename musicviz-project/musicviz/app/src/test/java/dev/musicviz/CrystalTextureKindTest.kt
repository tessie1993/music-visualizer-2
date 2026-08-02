package dev.musicviz

import dev.musicviz.ui.AppTheme
import dev.musicviz.ui.CrystalTextureKind
import dev.musicviz.ui.crystalTextureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CrystalTextureKindTest {
    @Test
    fun everyNamedCrystalThemeHasItsOwnMineralTexture() {
        val expected =
            mapOf(
                AppTheme.LAPIS to CrystalTextureKind.LAPIS,
                AppTheme.MALACHITE to CrystalTextureKind.MALACHITE,
                AppTheme.CLEAR_QUARTZ to CrystalTextureKind.CLEAR_QUARTZ,
                AppTheme.ROSE_QUARTZ to CrystalTextureKind.ROSE_QUARTZ,
                AppTheme.SUGILITE to CrystalTextureKind.SUGILITE,
                AppTheme.AMETHYST to CrystalTextureKind.AMETHYST,
                AppTheme.KYANITE to CrystalTextureKind.KYANITE,
                AppTheme.ONYX to CrystalTextureKind.ONYX,
            )

        assertEquals(expected.keys.size, expected.values.toSet().size)
        expected.forEach { (theme, texture) -> assertEquals(texture, theme.crystalTextureKind()) }
    }

    @Test
    fun nonMineralThemesUseTheNeutralTexture() {
        AppTheme.entries
            .filterNot {
                it in
                    setOf(
                        AppTheme.LAPIS,
                        AppTheme.MALACHITE,
                        AppTheme.CLEAR_QUARTZ,
                        AppTheme.ROSE_QUARTZ,
                        AppTheme.SUGILITE,
                        AppTheme.AMETHYST,
                        AppTheme.KYANITE,
                        AppTheme.ONYX,
                    )
            }.forEach { theme -> assertEquals(CrystalTextureKind.GENERIC, theme.crystalTextureKind()) }
    }
}
