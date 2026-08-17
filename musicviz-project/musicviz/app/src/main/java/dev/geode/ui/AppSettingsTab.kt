package dev.geode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Settings destination's tab bar, one tab per category. Each tab body
 * lives in its own file beside the other per-concern settings files
 * (LookSettings, AudioSettings, ExportSettings, FolderSettings,
 * BehaviorSettings, AboutSettings) so no single file grows back into the
 * ten-section scroll this replaced.
 *
 * Order deliberate: Look and Audio are the two tabs people live in, Export
 * and Folders are set-up-once, Behavior is the standing rules, About is
 * last because it is read once.
 */
internal val SETTINGS_TAB_TITLES = listOf("Look", "Audio", "Export", "Folders", "Behavior", "About")

/**
 * The app-preferences half of [SettingsScreen]: a [CrystalTabs] strip over
 * six per-category tab bodies. The selected tab survives navigation and
 * process death via rememberSaveable, and the strip itself never moves when
 * selection does (CrystalTabs reserves the gem space).
 *
 * [exportOpen] is whether the export dialog is currently up; the Export tab
 * re-reads its persisted defaults when the dialog closes, because the dialog
 * writes its own last-used choices back as the new defaults.
 */
@Composable
internal fun AppSettingsTab(
    viewModel: PlayerViewModel,
    exportOpen: Boolean = false,
    onOpenExport: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        CrystalTabs(titles = SETTINGS_TAB_TITLES, selected = tab, onSelect = { tab = it })
        when (tab) {
            0 -> LookSettingsTab(viewModel)
            1 -> AudioSettingsTab(viewModel)
            2 -> ExportSettingsTab(exportOpen, onOpenExport)
            3 -> FolderSettingsTab(viewModel)
            4 -> BehaviorSettingsTab(viewModel)
            else -> AboutSettingsTab()
        }
    }
}

/**
 * The scroll container every settings tab shares: one lazy column, one item
 * per group card, consistent spacing. Tabs stay a flat list of small groups -
 * no collapsible sections inside a tab.
 */
@Composable
internal fun SettingsTabColumn(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * One settings group as a crystal card: overline title, optional [header]
 * control on the title row (e.g. a group master switch), then the content.
 * Reuses [crystalPanel] rather than a stock Material Card or an ad-hoc
 * alpha so text on the card keeps the same contrast treatment as every
 * other panel in the shell.
 */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    header: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                0.30f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 18.dp,
                glowStrength = 0.45f,
            ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrystalOverline(title, Modifier.weight(1f))
            header?.invoke(this)
        }
        content()
    }
}
