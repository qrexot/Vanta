package com.stenograf.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.service.ReTranscribeWorker.AudioReview
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.LiveRecordingUiState
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.TranscriptReviewSheet
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaTextField
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.rememberVantaHaptics

@Composable
internal fun LiveRecordingModals(
    confirmLiveAi: Boolean,
    liveAiModel: TranscriptReviewModel,
    onSelectLiveAiModel: (TranscriptReviewModel) -> Unit,
    onDismissConfirmLiveAi: () -> Unit,
    onEnableLiveAi: (TranscriptReviewModel) -> Unit,
    reviewBlock: ParagraphEntity?,
    onDismissReviewBlock: () -> Unit,
    state: LiveRecordingUiState,
    russian: Boolean,
    hasCloudKey: Boolean,
    reviewModelId: String,
    onSelectReviewModel: (TranscriptReviewModel) -> Unit,
    onOpenGroqSettings: () -> Unit,
    onRecognizeBlock: suspend (ParagraphEntity, TranscriptReviewModel?, Boolean, String?, String, String, String, (Float) -> Unit) -> AudioReview,
    onApplyBlock: suspend (ParagraphEntity, String, List<TranscriptWord>?) -> Boolean,
    showLangSheet: Boolean,
    onDismissLangSheet: () -> Unit,
    modelManager: ModelManager,
    onSwitchLanguagePreset: (String) -> Unit,
    missingModelIdPrompt: String?,
    onMissingModelPrompt: (String?) -> Unit,
    onOpenModelManager: () -> Unit,
    showBookmarkDialog: Boolean,
    bookmarkNote: String,
    onBookmarkNoteChange: (String) -> Unit,
    onDismissBookmarkDialog: () -> Unit,
    onAddBookmark: (String?) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val haptics = rememberVantaHaptics()

    if (confirmLiveAi) {
        VantaBottomSheet(
            onDismissRequest = onDismissConfirmLiveAi,
            title = if (russian) "Автопроверка с ИИ" else "Automatic AI review",
            subtitle = if (russian) {
                "Аудио готовых блоков отправляется в Groq. Whisper распознаёт его заново, GPT-OSS проверяет текст."
            } else {
                "Completed audio blocks go to Groq. Whisper recognizes the audio again and GPT-OSS reviews the text."
            },
            headerIcon = Icons.Rounded.AutoFixHigh
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (model in TranscriptReviewModel.entries) {
                    SheetOptionRow(
                        selected = model == liveAiModel,
                        title = model.title,
                        subtitle = when (model) {
                            TranscriptReviewModel.Fast -> if (russian) "Быстрая обработка" else "Fast processing"
                            TranscriptReviewModel.Detailed -> if (russian) "Сложная речь и термины" else "Difficult speech and terminology"
                        },
                        onClick = { onSelectLiveAiModel(model) }
                    )
                }
                Text(
                    if (russian) "Блоки, требующие проверки, можно открыть и распознать вручную."
                    else "Open any completed block that needs a manual review.",
                    color = colors.textSecondary,
                    style = VantaType.bodySmall
                )
                VantaPrimaryButton(
                    label = if (russian) "Включить для этой записи" else "Enable for this recording",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onDismissConfirmLiveAi()
                        onEnableLiveAi(liveAiModel)
                    }
                )
            }
        }
    }

    reviewBlock?.let { paragraph ->
        val index = state.committedParagraphs.indexOfFirst { it.id == paragraph.id }
        TranscriptReviewSheet(
            originalText = paragraph.text,
            preferredModelId = reviewModelId,
            hasCloudKey = hasCloudKey,
            russian = russian,
            russianAudioByDefault = false,
            onSelectModel = onSelectReviewModel,
            onOpenSettings = {
                onDismissReviewBlock()
                onOpenGroqSettings()
            },
            onRecognize = { model, forceRussian, groqOverride, hint, progress ->
                val before = state.committedParagraphs.getOrNull(index - 1)?.text.orEmpty()
                val after = state.committedParagraphs.getOrNull(index + 1)?.text.orEmpty()
                onRecognizeBlock(paragraph, model, forceRussian, groqOverride, before, after, hint, progress)
            },
            onApply = { onApplyBlock(paragraph, it.proposal.correctedText, it.recognizedWords) },
            onCopied = { haptics.tick() },
            onApplied = { haptics.confirm() },
            onDismiss = onDismissReviewBlock
        )
    }

    if (showLangSheet && state.isActive && !state.isGroqEngine && !state.isFinishing) {
        VantaBottomSheet(
            onDismissRequest = onDismissLangSheet,
            title = s.reTranscribeModelLang,
            headerIcon = Icons.Rounded.Translate
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
            ) {
                items(TinyAsrCatalog.PRESETS, key = { it.id }) { preset ->
                    val installed = modelManager.isModelInstalled(preset.modelId) ||
                        TinyAsrCatalog.findModel(preset.modelId).bundledInApk
                    SheetOptionRow(
                        selected = preset.id == state.languagePresetId,
                        title = preset.label(s),
                        subtitle = if (installed) preset.modelId else "${preset.modelId} · ${s.modelAvailableLabel}",
                        onClick = {
                            haptics.tick()
                            onDismissLangSheet()
                            if (installed) {
                                onSwitchLanguagePreset(preset.id)
                            } else {
                                onMissingModelPrompt(preset.modelId)
                            }
                        }
                    )
                }
            }
        }
    }

    missingModelIdPrompt?.let { missingId ->
        val spec = TinyAsrCatalog.findModel(missingId)
        VantaDialog(
            onDismissRequest = { onMissingModelPrompt(null) },
            title = s.modelRequiredInstallPrompt,
            icon = Icons.Rounded.Download,
            highlightText = "${spec.localizedTitle(s)} (${spec.sizeMb} MB)",
            confirmLabel = s.manageModelsTitle,
            onConfirm = {
                onMissingModelPrompt(null)
                onOpenModelManager()
            },
            dismissLabel = s.cancel,
            onDismiss = { onMissingModelPrompt(null) }
        )
    }

    if (showBookmarkDialog) {
        VantaDialog(
            onDismissRequest = onDismissBookmarkDialog,
            title = s.bookmarkDialogTitle,
            icon = Icons.Rounded.BookmarkAdd,
            iconTint = colors.warning,
            iconContainerColor = colors.warningSoft,
            confirmLabel = s.addButton,
            onConfirm = {
                haptics.confirm()
                onAddBookmark(bookmarkNote.takeIf { it.isNotBlank() })
                onDismissBookmarkDialog()
            },
            dismissLabel = s.cancel,
            onDismiss = onDismissBookmarkDialog
        ) {
            VantaTextField(
                value = bookmarkNote,
                onValueChange = onBookmarkNoteChange,
                label = s.bookmarkNoteLabel,
                singleLine = true
            )
        }
    }
}

internal val liveCursorContent = mapOf(
    "live_cursor" to InlineTextContent(Placeholder(0.4.em, 0.78.em, PlaceholderVerticalAlign.TextCenter)) {
        val color = LocalStenografColors.current.accent
        val transition = rememberInfiniteTransition(label = "stream_cursor")
        val opacity by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(680, easing = Motion.StandardEasing), RepeatMode.Reverse),
            label = "cursor_opacity"
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 3.dp, end = 1.dp)
                .graphicsLayer { alpha = opacity }
                .background(color, RoundedCornerShape(1.dp))
        )
    }
)
