package com.stenograf.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import java.util.Locale

@Composable
fun VantaTopBar(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    title: String? = null,
    titleAlpha: () -> Float = { 1f },
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalStenografColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                VantaIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = backDescription ?: LocalStrings.current.back,
                    tint = colors.textPrimary,
                    onClick = onBack
                )
            }
            if (!title.isNullOrBlank()) {
                Spacer(Modifier.width(if (onBack != null) Spacing.xs else Spacing.md))
                Text(
                    text = title,
                    color = colors.textPrimary,
                    style = VantaType.pageTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = titleAlpha().coerceIn(0f, 1f) }
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)
    }
}

@Composable
fun VantaIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalStenografColors.current.textSecondary,
    containerColor: Color = Color.Transparent,
    active: Boolean = false,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp
) {
    val colors = LocalStenografColors.current
    val bg by animateColorAsState(
        targetValue = if (active) colors.accentSoft else containerColor,
        animationSpec = Motion.fastTween(),
        label = "icon_btn_bg"
    )
    val resolvedTint by animateColorAsState(
        targetValue = if (active) colors.accent else tint,
        animationSpec = Motion.fastTween(),
        label = "icon_btn_tint"
    )
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        modifier = modifier
            .size(size)
            .pressScale(pressedScale = 0.91f)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun VantaPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = LocalStenografColors.current.accent,
    contentColor: Color = LocalStenografColors.current.onAccent
) {
    val colors = LocalStenografColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = colors.surfaceHighlight,
            disabledContentColor = colors.textMuted
        ),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 0.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(enabled = enabled, pressedScale = 0.975f)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            text = label,
            style = VantaType.rowLabel.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VantaTonalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = LocalStenografColors.current.surfaceElevated,
    contentColor: Color = LocalStenografColors.current.textPrimary,
    borderColor: Color = LocalStenografColors.current.borderStrong
) {
    val colors = LocalStenografColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(0.75.dp, if (enabled) borderColor else colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = colors.surfaceRecessed,
            disabledContentColor = colors.textMuted
        ),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 0.dp),
        modifier = modifier
            .heightIn(min = 46.dp)
            .pressScale(enabled = enabled, pressedScale = 0.975f)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text = label,
            style = VantaType.body.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VantaDangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val colors = LocalStenografColors.current
    if (outlined) {
        VantaTonalButton(
            label = label,
            onClick = onClick,
            modifier = modifier,
            icon = icon,
            enabled = enabled,
            containerColor = colors.destructiveSoft,
            contentColor = colors.destructive,
            borderColor = colors.destructive.copy(alpha = 0.42f)
        )
    } else {
        VantaPrimaryButton(
            label = label,
            onClick = onClick,
            modifier = modifier,
            icon = icon,
            enabled = enabled,
            containerColor = colors.destructive,
            contentColor = colors.onDestructive
        )
    }
}

@Composable
fun VantaSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.surfaceRecessed,
        animationSpec = Motion.fastTween(),
        label = "switch_track"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.borderStrong,
        animationSpec = Motion.fastTween(),
        label = "switch_border"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.onAccent else colors.textSecondary,
        animationSpec = Motion.fastTween(),
        label = "switch_thumb"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = Motion.snappySpring(),
        label = "switch_offset"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (checked) 20.dp else 17.dp,
        animationSpec = Motion.snappySpring(),
        label = "switch_thumb_size"
    )

    Box(
        modifier = modifier
            .width(46.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.25.dp, borderColor, CircleShape),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
fun VantaSelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val checkBg by animateColorAsState(
        targetValue = if (selected) colors.accent else Color.Transparent,
        animationSpec = Motion.fastTween(),
        label = "sel_bg"
    )
    val checkBorder by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.borderStrong,
        animationSpec = Motion.fastTween(),
        label = "sel_border"
    )
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(checkBg)
            .border(width = 1.5.dp, color = checkBorder, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
fun VantaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    var focused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.accent else colors.border,
        animationSpec = Motion.fastTween(),
        label = "search_border"
    )
    val iconColor by animateColorAsState(
        targetValue = if (focused) colors.accent else colors.textMuted,
        animationSpec = Motion.fastTween(),
        label = "search_icon"
    )
    val borderWidth = if (focused) 1.25.dp else 0.75.dp
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.surfaceRecessed)
            .border(borderWidth, borderColor, shape)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.textMuted,
                    style = VantaType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = VantaType.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }

        if (trailingContent != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = trailingContent
            )
        }

        AnimatedVisibility(
            visible = value.isNotEmpty(),
            enter = fadeIn(Motion.fastTween()) + scaleIn(Motion.fastTween(), initialScale = 0.8f),
            exit = fadeOut(Motion.fastTween()) + scaleOut(Motion.fastTween(), targetScale = 0.8f)
        ) {
            VantaIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = s.close,
                tint = colors.textMuted,
                size = 36.dp,
                iconSize = 17.dp,
                onClick = { onValueChange("") }
            )
        }
    }
}

