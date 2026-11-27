package com.stenograf.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Spacing {
    val xxs = 4.dp
    val xs = 6.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

object Radius {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 24.dp
    val pill = 999.dp
}

object Motion {
    val StandardEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    private val ScreenEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun <T> fastTween() = tween<T>(durationMillis = 165, easing = StandardEasing)
    fun <T> mediumTween() = tween<T>(durationMillis = 250, easing = StandardEasing)
    fun <T> screenTween() = tween<T>(durationMillis = 300, easing = ScreenEasing)
    fun tabSpring() = spring<Float>(
        dampingRatio = 0.92f,
        stiffness = 360f
    )
    fun <T> snappySpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
}

/**
 * GPU-composited tactile press scale feedback with zero layout reflow.
 */
fun Modifier.pressScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f
): Modifier = composed {
    if (!enabled) return@composed this
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.88f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                }
            }
        }
}

/**
 * Clean Material-style tonal surface card for Telegram Android grouped sections.
 */
fun Modifier.vantaSurfaceCard(
    colors: StenografColors,
    radius: Dp = Radius.lg,
    elevated: Boolean = false
): Modifier {
    val shape = RoundedCornerShape(radius)
    val bg = if (elevated) colors.surfaceElevated else colors.surface
    return this
        .clip(shape)
        .background(bg)
        .border(0.75.dp, if (colors.isDark) colors.borderStrong.copy(alpha = 0.45f) else colors.border, shape)
}
