package com.stenograf.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.export.ExportFormat
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard

val VantaFolderPalette: List<String> = listOf(
    "#3B82F6", // Cobalt
    "#10B981", // Jade
    "#F59E0B", // Amber
    "#EC4899", // Rose
    "#8B5CF6", // Violet
    "#64748B"  // Slate
)

fun parseFolderColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(fallback)
}

@Composable
fun VantaExportSheet(
    title: String,
    onDismissRequest: () -> Unit,
    onShareExport: (ExportFormat) -> Unit,
    onSaveExport: (ExportFormat) -> Unit,
    onCopyPlainText: (() -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val haptics = rememberVantaHaptics()

    VantaBottomSheet(
        onDismissRequest = onDismissRequest,
        title = s.exportTitle,
        subtitle = title,
        headerIcon = Icons.Rounded.Share
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (onCopyPlainText != null) {
                VantaSheetActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    label = extra.copyAsText,
                    tint = colors.accent,
                    iconBg = colors.accentSoft,
                    onClick = {
                        haptics.click()
                        onCopyPlainText()
                        onDismissRequest()
                    }
                )
                HorizontalDivider(color = colors.border.copy(alpha = 0.7f))
            }

            for (fmt in ExportFormat.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .vantaSurfaceCard(colors, radius = Radius.md, elevated = true)
                        .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xs))
                            .background(colors.accentSoft)
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = fmt.ext.uppercase(),
                            color = colors.accent,
                            style = VantaType.monoBadge
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = fmt.label(s),
                        color = colors.textPrimary,
                        style = VantaType.rowLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    VantaIconButton(
                        icon = Icons.Rounded.Share,
                        contentDescription = s.share,
                        tint = colors.accent,
                        containerColor = colors.accentSoft,
                        size = 40.dp,
                        onClick = {
                            haptics.click()
                            onShareExport(fmt)
                            onDismissRequest()
                        }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    VantaIconButton(
                        icon = Icons.Rounded.SaveAlt,
                        contentDescription = s.saveFileAs,
                        tint = colors.textPrimary,
                        containerColor = colors.surfaceRecessed,
                        size = 40.dp,
                        onClick = {
                            haptics.click()
                            onSaveExport(fmt)
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VantaParagraphEditorSheet(
    initialText: String,
    startSec: Float,
    endSec: Float,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val haptics = rememberVantaHaptics()
    var text by remember(initialText) { mutableStateOf(initialText) }
    val wordCount = remember(text) { text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } }
    val timeRange = remember(startSec, endSec) {
        "${AsrTextFormatter.formatTimestampSec(startSec)} – ${AsrTextFormatter.formatTimestampSec(endSec.coerceAtLeast(startSec))}"
    }

    VantaBottomSheet(
        onDismissRequest = onDismissRequest,
        title = s.editFragmentTitle,
        subtitle = "$timeRange · $wordCount ${s.wordsShort}",
        headerIcon = Icons.Rounded.Edit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            VantaTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                minLines = 4,
                maxLines = 10
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                VantaTonalButton(
                    label = s.cancel,
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                )
                VantaPrimaryButton(
                    label = s.save,
                    enabled = text.isNotBlank(),
                    onClick = {
                        haptics.confirm()
                        onSave(text.trim())
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun VantaFolderEditorSheet(
    existingFolder: FolderEntity?,
    onDismissRequest: () -> Unit,
    onConfirm: (name: String, colorHex: String) -> Unit,
    onRequestDelete: ((FolderEntity) -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val haptics = rememberVantaHaptics()

    var name by remember(existingFolder?.id) { mutableStateOf(existingFolder?.name ?: "") }
    var selectedHex by remember(existingFolder?.id) {
        mutableStateOf(existingFolder?.colorHex ?: VantaFolderPalette.first())
    }

    VantaBottomSheet(
        onDismissRequest = onDismissRequest,
        title = if (existingFolder == null) s.newFolder else extra.renameFolder,
        headerIcon = if (existingFolder == null) Icons.Rounded.CreateNewFolder else Icons.Rounded.Edit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            VantaTextField(
                value = name,
                onValueChange = { name = it },
                label = s.folderNameLabel,
                required = true
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = extra.folderColorLabel,
                    color = colors.textSecondary,
                    style = VantaType.sectionLabel
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    for (hex in VantaFolderPalette) {
                        val swatch = parseFolderColor(hex, colors.accent)
                        val selected = selectedHex.equals(hex, ignoreCase = true)
                        val ringColor by animateColorAsState(
                            targetValue = if (selected) colors.textPrimary else Color.Transparent,
                            animationSpec = Motion.fastTween(),
                            label = "swatch_ring"
                        )
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .pressScale(pressedScale = 0.9f)
                                .clip(CircleShape)
                                .border(2.dp, ringColor, CircleShape)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .clickable {
                                    haptics.tick()
                                    selectedHex = hex
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (existingFolder != null && onRequestDelete != null) {
                    VantaDangerButton(
                        label = s.delete,
                        icon = Icons.Rounded.DeleteOutline,
                        outlined = true,
                        onClick = {
                            haptics.heavy()
                            onDismissRequest()
                            onRequestDelete(existingFolder)
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    VantaTonalButton(
                        label = s.cancel,
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f)
                    )
                }

                VantaPrimaryButton(
                    label = if (existingFolder == null) s.create else s.save,
                    enabled = name.isNotBlank(),
                    onClick = {
                        if (name.isNotBlank()) {
                            haptics.confirm()
                            onConfirm(name.trim(), selectedHex)
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
