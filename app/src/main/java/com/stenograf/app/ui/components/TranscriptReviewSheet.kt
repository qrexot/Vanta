package com.stenograf.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.GroqTranscriptReviewer
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.ReTranscribeWorker.AudioReview
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.vantaSurfaceCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val QUICK_REVIEW_LANGUAGES = listOf(
    "auto" to "Авто (все языки)",
    "multi" to "RU + EN",
    "es" to "Español",
    "en" to "English",
    "de" to "Deutsch",
    "fr" to "Français",
    "uk" to "Українська",
    "it" to "Italiano",
    "pt" to "Português",
    "pl" to "Polski",
    "tr" to "Türkçe"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptReviewSheet(
    originalText: String,
    wholeRecording: Boolean = false,
    preferredModelId: String,
    hasCloudKey: Boolean,
    russian: Boolean,
    russianAudioByDefault: Boolean = false,
    onSelectModel: (TranscriptReviewModel) -> Unit,
    onOpenSettings: () -> Unit,
    onRecognize: suspend (TranscriptReviewModel?, Boolean, String?, String, (Float) -> Unit) -> AudioReview,
    onApply: suspend (AudioReview) -> Boolean,
    onListen: (() -> Unit)? = null,
    onCopied: () -> Unit,
    onApplied: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalStenografColors.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var modelId by rememberSaveable { mutableStateOf(TranscriptReviewModel.resolve(preferredModelId).id) }
    var hint by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<AudioReview?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var russianAudio by rememberSaveable { mutableStateOf(russianAudioByDefault) }
    var groqLanguageOverride by rememberSaveable { mutableStateOf("auto") }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    val candidate = review
    val proposal = candidate?.proposal

    DisposableEffect(Unit) {
        onDispose { request?.cancel() }
    }

    fun recognize(model: TranscriptReviewModel?) {
        if (request != null) return
        keyboard?.hide()
        progress = 0f
        error = null
        val langOverride = if (!russianAudio && model != null) groqLanguageOverride else null
        request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                review = onRecognize(model, russianAudio, langOverride, hint) { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: if (russian) "Не удалось распознать аудио." else "Could not recognize the audio."
            } finally {
                request = null
            }
        }
        request?.start()
    }

    VantaBottomSheet(
        onDismissRequest = {
            if (!saving) {
                request?.cancel()
                onDismiss()
            }
        },
        title = if (wholeRecording) {
            if (russian) "Проверка всей записи" else "Review entire recording"
        } else {
            if (russian) "Распознать фрагмент заново" else "Recognize this audio again"
        },
        subtitle = if (russian) "Результат можно проверить перед заменой текста"
        else "Preview the new transcript before replacing text",
        headerIcon = Icons.Rounded.AutoFixHigh
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 490.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (originalText.isNotBlank()) item(key = "original") {
                val originalLabel = if (wholeRecording) {
                    if (russian) "Начало текущего текста" else "Current transcript excerpt"
                } else if (russian) "Текущий текст" else "Current transcript"
                ReviewPassage(originalLabel, originalText, onListen?.let { listen ->
                    {
                        VantaIconButton(
                            contentDescription = if (russian) "Слушать с этой отметки" else "Play from here",
                            icon = Icons.Rounded.PlayArrow,
                            tint = colors.accent,
                            containerColor = colors.accentSoft,
                            size = 40.dp,
                            onClick = listen
                        )
                    }
                })
            }
            when {
                candidate != null && proposal != null -> {
                    val detected = candidate.detectedLanguage?.takeIf { it.isNotBlank() }
                    if (detected != null) {
                        item(key = "detected-lang") {
                            StatusPill(
                                label = "${if (russian) "Язык" else "Language"}: ${TinyAsrCatalog.languageNativeName(detected)}",
                                dotColor = colors.accent,
                                containerColor = colors.accentSoft,
                                contentColor = colors.accent
                            )
                        }
                    }
                    val resultLabel = if (russian) "Новая расшифровка" else "New transcript"
                    if (candidate.paragraphs.isEmpty()) {
                        item(key = "review") { ReviewPassage(resultLabel, proposal.correctedText) }
                    } else {
                        items(candidate.paragraphs, key = { "result_${it.orderIndex}" }) {
                            ReviewPassage(AsrTextFormatter.formatTimestampSec(it.startSec), it.text)
                        }
                    }
                    if (proposal.uncertain.isNotEmpty()) {
                        item(key = "uncertain") {
                            ReviewPassage(
                                if (russian) "Требуют проверки по записи" else "Check against the recording",
                                proposal.uncertain.joinToString("\n")
                            )
                        }
                    }
                    item(key = "review-actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            error?.let { Text(it, color = colors.destructive, style = VantaType.bodySmall) }
                            if (!wholeRecording && proposal.correctedText == originalText.trim()) {
                                Text(
                                    if (russian) "Модель не предложила правок." else "The model suggested no edits.",
                                    color = colors.textSecondary,
                                    style = VantaType.bodySmall
                                )
                            }
                            VantaPrimaryButton(
                                label = if (russian) "Применить правку" else "Apply edit",
                                enabled = !saving && (wholeRecording || proposal.correctedText != originalText.trim()),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (saving) return@VantaPrimaryButton
                                    saving = true
                                    error = null
                                    scope.launch {
                                        try {
                                            if (onApply(candidate)) {
                                                onApplied()
                                                onDismiss()
                                            } else {
                                                error = if (russian) "Абзац изменился. Откройте его заново для вычитки."
                                                else "The paragraph changed. Reopen it before proofreading again."
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            error = if (russian) "Не удалось сохранить правку." else "Could not save the edit."
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            )
                            VantaTonalButton(
                                label = if (russian) "Копировать предложение" else "Copy suggestion",
                                icon = Icons.Rounded.ContentCopy,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                                onClick = {
                                    clipboard.setText(AnnotatedString(proposal.correctedText))
                                    onCopied()
                                }
                            )
                            VantaTonalButton(
                                label = if (russian) "Распознать ещё раз" else "Try again",
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                                onClick = { review = null; error = null }
                            )
                        }
                    }
                }
                request != null -> item(key = "review-progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            if (russian) "Обработка аудио… ${(progress * 100).toInt()}%" else "Processing audio… ${(progress * 100).toInt()}%",
                            color = colors.textSecondary,
                            style = VantaType.bodySmall
                        )
                        VantaLinearProgressTrack(progress = progress)
                        VantaTonalButton(label = strings.cancel, onClick = { request?.cancel() })
                    }
                }
                else -> item(key = "review-configuration") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            StatusPill(
                                label = if (russian) "Язык по записи" else "Recording language",
                                dotColor = if (!russianAudio) colors.accent else null,
                                containerColor = if (!russianAudio) colors.accentSoft else colors.surface,
                                contentColor = if (!russianAudio) colors.accent else colors.textSecondary,
                                onClick = { russianAudio = false }
                            )
                            StatusPill(
                                label = if (russian) "Только русский" else "Russian only",
                                dotColor = if (russianAudio) colors.accent else null,
                                containerColor = if (russianAudio) colors.accentSoft else colors.surface,
                                contentColor = if (russianAudio) colors.accent else colors.textSecondary,
                                onClick = {
                                    russianAudio = true
                                    showLanguagePicker = false
                                }
                            )
                            if (hasCloudKey && !russianAudio) {
                                val langLabel = if (groqLanguageOverride == "auto") {
                                    if (russian) "Авто ▾" else "Auto ▾"
                                } else {
                                    "${GroqAsrClient.languageLabel(groqLanguageOverride)} ▾"
                                }
                                StatusPill(
                                    label = langLabel,
                                    containerColor = if (showLanguagePicker || groqLanguageOverride != "auto") colors.accentSoft else colors.surface,
                                    contentColor = if (showLanguagePicker || groqLanguageOverride != "auto") colors.accent else colors.textSecondary,
                                    onClick = { showLanguagePicker = !showLanguagePicker }
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = hasCloudKey && !russianAudio && showLanguagePicker,
                            enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.fastTween()),
                            exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                                    .padding(Spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                for ((code, label) in QUICK_REVIEW_LANGUAGES) {
                                    val active = groqLanguageOverride == code
                                    StatusPill(
                                        label = if (code == "auto" && !russian) "Auto (all languages)" else label,
                                        containerColor = if (active) colors.accentSoft else colors.surfaceElevated,
                                        contentColor = if (active) colors.accent else colors.textSecondary,
                                        onClick = {
                                            groqLanguageOverride = code
                                            showLanguagePicker = false
                                        }
                                    )
                                }
                            }
                        }
                        if (!wholeRecording) {
                            VantaTonalButton(
                                label = if (russian) "Распознать локально" else "Recognize on device",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { recognize(null) }
                            )
                        }
                        if (!hasCloudKey) {
                            Text(
                                if (russian) "Для Whisper и GPT-OSS нужен ключ Groq." else "Whisper and GPT-OSS require a Groq key.",
                                style = VantaType.bodySmall,
                                color = colors.textSecondary
                            )
                            VantaTonalButton(label = strings.settings, onClick = onOpenSettings)
                        } else {
                            for (model in TranscriptReviewModel.entries.asReversed()) {
                                SheetOptionRow(
                                    selected = model.id == modelId,
                                    title = model.title,
                                    subtitle = when (model) {
                                        TranscriptReviewModel.Detailed ->
                                            if (russian) "Для сложного контекста и терминов" else "For difficult context and terminology"
                                        TranscriptReviewModel.Fast ->
                                            if (russian) "Быстрая проверка" else "For quick review"
                                    },
                                    onClick = {
                                        modelId = model.id
                                        onSelectModel(model)
                                    }
                                )
                            }
                            VantaTextField(
                                value = hint,
                                onValueChange = { hint = it.take(GroqTranscriptReviewer.MaxHintLength) },
                                modifier = Modifier.fillMaxWidth(),
                                label = if (russian) "Тема или термины (необязательно)" else "Topic or terms (optional)",
                                singleLine = false,
                                maxLines = 3
                            )
                            Text(
                                if (russian) "Whisper распознает аудио через Groq, затем GPT-OSS вычитает новый текст."
                                else "Whisper transcribes the audio through Groq, then GPT-OSS reviews the new text.",
                                style = VantaType.bodySmall,
                                color = colors.textSecondary
                            )
                            VantaPrimaryButton(
                                label = if (russian) "Распознать и проверить с ИИ" else "Transcribe and review with AI",
                                icon = Icons.Rounded.AutoFixHigh,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { recognize(TranscriptReviewModel.resolve(modelId)) }
                            )
                        }
                        error?.let { Text(it, color = colors.destructive, style = VantaType.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewPassage(label: String, text: String, action: (@Composable () -> Unit)? = null) {
    val colors = LocalStenografColors.current
    Row(
        Modifier.fillMaxWidth().vantaSurfaceCard(colors, radius = Radius.md).padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(label, color = colors.textSecondary, style = VantaType.sectionLabel)
            Text(text, color = colors.textPrimary, style = VantaType.body)
        }
        action?.invoke()
    }
}
