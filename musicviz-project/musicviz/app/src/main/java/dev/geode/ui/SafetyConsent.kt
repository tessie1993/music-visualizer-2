package dev.geode.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.render.VisualSafetyChoice

/**
 * The one screen that asks about flashing, before anything flashes.
 *
 * Geode draws full-screen brightness changes on purpose — that is what a music
 * visualizer is — and with limits off, the strobe runs at 9 Hz and the beat
 * flash at the track's rate. Both sit in the band that provokes photosensitive
 * seizures. The app already refuses to run those unasked: an unanswered choice
 * resolves to safe limits. What it did not do is *say so*, which left the
 * question half-answered in both directions — nobody was warned, and nobody
 * who wanted the full effects ever found out they were being held back.
 *
 * ## Why there is no live preview here
 *
 * The obvious design is a side-by-side "Safe vs Full effects" preview. It is
 * the wrong one: showing an unconsented user a 9 Hz sample to help them decide
 * whether to consent to 9 Hz samples is the exact harm the screen exists to
 * prevent. The comparison is therefore described in words, and the visuals
 * behind this screen stay limited until the moment the answer is given.
 *
 * Shown once, when the stored choice is [VisualSafetyChoice.UNKNOWN]. It also
 * reappears when the safety schema version changes — a choice made about an
 * older set of behaviours is not consent to a new one.
 */
@Composable
fun SafetyConsent(
    onChoose: (VisualSafetyChoice, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Text(
            stringResource(R.string.safety_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.safety_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.safety_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        ConsentOption(
            title = stringResource(R.string.safety_option_safe),
            detail = stringResource(R.string.safety_option_safe_detail),
            onClick = { onChoose(VisualSafetyChoice.SAFE, true) },
        )
        Spacer(Modifier.height(12.dp))
        ConsentOption(
            title = stringResource(R.string.safety_option_reduced_motion),
            detail = stringResource(R.string.safety_option_reduced_motion_detail),
            onClick = { onChoose(VisualSafetyChoice.REDUCED_MOTION, true) },
        )
        Spacer(Modifier.height(12.dp))
        ConsentOption(
            title = stringResource(R.string.safety_option_full),
            detail = stringResource(R.string.safety_option_full_detail),
            onClick = { onChoose(VisualSafetyChoice.CUSTOM, false) },
        )
    }
}

@Composable
private fun ConsentOption(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val spoken = stringResource(R.string.safety_option_description, title, detail)
    Column(Modifier.fillMaxWidth()) {
        CrystalButton(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The detail is the substance of the choice, so a screen
                    // reader has to hear it as part of the button rather than
                    // as loose text that follows it.
                    .semantics { contentDescription = spoken },
        ) {
            Text(title)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
