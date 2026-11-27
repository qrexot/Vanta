package com.stenograf.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val RemovalSweep = CubicBezierEasing(0.36f, 0f, 0.32f, 1f)
private val RemovalCollapse = CubicBezierEasing(0.22f, 0f, 0.2f, 1f)

private fun collapseFraction(phase: Float): Float =
    RemovalCollapse.transform(((phase - 0.24f) / 0.76f).coerceIn(0f, 1f))

private fun Modifier.collapseRemovedItem(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val item = measurable.measure(constraints)
        val height = (item.height * (1f - collapseFraction(progress()))).roundToInt()
        layout(item.width, height) { item.place(0, (height - item.height) / 2) }
    }

internal fun Modifier.recordingRemovalHeader(progress: () -> Float): Modifier =
    clipToBounds().collapseRemovedItem(progress).graphicsLayer {
        alpha = 1f - collapseFraction(progress())
    }

internal fun Modifier.recordingRemoval(
    background: Color,
    particle: Color,
    progress: () -> Float
): Modifier =
    clipToBounds().collapseRemovedItem(progress).drawWithContent {
        val phase = progress()
        if (phase <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val reveal = RemovalSweep.transform((phase / 0.84f).coerceIn(0f, 1f))
        val inset = minOf(12.dp.toPx(), size.width / 2f)
        val width = size.width - inset * 2
        val feather = minOf(10.dp.toPx(), width)
        val edge = inset + width * (1f - reveal)
        val rtl = layoutDirection == LayoutDirection.Rtl
        if (reveal < 1f) {
            val leadingEdge = if (rtl) size.width - edge else edge
            val left = if (rtl) leadingEdge else (leadingEdge - feather).coerceAtLeast(0f)
            val right = if (rtl) (leadingEdge + feather).coerceAtMost(size.width) else leadingEdge
            if (rtl) {
                clipRect(left = leadingEdge) { this@drawWithContent.drawContent() }
            } else {
                clipRect(right = leadingEdge) { this@drawWithContent.drawContent() }
            }
            if (right > left && reveal > 0f) {
                val transparent = background.copy(alpha = 0f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = if (rtl) listOf(background, transparent) else listOf(transparent, background),
                        startX = left,
                        endX = right
                    ),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                    alpha = (reveal * 12f).coerceAtMost(1f)
                )
            }
        }

        for (index in 0 until 40) {
            val seed = (index * 19 % 41) / 40f
            val birth = 0.07f + 0.67f * index / 40f
            val age = (phase - birth) / (0.21f + 0.1f * seed)
            if (age <= 0f || age >= 1f) continue

            val origin = inset + width * (1f - RemovalSweep.transform(birth / 0.84f))
            val x = origin + (5.dp.toPx() + 16.dp.toPx() * seed) * age
            val y = size.height * (0.2f + 0.6f * seed) + 16.dp.toPx() * age * (seed - 0.68f)
            val fade = (1f - age) * (1f - age)
            drawCircle(
                color = particle,
                radius = (0.7.dp.toPx() + 0.8.dp.toPx() * seed) * (1f - 0.6f * age),
                center = Offset(if (rtl) size.width - x else x, y),
                alpha = 0.68f * fade * (age * 9f).coerceAtMost(1f)
            )
        }
    }
