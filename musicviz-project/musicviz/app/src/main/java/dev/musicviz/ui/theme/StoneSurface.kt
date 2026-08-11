package dev.musicviz.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * The photographed stone surface behind one control, cross-faded between its
 * five shipped interaction states.
 *
 * This is the heart of the pack design: a pressed button shows the *pressed
 * photograph* - its internal light was brightened by the pack author, not
 * synthesised here. Each state renders its own artwork and the states fade
 * across [StoneMotion]'s timings, so interaction light "settles" the way the
 * pack contract describes instead of looping as an effect.
 *
 * All states' painters stay composed (alpha-faded rather than swapped) so a
 * fast press never pops. The art is premultiplied-alpha WebP with tumbled
 * edges baked in; [ContentScale.FillBounds] stretches it to the control's
 * bounds, which the near-rectangular masters tolerate across the aspect
 * ratios the app actually lays out.
 */
@Composable
fun StoneSurfaceArt(
    component: StoneComponent,
    state: StoneState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val pack = LocalThemePack.current
    val art = pack.surface(component)
    val motion = pack.motion

    @Composable
    fun fade(target: StoneState): Float {
        val visible = state == target
        val durationMs =
            when {
                reducedMotion -> motion.reduceMotionCrossfadeMs
                target == StoneState.PRESSED || state == StoneState.PRESSED -> motion.pressDurationMs
                target == StoneState.FOCUSED || state == StoneState.FOCUSED -> motion.focusDurationMs
                else -> motion.selectedDurationMs
            }
        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMs),
            label = "stone-state-$target",
        )
        return alpha
    }

    Box(modifier = modifier) {
        // The default surface always underpins the stack so a mid-fade never
        // shows the screen through the stone.
        Image(
            painter = painterResource(art.default),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        for (s in listOf(StoneState.FOCUSED, StoneState.SELECTED, StoneState.PRESSED, StoneState.DISABLED)) {
            val alpha = fade(s)
            if (alpha > 0.01f) {
                Image(
                    painter = painterResource(art.forState(s)),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    alpha = alpha,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * Press scale per the pack contract: down to `pressScale` over
 * `pressDurationMs`, spring-settled back over `releaseDurationMs`. Under
 * reduced motion the scale is pinned at 1 and only the state crossfade
 * remains.
 */
@Composable
fun Modifier.stonePress(
    interaction: InteractionSource,
    reducedMotion: Boolean = false,
): Modifier {
    val motion = LocalThemePack.current.motion
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) motion.pressScale else 1f,
        animationSpec =
            if (pressed) {
                tween(motion.pressDurationMs)
            } else {
                spring(dampingRatio = 0.78f, stiffness = 380f)
            },
        label = "stone-press",
    )
    return scale(scale)
}

/**
 * The interaction state a control should paint, combining its own flags with
 * live press/focus from [interaction].
 */
@Composable
fun rememberStoneState(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    selected: Boolean = false,
): StoneState {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    return stoneStateOf(enabled = enabled, pressed = pressed, selected = selected, focused = focused)
}

/** Shorthand for a remembered [MutableInteractionSource]. */
@Composable
fun rememberStoneInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }
