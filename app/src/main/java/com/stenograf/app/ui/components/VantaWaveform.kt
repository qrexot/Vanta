package com.stenograf.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.rememberVantaHaptics
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

@Immutable
data class BookmarkMarker(val sec: Float)

private val ScrubberPadding = 4.dp

private fun PointerInputScope.scrubberFractionAt(x: Float): Float {
    val padding = ScrubberPadding.toPx()
    val trackWidth = (size.width - 2f * padding).coerceAtLeast(1f)
    return ((x - padding) / trackWidth).coerceIn(0f, 1f)
}

/**
 * Studio oscilloscope ribbon for real-time audio capture.
 * Attenuates older bars on the left and highlights speech activity on the right live edge.
 */
@Composable
internal fun LiveWaveformView(
    waveform: Flow<List<Float>>,
    isActive: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier.height(52.dp)
) {
    val colors = LocalStenografColors.current
    val amplitudes by waveform.collectAsStateWithLifecycle(initialValue = emptyList())
    var frame by remember { mutableStateOf(WaveformFrame(emptyList(), emptyList(), 1f)) }

    LaunchedEffect(amplitudes, isActive, isPaused) {
        val target = if (isActive && !isPaused) amplitudes else List(amplitudes.size) { 0f }
        val previous = frame
        if (previous.end == target && previous.progress == 1f) return@LaunchedEffect
        val start = List(target.size) { index ->
            if (index < previous.end.size) previous.amplitudeAt(index) else 0f
        }
        animate(0f, 1f, animationSpec = tween(if (isPaused) 180 else 64, easing = LinearEasing)) { progress, _ ->
            frame = WaveformFrame(start, target, progress)
        }
    }
    val activeColor by animateColorAsState(
        targetValue = when {
            isPaused -> colors.warning
            isActive -> colors.accent
            else -> colors.textMuted
        },
        animationSpec = Motion.mediumTween(),
        label = "waveform_color"
    )
    val baselineColor = colors.border

    Canvas(modifier = modifier.fillMaxWidth()) {
        val currentFrame = frame
        val count = currentFrame.end.size
        val step = size.width / count.coerceAtLeast(1)
        val barWidth = (step * 0.58f).coerceAtMost(4.dp.toPx())
        val centerY = size.height / 2f
        val maxHalf = (size.height / 2f - 3.dp.toPx()).coerceAtLeast(0f)
        val tint = activeColor

        drawLine(
            color = baselineColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 0.75.dp.toPx()
        )

        for (i in 0 until count) {
            val amp = currentFrame.amplitudeAt(i).coerceIn(0f, 1f)
            val halfH = (amp * maxHalf).coerceAtLeast(1.dp.toPx())
            val x = step * i + (step - barWidth) / 2f
            val recencyAlpha = 0.4f + 0.6f * (i.toFloat() / (count - 1).coerceAtLeast(1))
            val intensity = (amp * 5f).coerceAtMost(1f)
            val color = tint.copy(alpha = (0.18f + 0.82f * intensity) * recencyAlpha)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - halfH),
                size = Size(barWidth, halfH * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private data class WaveformFrame(
    val start: List<Float>,
    val end: List<Float>,
    val progress: Float
) {
    fun amplitudeAt(index: Int): Float = start[index] + (end[index] - start[index]) * progress
}

/**
 * Interactive waveform audio scrubber with on-track amber bookmark diamonds and tactile haptics.
 */
@Composable
fun WaveformScrubberWithBookmarks(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    waveformPeaks: List<Float>,
    bookmarks: List<BookmarkMarker>,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val haptics = rememberVantaHaptics()
    val safeDuration = durationMs.coerceAtLeast(1L)
    val liveFraction = (positionMs.coerceIn(0L, safeDuration).toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(liveFraction) }
    var lastCrossedBookmarkIdx by remember { mutableIntStateOf(-1) }

    val playheadFraction by animateFloatAsState(
        targetValue = liveFraction,
        animationSpec = if (isPlaying && !isDragging) tween(55, easing = LinearEasing) else snap(),
        label = "playhead_position"
    )
    val currentOnSeek by rememberUpdatedState(onSeekToMs)
    val currentBookmarks by rememberUpdatedState(bookmarks)
    val currentDuration by rememberUpdatedState(safeDuration)

    fun seekForFraction(frac: Float) {
        val clamped = frac.coerceIn(0f, 1f)
        val durSec = currentDuration / 1000f
        if (durSec > 0f) {
            val hitIdx = currentBookmarks.indexOfFirst { b ->
                abs((b.sec / durSec).coerceIn(0f, 1f) - clamped) < 0.015f
            }
            if (hitIdx >= 0 && hitIdx != lastCrossedBookmarkIdx) {
                lastCrossedBookmarkIdx = hitIdx
                haptics.tick()
            } else if (hitIdx < 0) {
                lastCrossedBookmarkIdx = -1
            }
        }
        currentOnSeek((clamped * currentDuration).toLong())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(safeDuration) {
                detectTapGestures { offset ->
                    haptics.tick()
                    seekForFraction(scrubberFractionAt(offset.x))
                }
            }
            .pointerInput(safeDuration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = scrubberFractionAt(offset.x)
                        isDragging = true
                        seekForFraction(dragFraction)
                    },
                    onDragEnd = {
                        isDragging = false
                        lastCrossedBookmarkIdx = -1
                    },
                    onDragCancel = {
                        isDragging = false
                        lastCrossedBookmarkIdx = -1
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = scrubberFractionAt(change.position.x)
                        seekForFraction(dragFraction)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padX = ScrubberPadding.toPx()
            val trackW = (size.width - padX * 2f).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val displayFraction = if (isDragging) dragFraction else playheadFraction
            val playheadX = padX + trackW * displayFraction

            if (waveformPeaks.isNotEmpty()) {
                val count = waveformPeaks.size
                val step = trackW / count
                val barW = (step * 0.56f).coerceIn(1.8.dp.toPx(), 4.dp.toPx())
                val maxHalf = (size.height * 0.36f)
                for (i in 0 until count) {
                    val amp = waveformPeaks[i].coerceIn(0.08f, 1.0f)
                    val halfH = (amp * maxHalf).coerceAtLeast(2.dp.toPx())
                    val x = padX + step * i + (step - barW) / 2f
                    val centerBarX = x + barW / 2f
                    val barColor = if (centerBarX <= playheadX) {
                        colors.accent
                    } else {
                        colors.borderStrong.copy(alpha = 0.65f)
                    }
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, centerY - halfH),
                        size = Size(barW, halfH * 2f),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            } else {
                val trackH = 4.dp.toPx()
                drawRoundRect(
                    color = colors.borderStrong.copy(alpha = 0.65f),
                    topLeft = Offset(padX, centerY - trackH / 2f),
                    size = Size(trackW, trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
                )
                drawRoundRect(
                    color = colors.accent,
                    topLeft = Offset(padX, centerY - trackH / 2f),
                    size = Size((trackW * displayFraction).coerceAtLeast(0f), trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
                )
            }

            // Bookmark diamond markers directly along the track
            if (currentBookmarks.isNotEmpty() && durationMs > 0L) {
                val durSec = durationMs / 1000f
                val r = 4.2.dp.toPx()
                val outlineColor = if (colors.isDark) colors.surface else colors.surfaceElevated
                for (b in currentBookmarks) {
                    val frac = (b.sec / durSec).coerceIn(0f, 1f)
                    val bx = padX + trackW * frac
                    val path = Path().apply {
                        moveTo(bx, centerY - r)
                        lineTo(bx + r, centerY)
                        lineTo(bx, centerY + r)
                        lineTo(bx - r, centerY)
                        close()
                    }
                    drawPath(path = path, color = colors.warning)
                    drawPath(path = path, color = outlineColor, style = Stroke(width = 1.25.dp.toPx()))
                }
            }

            // Sculptural vertical playhead needle
            val needleW = if (isDragging) 3.dp.toPx() else 2.2.dp.toPx()
            val needleH = if (isDragging) size.height * 0.86f else size.height * 0.72f
            drawRoundRect(
                color = colors.accentSoft,
                topLeft = Offset(playheadX - 5.dp.toPx(), centerY - needleH / 2f - 2.dp.toPx()),
                size = Size(10.dp.toPx(), needleH + 4.dp.toPx()),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
            drawRoundRect(
                color = colors.textPrimary,
                topLeft = Offset(playheadX - needleW / 2f, centerY - needleH / 2f),
                size = Size(needleW, needleH),
                cornerRadius = CornerRadius(needleW / 2f, needleW / 2f)
            )
        }
    }
}