@Composable
fun VantaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    required: Boolean = false
) {
    val colors = LocalStenografColors.current
    var touched by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val hasError = required && touched && value.isBlank()
    val requiredMessage = LocalExtraStrings.current.requiredFieldMessage

    OutlinedTextField(
        value = value,
        onValueChange = {
            touched = true
            onValueChange(it)
        },
        isError = hasError,
        supportingText = if (hasError) {
            { Text(requiredMessage, color = colors.destructive, style = VantaType.bodySmall) }
        } else null,
        label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        placeholder = placeholder?.let { { Text(it, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = VantaType.body,
        shape = RoundedCornerShape(Radius.sm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceRecessed,
            unfocusedContainerColor = colors.surfaceRecessed,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.borderStrong,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textMuted,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.accent
        ),
        modifier = modifier.fillMaxWidth().onFocusChanged {
            if (hadFocus && !it.isFocused) touched = true
            hadFocus = it.isFocused
        }
    )
}

@Composable
fun StatusPill(
    label: String,
    dotColor: Color? = null,
    containerColor: Color = LocalStenografColors.current.surfaceElevated,
    contentColor: Color = LocalStenografColors.current.textSecondary,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val animBg by animateColorAsState(containerColor, Motion.fastTween(), label = "pill_bg")
    val animText by animateColorAsState(contentColor, Motion.fastTween(), label = "pill_text")
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressScale(pressedScale = 0.96f) else Modifier)
            .clip(shape)
            .background(animBg)
            .border(0.75.dp, colors.border.copy(alpha = 0.9f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = animText,
            style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AnimatedRecordingDot(isPaused: Boolean) {
    val colors = LocalStenografColors.current
    val dotColor by animateColorAsState(
        targetValue = if (isPaused) colors.warning else colors.liveRed,
        animationSpec = Motion.mediumTween(),
        label = "rec_dot_color"
    )
    Box(
        modifier = Modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!isPaused) {
            val transition = rememberInfiniteTransition(label = "recording_pulse")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
                label = "pulse_phase"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val growth = Motion.StandardEasing.transform(phase)
                        scaleX = 0.85f + growth
                        scaleY = scaleX
                        alpha = 0.46f * (1f - phase) * (1f - phase)
                    }
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if (isPaused) 0.78f else 1f }
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    Text(
        text = title,
        color = colors.accent,
        style = VantaType.sectionLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = Spacing.md, bottom = Spacing.xs, start = Spacing.xs)
    )
}

data class StorageSegment(
    val label: String,
    val bytes: Long,
    val color: Color
)

@Composable
fun SegmentedStorageBar(
    segments: List<StorageSegment>,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val total = segments.sumOf { it.bytes.coerceAtLeast(0L) }.coerceAtLeast(1L)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.surfaceRecessed)
            .padding(1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (seg in segments) {
            if (seg.bytes > 0L) {
                val weight = (seg.bytes.toFloat() / total.toFloat()).coerceAtLeast(0.03f)
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .height(8.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(seg.color)
                )
            }
        }
    }
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(72.dp)) {
                drawCircle(
                    color = colors.accent.copy(alpha = 0.14f),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = colors.surfaceElevated,
                    radius = size.minDimension * 0.38f
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            color = colors.textPrimary,
            style = VantaType.sheetTitle,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = subtitle,
            color = colors.textSecondary,
            style = VantaType.body,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            VantaTonalButton(
                label = actionLabel,
                onClick = onAction,
                containerColor = colors.accentSoft,
                contentColor = colors.accent,
                borderColor = colors.accent.copy(alpha = 0.25f)
            )
        }
    }
}

fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatTransferSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return ""
    val kb = bytesPerSec / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB/s", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB/s", mb)
}

fun formatEtaDuration(seconds: Long): String {
    if (seconds < 0L) return ""
    if (seconds < 60L) return "${seconds}s"
    val m = seconds / 60L
    val s = seconds % 60L
    return "${m}m ${s}s"
}
