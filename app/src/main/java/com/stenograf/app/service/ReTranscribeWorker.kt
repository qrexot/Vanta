package com.stenograf.app.service

import android.content.Context
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrWindowMerger
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.GroqTranscriptReviewer
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.asr.SpeakerAttributionSession
import com.stenograf.app.asr.LocalSpeakerLabeler
import com.stenograf.app.asr.SpeechSegment
import com.stenograf.app.asr.SpeakerTimeline
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.FileAsr
import com.stenograf.app.asr.mergeDetectedLanguages
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.ParagraphAccumulator
import com.stenograf.app.asr.TinyAsrNative
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.audio.AudioDecoder
import com.stenograf.app.audio.NeuralSpeechEnhancer
import com.stenograf.app.audio.SpeechAnalysisStats
import com.stenograf.app.audio.SpeechNormalizer
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.repo.RecordingRepository
import com.stenograf.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
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
    val startedAtMs: Long = System.currentTimeMillis(),
    val isIndeterminate: Boolean = false
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
    private val modelManager: ModelManager,
    private val preferences: AppPreferences
) {
    companion object {
        private const val SAMPLE_RATE = 16_000L
        private const val BYTES_PER_SEC = SAMPLE_RATE * 2L
        private const val WINDOW_SECONDS = 30L
        private const val STREAM_CHUNK_SECONDS = 20L
        private const val OVERLAP_SECONDS = 2L
        private const val SAVED_SPEAKER_WINDOW_SECONDS = 60
    }

    private data class PreparedPcmSource(
        val file: File,
        val startByte: Long,
        val endByte: Long,
        val fromAsrSpool: Boolean,
        val headerBytes: Long = 0L
    )

    private suspend fun createEnhancer(): NeuralSpeechEnhancer? =
        NeuralSpeechEnhancer.tryCreate(context.assets, preferFast = preferences.settingsFlow.first().fastEnhancer)

    private suspend fun createSpeakerSession(
        enabled: Boolean,
        autoLearnSpeakers: Boolean,
        onWarning: () -> Unit
    ): SpeakerAttributionSession? {
        if (!enabled) return null
        val stored = repository.getAllStoredVoiceprints()
        return SpeakerAttributionSession(
            assets = context.assets,
            label = speakerLabel(),
            autoVoicePrefix = autoVoicePrefix(),
            autoLearnSpeakers = autoLearnSpeakers,
            storedProfiles = stored,
            onPersistUpdates = { updates ->
                kotlinx.coroutines.runBlocking(NonCancellable) {
                    repository.persistLearnedVoiceprints(updates)
                }
            },
            onUnavailable = onWarning
        )
    }

    private inner class OfflineCommit(
        private var expected: RecordingEntity,
        var baseline: List<ParagraphEntity>,
        private val preserveProgress: Boolean
    ) {
        suspend fun save(
            paragraphs: List<ParagraphEntity>, model: String, preset: String, language: String,
            processed: Long, completed: Boolean = false
        ) {
            val result = repository.commitOfflineTranscript(
                expected, baseline, paragraphs, model, preset, language, processed,
                completedAudioBytes = processed.takeIf { completed },
                reuseParagraphIds = preserveProgress
            ) ?: throw IllegalStateException(
                if (AppLocaleManager.currentLanguage.value.code == "ru") {
                    "Расшифровка изменилась во время обработки. Запустите её снова."
                } else "The transcript changed during processing. Please run transcription again."
            )
            expected = result.first
            baseline = result.second
        }
    }

    suspend fun reTranscribeRecording(
        recording: RecordingEntity,
        presetId: String,
        requestedThreads: Int,
        beam: Int,
        autoParagraph: Boolean,
        onlyUnprocessedTail: Boolean = false,
        distantSpeechBoost: Boolean = true,
        neuralNoiseSuppression: Boolean = true,
        speakerDiarization: Boolean = true,
        autoLearnSpeakers: Boolean = false,
        onProgress: (ProcessingStatus) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempPcm = mutableListOf<File>()
        var fileAsr: FileAsr? = null
        var speakerAudio: RandomAccessFile? = null
        var enhancer: NeuralSpeechEnhancer? = null
        var speakerWarning = false
        val strings = AppLocaleManager.currentStrings()
        val startedMs = System.currentTimeMillis()
        val speakers = createSpeakerSession(speakerDiarization, autoLearnSpeakers) { speakerWarning = true }
        try {
            val original = repository.getParagraphs(recording.id)
            val commit = OfflineCommit(recording, original, preserveProgress = onlyUnprocessedTail)
            val preset = TinyAsrCatalog.findPreset(presetId)
            val bigCores = TinyAsrNative.nativePinToBigCores()
            val threads = if (requestedThreads > 0) requestedThreads else bigCores.coerceIn(2, 4)

            onProgress(ProcessingStatus(0.02f, "${strings.loadingModelProgress} (${preset.modelId.uppercase()})", startedAtMs = startedMs))
            val recognizer = FileAsr.acquire(context, modelManager, preset, threads)
            fileAsr = recognizer

            val prepared = preparePcm16kSource(
                recording = recording,
                onlyTail = onlyUnprocessedTail,
                tempCollector = tempPcm,
                onDecodeProgress = { p ->
                    onProgress(ProcessingStatus(0.02f + p * 0.18f, strings.decodingAudio, startedAtMs = startedMs))
                }
            )
            val pcmFile = prepared.file
            val startByteOffset = prepared.startByte
            val totalBytes = prepared.endByte and -2L
            if (neuralNoiseSuppression && !prepared.fromAsrSpool) {
                enhancer = createEnhancer()
            }

            val existingParagraphs = if (onlyUnprocessedTail) original else emptyList()
            val accumulator = ParagraphAccumulator(autoParagraph = autoParagraph)
            val resultParagraphs = existingParagraphs.toMutableList()
            var orderIdx = (resultParagraphs.maxOfOrNull { it.orderIndex } ?: -1) + 1

            val pcmBase = prepared.headerBytes
            val availableAudioBytes = (totalBytes - pcmBase) and -2L
            val worker = coroutineContext
            var speakerDenoisedFile: File? = null
            var speakerDenoisedStartByte = 0L
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
                    val absPos = pcmBase + start
                    val dFile = speakerDenoisedFile
                    val pcm = ByteArray(size)
                    if (dFile != null && absPos >= speakerDenoisedStartByte && (absPos - speakerDenoisedStartByte) + size <= dFile.length()) {
                        RandomAccessFile(dFile, "r").use { raf ->
                            raf.seek(absPos - speakerDenoisedStartByte)
                            raf.readFully(pcm)
                        }
                    } else {
                        audio.seek(absPos)
                        audio.readFully(pcm)
                    }
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

            var transcriptEndSec = transcriptSpeechEnd(existingParagraphs)
            val recentEmittedWords = ArrayDeque<TranscriptWord>(8).apply {
                existingParagraphs.lastOrNull()?.wordsJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { AsrJsonParser.parseWords(it).takeLast(4).forEach(::addLast) }
            }
            var detectedLang = preset.startLang
            var recognizedLang: String? = if (onlyUnprocessedTail) {
                recording.detectedLanguage?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            var cursorBytes = startByteOffset
            val windowBytes = WINDOW_SECONDS * BYTES_PER_SEC
            val overlapBytes = OVERLAP_SECONDS * BYTES_PER_SEC
            val totalSpanBytes = (totalBytes - startByteOffset).coerceAtLeast(1L)
            var lastProgressMs = 0L

            // Run neural enhancement once sequentially across the recording span so GRU noise state
            // stays smooth across overlapping ASR windows, speaker diarization uses clean PCM, and
            // SpeechNormalizer calibrates on the real post-GTCRN residual floor.
            val denoisedFile = if (enhancer != null && totalBytes > startByteOffset) {
                File(context.cacheDir, "retrans_gtcrn_${recording.id}_${System.currentTimeMillis()}.pcm").also { dst ->
                    tempPcm.add(dst)
                    SpeechNormalizer.writeNormalizedPcm16Window(
                        srcPcm = pcmFile,
                        startByte = startByteOffset,
                        endByte = totalBytes,
                        dstPcm = dst,
                        stats = SpeechAnalysisStats(0.002f, SpeechNormalizer.DEFAULT_TARGET_RMS, 1.0f),
                        onCheckCancelled = worker::ensureActive,
                        enhancer = enhancer,
                        applyNormalizer = false
                    )
                }.takeIf { it.length() >= minOf(2L, (totalBytes - startByteOffset) and -2L) }
            } else null
            if (denoisedFile != null) {
                speakerDenoisedFile = denoisedFile
                speakerDenoisedStartByte = startByteOffset
            }
            val asrSourceFile = denoisedFile ?: pcmFile
            val asrSourceDelta = if (denoisedFile != null) startByteOffset else 0L
            val asrSourceMaxLen = asrSourceFile.length() and -2L

            val speechStats = if (distantSpeechBoost) {
                val statsStart = (startByteOffset - asrSourceDelta).coerceIn(0L, asrSourceMaxLen)
                val statsEnd = (totalBytes - asrSourceDelta).coerceIn(statsStart, asrSourceMaxLen)
                SpeechNormalizer.analyzePcm16File(asrSourceFile, statsStart, statsEnd, worker::ensureActive)
            } else {
                SpeechAnalysisStats(0.002f, SpeechNormalizer.DEFAULT_TARGET_RMS, 1.0f)
            }
            val normWindowFile = if (distantSpeechBoost) {
                File(context.cacheDir, "retrans_norm_${recording.id}_${System.currentTimeMillis()}.pcm").also {
                    tempPcm.add(it)
                }
            } else null

            while (cursorBytes < totalBytes) {
                coroutineContext.ensureActive()
                val rawEnd = (cursorBytes + windowBytes).coerceAtMost(totalBytes)
                val alignedEnd = if (rawEnd < totalBytes) {
                    val localTarget = (rawEnd - asrSourceDelta).coerceIn(0L, asrSourceMaxLen)
                    val localCut = findQuietCutByte(asrSourceFile, localTarget, searchBackBytes = 8L * BYTES_PER_SEC)
                    (localCut + asrSourceDelta).coerceIn((cursorBytes + BYTES_PER_SEC).coerceAtMost(totalBytes), totalBytes)
                } else {
                    rawEnd
                }
                val coveredUntil = transcriptEndSec
                val chunkBytes = (alignedEnd - cursorBytes).coerceAtLeast(2L)
                val sampleCount = chunkBytes / 2L
                val windowStartSec = (cursorBytes - pcmBase).toFloat() / BYTES_PER_SEC
                val srcStart = (cursorBytes - asrSourceDelta).coerceIn(0L, asrSourceMaxLen)
                val srcEnd = (alignedEnd - asrSourceDelta).coerceIn(srcStart, asrSourceMaxLen)

                val normBytes = if (distantSpeechBoost && normWindowFile != null && srcEnd > srcStart) {
                    SpeechNormalizer.writeNormalizedPcm16Window(
                        srcPcm = asrSourceFile,
                        startByte = srcStart,
                        endByte = srcEnd,
                        dstPcm = normWindowFile,
                        stats = speechStats,
                        onCheckCancelled = worker::ensureActive,
                        enhancer = null,
                        applyNormalizer = true
                    )
                } else 0L

                val window = recognizer.open(
                    if (normBytes >= 2L) normWindowFile!! else asrSourceFile,
                    if (normBytes >= 2L) 0L else srcStart,
                    if (normBytes >= 2L) (normBytes / 2L) else ((srcEnd - srcStart) / 2L).coerceAtLeast(1L).coerceAtMost(sampleCount),
                    beam
                )
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val recognized = window.nextWords() ?: break
                        val shifted = recognized.map { w ->
                            w.copy(start = w.start + windowStartSec, end = w.end + windowStartSec)
                        }
                        val words = AsrWindowMerger.trimOverlapWords(shifted, coveredUntil, recentEmittedWords)
                        if (words.isNotEmpty()) {
                            transcriptEndSec = maxOf(transcriptEndSec, words.last().end)
                            words.forEach { w ->
                                recentEmittedWords.addLast(w)
                                while (recentEmittedWords.size > 6) recentEmittedWords.removeFirst()
                            }
                            if (labeler == null) {
                                appendSpeech(SpeechSegment.fromWords(words))
                            } else {
                                labeler.offer(words)
                                labeler.drain(availableAudioBytes, finished = true, emit = ::appendSpeech)
                            }
                        }
                        window.language?.takeIf { it.isNotBlank() }?.let {
                            recognizedLang = mergeDetectedLanguages(recognizedLang, it)
                            detectedLang = recognizedLang ?: it
                        }
                        val nowMs = System.currentTimeMillis()
                        val localProg = window.progress.coerceIn(0f, 1f)
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
                    window.close()
                }

                if (onlyUnprocessedTail && alignedEnd < totalBytes) {
                    commit.save(resultParagraphs, preset.modelId, preset.id, detectedLang, alignedEnd - pcmBase)
                }
                cursorBytes = if (alignedEnd >= totalBytes) totalBytes else (alignedEnd - overlapBytes).coerceAtLeast(cursorBytes + BYTES_PER_SEC)
                if (alignedEnd >= totalBytes) break
            }

            coroutineContext.ensureActive()
            if (!onlyUnprocessedTail && resultParagraphs.isEmpty()) {
                throw IllegalStateException(noSpeechMessage())
            }
            val output = if (onlyUnprocessedTail) resultParagraphs
                else preserveTranscriptAnnotations(original, resultParagraphs)
            commit.save(output, preset.modelId, preset.id, detectedLang, availableAudioBytes, completed = true)
            runCatching { speakers?.flushLearned() }
            runCatching { repository.backfillLearnedVoiceSamples(recording.id) }
            onProgress(ProcessingStatus(1f, strings.done, startedAtMs = startedMs))
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { enhancer?.close() }
            runCatching { speakerAudio?.close() }
            runCatching { speakers?.close() }
            withContext(NonCancellable) { fileAsr?.release() }
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
        neuralNoiseSuppression: Boolean = true,
        onProgress: (Float) -> Unit
    ): AudioReview = withContext(Dispatchers.IO) {
        val preset = TinyAsrCatalog.findPreset(presetId)
        val recognizer = FileAsr.acquire(context, modelManager, preset, 2)
        try {
            val segments = readAudioWindows(recording, paragraph, neuralNoiseSuppression, onProgress) { source, offset, count ->
                val words = recognizer.open(source, offset, count / 2L, 4).use { window ->
                    buildList {
                        while (true) {
                            coroutineContext.ensureActive()
                            addAll(window.nextWords() ?: break)
                        }
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
            withContext(NonCancellable) { recognizer.release() }
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
        neuralNoiseSuppression: Boolean = true,
        onProgress: (Float) -> Unit
    ): AudioReview = withContext(Dispatchers.IO) {
        check(paragraph != null || recording.statusEnum !in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)) {
            if (AppLocaleManager.currentLanguage.value.code == "ru") "Сначала завершите запись."
            else "Finish recording before reviewing it in full."
        }
        var processedAudioBytes = 0L
        val detectedLangCounts = linkedMapOf<String, Int>()
        val recognized = readAudioWindows(recording, paragraph, neuralNoiseSuppression, { onProgress(it * 0.68f) }, { processedAudioBytes = it }) { source, offset, count ->
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
        neuralNoiseSuppression: Boolean = true,
        onProgress: (Float) -> Unit,
        onProcessed: (Long) -> Unit = {},
        decode: suspend (File, Long, Int) -> List<SpeechSegment>
    ): List<SpeechSegment> {
        val temporaryPcm = mutableListOf<File>()
        try {
            val spool = File(recording.asrSpoolPath)
            val live = recording.statusEnum in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)
            val prepared = if (live && spool.isFile) {
                PreparedPcmSource(spool, 0L, spool.length(), fromAsrSpool = true)
            } else {
                preparePcm16kSource(recording, false, temporaryPcm) { onProgress(it * 0.15f) }
            }
            var source = prepared.file
            var base = prepared.startByte
            val limit = prepared.endByte
            val maxBytes = (limit - base).coerceAtLeast(0L) and -2L
            var from = if (paragraph == null) 0L else
                ((paragraph.startSec.toDouble() * BYTES_PER_SEC).toLong().coerceIn(0L, maxBytes) and -2L)
            var to = if (paragraph == null) maxBytes else
                ((paragraph.endSec.toDouble() * BYTES_PER_SEC).toLong().coerceIn(from, maxBytes) and -2L)
            onProcessed(to - from)
            check(to - from >= 1600L) { noSpeechMessage() }
            if (neuralNoiseSuppression && !prepared.fromAsrSpool) {
                createEnhancer()?.use { enhancer ->
                    val denoisedFile = File.createTempFile("retrans_clean_${recording.id}_", ".pcm", context.cacheDir).also {
                        temporaryPcm.add(it)
                    }
                    val written = SpeechNormalizer.writeNormalizedPcm16Window(
                        srcPcm = source,
                        startByte = base + from,
                        endByte = base + to,
                        dstPcm = denoisedFile,
                        stats = SpeechAnalysisStats(0.002f, SpeechNormalizer.DEFAULT_TARGET_RMS, 1.0f),
                        onCheckCancelled = { /* checked by enclosing coroutine below */ },
                        enhancer = enhancer,
                        applyNormalizer = false
                    )
                    if (written >= 1600L) {
                        source = denoisedFile
                        base = 0L
                        to = written
                        from = 0L
                    }
                }
            }
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
        neuralNoiseSuppression: Boolean = true,
        autoLearnSpeakers: Boolean = false,
        onlyUnprocessedTail: Boolean = false,
        onProgress: (ProcessingStatus) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val startedMs = System.currentTimeMillis()
        val temporaryPcm = mutableListOf<File>()
        var enhancer: NeuralSpeechEnhancer? = null
        var speakerWarning = false
        val speakers = createSpeakerSession(speakerDiarization, autoLearnSpeakers) { speakerWarning = true }
        val paragraphs = mutableListOf<ParagraphEntity>()
        var detectedLang = recording.detectedLanguage
        var cursor = 0L
        var initialCursor = 0L

        try {
            val original = repository.getParagraphs(recording.id)
            val commit = OfflineCommit(recording, original, preserveProgress = onlyUnprocessedTail)
            onProgress(ProcessingStatus(0.02f, strings.decodingAudio, startedAtMs = startedMs))
            val prepared = preparePcm16kSource(recording, false, temporaryPcm) { fraction ->
                onProgress(ProcessingStatus(0.02f + 0.16f * fraction, strings.decodingAudio, startedAtMs = startedMs))
            }
            val source = prepared.file
            val start = prepared.startByte
            val end = prepared.endByte
            if (neuralNoiseSuppression && !prepared.fromAsrSpool) {
                enhancer = createEnhancer()
            }
            val length = (end - start) and -2L
            if (length <= 0L) throw IllegalStateException(strings.audioFileNotFound)
            val worker = coroutineContext
            if (onlyUnprocessedTail) {
                val savedSpeechBytes = (transcriptSpeechEnd(original).toDouble() * BYTES_PER_SEC).toLong()
                initialCursor = resumePcmOffset(maxOf(recording.processedAsrBytes, savedSpeechBytes), 0L, length)
                cursor = initialCursor
                paragraphs.addAll(original)
            }
            var nextOrder = (paragraphs.maxOfOrNull { it.orderIndex } ?: -1) + 1
            RandomAccessFile(source, "r").use { input ->
                if (onlyUnprocessedTail) {
                    speakers?.seedFromTranscript(input, start, initialCursor, paragraphs, worker::ensureActive)
                }
                input.seek(start + cursor)
                val speakerWindowBytes = SAVED_SPEAKER_WINDOW_SECONDS * BYTES_PER_SEC
                var speakerWindowStart = -1L
                var speakerPcmCache = ByteArray(0)
                var timeline = SpeakerTimeline.Empty
                while (cursor < length) {
                    worker.ensureActive()
                    val windowStart = initialCursor + (cursor - initialCursor) / speakerWindowBytes * speakerWindowBytes
                    val windowEnd = minOf(length, windowStart + speakerWindowBytes)
                    val maxEnd = minOf(windowEnd, cursor + STREAM_CHUNK_SECONDS * BYTES_PER_SEC)
                    val chunkEnd = if (!onlyUnprocessedTail && maxEnd < windowEnd && maxEnd - cursor > 10L * BYTES_PER_SEC) {
                        val quietAbs = findQuietCutByte(source, start + maxEnd, searchBackBytes = 4L * BYTES_PER_SEC)
                        (quietAbs - start).coerceIn(cursor + 6L * BYTES_PER_SEC, maxEnd) and -2L
                    } else {
                        maxEnd
                    }
                    val chunkLen = (chunkEnd - cursor).toInt().coerceAtLeast(2)

                    val pcm: ByteArray
                    val segments = if (speakers != null) {
                        if (speakerWindowStart != windowStart) {
                            val speakerPcmRaw = ByteArray((windowEnd - windowStart).toInt())
                            input.seek(start + windowStart)
                            input.readFully(speakerPcmRaw)
                            speakerPcmCache = enhancer?.denoisePcm16BytesExact(speakerPcmRaw) ?: speakerPcmRaw
                            timeline = speakers.analyze(speakerPcmCache, windowStart) { !worker.isActive }
                            speakerWindowStart = windowStart
                        }
                        val relStart = (cursor - windowStart).toInt().coerceIn(0, speakerPcmCache.size)
                        val relEnd = (relStart + chunkLen).coerceIn(relStart, speakerPcmCache.size)
                        pcm = speakerPcmCache.copyOfRange(relStart, relEnd)
                        val transcript = GroqAsrClient.transcribeChunk(apiKey, modelId, pcm, languageMode = languageMode)
                        if (transcript.language.isNotBlank()) detectedLang = transcript.language
                        if (transcript.segments.isNotEmpty()) {
                            timeline.assign(transcript.segments, (speakerWindowStart - cursor).toFloat() / BYTES_PER_SEC)
                        } else emptyList()
                    } else {
                        val rawPcm = ByteArray(chunkLen)
                        input.seek(start + cursor)
                        input.readFully(rawPcm)
                        pcm = enhancer?.denoisePcm16BytesExact(rawPcm) ?: rawPcm
                        val transcript = GroqAsrClient.transcribeChunk(apiKey, modelId, pcm, languageMode = languageMode)
                        if (transcript.language.isNotBlank()) detectedLang = transcript.language
                        transcript.segments
                    }
                    val offset = cursor.toFloat() / BYTES_PER_SEC
                    val batch = appendSpeechParagraphs(
                        paragraphs.lastOrNull().takeIf { !onlyUnprocessedTail || cursor > initialCursor },
                        segments.map { it.toParagraph(recording.id, 0, offset) },
                        nextOrder
                    )
                    batch.updatedLast?.let { paragraphs[paragraphs.lastIndex] = it }
                    paragraphs.addAll(batch.newParagraphs)
                    nextOrder += batch.newParagraphs.size
                    cursor += chunkLen
                    worker.ensureActive()
                    if (onlyUnprocessedTail && cursor < length) {
                        commit.save(paragraphs, modelId, recording.languagePresetId, detectedLang, cursor)
                    }
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
            if (!onlyUnprocessedTail && paragraphs.isEmpty()) throw IllegalStateException(noSpeechMessage())
            val result = if (onlyUnprocessedTail) paragraphs else preserveTranscriptAnnotations(original, paragraphs)
            commit.save(result, modelId, recording.languagePresetId, detectedLang, length, completed = true)
            runCatching { speakers?.flushLearned() }
            runCatching { repository.backfillLearnedVoiceSamples(recording.id) }
            onProgress(ProcessingStatus(1f, strings.done, startedAtMs = startedMs))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { enhancer?.close() }
            runCatching { speakers?.close() }
            temporaryPcm.forEach { it.delete() }
        }
    }

    /**
     * Stage the selected file, verify its audio track and publish it before transcription starts.
     */
    suspend fun prepareImportedRecording(
        uri: Uri,
        presetId: String,
        folderId: Long? = null,
        modelId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val (displayName, expectedBytes) = queryImportMetadata(uri)
        var temporary: File? = null
        var published = false
        try {
            val nameExtension = displayName?.substringAfterLast('.', "")?.let(::safeMediaExtension)
            val mimeExtension = if (nameExtension == null) {
                context.contentResolver.getType(uri)
                    ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    ?.let(::safeMediaExtension)
            } else null
            val ext = nameExtension ?: mimeExtension ?: "media"
            val destAudio = File.createTempFile("imp_${stamp}_", ".$ext", repository.recordingsDir)
            temporary = destAudio
            val preset = TinyAsrCatalog.findPreset(presetId)
            val title = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: "${strings.importedPrefix} $stamp"

            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(strings.audioFileNotFound)
            var copiedBytes = 0L
            var lastUpdateMs = SystemClock.elapsedRealtime()
            input.use { src ->
                destAudio.outputStream().buffered(64 * 1024).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = src.read(buffer)
                        if (count == -1) break
                        out.write(buffer, 0, count)
                        copiedBytes += count
                        val now = SystemClock.elapsedRealtime()
                        if (expectedBytes != null && now - lastUpdateMs >= 150L) {
                            onProgress((copiedBytes.toDouble() / expectedBytes).toFloat().coerceAtMost(0.99f))
                            lastUpdateMs = now
                        }
                    }
                }
            }
            check(copiedBytes > 0L) { strings.audioTrackNotFound }
            coroutineContext.ensureActive()
            val durationMs = AudioDecoder.readAudioDurationMs(destAudio)
            val format = if (ext == "wav") AudioFormat.WAV_48K else AudioFormat.M4A_48K
            coroutineContext.ensureActive()
            val rec = withContext(NonCancellable) {
                repository.createImportedRecording(
                    title = title,
                    audioFile = destAudio,
                    asrSpoolFile = destAudio,
                    durationMs = durationMs,
                    format = format,
                    modelId = modelId,
                    languagePresetId = preset.id,
                    startLang = preset.startLang,
                    folderId = folderId
                ).also { published = true }
            }
            Result.success(rec)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            if (!published) temporary?.delete()
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
        neuralNoiseSuppression: Boolean = true,
        speakerDiarization: Boolean,
        autoLearnSpeakers: Boolean = false,
        onProgress: (ProcessingStatus) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val strings = AppLocaleManager.currentStrings()
        val startedMs = System.currentTimeMillis()
        val pcmFile = File(context.cacheDir, "stream_imp_${recording.id}_${System.currentTimeMillis()}.pcm")
        val enhancer = if (neuralNoiseSuppression) createEnhancer() else null
        val normChunkFile = if ((distantSpeechBoost || enhancer != null) && !useGroqApi) {
            File(context.cacheDir, "stream_norm_${recording.id}_${System.currentTimeMillis()}.pcm")
        } else null
        var fileAsr: FileAsr? = null
        var speakerWarning = false
        val speakers = createSpeakerSession(speakerDiarization, autoLearnSpeakers) { speakerWarning = true }

        try {
            val audioFile = File(recording.audioPath)
            if (!audioFile.exists()) throw IllegalStateException(strings.audioFileNotFound)

            val useGroq = useGroqApi && groqApiKey.isNotBlank()
            val preset = TinyAsrCatalog.findPreset(presetId)
            val modelId = if (useGroq) groqModelId else preset.modelId
            var detectedLang = preset.startLang
            var recognizedLang: String? = null
            val worker = coroutineContext

            if (!useGroq) {
                val bigCores = TinyAsrNative.nativePinToBigCores()
                val threads = if (requestedThreads > 0) requestedThreads else bigCores.coerceIn(2, 4)
                onProgress(ProcessingStatus(0.02f, "${strings.loadingModelProgress} (${preset.modelId.uppercase()})", startedAtMs = startedMs))
                fileAsr = FileAsr.acquire(context, modelManager, preset, threads)
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
            val recentLocalWords = ArrayDeque<TranscriptWord>(8)
            var latestSnippet: String? = null
            val overlapBytes = OVERLAP_SECONDS * BYTES_PER_SEC

            val baseline = repository.getParagraphs(recording.id)
            val commit = OfflineCommit(recording, baseline, preserveProgress = true)
            check(baseline.isEmpty() && recording.processedAsrBytes == 0L)
            suspend fun flush(processedBytes: Long, completed: Boolean = false) {
                commit.save(
                    if (useGroq) groqParagraphs else localParagraphs,
                    modelId, preset.id, detectedLang, processedBytes, completed
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
            AudioDecoder.decodeUriToPcm16kStreaming(
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
                        val rawPcm = ByteArray(chunkLen)
                        RandomAccessFile(pcmFile, "r").use { raf ->
                            raf.seek(chunkStartByte)
                            raf.readFully(rawPcm)
                        }
                        val pcm = enhancer?.denoisePcm16BytesExact(rawPcm) ?: rawPcm
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
                    }
                } else {
                    val recognizer = fileAsr ?: throw IllegalStateException(strings.asrSessionError)
                    val windowStartByte = (chunkStartByte - overlapBytes).coerceAtLeast(0L) and -2L
                    val windowBytes = (chunkEndByte - windowStartByte) and -2L
                    if (windowBytes >= 3200L) {
                        val windowStartSec = windowStartByte.toFloat() / BYTES_PER_SEC
                        val coveredUntil = transcriptEndSec
                        val normBytes = if ((distantSpeechBoost || enhancer != null) && normChunkFile != null) {
                            SpeechNormalizer.writeNormalizedPcm16Window(
                                srcPcm = pcmFile,
                                startByte = windowStartByte,
                                endByte = chunkEndByte,
                                dstPcm = normChunkFile,
                                stats = SpeechAnalysisStats(0.002f, SpeechNormalizer.DEFAULT_TARGET_RMS, 1.5f),
                                onCheckCancelled = worker::ensureActive,
                                enhancer = enhancer,
                                applyNormalizer = distantSpeechBoost
                            )
                        } else 0L

                        val speakerTimeline = if (speakers != null) {
                            val useDenoised = enhancer != null && normBytes >= 2L && normChunkFile != null
                            val srcFile = if (useDenoised) normChunkFile!! else pcmFile
                            val srcOffset = if (useDenoised) 0L else windowStartByte
                            val srcLen = if (useDenoised) minOf(normBytes, windowBytes).toInt() else windowBytes.toInt()
                            val pcm = ByteArray(srcLen)
                            RandomAccessFile(srcFile, "r").use { raf ->
                                raf.seek(srcOffset)
                                raf.readFully(pcm)
                            }
                            speakers.analyze(pcm, windowStartByte) { !worker.isActive }
                        } else null

                        val window = recognizer.open(
                            if (normBytes >= 2L) normChunkFile!! else pcmFile,
                            if (normBytes >= 2L) 0L else windowStartByte,
                            if (normBytes >= 2L) (normBytes / 2L) else (windowBytes / 2L),
                            beam
                        )
                        try {
                            while (true) {
                                worker.ensureActive()
                                val recognized = window.nextWords() ?: break
                                val shifted = recognized.map { w ->
                                    w.copy(start = w.start + windowStartSec, end = w.end + windowStartSec)
                                }
                                val words = AsrWindowMerger.trimOverlapWords(shifted, coveredUntil, recentLocalWords)
                                if (words.isNotEmpty()) {
                                    transcriptEndSec = maxOf(transcriptEndSec, words.last().end)
                                    words.forEach { w ->
                                        recentLocalWords.addLast(w)
                                        while (recentLocalWords.size > 6) recentLocalWords.removeFirst()
                                    }
                                    val seg = SpeechSegment.fromWords(words)
                                    val assigned = if (speakerTimeline != null) {
                                        speakerTimeline.assign(listOf(seg), -windowStartSec).firstOrNull() ?: seg
                                    } else seg
                                    appendLocalSpeech(assigned)
                                }
                                window.language?.takeIf { it.isNotBlank() }?.let {
                                    recognizedLang = mergeDetectedLanguages(recognizedLang, it)
                                    detectedLang = recognizedLang ?: it
                                }
                            }
                        } finally {
                            window.close()
                        }
                    }
                }
                worker.ensureActive()
                flush(chunkEndByte)
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
            check(totalPcmBytes > 0L) { strings.audioTrackNotFound }
            flush(totalPcmBytes, completed = true)
            runCatching { speakers?.flushLearned() }
            runCatching { repository.backfillLearnedVoiceSamples(recording.id) }
            onProgress(ProcessingStatus(1f, strings.done, startedAtMs = startedMs))
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { enhancer?.close() }
            runCatching { speakers?.close() }
            withContext(NonCancellable) { fileAsr?.release() }
            pcmFile.delete()
            normChunkFile?.delete()
        }
    }

    private suspend fun preparePcm16kSource(
        recording: RecordingEntity,
        onlyTail: Boolean,
        tempCollector: MutableList<File>,
        onDecodeProgress: (Float) -> Unit
    ): PreparedPcmSource {
        val strings = AppLocaleManager.currentStrings()
        val spoolFile = File(recording.asrSpoolPath)
        val audioFile = File(recording.audioPath)
        val nativePcm = WavHelper.monoPcm16SampleRate(audioFile) == 16_000
        val checkpoint = if (onlyTail) recording.processedAsrBytes else 0L
        val spoolComplete = recording.asrSpoolPath.isNotBlank() &&
            spoolFile.canonicalFile != audioFile.canonicalFile &&
            spoolFile.isFile && spoolFile.length() > 3200L &&
            spoolFile.length() >= recording.totalAsrBytes
        if (spoolComplete && (onlyTail || !nativePcm)) {
            return PreparedPcmSource(
                spoolFile, resumePcmOffset(checkpoint, 0L, spoolFile.length()),
                spoolFile.length(), fromAsrSpool = true
            )
        }
        if (nativePcm && audioFile.length() > WavHelper.HEADER_SIZE) {
            return PreparedPcmSource(
                audioFile, resumePcmOffset(checkpoint, WavHelper.HEADER_SIZE.toLong(), audioFile.length()),
                audioFile.length(), fromAsrSpool = false, headerBytes = WavHelper.HEADER_SIZE.toLong()
            )
        }
        if (!audioFile.isFile) throw IllegalStateException(strings.audioFileNotFound)
        val tmp = File.createTempFile("retrans_${recording.id}_", ".pcm", context.cacheDir)
        tempCollector.add(tmp)
        AudioDecoder.decodeUriToPcm16kFile(context, Uri.fromFile(audioFile), tmp, onDecodeProgress)
        return PreparedPcmSource(tmp, resumePcmOffset(checkpoint, 0L, tmp.length()), tmp.length(), fromAsrSpool = false)
    }

    private fun findQuietCutByte(pcmFile: File, targetByte: Long, searchBackBytes: Long): Long {
        val frameBytes = 1280L // 40 ms frame @ 16kHz PCM16
        val alignedTarget = targetByte and 0x7FFFFFFFFFFFFFFEL
        val startSearch = (alignedTarget - searchBackBytes).coerceAtLeast(0L) and 0x7FFFFFFFFFFFFFFEL
        if (alignedTarget - startSearch < frameBytes * 3L) return alignedTarget

        val frameEnergies = ArrayList<Long>()
        val frameStarts = ArrayList<Long>()
        val buf = ByteArray(frameBytes.toInt())
        RandomAccessFile(pcmFile, "r").use { raf ->
            raf.seek(startSearch)
            var pos = startSearch
            while (pos + frameBytes <= alignedTarget) {
                val n = raf.read(buf)
                if (n < frameBytes) break
                var energy = 0L
                var i = 0
                while (i < n) {
                    val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                    energy += kotlin.math.abs(sample)
                    i += 2
                }
                frameEnergies.add(energy)
                frameStarts.add(pos)
                pos += frameBytes
            }
        }
        if (frameEnergies.isEmpty()) return alignedTarget
        if (frameEnergies.size < 3) {
            var bestIdx = 0
            for (i in 1 until frameEnergies.size) {
                if (frameEnergies[i] <= frameEnergies[bestIdx]) bestIdx = i
            }
            return (frameStarts[bestIdx] + frameBytes / 2L) and 0x7FFFFFFFFFFFFFFEL
        }

        // Smooth across 3 consecutive 40ms frames (120ms pause window) so brief stop-consonant
        // closures inside words don't trigger mid-word splits; break ties near targetByte.
        var bestIdx = 1
        var bestWindowEnergy = Long.MAX_VALUE
        for (i in 1 until frameEnergies.size - 1) {
            val windowEnergy = frameEnergies[i - 1] + frameEnergies[i] + frameEnergies[i + 1]
            if (windowEnergy <= bestWindowEnergy) {
                bestWindowEnergy = windowEnergy
                bestIdx = i
            }
        }
        return (frameStarts[bestIdx] + frameBytes / 2L) and 0x7FFFFFFFFFFFFFFEL
    }

    private fun safeMediaExtension(value: String): String? =
        value.lowercase(Locale.ROOT)
            .takeIf { it.length in 1..10 && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' } }

    private fun queryImportMetadata(uri: Uri): Pair<String?, Long?> {
        return try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L
                name to size.takeIf { it > 0L }
            } ?: (null to null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null to null
        }
    }
}
