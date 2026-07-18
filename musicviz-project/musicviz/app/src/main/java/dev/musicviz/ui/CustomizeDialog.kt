package dev.musicviz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.musicviz.render.scene.SceneParams

/**
 * Scene customization panel. Tabs group the controls the way people think
 * about them - how it moves, how it reacts, how it's colored - with the raw
 * GLSL editor as an Advanced tab on shader scenes. All changes apply live.
 */
@Composable
fun CustomizeDialog(
    params: SceneParams,
    onParamsChange: (SceneParams) -> Unit,
    shaderSource: String?,
    shaderError: String?,
    onApplyShader: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf(shaderSource.orEmpty()) }
    val hasAdvanced = shaderSource != null
    val tabs = if (hasAdvanced) listOf("Motion", "Behavior", "Color", "GLSL") else listOf("Motion", "Behavior", "Color")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize scene") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    when (tab) {
                        0 -> MotionTab(params, onParamsChange)
                        1 -> BehaviorTab(params, onParamsChange)
                        2 -> ColorTab(params, onParamsChange)
                        else -> {
                            OutlinedTextField(
                                value = source,
                                onValueChange = { source = it },
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            if (shaderError != null) {
                                Text(
                                    text = shaderError,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (tab == 3 && hasAdvanced) {
                TextButton(onClick = { onApplyShader(source) }) { Text("Apply shader") }
            } else {
                TextButton(onClick = { onParamsChange(SceneParams.DEFAULT) }) { Text("Reset") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun MotionTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        LabeledSlider("Speed", p.speed, 0.1f..3f) { onChange(p.copy(speed = it)) }
        LabeledSlider("Zoom", p.zoom, 0.4f..2.5f) { onChange(p.copy(zoom = it)) }
        LabeledSlider("Rotation", p.rotation, -1.5f..1.5f) { onChange(p.copy(rotation = it)) }
        CheckRow("Endless zoom", p.endlessZoom) { onChange(p.copy(endlessZoom = it)) }
        if (p.endlessZoom) {
            LabeledSlider("Dive speed", p.endlessZoomSpeed, 0.05f..1.2f) { onChange(p.copy(endlessZoomSpeed = it)) }
        }
    }
}

@Composable
private fun BehaviorTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        LabeledSlider("Audio drive", p.audioDrive, 0.2f..2.5f) { onChange(p.copy(audioDrive = it)) }
        LabeledSlider("Beat response", p.beatResponse, 0f..2f) { onChange(p.copy(beatResponse = it)) }
        LabeledSlider("Turbulence", p.turbulence, 0f..1.5f) { onChange(p.copy(turbulence = it)) }
        LabeledSlider("Density", p.density, 0.1f..1f) { onChange(p.copy(density = it)) }
        CheckRow("Mirror", p.mirror) { onChange(p.copy(mirror = it)) }
        CheckRow("Trails (particle scenes)", p.trails) { onChange(p.copy(trails = it)) }
        if (p.trails) {
            LabeledSlider("Trail length", p.trailLength, 0.05f..0.98f) { onChange(p.copy(trailLength = it)) }
        }
    }
}

@Composable
private fun ColorTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        Text("Palette", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SceneParams.PALETTES.forEachIndexed { index, (name, _, _) ->
                FilterChip(
                    selected = p.palette == index,
                    onClick = { onChange(p.copy(palette = index)) },
                    label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LabeledSlider("Hue shift", p.colorShift, 0f..1f) { onChange(p.copy(colorShift = it)) }
        LabeledSlider("Hue range", p.hueRange, 0f..1.5f) { onChange(p.copy(hueRange = it)) }
        LabeledSlider("Saturation", p.saturation, 0f..1.5f) { onChange(p.copy(saturation = it)) }
        LabeledSlider("Brightness", p.brightness, 0.2f..2f) { onChange(p.copy(brightness = it)) }
        LabeledSlider("Intensity", p.intensity, 0.2f..2f) { onChange(p.copy(intensity = it)) }
        CheckRow("Color cycle", p.colorCycle) { onChange(p.copy(colorCycle = it)) }
        if (p.colorCycle) {
            LabeledSlider("Cycle speed", p.cycleSpeed, 0.02f..0.6f) { onChange(p.copy(cycleSpeed = it)) }
        }
        CheckRow("Invert", p.invert) { onChange(p.copy(invert = it)) }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label ${"%.2f".format(value)}", style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
