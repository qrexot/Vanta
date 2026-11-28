package com.stenograf.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.i18n.AppStrings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.stenograf.app.ui.components.VantaSelectionMark
import com.stenograf.app.ui.components.parseFolderColor
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryFolderTab(
    label: String,
    count: Int,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val colors = LocalStenografColors.current
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textSecondary,
        animationSpec = Motion.fastTween(),
        label = "tab_text"
    )
    val badgeBg by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceRecessed,
        animationSpec = Motion.fastTween(),
        label = "tab_badge_bg"
    )
    val badgeText by animateColorAsState(
        targetValue = if (selected) colors.onAccent else colors.textMuted,
        animationSpec = Motion.fastTween(),
        label = "tab_badge_text"
    )
    val tabBackground by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surfaceRecessed.copy(alpha = 0.55f),
        animationSpec = Motion.mediumTween(),
        label = "folder_background"
    )

    Row(
        modifier = Modifier
            .height(44.dp)
            .pressScale()
            .clip(RoundedCornerShape(Radius.pill))
            .background(tabBackground)
            .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = VantaType.rowLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                color = badgeText,
                style = VantaType.monoBadge,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(badgeBg)
                    .padding(horizontal = Spacing.xs, vertical = 1.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecordingCard(
    recording: RecordingEntity,
    strings: AppStrings,
    folder: FolderEntity?,
    bookmarkCount: Int,
    selectionMode: Boolean,
    selected: Boolean,
    removing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val time = remember(recording.createdAtMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
    }
    val duration = remember(recording.durationMs) {
        AsrTextFormatter.formatDurationMs(recording.durationMs)
    }
    val language = remember(recording.languagePresetId, recording.modelId, recording.detectedLanguage) {
        if (GroqAsrClient.SUPPORTED_MODELS.any { it.first == recording.modelId } && recording.detectedLanguage.isNotBlank()) {
            recording.detectedLanguage.uppercase()
        } else {
            TinyAsrCatalog.findPreset(recording.languagePresetId).shortLabel
        }
    }
    val formatBadge = remember(recording.audioFormatEnum) {
        recording.audioFormatEnum.extension.uppercase()
    }
    val warning = when {
        recording.statusEnum == RecordingStatus.INTERRUPTED -> strings.statusRecovered
        recording.hasUnprocessedAudio -> strings.statusUnprocessedTail
        else -> null
    }
    val folderColor = folder?.let { parseFolderColor(it.colorHex, colors.accent) } ?: colors.accent
    val avatarBg = when {
        warning != null -> colors.warningSoft
        folder != null -> folderColor.copy(alpha = if (colors.isDark) 0.22f else 0.14f)
        else -> colors.accentSoft
    }
    val avatarTint = when {
        warning != null -> colors.warning
        folder != null -> folderColor
        else -> colors.accent
    }
    val rowBg by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surface,
        animationSpec = Motion.fastTween(),
        label = "rec_row_bg"
    )

    val cardShape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(cardShape)
            .background(rowBg)
            .border(0.75.dp, if (selected) colors.accent.copy(alpha = 0.42f) else colors.border, cardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(enabled = !removing, onClick = onClick, onLongClick = onLongClick)
                .semantics { if (selectionMode) this.selected = selected }
                .padding(start = 12.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(avatarBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (warning != null) Icons.Rounded.WarningAmber else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = avatarTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .padding(1.5.dp)
                    ) {
                        VantaSelectionMark(selected = selected)
                    }
                }
            }

            Spacer(Modifier.width(11.dp))

            Column(
                modifier = Modifier.weight(1f).padding(top = 1.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = recording.title,
                        color = colors.textPrimary,
                        style = VantaType.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = time,
                        color = colors.textMuted,
                        style = VantaType.monoMetric
                    )
                }

                if (recording.previewText.isNotBlank()) {
                    Text(
                        text = recording.previewText,
                        color = colors.textSecondary,
                        style = VantaType.body.copy(textDirection = TextDirection.Content),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Text(
                        text = "$duration · $language · $formatBadge",
                        color = colors.textMuted,
                        style = VantaType.monoMetric
                    )
                    if (folder != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(folderColor)
                            )
                            Text(
                                text = folder.name,
                                color = folderColor,
                                style = VantaType.monoMetric,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (bookmarkCount > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Bookmark,
                                contentDescription = strings.bookmarkButton,
                                tint = colors.warning,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(bookmarkCount.toString(), color = colors.warning, style = VantaType.monoBadge)
                        }
                    }
                    if (!recording.hasTranscript) {
                        Text(strings.statusNoTranscript, color = colors.textMuted, style = VantaType.bodySmall)
                    }
                }

                if (warning != null) {
                    Text(warning, color = colors.warning, style = VantaType.bodySmall)
                }
            }

            if (!selectionMode) {
                val menuInteraction = remember { MutableInteractionSource() }
                val pressed by menuInteraction.collectIsPressedAsState()
                val focused by menuInteraction.collectIsFocusedAsState()
                val shape = RoundedCornerShape(Radius.sm)
                val menuBackground by animateColorAsState(
                    targetValue = if (pressed) colors.surfaceHighlight else colors.surfaceRecessed,
                    animationSpec = Motion.fastTween(),
                    label = "recording_menu_background"
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = menuInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = { if (!removing) onOpenMenu() }
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(shape)
                            .background(menuBackground)
                            .border(1.dp, if (focused) colors.accent else Color.Transparent, shape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = strings.menu,
                            tint = if (pressed) colors.textPrimary else colors.textSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(Spacing.md))
            }
        }
    }
}

internal fun groupByDate(list: List<RecordingEntity>, strings: AppStrings): List<Pair<String, List<RecordingEntity>>> {
    val todayCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayMs = todayCal.timeInMillis
    val yesterdayMs = todayMs - 86_400_000L
    val weekMs = todayMs - 6 * 86_400_000L
    val monthFmt = SimpleDateFormat("LLLL yyyy", Locale.getDefault())

    return list.groupBy { rec ->
        when {
            rec.createdAtMs >= todayMs -> strings.dateToday
            rec.createdAtMs >= yesterdayMs -> strings.dateYesterday
            rec.createdAtMs >= weekMs -> strings.dateThisWeek
            else -> monthFmt.format(Date(rec.createdAtMs))
        }
    }.entries.map { it.key to it.value }
}
