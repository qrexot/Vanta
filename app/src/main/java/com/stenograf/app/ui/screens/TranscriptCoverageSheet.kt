package com.stenograf.app.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.SpeechGap
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.CoverageAudio
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaLinearProgressTrack
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TranscriptCoverageSheet(
    recording: RecordingEntity,
    paragraphs: List<ParagraphEntity>,
    models: ModelManager,
    russian: Boolean,
    playerReady: Boolean,
    onPreview: (SpeechGap) -> Unit,
    onStopPreview: () -> Unit,
    onInsert: suspend (SpeechGap, List<TranscriptWord>) -> Boolean,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val colors = LocalStenografColors.current
    val snapshot = remember(recording.id) { paragraphs }
    var source by remember { mutableStateOf<CoverageAudio.Source?>(null) }
    var gaps by remember { mutableStateOf<List<SpeechGap>?>(null) }
    var selected by remember { mutableStateOf<SpeechGap?>(null) }
    var words by remember { mutableStateOf<List<TranscriptWord>?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var insertedCount by remember { mutableStateOf(0) }
    val gigaV3Installed = models.isModelInstalled(TinyAsrCatalog.GIGAAM)
    val gigaV4Installed = models.isModelInstalled(TinyAsrCatalog.GIGAAM_V4)
    val gigaInstalled = gigaV3Installed || gigaV4Installed

    DisposableEffect(Unit) { onDispose { onStopPreview() } }
    LaunchedEffect(recording.id) {
        try {
            CoverageAudio.withSource(context, recording, { progress = it }) { prepared ->
                val missing = CoverageAudio.scan(prepared, models, snapshot) { progress = it }
                withContext(Dispatchers.Main) {
                    source = prepared
                    gaps = missing
                    awaitCancellation()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("Vanta", "Transcript coverage scan failed", failure)
            error = if (russian) "Не удалось проверить аудио." else "Could not check the audio."
        } catch (failure: LinkageError) {
            Log.w("Vanta", "Silero VAD unavailable", failure)
            error = if (russian) "Проверка речи недоступна." else "Speech detection is unavailable."
        }
    }

    fun timeLabel(gap: SpeechGap): String =
        AsrTextFormatter.formatDurationMs(gap.startMs) + "–" +
            AsrTextFormatter.formatDurationMs((gap.endMs + 999L) / 1_000L * 1_000L)

    val gap = selected
    val missing = gaps
    VantaBottomSheet(
        onDismissRequest = onDismiss,
        title = if (russian) "Проверить полноту" else "Check transcript coverage",
        headerIcon = Icons.Rounded.Search
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (missing == null && error == null) {
                item {
                    Text(
                        if (russian) "Ищем речь без текста…" else "Checking for speech without text…",
                        style = VantaType.bodySmall
                    )
                }
                item { VantaLinearProgressTrack(progress = progress.takeIf { it > 0f }) }
            } else if (missing != null && gap == null) {
                if (recording.hasUnprocessedAudio) {
                    item {
                        Text(
                            if (russian) "Проверена обработанная часть. Для остатка используйте «Дораспознать»."
                            else "Checked the processed portion. Use Resume transcription for the rest.",
                            color = colors.textSecondary,
                            style = VantaType.bodySmall
                        )
                    }
                }
                if (insertedCount > 0) {
                    item {
                        Text(
                            if (russian) "Добавлено фрагментов: $insertedCount" else "Fragments added: $insertedCount",
                            color = colors.success,
                            style = VantaType.bodySmall
                        )
                    }
                }
                if (missing.isEmpty()) {
                    item {
                        Text(
                            if (russian) "Подозрительных пропусков не найдено." else "No possible gaps found.",
                            style = VantaType.bodySmall
                        )
                    }
                }
                items(missing, key = { it.startMs }) { candidate ->
                    SheetOptionRow(
                        selected = false,
                        title = timeLabel(candidate),
                        subtitle = if (russian) "Возможно, здесь есть речь без текста"
                            else "Possible speech without transcript text",
                        onClick = {
                            selected = candidate
                            words = null
                            error = null
                        }
                    )
                }
            } else if (gap != null) {
                item {
                    Text(timeLabel(gap), style = VantaType.cardTitle, color = colors.textPrimary)
                }
                item {
                    VantaTonalButton(
                        label = if (russian) "Прослушать отрывок" else "Play excerpt",
                        icon = Icons.Rounded.PlayArrow,
                        enabled = playerReady,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPreview(gap) }
                    )
                }
                val recognized = words
                if (recognized != null) {
                    item {
                        Text(
                            if (recognized.isEmpty()) {
                                if (russian) "GigaAM не нашла разборчивой речи." else "GigaAM found no clear speech."
                            } else AsrTextFormatter.joinWords(recognized),
                            style = VantaType.bodySmall,
                            color = colors.textPrimary
                        )
                    }
                }
                item {
                    when {
                        busy -> VantaLinearProgressTrack(progress = null)
                        recognized.isNullOrEmpty() -> VantaPrimaryButton(
                            label = when {
                                gigaV3Installed -> if (russian) "Распознать через GigaAM · русский" else "Transcribe with GigaAM · Russian"
                                gigaV4Installed -> if (russian) "Распознать через GigaAM v4" else "Transcribe with GigaAM v4"
                                else -> if (russian) "Скачать GigaAM в каталоге" else "Get GigaAM from the catalog"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (!gigaInstalled) {
                                    onOpenModels()
                                    return@VantaPrimaryButton
                                }
                                val prepared = source ?: return@VantaPrimaryButton
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        words = CoverageAudio.recognize(context, models, prepared, gap)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        Log.w("Vanta", "Gap recognition failed", failure)
                                        error = if (russian) "Не удалось распознать отрывок."
                                            else "Could not transcribe the excerpt."
                                    } catch (failure: LinkageError) {
                                        Log.w("Vanta", "GigaAM unavailable", failure)
                                        error = if (russian) "GigaAM недоступна." else "GigaAM is unavailable."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        )
                        else -> VantaPrimaryButton(
                            label = if (russian) "Вставить в расшифровку" else "Insert into transcript",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        if (onInsert(gap, recognized)) {
                                            onStopPreview()
                                            gaps = missing.orEmpty().filterNot { it == gap }
                                            selected = null
                                            words = null
                                            insertedCount++
                                        } else {
                                            error = if (russian) {
                                                "Вставить без наложения на текущий текст не получилось. Проверьте запись заново."
                                            } else {
                                                "The text could not be inserted safely. Run the check again."
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        Log.w("Vanta", "Gap insertion failed", failure)
                                        error = if (russian) "Не удалось сохранить вставку." else "Could not save the insertion."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        )
                    }
                }
                item {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            onStopPreview()
                            selected = null
                            words = null
                            error = null
                        }
                    ) { Text(if (russian) "К списку отрывков" else "Back to excerpts") }
                }
            }
            error?.let { notice ->
                item { Text(notice, color = colors.destructive, style = VantaType.bodySmall) }
            }
            item {
                TextButton(onClick = onDismiss) {
                    Text(if (missing == null && error == null) strings.cancel else if (russian) "Закрыть" else "Close")
                }
            }
        }
    }
}
