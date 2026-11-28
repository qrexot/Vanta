package com.stenograf.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale

@Composable
fun VantaDialog(
    onDismissRequest: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = LocalStenografColors.current.accent,
    iconContainerColor: Color = LocalStenografColors.current.accentSoft,
    highlightText: String? = null,
    message: String? = null,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    isDestructive: Boolean = false,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    val shape = RoundedCornerShape(Radius.lg)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        MatchModalSystemBars()
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 392.dp)
                .fillMaxWidth()
                .shadow(18.dp, shape, clip = false)
                .clip(shape)
                .background(colors.surface)
                .border(0.75.dp, colors.borderStrong, shape)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = colors.textPrimary,
                        style = VantaType.sheetTitle,
                        modifier = Modifier.weight(1f)
                    )
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(iconContainerColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (!highlightText.isNullOrBlank()) {
                    Text(
                        text = highlightText,
                        color = colors.textPrimary,
                        style = VantaType.rowLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!message.isNullOrBlank()) {
                    Text(text = message, color = colors.textSecondary, style = VantaType.bodySmall)
                }
                content?.invoke(this)
            }

            HorizontalDivider(color = colors.border, thickness = 0.75.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dismissLabel != null) {
                    VantaTonalButton(
                        label = dismissLabel,
                        modifier = Modifier.weight(1f),
                        containerColor = colors.surface,
                        onClick = { (onDismiss ?: onDismissRequest)() }
                    )
                }
                if (isDestructive) {
                    VantaDangerButton(
                        label = confirmLabel,
                        modifier = Modifier.weight(1f),
                        enabled = confirmEnabled,
                        outlined = true,
                        onClick = onConfirm
                    )
                } else {
                    VantaPrimaryButton(
                        label = confirmLabel,
                        modifier = Modifier.weight(1f),
                        enabled = confirmEnabled,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VantaBottomSheet(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    headerIcon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalStenografColors.current
    val shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = colors.surfaceElevated,
        contentColor = colors.textPrimary,
        shape = shape,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (colors.isDark) 0.52f else 0.34f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null
    ) {
        MatchModalSystemBars()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(BottomSheetDefaults.windowInsets)
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = Spacing.md)
                    .size(width = 36.dp, height = 4.dp)
                    .background(colors.borderStrong, RoundedCornerShape(Radius.pill))
                    .semantics {
                        dismiss {
                            onDismissRequest()
                            true
                        }
                    }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xxs, vertical = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (headerIcon != null) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = colors.textPrimary,
                        style = VantaType.sheetTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = colors.textSecondary,
                            style = VantaType.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = colors.border, thickness = 0.5.dp)
            Spacer(Modifier.height(Spacing.sm))
            content()
        }
    }
}

@Composable
private fun MatchModalSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val light = !LocalStenografColors.current.isDark
    SideEffect {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }
}

@Composable
fun VantaSheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = LocalStenografColors.current.textPrimary,
    iconBg: Color = LocalStenografColors.current.surfaceRecessed,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalStenografColors.current
    val resolvedTint = if (isDestructive) colors.destructive else tint
    val resolvedIconBg = if (isDestructive) colors.destructiveSoft else iconBg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(pressedScale = 0.985f)
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(resolvedIconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = resolvedTint,
            style = VantaType.rowLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SheetOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val colors = LocalStenografColors.current
    val bg by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else Color.Transparent,
        animationSpec = Motion.fastTween(),
        label = "opt_bg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.accent.copy(alpha = 0.35f) else Color.Transparent,
        animationSpec = Motion.fastTween(),
        label = "opt_border"
    )
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .pressScale(pressedScale = 0.985f)
            .clip(shape)
            .background(bg)
            .border(width = 0.75.dp, color = border, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = VantaType.rowLabel.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    style = VantaType.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        VantaSelectionMark(selected = selected)
    }
}
