package com.stenograf.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.components.SectionHeader
import com.stenograf.app.ui.components.SpeakerPill
import com.stenograf.app.ui.components.StatusPill
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaSelectionMark
import com.stenograf.app.ui.components.VantaTextField
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard

/**
 * Interactive speaker assignment and manual voiceprint tuning sheet on DetailScreen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailSpeakerAssignSheet(
    targetParagraph: ParagraphEntity,
    allMatchingCount: Int,
    savedProfiles: List<SpeakerProfileEntity>,
    onDismiss: () -> Unit,
    onApply: (newName: String, applyToAllMatching: Boolean, enrollVoiceprint: Boolean) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val haptics = rememberVantaHaptics()

    val hasSpeakerGroup = !targetParagraph.speaker.isNullOrBlank() && allMatchingCount > 1
    var nameInput by remember(targetParagraph.id, targetParagraph.speaker) {
        mutableStateOf(targetParagraph.speaker.orEmpty())
    }
    var applyAll by remember(targetParagraph.id) { mutableStateOf(hasSpeakerGroup) }
    var learnVoice by remember(targetParagraph.id) { mutableStateOf(true) }

    VantaBottomSheet(
        onDismissRequest = onDismiss,
        title = extra.assignSpeakerTitle,
        subtitle = targetParagraph.text.take(120),
        headerIcon = Icons.Rounded.RecordVoiceOver
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            VantaTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = extra.newSpeakerNameHint,
                singleLine = true,
                required = true
            )

            if (savedProfiles.isNotEmpty()) {
                SectionHeader(extra.existingProfilesLabel)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    savedProfiles.forEach { profile ->
                        val selected = nameInput.trim().equals(profile.name, ignoreCase = true)
                        val chipLabel = if (profile.tagOrPhone.isNotBlank()) {
                            "${profile.name} (${profile.tagOrPhone})"
                        } else profile.name
                        StatusPill(
                            label = chipLabel,
                            containerColor = if (selected) colors.accentSoft else colors.surface,
                            contentColor = if (selected) colors.accent else colors.textSecondary,
                            onClick = {
                                haptics.tick()
                                nameInput = profile.name
                            }
                        )
                    }
                }
            }

            if (hasSpeakerGroup) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatusPill(
                        label = "${extra.allMatchingBlocksOption} ($allMatchingCount)",
                        containerColor = if (applyAll) colors.accentSoft else colors.surface,
                        contentColor = if (applyAll) colors.accent else colors.textSecondary,
                        onClick = {
                            haptics.tick()
                            applyAll = true
                        }
                    )
                    StatusPill(
                        label = extra.onlyThisBlockOption,
                        containerColor = if (!applyAll) colors.accentSoft else colors.surface,
                        contentColor = if (!applyAll) colors.accent else colors.textSecondary,
                        onClick = {
                            haptics.tick()
                            applyAll = false
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable {
                        haptics.tick()
                        learnVoice = !learnVoice
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                VantaSelectionMark(selected = learnVoice)
                Text(
                    text = extra.learnVoiceFromRecordingOption,
                    color = colors.textSecondary,
                    style = VantaType.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(Spacing.xs))
            VantaPrimaryButton(
                label = s.save,
                enabled = nameInput.trim().isNotEmpty(),
                onClick = {
                    haptics.confirm()
                    onApply(nameInput.trim(), applyAll, learnVoice)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Lists all distinct speakers detected in the current recording so the user can sign or tune them in one tap.
 */
@Composable
internal fun RecordingSpeakersOverviewSheet(
    paragraphs: List<ParagraphEntity>,
    onDismiss: () -> Unit,
    onSelectSpeakerSample: (ParagraphEntity) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val haptics = rememberVantaHaptics()
    val groups = remember(paragraphs) {
        paragraphs.filter { !it.isBookmark || it.text.isNotBlank() }
            .groupBy { it.speaker }
            .entries
            .map { (speaker, items) -> Triple(speaker, items.size, items.first()) }
    }

    VantaBottomSheet(
        onDismissRequest = onDismiss,
        title = extra.speakerProfilesInRecording,
        headerIcon = Icons.Rounded.RecordVoiceOver
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            items(groups, key = { it.first ?: "__unassigned" }) { (speaker, count, samplePara) ->
                val label = speaker ?: s.unclearSpeaker
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                        .clickable {
                            haptics.tick()
                            onDismiss()
                            onSelectSpeakerSample(samplePara)
                        }
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SpeakerPill(speakerKey = speaker, label = label)
                    Text(
                        text = "$count",
                        color = colors.textMuted,
                        style = VantaType.monoMetric
                    )
                }
            }
        }
    }
}
