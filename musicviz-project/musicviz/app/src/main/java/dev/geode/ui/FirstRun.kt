package dev.geode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R

/**
 * What the person opening the app came here to do.
 *
 * This is the only question first run asks, and it earns its place by deciding two things at
 * once: which tab the app lands on, and whether Studio appears in the navigation at all. Someone
 * who only wants to listen should not have to walk past a render queue to reach their music.
 *
 * Changeable later in Settings — it is a starting point, not a commitment.
 */
enum class UserIntent(
    @param:StringRes val labelRes: Int,
    @param:StringRes val detailRes: Int,
) {
    LISTENING(R.string.first_run_intent_listening, R.string.first_run_intent_listening_detail),
    MAKING_VIDEOS(R.string.first_run_intent_videos, R.string.first_run_intent_videos_detail),
    BOTH(R.string.first_run_intent_both, R.string.first_run_intent_both_detail),
    ;

    /** Studio is hidden for pure listeners; everyone else gets it. */
    val showsStudio: Boolean get() = this != LISTENING

    /** Where the app opens. Video-first users land in Studio, everyone else on the Stage. */
    val landingDestination: GeodeDestination
        get() =
            when (this) {
                LISTENING, BOTH -> GeodeDestination.PLAYER
                MAKING_VIDEOS -> GeodeDestination.STUDIO
            }
}

/**
 * Supplies [FirstRunSetup] with the library plumbing it needs, so the screen itself stays a
 * function of its arguments and can be looked at without a ViewModel in scope.
 */
@Composable
internal fun FirstRunGate(onChooseIntent: (UserIntent) -> Unit) {
    val libraryViewModel: LibraryViewModel = geodeViewModel()
    val roots by libraryViewModel.mediaRoots.collectAsStateWithLifecycle()
    val tracks by libraryViewModel.deviceTracks.collectAsStateWithLifecycle()
    val scanning by libraryViewModel.libraryScanning.collectAsStateWithLifecycle()
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) libraryViewModel.importFolder(uri)
        }
    val found = tracks.size
    FirstRunSetup(
        onPickFolder = { folderPicker.launch(null) },
        onUseMediaLibrary = libraryViewModel::refreshDeviceTracks,
        hasSource = roots.isNotEmpty() || found > 0,
        // The count is the progress line: it climbs while the scan runs and stands still when it
        // finishes, which is all anyone needs to know from this screen.
        scanProgressLabel =
            if (scanning || found > 0) stringResource(R.string.first_run_scanning, found) else null,
        onChooseIntent = onChooseIntent,
    )
}

/**
 * First run, after the photosensitivity notice has been acknowledged.
 *
 * Two steps and no more: where the music is, and what the person is here for. No account, no
 * login, no email. Scanning starts as soon as a source is picked and everything stays usable
 * while it runs, so this screen never blocks on it.
 */
@Composable
fun FirstRunSetup(
    onPickFolder: () -> Unit,
    onUseMediaLibrary: () -> Unit,
    hasSource: Boolean,
    scanProgressLabel: String?,
    onChooseIntent: (UserIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceStepDone by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!sourceStepDone) {
            MusicSourceStep(
                onPickFolder = onPickFolder,
                onUseMediaLibrary = onUseMediaLibrary,
                hasSource = hasSource,
                scanProgressLabel = scanProgressLabel,
                onContinue = { sourceStepDone = true },
            )
        } else {
            IntentStep(onChooseIntent = onChooseIntent)
        }
    }
}

@Composable
private fun MusicSourceStep(
    onPickFolder: () -> Unit,
    onUseMediaLibrary: () -> Unit,
    hasSource: Boolean,
    scanProgressLabel: String?,
    onContinue: () -> Unit,
) {
    Text(
        stringResource(R.string.first_run_source_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.first_run_source_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    CrystalButton(onClick = onUseMediaLibrary, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.first_run_source_media_library))
    }
    Spacer(Modifier.height(10.dp))
    CrystalButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.first_run_source_folder))
    }
    // Both are allowed, so picking one does not end the step — the person says when they are done.
    if (scanProgressLabel != null) {
        Spacer(Modifier.height(16.dp))
        Text(
            scanProgressLabel,
            style = MaterialTheme.typography.labelMedium,
            color = accentTextColor(),
            textAlign = TextAlign.Center,
        )
    }
    if (hasSource) {
        Spacer(Modifier.height(20.dp))
        CrystalButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.first_run_continue))
        }
    }
}

@Composable
private fun IntentStep(onChooseIntent: (UserIntent) -> Unit) {
    Text(
        stringResource(R.string.first_run_intent_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.first_run_intent_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    UserIntent.entries.forEach { intent ->
        CrystalButton(onClick = { onChooseIntent(intent) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(intent.labelRes))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(intent.detailRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }
}
