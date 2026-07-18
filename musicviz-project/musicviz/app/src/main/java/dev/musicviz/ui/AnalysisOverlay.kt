package dev.musicviz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.musicviz.analysis.AudioFeatures

/**
 * Small spectrum + info readout. Compose Canvas is fine at this scale;
 * the particle layer itself stays GL-only per the project rules.
 */
@Composable
fun AnalysisOverlay(
    features: AudioFeatures,
    bpm: Float,
    sectionCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            val n = features.bands.size
            val barWidth = size.width / n
            for (i in 0 until n) {
                val h = features.bands[i] * size.height
                drawRect(
                    color = Color(0.4f + 0.6f * features.bands[i], 0.5f, 1f, 0.8f),
                    topLeft = Offset(i * barWidth, size.height - h),
                    size = Size(barWidth * 0.8f, h),
                )
            }
        }
        val bpmText = if (bpm > 0f) "${bpm.toInt()} BPM" else "BPM ..."
        val beatMark = if (features.beat) " *" else ""
        Text(
            text = "$bpmText | energy ${"%.2f".format(features.rms)} | sections $sectionCount$beatMark",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
