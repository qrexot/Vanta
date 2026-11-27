package com.stenograf.app.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.GroqTranscriptReviewer
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.asr.SpeakerAttributionSession
import com.stenograf.app.asr.LocalSpeakerLabeler
import com.stenograf.app.asr.SpeechSegment
import com.stenograf.app.asr.SpeakerTimeline
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.ModelLease
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.ParagraphAccumulator
import com.stenograf.app.asr.TinyAsrNative
import com.stenograf.app.audio.AudioDecoder
import com.stenograf.app.audio.SpeechAnalysisStats
import com.stenograf.app.audio.SpeechNormalizer
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.repo.RecordingRepository
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class ProcessingStatus(
    val progress: Float,
    val message: String,
    val liveSnippet: String? = null,
    val startedAtMs: Long = System.currentTimeMillis()
)

/**
 * Full-context offline ASR processor for:
 * 1) Re-transcribing saved recordings with TinyASR in bounded-memory windows.
 * 2) Completing unprocessed tail audio when live ASR fell behind.
 * 3) Importing & transcribing external audio/video files.
 */
class ReTranscribeWorker(
    private val context: Context,
    private val repository: RecordingRepository,
    private val modelManager: ModelManager
) {
    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val BYTES_PER_SEC = SAMPLE_RATE * 2L
        private const val WINDOW_SECONDS = 30L
        private const val STREAM_CHUNK_SECONDS = 20L
        private const val OVERLAP_SECONDS = 2L
        private const val SAVED_SPEAKER_WINDOW_SECONDS = 60
    }

    suspend fun reTranscribeRecording(
        recording: RecordingEntity,
        presetId: String,
        requestedThreads: Int,
        beam: Int,
        autoParagraph: Boolean,
        onlyUnprocessedTail: Boolean = false,
        distantSpeechBoost: Boolean = true,
        speakerDiarization: Boolean = true,
        onProgress: (ProcessingStatus) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempPcm = mutableListOf<File>()
        var modelLease: ModelLease? = null
        var speakerAudio: RandomAccessFile? = null
        var speakerWarning = false
        val strings = AppLocaleManager.currentStrings()
        val startedMs = System.currentTimeMillis()
        val speakers = if (speakerDiarization) {
            SpeakerAttributionSession(context.assets, speakerLabel()) { speakerWarning = true }
        } else null
        try {
            val preset = TinyAsrCatalog.findPreset(presetId)
            val bigCores = TinyAsrNative.nativePinToBigCores()
            val threads = if (requestedThreads > 0) requestedThreads else bigCores.coerceIn(2, 4)

            onProgress(ProcessingStatus(0.02f, "${strings.loadingModelProgress} (${preset.modelId.uppercase()})", startedAtMs = startedMs))
            val lease = modelManager.acquireModelLease(preset.modelId, threads)
            modelLease = lease

            val (pcmFile, startByteOffset, totalBytes) = preparePcm16kSource(
                recording = recording,
                onlyTail = onlyUnprocessedTail,
                tempCollector = tempPcm,
                onDecodeProgress = { p ->
                    onProgress(ProcessingStatus(p * 0.2f, strings.decodingAudio, startedAtMs = startedMs))
                }
            )

            if (totalBytes <= startByteOffset + 3200L) {
                return@withContext Result.success(Unit)
            }

            val existingParagraphs = if (onlyUnprocessedTail) {
                repository.getParagraphs(recording.id)
            } else {
                emptyList()
            }

            val accumulator = ParagraphAccumulator(autoParagraph = autoParagraph)
            val resultParagraphs = existingParagraphs.toMutableList()
            var orderIdx = (resultParagraphs.maxOfOrNull { it.orderIndex } ?: -1) + 1
            if (onlyUnprocessedTail && resultParagraphs.isNotEmpty()) {
                val last = resultParagraphs.last()
                accumulator.restoreCurrent(
                    AsrJsonParser.parseWords(last.wordsJson),
                    last.isBookmark,
                    last.bookmarkLabel,
                    last.speaker
                )
            }

            val pcmBase = if (recording.audioFormatEnum == AudioFormat.WAV_16K &&
                pcmFile.absolutePath == recording.audioPath
            ) WavHelper.HEADER_SIZE.toLong() else 0L
            val availableAudioBytes = (totalBytes - pcmBase) and -2L
            val worker = coroutineContext
            val labeler = speakers?.let { session ->
                val audio = RandomAccessFile(pcmFile, "r")
                speakerAudio = audio
                if (onlyUnprocessedTail) {
                    session.seedFromTranscript(
                        audio, pcmBase, recording.processedAsrBytes.coerceIn(0L, availableAudioBytes),
                        existingParagraphs, worker::ensureActive
                    )
                }
                LocalSpeakerLabeler((startByteOffset - pcmBase).coerceAtLeast(0L), windowSeconds = SAVED_SPEAKER_WINDOW_SECONDS) { start, size ->
                    worker.ensureActive()
                    val pcm = ByteArray(size)
                    audio.seek(pcmBase + start)
                    audio.readFully(pcm)
                    session.analyze(pcm, start) { !worker.isActive }
                }
            }
            var latestSnippet: String? = null
            fun appendSpeech(speech: SpeechSegment) {
                val update = accumulator.acceptCommit(speech.words, speech.speaker)
                update.updatedLastParagraph?.let { draft ->
                    latestSnippet = draft.text.takeLast(96)
                    if (resultParagraphs.isNotEmpty()) {
                        val last = resultParagraphs.last()
                        resultParagraphs[resultParagraphs.lastIndex] = last.copy(
                            endSec = draft.endSec,
                            text = draft.text,
                            wordsJson = AsrJsonParser.encodeWords(draft.words),
                            speaker = draft.speaker
                        )
                    }
                }
                update.newParagraph?.let { draft ->
                    latestSnippet = draft.text.takeLast(96)
                    resultParagraphs.add(
                        ParagraphEntity(
                            recordingId = recording.id,
                            orderIndex = orderIdx++,
                            startSec = draft.startSec,
                            endSec = draft.endSec,
                            text = draft.text,
                            wordsJson = AsrJsonParser.encodeWords(draft.words),
                            isBookmark = draft.isBookmark,
                            bookmarkLabel = draft.bookmarkLabel,
                            speaker = draft.speaker
                        )
                    )
                }
            }

            var transcriptEndSec = existingParagraphs.lastOrNull()?.endSec ?: 0f
            var detectedLang = preset.startLang
            var cursorBytes = startByteOffset
            val windowBytes = WINDOW_SECONDS * BYTES_PER_SEC
            val overlapBytes = OVERLAP_SECONDS * BYTES_PER_SEC
            val totalSpanBytes = (totalBytes - startByteOffset).coerceAtLeast(1L)
            var lastDbFlushMs = 0L
            var dirtyForDb = false
            var lastProgressMs = 0L

            suspend fun flushTranscript() {
                val wordCount = resultParagraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
                val preview = resultParagraphs.joinToString(" ") { it.text }.take(220)
                repository.replaceTranscript(
                    recordingId = recording.id,
                    paragraphs = resultParagraphs.toList(),
                    wordCount = wordCount,
                    previewText = preview,
                    detectedLang = detectedLang
                )
            }

            val speechStats = if (distantSpeechBoost) {
                SpeechNormalizer.analyzePcm16File(pcmFile, startByteOffset, totalBytes, worker::ensureActive)
            } else null
            val normWindowFile = if (distantSpeechBoost) {
                File(context.cacheDir, "retrans_norm_${recording.id}_${System.currentTimeMillis()}.pcm").also {
                    tempPcm.add(it)
                }
            } else null

            while (cursorBytes < totalBytes) {
                coroutineContext.ensureActive()
                val rawEnd = (cursorBytes + windowBytes).coerceAtMost(totalBytes)
                val alignedEnd = if (rawEnd < totalBytes) {
                    findQuietCutByte(pcmFile, rawEnd, searchBackBytes = 8L * BYTES_PER_SEC)
                } else {
                    rawEnd
                }
                val coveredUntil = transcriptEndSec
                val chunkBytes = (alignedEnd - cursorBytes).coerceAtLeast(2L)
                val sampleCount = chunkBytes / 2L
                val windowStartSec = (cursorBytes - pcmBase).toFloat() / BYTES_PER_SEC

                val normBytes = if (speechStats != null && normWindowFile != null) {
                    SpeechNormalizer.writeNormalizedPcm16Window(
                        srcPcm = pcmFile,
                        startByte = cursorBytes,
                        endByte = alignedEnd,
                        dstPcm = normWindowFile,
                        stats = speechStats,
                        onCheckCancelled = worker::ensureActive
                    )
                } else 0L

                val fileHandle = TinyAsrNative.nativeFileCreateFromPcm16(
                    lease.handle,
                    if (normBytes >= 2L) normWindowFile!!.absolutePath else pcmFile.absolutePath,
                    if (normBytes >= 2L) 0L else cursorBytes,
                    if (normBytes >= 2L) (normBytes / 2L) else sampleCount,
                    preset.langsCsv,
                    beam
                )
                if (fileHandle == 0L) {
                    throw IllegalStateException(strings.asrSessionError)
                }

                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val wordsJson = TinyAsrNative.nativeFileNext(fileHandle) ?: break
                        val words = AsrJsonParser.parseWords(wordsJson).map { w ->
                            w.copy(start = w.start + windowStartSec, end = w.end + windowStartSec)
                        }.dropWhile { word -> (word.start + word.end) / 2f < coveredUntil }
                        if (words.isNotEmpty()) {
                            transcriptEndSec = maxOf(transcriptEndSec, words.last().end)
                            if (labeler == null) {
                                appendSpeech(SpeechSegment.fromWords(words))
                            } else {
                                labeler.offer(words)
                                labeler.drain(availableAudioBytes, finished = true, emit = ::appendSpeech)
                            }
                            dirtyForDb = true
                        }
                        TinyAsrNative.nativeFileLang(fileHandle)?.takeIf { it.isNotBlank() }?.let {
                            detectedLang = it
                        }
                        val nowMs = System.currentTimeMillis()
                        if (dirtyForDb && nowMs - lastDbFlushMs >= 900L) {
                            coroutineContext.ensureActive()
                            flushTranscript()
                            dirtyForDb = false
                            lastDbFlushMs = nowMs
                        }
                        val localProg = TinyAsrNative.nativeFileProgress(fileHandle).coerceIn(0f, 1f)
                        val doneBytes = (cursorBytes - startByteOffset) + (chunkBytes * localProg)
                        val overall = (0.2f + 0.78f * (doneBytes / totalSpanBytes.toFloat())).coerceIn(0.2f, 0.99f)
                        if (nowMs - lastProgressMs >= 80L || localProg >= 0.99f) {
                            lastProgressMs = nowMs
                            onProgress(
                                ProcessingStatus(
                                    progress = overall,
                                    message = if (speakerWarning) strings.speakerDiarizationUnavailable else strings.recognizingProgress,
                                    liveSnippet = latestSnippet,
                                    startedAtMs = startedMs
                                )
                            )
                        }
                    }
                } finally {
                    TinyAsrNative.nativeFileFree(fileHandle)
                }

                cursorBytes = if (alignedEnd >= totalBytes) totalBytes else (alignedEnd - overlapBytes).coerceAtLeast(cursorBytes + BYTES_PER_SEC)
                if (alignedEnd >= totalBytes) break
            }

            coroutineContext.ensureActive()
            flushTranscript()
            val latest = repository.getRecording(recording.id) ?: recording
            var finalSpoolPath = latest.asrSpoolPath
            if (finalSpoolPath.isNotBlank() && finalSpoolPath != latest.audioPath) {
                File(finalSpoolPath).delete()
                finalSpoolPath = ""
            }
            repository.updateRecording(
                latest.copy(
                    modelId = preset.modelId,
                    languagePresetId = preset.id,
                    detectedLanguage = detectedLang,
                    asrSpoolPath = finalSpoolPath,
                    processedAsrBytes = latest.totalAsrBytes
                )
            )
            onProgress(ProcessingStatus(1f, strings.done, startedAtMs = startedMs))
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { speakerAudio?.close() }
            runCatching { speakers?.close() }
            withContext(NonCancellable) { modelManager.releaseModelLease(modelLease) }
            tempPcm.forEach { it.delete() }
        }
    }

    internal data class AudioReview(
        val proposal: GroqTranscriptReviewer.Review,
        val paragraphs: List<ParagraphEntity> = emptyList(),
        val processedAudioBytes: Long? = null,
        val detectedLanguage: String? = null,
        val recognizedWords: List<com.stenograf.app.asr.TranscriptWord>? = null
    )

    internal suspend fun recognizeBlockLocally(
        recording: RecordingEntity,
        paragraph: ParagraphEntity,
        presetId: String,
        onProgress: (Float) -> Unit
    ): AudioReview = withContext(Dispatchers.IO) {
        val preset = TinyAsrCatalog.findPreset(presetId)
        val lease = modelManager.acquireModelLease(preset.modelId, 2)
        try {
            val segments = readAudioWindows(recording, paragraph, onProgress) { source, offset, count ->
                val handle = TinyAsrNative.nativeFileCreateFromPcm16(
                    lease.handle, source.absolutePath, offset, count / 2L, preset.langsCsv, 4
                )
                check(handle != 0L) { AppLocaleManager.currentStrings().asrSessionError }
                val words = buildList {
                    try {
                        while (true) {
                            coroutineContext.ensureActive()
                            val result = TinyAsrNative.nativeFileNext(handle) ?: break
                            addAll(AsrJsonParser.parseWords(result))
                        }
                    } finally {
                        TinyAsrNative.nativeFileFree(handle)
                    }
                }
                if (words.isEmpty()) emptyList() else listOf(SpeechSegment.fromWords(words))
            }
            check(segments.isNotEmpty()) { noSpeechMessage() }
            val text = segments.joinToString(" ") { it.text }
            val allWords = segments.flatMap { it.words }
            AudioReview(
                proposal = GroqTranscriptReviewer.Review(text, emptyList()),
                recognizedWords = allWords
            )
        } finally {
            withContext(NonCancellable) { modelManager.releaseModelLease(lease) }
        }
    }

    internal suspend fun reviewAudio(
        recording: RecordingEntity,
        paragraph: ParagraphEntity?,
        apiKey: String,
        whisperModelId: String,
        reviewModel: TranscriptReviewModel,
        languageMode: String,
        previous: String,
        following: String,
        hint: String,
        onProgress: (Float) -> Unit
    ): AudioReview = withContext(Dispatchers.IO) {
        check(paragraph != null || recording.statusEnum !in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)) {
            if (AppLocaleManager.currentLanguage.value.code == "ru") "Сначала завершите запись."
            else "Finish recording before reviewing it in full."
        }
        var processedAudioBytes = 0L
        val detectedLangCounts = linkedMapOf<String, Int>()
        val recognized = readAudioWindows(recording, paragraph, { onProgress(it * 0.68f) }, { processedAudioBytes = it }) { source, offset, count ->
            val pcm = ByteArray(count)
            RandomAccessFile(source, "r").use { input ->
                input.seek(offset)
                input.readFully(pcm)
            }
            val transcript = GroqAsrClient.transcribeChunk(
                apiKey, whisperModelId, pcm, languageMode = languageMode
            )
            if (transcript.language.isNotBlank()) {
                detectedLangCounts[transcript.language] = (detectedLangCounts[transcript.language] ?: 0) + 1
            }
            val segments = transcript.segments
            if (segments.isEmpty()) emptyList() else listOf(
                SpeechSegment(
                    segments.minOf { it.startSec },
                    segments.maxOf { it.endSec },
                    segments.joinToString(" ") { it.text },
                    segments.flatMap { it.words }
                )
            )
        }
        check(recognized.isNotEmpty()) { noSpeechMessage() }
        val replacements = ArrayList<ParagraphEntity>(recognized.size)
        val uncertain = linkedSetOf<String>()
        for ((index, segment) in recognized.withIndex()) {
            coroutineContext.ensureActive()
            val proofread = GroqTranscriptReviewer.review(
                apiKey, reviewModel, segment.text,
                recognized.getOrNull(index - 1)?.text ?: previous,
                recognized.getOrNull(index + 1)?.text ?: following,
                hint,
                languageMode = languageMode
            )
            uncertain.addAll(proofread.uncertain)
            val newParagraph = segment.toParagraph(recording.id, index, 0f)
            replacements += repository.paragraphWithEditedText(
                newParagraph, proofread.correctedText, preserveWordTimings = false
            )
            onProgress(0.68f + 0.32f * (index + 1) / recognized.size)
        }

        val dominantLang = detectedLangCounts.maxByOrNull { it.value }?.key
        AudioReview(
            GroqTranscriptReviewer.Review(
                replacements.joinToString("\n\n") { it.text }, uncertain.take(20)
            ),
            if (paragraph == null) replacements else emptyList(),
            processedAudioBytes,
            dominantLang
        )
    }

    private suspend fun readAudioWindows(
        recording: RecordingEntity,
        paragraph: ParagraphEntity?,
        onProgress: (Float) -> Unit,
        onProcessed: (Long) -> Unit = {},
        decode: suspend (File, Long, Int) -> List<SpeechSegment>
    ): List<SpeechSegment> {
        val temporaryPcm = mutableListOf<File>()
        try {
            val spool = File(recording.asrSpoolPath)
            val live = recording.statusEnum in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)
            val (source, base, limit) = if (live && spool.isFile) Triple(spool, 0L, spool.length())
            else preparePcm16kSource(recording, false, temporaryPcm) { onProgress(it * 0.15f) }
            val maxBytes = (limit - base).coerceAtLeast(0L) and -2L
            val from = if (paragraph == null) 0L else
                ((paragraph.startSec.toDouble() * BYTES_PER_SEC).toLong().coerceIn(0L, maxBytes) and -2L)
            val to = if (paragraph == null) maxBytes else
                ((paragraph.endSec.toDouble() * BYTES_PER_SEC).toLong().coerceIn(from, maxBytes) and -2L)
            onProcessed(to - from)
            check(to - from >= 1600L) { noSpeechMessage() }
            val result = mutableListOf<SpeechSegment>()
            var cursor = from
            while (cursor < to) {
                coroutineContext.ensureActive()
                val maximum = minOf(to, cursor + STREAM_CHUNK_SECONDS * BYTES_PER_SEC)
                val end = if (maximum == to) to else
                    (findQuietCutByte(source, base + maximum, 2 * BYTES_PER_SEC) - base)
                        .coerceIn(cursor + BYTES_PER_SEC, maximum) and -2L
                val offset = cursor.toFloat() / BYTES_PER_SEC
                result += decode(source, base + cursor, (end - cursor).toInt()).map { part ->
                    part.copy(
                        startSec = part.startSec + offset,
                        endSec = part.endSec + offset,
                        words = part.words.map { it.copy(start = it.start + offset, end = it.end + offset) }
                    )
                }
                cursor = end
                onProgress(0.15f + 0.85f * (cursor - from).toFloat() / (to - from))
            }
            return result
        } finally {
            temporaryPcm.forEach { it.delete() }
        }
    }

    private fun noSpeechMessage(): String =
        if (AppLocaleManager.currentLanguage.value.code == "ru") "В выбранном аудио речь не распознана."
        else "No speech was recognized in the selected audio."

    suspend fun reTranscribeWithGroq(
        recording: RecordingEntity,
        apiKey: String,
        modelId: String,
        languageMode: String = GroqAsrClient.DEFAULT_LANGUAGE_MODE,
        speakerDiarization: Boolean,
        onlyUnprocessedTail: Boolean = false,
        onProgress: (ProcessingStatus) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val startedMs = System.currentTimeMillis()
        val temporaryPcm = mutableListOf<File>()
        var speakerWarning = false
        val speakers = if (speakerDiarization) {
            SpeakerAttributionSession(context.assets, speakerLabel()) { speakerWarning = true }
        } else null
        val paragraphs = mutableListOf<ParagraphEntity>()
        var detectedLang = recording.detectedLanguage
        var cursor = 0L
        var initialCursor = 0L

        suspend fun saveTranscript(processedBytes: Long) {
            val wordCount = paragraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
            val preview = paragraphs.asSequence().map { it.text }.take(20).joinToString(" ").take(220)
            repository.replaceTranscript(
                recordingId = recording.id,
                paragraphs = paragraphs,
                wordCount = wordCount,
                previewText = preview,
                detectedLang = detectedLang,
                modelId = modelId,
                processedAsrBytes = processedBytes
            )
        }

        try {
            onProgress(ProcessingStatus(0.02f, strings.decodingAudio, startedAtMs = startedMs))
            val (source, start, end) = preparePcm16kSource(recording, false, temporaryPcm) { fraction ->
                onProgress(ProcessingStatus(0.02f + 0.16f * fraction, strings.decodingAudio, startedAtMs = startedMs))
            }
            val length = (end - start) and -2L
            if (length <= 0L) throw IllegalStateException(strings.audioFileNotFound)
            val worker = coroutineContext
            if (onlyUnprocessedTail) {
                initialCursor = (recording.processedAsrBytes.coerceIn(0L, length)) and -2L
                cursor = initialCursor
                paragraphs.addAll(repository.getParagraphs(recording.id))
            }
            var nextOrder = (paragraphs.maxOfOrNull { it.orderIndex } ?: -1) + 1
            RandomAccessFile(source, "r").use { input ->
                if (onlyUnprocessedTail) {
                    speakers?.seedFromTranscript(input, start, initialCursor, paragraphs, worker::ensureActive)
                }
                input.seek(start + cursor)
                val speakerWindowBytes = SAVED_SPEAKER_WINDOW_SECONDS * BYTES_PER_SEC
                var speakerWindowStart = -1L
                var timeline = SpeakerTimeline.Empty
                while (cursor < length) {
                    worker.ensureActive()
                    val pcm = ByteArray(minOf(length - cursor, STREAM_CHUNK_SECONDS * BYTES_PER_SEC).toInt())
                    input.readFully(pcm)
                    val transcript = GroqAsrClient.transcribeChunk(apiKey, modelId, pcm, languageMode = languageMode)
                    if (transcript.language.isNotBlank()) detectedLang = transcript.language
                    val segments = if (speakers != null && transcript.segments.isNotEmpty()) {
                        val windowStart = initialCursor + (cursor - initialCursor) / speakerWindowBytes * speakerWindowBytes
                        if (speakerWindowStart != windowStart) {
                            val speakerPcm = ByteArray(minOf(length - windowStart, speakerWindowBytes).toInt())
                            input.seek(start + windowStart)
                            input.readFully(speakerPcm)
                            timeline = speakers.analyze(speakerPcm, windowStart) { !worker.isActive }
                            speakerWindowStart = windowStart
                            input.seek(start + cursor + pcm.size)
                        }
                        timeline.assign(transcript.segments, (speakerWindowStart - cursor).toFloat() / BYTES_PER_SEC)
                    } else transcript.segments
                    val offset = cursor.toFloat() / BYTES_PER_SEC
                    val batch = appendSpeechParagraphs(
                        paragraphs.lastOrNull(),
                        segments.map { it.toParagraph(recording.id, 0, offset) },
                        nextOrder
                    )
                    batch.updatedLast?.let { paragraphs[paragraphs.lastIndex] = it }
                    paragraphs.addAll(batch.newParagraphs)
                    nextOrder += batch.newParagraphs.size
                    cursor += pcm.size
                    worker.ensureActive()
                    saveTranscript(cursor)
                    onProgress(
                        ProcessingStatus(
                            0.18f + 0.81f * (cursor.toFloat() / length),
                            if (speakerWarning) strings.speakerDiarizationUnavailable else strings.recognizingProgress,
                            paragraphs.lastOrNull()?.text?.takeLast(96),
                            startedMs
                        )
                    )
                }
            }

            worker.ensureActive()
            saveTranscript(maxOf(length, recording.totalAsrBytes))
            onProgress(ProcessingStatus(1f, strings.done, startedAtMs = startedMs))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cursor > initialCursor && (onlyUnprocessedTail || !recording.hasTranscript)) {
                try {
                    saveTranscript(cursor)
                } catch (saveError: CancellationException) {
                    throw saveError
                } catch (saveError: Exception) {
                    e.addSuppressed(saveError)
                }
            }
            Result.failure(e)
        } finally {
            runCatching { speakers?.close() }
            temporaryPcm.forEach { it.delete() }
        }
    }

    /**
     * Copies the imported audio file into app storage in milliseconds and creates the DB entry
     * so the player screen can open immediately before streaming decode + transcription starts.
     */
    suspend fun prepareImportedRecording(
        uri: Uri,
        presetId: String,
        folderId: Long? = null,
        modelId: String
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val displayName = queryDisplayName(uri)
        val ext = displayName?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "m4a"
        val destAudio = File(repository.recordingsDir, "imp_$stamp.$ext")
        try {
            val preset = TinyAsrCatalog.findPreset(presetId)
            val title = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "${strings.importedPrefix} $stamp"

            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(strings.audioFileNotFound)
            input.use { src ->
                destAudio.outputStream().buffered(64 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                }
            }
            coroutineContext.ensureActive()

            val durationMs = runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(destAudio.absolutePath)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    mmr.release()
                }
            }.getOrDefault(0L)

            val format = if (ext == "wav") AudioFormat.WAV_48K else AudioFormat.M4A_48K
            val rec = repository.createImportedRecording(
                title = title,
                audioFile = destAudio,
                asrSpoolFile = destAudio,
                durationMs = durationMs,
                format = format,
                modelId = modelId,
                languagePresetId = preset.id,
                startLang = preset.startLang,
                folderId = folderId
            )
            Result.success(rec)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { destAudio.delete() }
            throw ce
        } catch (t: Throwable) {
            withContext(NonCancellable) { destAudio.delete() }
            Result.failure(t)
        }
    }

    /**
     * Streams decoded 20-second PCM chunks directly into Groq API or Local TinyASR,
     * flushing paragraphs to Room DB in real time as each chunk finishes.
     */
    suspend fun streamTranscribeImportedRecording(
        recording: RecordingEntity,
        useGroqApi: Boolean,
        groqApiKey: String,
        groqModelId: String,
        groqLanguageMode: String = GroqAsrClient.DEFAULT_LANGUAGE_MODE,
        presetId: String,
        requestedThreads: Int,
        beam: Int,
        autoParagraph: Boolean,
        distantSpeechBoost: Boolean,
        speakerDiarization: Boolean,
        onProgress: (ProcessingStatus) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val startedMs = System.currentTimeMillis()
        val pcmFile = File(context.cacheDir, "stream_imp_${recording.id}_${System.currentTimeMillis()}.pcm")
        val normChunkFile = if (distantSpeechBoost && !useGroqApi) {
            File(context.cacheDir, "stream_norm_${recording.id}_${System.currentTimeMillis()}.pcm")
        } else null
        var modelLease: ModelLease? = null
        var speakerWarning = false
        val speakers = if (speakerDiarization) {
            SpeakerAttributionSession(context.assets, speakerLabel()) { speakerWarning = true }
        } else null

        try {
            val audioFile = File(recording.audioPath)
            if (!audioFile.exists()) throw IllegalStateException(strings.audioFileNotFound)

            val useGroq = useGroqApi && groqApiKey.isNotBlank()
            val preset = TinyAsrCatalog.findPreset(presetId)
            val modelId = if (useGroq) groqModelId else preset.modelId
            var detectedLang = preset.startLang
            val worker = coroutineContext

            if (!useGroq) {
                val bigCores = TinyAsrNative.nativePinToBigCores()
                val threads = if (requestedThreads > 0) requestedThreads else bigCores.coerceIn(2, 4)
                onProgress(ProcessingStatus(0.02f, "${strings.loadingModelProgress} (${preset.modelId.uppercase()})", startedAtMs = startedMs))
                modelLease = modelManager.acquireModelLease(preset.modelId, threads)
            } else {
                onProgress(ProcessingStatus(0.02f, strings.recognizingProgress, startedAtMs = startedMs))
            }

            // Shared transcript state across streamed chunks
            val groqParagraphs = mutableListOf<ParagraphEntity>()
            var nextGroqOrder = 0
            val accumulator = ParagraphAccumulator(autoParagraph = autoParagraph)
            val localParagraphs = mutableListOf<ParagraphEntity>()
            var nextLocalOrder = 0
            var transcriptEndSec = 0f
            var latestSnippet: String? = null
            val overlapBytes = OVERLAP_SECONDS * BYTES_PER_SEC
            val normalizer = if (normChunkFile != null) SpeechNormalizer() else null

            suspend fun flushGroq(processedBytes: Long) {
                val wordCount = groqParagraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
                val preview = groqParagraphs.asSequence().map { it.text }.take(20).joinToString(" ").take(220)
                repository.replaceTranscript(
                    recordingId = recording.id,
                    paragraphs = groqParagraphs.toList(),
                    wordCount = wordCount,
                    previewText = preview,
                    detectedLang = detectedLang,
                    modelId = modelId,
                    processedAsrBytes = processedBytes
                )
            }

            suspend fun flushLocal(processedBytes: Long? = null) {
                val wordCount = localParagraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
                val preview = localParagraphs.joinToString(" ") { it.text }.take(220)
                repository.replaceTranscript(
                    recordingId = recording.id,
                    paragraphs = localParagraphs.toList(),
                    wordCount = wordCount,
                    previewText = preview,
                    detectedLang = detectedLang,
                    modelId = modelId,
                    processedAsrBytes = processedBytes
                )
            }

            fun appendLocalSpeech(speech: SpeechSegment) {
                val update = accumulator.acceptCommit(speech.words, speech.speaker)
                update.updatedLastParagraph?.let { draft ->
                    latestSnippet = draft.text.takeLast(96)
                    if (localParagraphs.isNotEmpty()) {
                        val last = localParagraphs.last()
                        localParagraphs[localParagraphs.lastIndex] = last.copy(
                            endSec = draft.endSec,
                            text = draft.text,
                            wordsJson = AsrJsonParser.encodeWords(draft.words),
                            speaker = draft.speaker
                        )
                    }
                }
                update.newParagraph?.let { draft ->
                    latestSnippet = draft.text.takeLast(96)
                    localParagraphs.add(
                        ParagraphEntity(
                            recordingId = recording.id,
                            orderIndex = nextLocalOrder++,
                            startSec = draft.startSec,
                            endSec = draft.endSec,
                            text = draft.text,
                            wordsJson = AsrJsonParser.encodeWords(draft.words),
                            isBookmark = draft.isBookmark,
                            bookmarkLabel = draft.bookmarkLabel,
                            speaker = draft.speaker
                        )
                    )
                }
            }

            var lastProgressMs = 0L
            val decodedInfo = AudioDecoder.decodeUriToPcm16kStreaming(
                context = context,
                sourceUri = Uri.fromFile(audioFile),
                destPcm16kFile = pcmFile,
                chunkBytes = STREAM_CHUNK_SECONDS * BYTES_PER_SEC,
                onProgress = { decodeProg ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressMs >= 120L) {
                        lastProgressMs = now
                        onProgress(
                            ProcessingStatus(
                                progress = decodeProg.coerceIn(0.02f, 0.98f),
                                message = if (speakerWarning) strings.speakerDiarizationUnavailable else strings.recognizingProgress,
                                liveSnippet = latestSnippet,
                                startedAtMs = startedMs
                            )
                        )
                    }
                }
            ) { chunkStartByte, chunkEndByte, decodeProgress ->
                worker.ensureActive()
                if (useGroq) {
                    val chunkLen = (chunkEndByte - chunkStartByte).toInt()
                    if (chunkLen >= 3200) {
                        val pcm = ByteArray(chunkLen)
                        RandomAccessFile(pcmFile, "r").use { raf ->
                            raf.seek(chunkStartByte)
                            raf.readFully(pcm)
                        }
                        val transcript = GroqAsrClient.transcribeChunk(groqApiKey, groqModelId, pcm, languageMode = groqLanguageMode)
                        if (transcript.language.isNotBlank()) detectedLang = transcript.language
                        val segments = if (speakers != null && transcript.segments.isNotEmpty()) {
                            val timeline = speakers.analyze(pcm, chunkStartByte) { !worker.isActive }
                            timeline.assign(transcript.segments, 0f)
                        } else transcript.segments
                        val offset = chunkStartByte.toFloat() / BYTES_PER_SEC
                        val batch = appendSpeechParagraphs(
                            groqParagraphs.lastOrNull(),
                            segments.map { it.toParagraph(recording.id, 0, offset) },
                            nextGroqOrder
                        )
                        batch.updatedLast?.let { groqParagraphs[groqParagraphs.lastIndex] = it }
                        groqParagraphs.addAll(batch.newParagraphs)
                        nextGroqOrder += batch.newParagraphs.size
                        latestSnippet = groqParagraphs.lastOrNull()?.text?.takeLast(96)
                        worker.ensureActive()
                        flushGroq(chunkEndByte)
                    }
                } else {
                    val lease = modelLease ?: throw IllegalStateException(strings.asrSessionError)
                    val windowStartByte = (chunkStartByte - overlapBytes).coerceAtLeast(0L) and -2L
                    val windowBytes = (chunkEndByte - windowStartByte) and -2L
                    if (windowBytes >= 3200L) {
                        val windowStartSec = windowStartByte.toFloat() / BYTES_PER_SEC
                        val coveredUntil = transcriptEndSec
                        val normBytes = if (normalizer != null && normChunkFile != null) {
                            SpeechNormalizer.writeNormalizedPcm16Window(
                                srcPcm = pcmFile,
                                startByte = windowStartByte,
                                endByte = chunkEndByte,
                                dstPcm = normChunkFile,
                                stats = SpeechAnalysisStats(0.002f, SpeechNormalizer.DEFAULT_TARGET_RMS, 1.5f),
                                onCheckCancelled = worker::ensureActive
                            )
                        } else 0L

                        val fileHandle = TinyAsrNative.nativeFileCreateFromPcm16(
                            lease.handle,
                            if (normBytes >= 2L) normChunkFile!!.absolutePath else pcmFile.absolutePath,
                            if (normBytes >= 2L) 0L else windowStartByte,
                            if (normBytes >= 2L) (normBytes / 2L) else (windowBytes / 2L),
                            preset.langsCsv,
                            beam
                        )
                        if (fileHandle == 0L) throw IllegalStateException(strings.asrSessionError)
                        var lastFlushMs = 0L
                        var dirty = false
                        val speakerTimeline = if (speakers != null) {
                            val pcm = ByteArray(windowBytes.toInt())
                            RandomAccessFile(pcmFile, "r").use { raf ->
                                raf.seek(windowStartByte)
                                raf.readFully(pcm)
                            }
                            speakers.analyze(pcm, windowStartByte) { !worker.isActive }
                        } else null

                        try {
                            while (true) {
                                worker.ensureActive()
                                val wordsJson = TinyAsrNative.nativeFileNext(fileHandle) ?: break
                                val words = AsrJsonParser.parseWords(wordsJson).map { w ->
                                    w.copy(start = w.start + windowStartSec, end = w.end + windowStartSec)
                                }.dropWhile { word -> (word.start + word.end) / 2f < coveredUntil }
                                if (words.isNotEmpty()) {
                                    transcriptEndSec = maxOf(transcriptEndSec, words.last().end)
                                    val seg = SpeechSegment.fromWords(words)
                                    val assigned = if (speakerTimeline != null) {
                                        speakerTimeline.assign(listOf(seg), -windowStartSec).firstOrNull() ?: seg
                                    } else seg
                                    appendLocalSpeech(assigned)
                                    dirty = true
                                }
                                TinyAsrNative.nativeFileLang(fileHandle)?.takeIf { it.isNotBlank() }?.let {
                                    detectedLang = it
                                }
                                val now = System.currentTimeMillis()
                                if (dirty && now - lastFlushMs >= 650L) {
                                    worker.ensureActive()
                                    flushLocal()
                                    dirty = false
                                    lastFlushMs = now
                                }
                            }
                            if (dirty) {
                                worker.ensureActive()
                                flushLocal(chunkEndByte)
                            }
                        } finally {
                            TinyAsrNative.nativeFileFree(fileHandle)
                        }
                    }
                }
                onProgress(
                    ProcessingStatus(
                        progress = decodeProgress.coerceIn(0.05f, 0.99f),
                        message = if (speakerWarning) strings.speakerDiarizationUnavailable else strings.recognizingProgress,
                        liveSnippet = latestSnippet,
                        startedAtMs = startedMs
                    )
                )
            }

            worker.ensureActive()
            val totalPcmBytes = pcmFile.length()
            if (useGroq) flushGroq(totalPcmBytes) else flushLocal(totalPcmBytes)
            val latest = repository.getRecording(recording.id) ?: recording
            repository.updateRecording(
                latest.copy(
                    durationMs = if (latest.durationMs <= 0L) decodedInfo.durationMs else latest.durationMs,
                    modelId = modelId,
                    languagePresetId = preset.id,
                    detectedLanguage = detectedLang,
                    totalAsrBytes = totalPcmBytes,
                    processedAsrBytes = totalPcmBytes,
                    asrSpoolPath = ""
                )
            )
            onProgress(ProcessingStatus(1f, strings.done, startedAtMs = startedMs))
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { speakers?.close() }
            withContext(NonCancellable) { modelManager.releaseModelLease(modelLease) }
            pcmFile.delete()
            normChunkFile?.delete()
        }
    }

    private suspend fun preparePcm16kSource(
        recording: RecordingEntity,
        onlyTail: Boolean,
        tempCollector: MutableList<File>,
        onDecodeProgress: (Float) -> Unit
    ): Triple<File, Long, Long> {
        val strings = AppLocaleManager.currentStrings()
        val spoolFile = File(recording.asrSpoolPath)
        if (onlyTail && recording.asrSpoolPath != recording.audioPath && spoolFile.exists() && spoolFile.length() > recording.processedAsrBytes) {
            return Triple(spoolFile, recording.processedAsrBytes and 0x7FFFFFFFFFFFFFFEL, spoolFile.length())
        }
        val audioFile = File(recording.audioPath)
        if (recording.audioFormatEnum == AudioFormat.WAV_16K && audioFile.exists() && audioFile.length() > WavHelper.HEADER_SIZE) {
            val offset = if (onlyTail) (WavHelper.HEADER_SIZE + recording.processedAsrBytes) else WavHelper.HEADER_SIZE.toLong()
            return Triple(audioFile, offset and 0x7FFFFFFFFFFFFFFEL, audioFile.length())
        }
        if (recording.asrSpoolPath != recording.audioPath && spoolFile.exists() && spoolFile.length() > 3200L) {
            val offset = if (onlyTail) recording.processedAsrBytes else 0L
            return Triple(spoolFile, offset and 0x7FFFFFFFFFFFFFFEL, spoolFile.length())
        }
        if (!audioFile.exists()) throw IllegalStateException(strings.audioFileNotFound)
        val tmp = File.createTempFile("retrans_${recording.id}_", ".pcm", context.cacheDir)
        tempCollector.add(tmp)
        AudioDecoder.decodeUriToPcm16kFile(context, Uri.fromFile(audioFile), tmp, onDecodeProgress)
        return Triple(tmp, 0L, tmp.length())
    }

    private fun findQuietCutByte(pcmFile: File, targetByte: Long, searchBackBytes: Long): Long {
        val frameBytes = 1280L
        val startSearch = (targetByte - searchBackBytes).coerceAtLeast(0L)
        var bestPos = targetByte and 0x7FFFFFFFFFFFFFFEL
        var bestEnergy = Long.MAX_VALUE
        val buf = ByteArray(frameBytes.toInt())
        RandomAccessFile(pcmFile, "r").use { raf ->
            var pos = startSearch and 0x7FFFFFFFFFFFFFFEL
            while (pos + frameBytes <= targetByte) {
                raf.seek(pos)
                val n = raf.read(buf)
                if (n < frameBytes) break
                var energy = 0L
                var i = 0
                while (i < n) {
                    val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                    energy += kotlin.math.abs(sample)
                    i += 2
                }
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    bestPos = pos + frameBytes / 2L
                }
                pos += frameBytes
            }
        }
        return bestPos and 0x7FFFFFFFFFFFFFFEL
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
