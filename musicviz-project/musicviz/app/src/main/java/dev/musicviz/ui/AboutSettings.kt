package dev.musicviz.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.musicviz.BuildConfig
import dev.musicviz.engine.EngineDiagnostics
import dev.musicviz.engine.EngineGeneration
import dev.musicviz.engine.EngineGenerationStore
import dev.musicviz.engine.EngineSelection
import dev.musicviz.engine.engineControlsVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the privacy policy is published; also linked from the Play listing. */
const val PRIVACY_POLICY_URL = "https://tessie1993.github.io/music-visualizer-2/privacy-policy.html"

/** ABOUT: the Settings tab around [AboutSection] - version, licenses, privacy. */
@Composable
internal fun AboutSettingsTab() {
    SettingsTabColumn {
        item { SettingsGroup("About") { AboutSection() } }
        // MusicViz 2.0 slice 0.2b. Debug builds only - the selector can put the
        // app on an engine that is not the shipped one. The guard is a function
        // of the build flag so the release case stays testable; see
        // engineControlsVisible and AppSettingsTabSplitTest.
        if (engineControlsVisible(BuildConfig.DEBUG)) {
            item { SettingsGroup("Engine (debug)") { EngineDebugSection() } }
        }
    }
}

/**
 * Debug-only engine controls: which generation to run, and the diagnostics
 * report.
 *
 * The selection is a REQUEST, not a guarantee - V2 availability depends on GPU
 * capability probes that can fail on a given driver. So the row below shows
 * what actually resolved, and when that differs from the request it says why.
 * A silent black frame is the outcome this whole switch exists to prevent.
 *
 * `v2Available` is hard-coded false until Render Core V2 lands (Phase 4); the
 * probe replaces this constant then. It is deliberately not `true` - claiming
 * availability the engine cannot deliver is exactly the lie the fallback path
 * is here to make impossible.
 */
@Composable
internal fun EngineDebugSection() {
    val context = LocalContext.current
    val store = remember { EngineGenerationStore(context) }
    var requested by remember { mutableStateOf(store.load()) }
    val v2Available = false
    val selection = EngineGeneration.resolve(requested, v2Available)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Engine generation", style = MaterialTheme.typography.titleSmall)
        CrystalSegmented(
            options = EngineGeneration.entries.map { it.name },
            selected = EngineGeneration.entries.indexOf(requested),
            onSelect = { i ->
                val picked = EngineGeneration.entries[i]
                requested = picked
                store.save(picked)
            },
        )
        Text("Running: ${selection.active}", style = MaterialTheme.typography.bodySmall)
        when (selection) {
            is EngineSelection.Active -> Unit
            is EngineSelection.FellBack ->
                Text(
                    selection.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val report = EngineDiagnostics().report(selection)
                runCatching {
                    context
                        .getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(
                            android.content.ClipData.newPlainText("MusicViz diagnostics", report),
                        )
                }
            }) { Text("Copy diagnostics") }
        }
    }
}

/**
 * "About" block for the Settings screen: version, open-source notices and the
 * privacy policy link.
 *
 * The notices are not decoration — libprojectM ships under LGPL-2.1, which
 * requires the attribution and licence terms to reach the user, not just sit
 * in the repository. app/build.gradle.kts copies the repo's
 * THIRD_PARTY_NOTICES into assets/third_party_notices.txt at build time so
 * there is a single source of truth.
 */
@Composable
fun AboutSection() {
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MusicViz", style = MaterialTheme.typography.titleSmall)
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Plays music from your device and renders it. No account, no ads, " +
                "no analytics — nothing leaves the phone unless you export a video and share it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showLicenses = true }) { Text("Open source licenses") }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                }
            }) { Text("Privacy policy") }
        }
    }
    if (showLicenses) {
        LicensesDialog { showLicenses = false }
    }
}

/** Scrollable, verbatim rendering of assets/third_party_notices.txt. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var notices by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        notices =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("third_party_notices.txt").use { it.readBytes().decodeToString() }
                }.getOrElse { "Third-party notices could not be loaded." }
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open source licenses") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    notices ?: "Loading…",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
