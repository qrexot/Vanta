package com.stenograf.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.vantaSurfaceCard

@Composable
fun VantaProgressBanner(
    message: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    liveSnippet: String? = null,
    startedAtMs: Long = 0L,
    onCancel: (() -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val clamped = progress?.coerceIn(0f, 1f)

    // Smooth active beacon + elapsed time ticker during offline processing
    val infinite = rememberInfiniteTransition(label = "prog_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = Motion.StandardEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "prog_dot_alpha"
    )
    var elapsedSec by remember(startedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs > 0L) {
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
                kotlinx.coroutines.delay(500L)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .vantaSurfaceCard(colors, radius = Radius.md, elevated = true)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = pulseAlpha }
                    .clip(CircleShape)
                    .background(colors.accent)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = message,
                color = colors.textPrimary,
                style = VantaType.body.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (startedAtMs > 0L && elapsedSec > 0L) {
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "${elapsedSec}s",
                    color = colors.textMuted,
                    style = VantaType.monoBadge
                )
            }
            if (clamped != null) {
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "${(clamped * 100).toInt()}%",
                    color = colors.accent,
                    style = VantaType.monoMetric
                )
            }
            if (onCancel != null) {
                Spacer(Modifier.width(Spacing.xs))
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(s.cancel, color = colors.destructive, style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
        VantaLinearProgressTrack(progress = clamped)
        if (!liveSnippet.isNullOrBlank()) {
            Text(
                text = "«$liveSnippet»",
                color = colors.textSecondary,
                style = VantaType.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun VantaLinearProgressTrack(
    progress: Float?,
    modifier: Modifier = Modifier,
    fillColor: Color = LocalStenografColors.current.accent
) {
    val colors = LocalStenografColors.current
    val position = if (progress == null) {
        val transition = rememberInfiniteTransition(label = "indeterminate_progress")
        transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
            label = "segment_position"
        )
    } else {
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = Motion.fastTween(),
            label = "progress_fraction"
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        val h = size.height
        val r = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(
            color = colors.surfaceRecessed,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = r
        )
        if (progress != null) {
            val w = size.width * position.value
            if (w > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    cornerRadius = r
                )
            }
        } else {
            val segW = size.width * 0.35f
            val segmentX = position.value * size.width
            val startX = segmentX.coerceIn(0f, size.width)
            val endX = (segmentX + segW).coerceIn(0f, size.width)
            val w = (endX - startX).coerceAtLeast(0f)
            if (w > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(startX, 0f),
                    size = Size(w, h),
                    cornerRadius = r
                )
            }
        }
    }
}

@Composable
fun VantaInfoCallout(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = LocalStenografColors.current.accent
) {
    val colors = LocalStenografColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(Radius.pill))
                .background(accentColor)
        )
        Spacer(Modifier.width(Spacing.md))
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            color = colors.textSecondary,
            style = VantaType.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}
