package com.stenograf.app.ui.screens

import android.content.ClipData
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.stenograf.app.asr.ModelDownloadPhase
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.audio.AudioPlayerController
import com.stenograf.app.audio.AudioTreatment
import com.stenograf.app.audio.RecordingAudioTreatment
import com.stenograf.app.audio.TreatedAudio
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.export.TranscriptExporter
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaProgressBanner
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.components.formatStorageSize
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun RecordingAudioToolsSheet(
    recording: RecordingEntity,
    models: ModelManager,
    positionMs: Long,
    russian: Boolean,
    onDownloadModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val colors = LocalStenografColors.current
    val player = remember { AudioPlayerController() }
    val playback by player.state.collectAsState()
    val downloads by models.downloadStates.collectAsState()
    var selected by remember {
        mutableStateOf(
            AudioTreatment.DEEP_FILTER_3.takeIf { models.isModelInstalled(it.modelId) } ?: AudioTreatment.QUICK_CLEANUP
        )
    }
    var choosingFilter by remember { mutableStateOf(false) }
    var requestedAudio by remember { mutableStateOf<File?>(null) }
    var previewResult by remember { mutableStateOf<TreatedAudio?>(null) }
    var exported by remember { mutableStateOf<List<File>>(emptyList()) }
    var running by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    var saved by remember { mutableStateOf(false) }

    val transfer = downloads[selected.modelId]?.takeUnless { it.phase == ModelDownloadPhase.IDLE }
    val installed = selected.isBuiltIn || models.isModelInstalled(selected.modelId)
    val guide = selected.guide(russian)

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(previewResult) {
        val files = previewResult?.let { it.tracks + listOfNotNull(it.originalPreview) }.orEmpty()
        onDispose { files.forEach { it.delete() } }
    }
    LaunchedEffect(requestedAudio) {
        requestedAudio?.let { player.load(it.absolutePath) }
    }
    LaunchedEffect(requestedAudio, playback.loadedPath, playback.isReady) {
        if (requestedAudio?.absolutePath == playback.loadedPath && playback.isReady) player.togglePlayPause()
    }

    fun stopAudio() {
        requestedAudio = null
        player.release()
    }

    fun runFilter(preview: Boolean) {
        if (running != null) return
        stopAudio()
        error = null
        progress = 0f
        stage = if (preview) {
            if (russian) "Готовим фрагмент для сравнения" else "Preparing a comparison sample"
        } else if (russian) "Обрабатываем аудио" else "Processing audio"
        val mode = selected
        running = scope.launch {
            try {
                val result = RecordingAudioTreatment.render(context, models, recording, mode, positionMs, preview) {
                    progress = it
                }
                if (preview) previewResult = result else exported = result.tracks
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("Vanta", "Unable to apply audio filter", failure)
                error = if (russian) "Не удалось применить фильтр. Проверьте, открывается ли запись."
                else "Could not apply the filter. Check that the recording plays."
            } catch (failure: LinkageError) {
                Log.w("Vanta", "Audio filtering runtime unavailable", failure)
                error = if (russian) "Фильтр недоступен на этом устройстве." else "The filter is unavailable on this device."
            } finally {
                running = null
            }
        }
    }

    val saveAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) {
            scope.launch {
                try {
                    TranscriptExporter.copyAudioFileToUri(context, file, uri)
                    error = null
                    saved = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("Vanta", "Unable to save treated audio", failure)
                    error = strings.exportError
                }
            }
        }
    }

    @Composable
    fun ListenButton(file: File, label: String) {
        val isPlaying = playback.loadedPath == file.absolutePath && playback.isPlaying
        VantaTonalButton(
            label = if (isPlaying) strings.pauseButton else label,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (requestedAudio != file) requestedAudio = file
                else if (playback.isReady) player.togglePlayPause()
                else if (playback.error != null) scope.launch { player.load(file.absolutePath) }
            }
        )
    }

    VantaBottomSheet(
        onDismissRequest = {
            running?.cancel()
            stopAudio()
            onDismiss()
        },
        title = if (russian) "Фильтры аудио" else "Audio filters",
        subtitle = if (russian) "Обработка создаёт копию. Исходная запись сохраняется."
        else "Filtering creates a copy and preserves the original recording."
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (running != null) {
                VantaProgressBanner(message = stage, progress = progress.takeIf { it > 0f }, onCancel = { running?.cancel() })
            } else if (choosingFilter) {
                AudioTreatment.entries.forEach { mode ->
                    val choice = mode.guide(russian)
                    val ready = mode.isBuiltIn || models.isModelInstalled(mode.modelId)
                    val availability = when {
                        mode.isBuiltIn -> if (russian) "Встроено" else "Built in"
                        ready -> if (russian) "Скачано" else "Downloaded"
                        else -> {
                            val size = formatStorageSize(TinyAsrCatalog.findModel(mode.modelId).expectedTotalBytes)
                            "${strings.modelDownloadButton} · $size"
                        }
                    }
                    SheetOptionRow(
                        selected = selected == mode,
                        title = choice.title,
                        subtitle = "$availability · ${choice.model}",
                        onClick = {
                            if (mode != selected) {
                                stopAudio()
                                previewResult = null
                                selected = mode
                                error = null
                            }
                            choosingFilter = false
                        }
                    )
                }
            } else if (exported.isEmpty()) {
                Text(guide.title, style = VantaType.cardTitle, color = colors.textPrimary)
                Text(guide.description, style = VantaType.bodySmall, color = colors.textSecondary)
                TextButton(onClick = { stopAudio(); choosingFilter = true }) {
                    Text(if (russian) "Выбрать другой фильтр" else "Choose another filter", color = colors.accent)
                }

                when {
                    transfer != null && (!installed || transfer.phase == ModelDownloadPhase.VERIFYING) -> {
                        Text(guide.model, style = VantaType.rowLabel, color = colors.textPrimary)
                        ModelTransferControls(
                            transfer, onResume = { onDownloadModel(selected.modelId) },
                            onPause = { models.pauseDownload(selected.modelId) },
                            onCancel = { models.cancelDownload(selected.modelId) }
                        )
                    }
                    !installed -> {
                        Text(
                            if (russian) "Требуется ${guide.model}" else "Requires ${guide.model}",
                            style = VantaType.bodySmall, color = colors.textSecondary
                        )
                        val bytes = TinyAsrCatalog.findModel(selected.modelId).expectedTotalBytes
                        VantaPrimaryButton(
                            label = "${strings.modelDownloadButton} · ${formatStorageSize(bytes)}",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onDownloadModel(selected.modelId) }
                        )
                    }
                    else -> {
                        val sample = previewResult
                        if (sample == null) {
                            VantaTonalButton(
                                label = if (russian) "Сравнить на фрагменте" else "Compare a sample",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { runFilter(preview = true) }
                            )
                        } else {
                            Text(
                                if (russian) "Один фрагмент до и после" else "The same sample before and after",
                                style = VantaType.rowLabel, color = colors.textPrimary
                            )
                            sample.originalPreview?.let { ListenButton(it, if (russian) "До обработки" else "Before filtering") }
                            sample.tracks.forEachIndexed { index, file ->
                                ListenButton(file, if (sample.tracks.size > 1) voiceLabel(index, russian)
                                    else if (russian) "После обработки" else "After filtering")
                            }
                        }
                        VantaPrimaryButton(
                            label = when {
                                selected.isSeparation -> if (russian) "Разделить фрагмент" else "Split the sample"
                                russian -> "Применить ко всей записи"
                                else -> "Apply to the whole recording"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { runFilter(preview = false) }
                        )
                    }
                }
            } else {
                Text(
                    if (exported.size == 1) {
                        if (russian) "Обработанная копия готова" else "The filtered copy is ready"
                    } else if (russian) "Две дорожки готовы" else "Two tracks are ready",
                    style = VantaType.rowLabel,
                    color = colors.textPrimary
                )
                exported.forEachIndexed { index, file ->
                    ListenButton(
                        file, if (exported.size > 1) voiceLabel(index, russian)
                        else if (russian) "Прослушать копию" else "Listen to the copy"
                    )
                    TextButton(onClick = {
                        saved = false
                        pendingSave = file
                        val suffix = if (exported.size > 1) "voice-${index + 1}" else selected.modelId
                        saveAudio.launch("${TranscriptExporter.safeBaseName(recording.title)}-$suffix.wav")
                    }) {
                        Text(if (exported.size == 1) strings.saveFileAs else if (russian) "Сохранить голос ${index + 1}"
                            else "Save voice ${index + 1}")
                    }
                }
                VantaPrimaryButton(label = strings.shareAudio, modifier = Modifier.fillMaxWidth(), onClick = {
                    try {
                        val uris = ArrayList(exported.map {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                        })
                        val send = if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        }
                        send.type = "audio/wav"
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri("WAV", uris.first()).apply {
                            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                        }
                        context.startActivity(Intent.createChooser(send, strings.shareAudio))
                    } catch (failure: Exception) {
                        Log.w("Vanta", "Unable to share treated audio", failure)
                        error = strings.exportError
                    }
                })
                TextButton(onClick = {
                    stopAudio()
                    exported = emptyList()
                    previewResult = null
                    choosingFilter = true
                    saved = false
                    error = null
                }) { Text(if (russian) "Выбрать другой фильтр" else "Choose another filter") }
                if (saved) Text(strings.exportSavedSuccess, color = colors.success, style = VantaType.bodySmall)
            }
            (error ?: playback.error)?.let { message ->
                Text(message, color = colors.destructive, style = VantaType.bodySmall)
            }
        }
    }
}

private fun voiceLabel(index: Int, russian: Boolean): String =
    if (russian) "Голос ${index + 1}" else "Voice ${index + 1}"
