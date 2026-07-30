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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the privacy policy is published; also linked from the Play listing. */
const val PRIVACY_POLICY_URL = "https://tessie1993.github.io/music-visualizer-2/privacy-policy.html"

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
