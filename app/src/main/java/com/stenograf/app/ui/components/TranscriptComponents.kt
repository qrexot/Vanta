package com.stenograf.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType

private val SPEAKER_PALETTE = listOf(
    Color(0xFF3390EC),
    Color(0xFF2EAD5B),
    Color(0xFF8E63E6),
    Color(0xFFEF6C00),
    Color(0xFF0097A7),
    Color(0xFFE91E63)
)

/**
 * Deterministic accent badge identifying an attributed speaker across recording and detail views.
 */
@Composable
internal fun SpeakerPill(
    speakerKey: String?,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalStenografColors.current
    val speakerColor = remember(speakerKey, colors.textMuted) {
        if (speakerKey == null) {
            colors.textMuted
        } else {
            val idx = (speakerKey.hashCode() and 0x7FFFFFFF) % SPEAKER_PALETTE.size
            SPEAKER_PALETTE[idx]
        }
    }
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(speakerColor.copy(alpha = if (colors.isDark) 0.22f else 0.14f))
            .then(clickModifier)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = speakerColor,
            style = VantaType.monoBadge
        )
    }
}

/**
 * Compact FlowRow of Groq Whisper source language overrides shared across import and re-transcribe sheets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroqLanguagePillRow(
    selectedCode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        GroqAsrClient.QUICK_LANGUAGE_PILLS.forEach { (code, pillLabel) ->
            StatusPill(
                label = pillLabel,
                containerColor = if (selectedCode == code) colors.accentSoft else colors.surface,
                contentColor = if (selectedCode == code) colors.accent else colors.textSecondary,
                onClick = { onSelect(code) }
            )
        }
    }
}
