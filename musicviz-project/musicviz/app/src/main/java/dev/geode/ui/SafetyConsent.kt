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
