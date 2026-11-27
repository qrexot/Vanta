package com.stenograf.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import com.stenograf.app.MainActivity
import com.stenograf.app.R
import com.stenograf.app.StenografApp
import com.stenograf.app.asr.AsrEvent
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.LocalSpeakerLabeler
import com.stenograf.app.asr.SpeakerAttributionSession
import com.stenograf.app.asr.SpeechSegment
import com.stenograf.app.asr.ModelLease
import com.stenograf.app.asr.ParagraphAccumulator
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.asr.TinyAsrNative
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.audio.M4aAudioWriter
import com.stenograf.app.audio.SpeechNormalizer
import com.stenograf.app.audio.StreamingResamplerTo16k
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.prefs.AsrEngineMode
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class LiveRecordingUiState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val isFinishing: Boolean = false,
    val isEngineProcessing: Boolean = false,
    val recordingId: Long = 0L,
    val title: String = "",
    val durationMs: Long = 0L,
    val modelId: String = "en-ru-uk",
    val languagePresetId: String = "ru_only",
    val activeLanguage: String = "ru",
    val isGroqEngine: Boolean = false,
    val speakerDiarization: Boolean = false,
    val isGroqRetrying: Boolean = false,
    val groqModelId: String = GroqAsrClient.DEFAULT_MODEL_ID,
    val asrStatusText: String = "",
    val asrError: String? = null,
    val statusNotice: String? = null,
    val lowStorageWarning: Boolean = false,
    val backlogSeconds: Float = 0f,
    val waveform: List<Float> = List(72) { 0f },
    val committedParagraphs: List<ParagraphEntity> = emptyList(),
    val pendingSpeakerWords: List<TranscriptWord> = emptyList(),
    val openWords: List<TranscriptWord> = emptyList(),
    val tailWords: List<TranscriptWord> = emptyList(),
    val finishedRecordingId: Long? = null,
    val liveAiEnabled: Boolean = false,
    val liveAiWorking: Boolean = false
)

class RecordingForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): RecordingForegroundService = this@RecordingForegroundService
    }

    private val binder = LocalBinder()
    private var liveAiJob: kotlinx.coroutines.Job? = null
    private val liveAiGeneration = AtomicLong()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var app: StenografApp
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioFocusLock = Any()
    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    private val sessionStarting = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    @Volatile
    private var destroyedSnapshot: LiveRecordingUiState? = null
    private val isRecordingFlag = AtomicBoolean(false)
    private val isPausedFlag = AtomicBoolean(false)
    private val pausedByAudioFocus = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val spoolLock = Object()
    private val totalSpoolBytes = AtomicLong(0L)
    private val processedSpoolBytes = AtomicLong(0L)
    private val localDecodedSpoolBytes = AtomicLong(0L)
    private val captureClosedFlag = AtomicBoolean(false)

    @Volatile
    private var currentRecording: RecordingEntity? = null
    @Volatile
    private var requestedPresetSwitch: String? = null
    @Volatile
    private var activeThreadCount: Int = 2

    private var captureThread: Thread? = null

    private val waveformHistory = ArrayDeque<Float>(72)
    @Volatile
    private var paragraphAccumulator = ParagraphAccumulator()
    private data class LocalParagraphBreak(val atSec: Float, val label: String? = null)
    private val localParagraphBreaks = ConcurrentLinkedQueue<LocalParagraphBreak>()
    private val committedList = mutableListOf<ParagraphEntity>()
    private var currentParagraphEntityId: Long = 0L
    private var nextOrderIndex: Int = 0
    private var timeOffsetSec: Float = 0f

    companion object {
        const val CHANNEL_ID = "stenograf_recording_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.stenograf.app.action.START_RECORDING"
        const val ACTION_PAUSE = "com.stenograf.app.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.stenograf.app.action.RESUME_RECORDING"
        const val ACTION_BOOKMARK = "com.stenograf.app.action.ADD_BOOKMARK"
        const val ACTION_STOP = "com.stenograf.app.action.STOP_RECORDING"

        const val EXTRA_FOLDER_ID = "extra_folder_id"

        private val _uiState = MutableStateFlow(LiveRecordingUiState())
        val uiState: StateFlow<LiveRecordingUiState> = _uiState.asStateFlow()

        fun startNewRecording(context: Context, folderId: Long? = null) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                if (folderId != null) putExtra(EXTRA_FOLDER_ID, folderId)
            }
            context.startForegroundService(intent)
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }

        fun clearFinishedEvent() {
            _uiState.update { it.copy(finishedRecordingId = null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as StenografApp
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRecordingFlag.get() && sessionStarting.compareAndSet(false, true)) {
                    stopRequested.set(false)
                    val folderId = if (intent.hasExtra(EXTRA_FOLDER_ID)) {
                        intent.getLongExtra(EXTRA_FOLDER_ID, -1L).takeIf { it > 0L }
                    } else null
                    startForegroundCompat(AppLocaleManager.currentStrings().preparingModel, isPaused = false)
                    serviceScope.launch {
                        try {
                            beginRecordingSession(folderId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _uiState.value = LiveRecordingUiState(asrError = e.message)
                            stopSelf()
                        } finally {
                            sessionStarting.set(false)
                        }
                    }
                }
            }
            ACTION_PAUSE -> pauseRecording(byAudioFocus = false, notice = null)
            ACTION_RESUME -> resumeRecording()
            ACTION_BOOKMARK -> addBookmark()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(statusText: String, isPaused: Boolean) {
        val notification = buildNotification(statusText, isPaused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun beginRecordingSession(folderId: Long?) {
        val strings = AppLocaleManager.currentStrings()
        app.recordingRecovery.join()
        val settings = app.preferences.settingsFlow.first()
        if (stopRequested.get()) {
            _uiState.value = LiveRecordingUiState()
            stopSelf()
            return
        }
        val preset = TinyAsrCatalog.findPreset(settings.languagePresetId)

        val availBytes = app.repository.getAvailableInternalStorageBytes()
        if (availBytes < 35L * 1024L * 1024L) {
            _uiState.value = LiveRecordingUiState(
                asrError = strings.criticalStorageStop
            )
            stopSelf()
            return
        }

        val useGroqLive = settings.asrEngineMode == AsrEngineMode.GROQ_API
        val initialLang = if (useGroqLive) {
            settings.groqLanguageMode.takeUnless { it == "multi" || it == "auto" } ?: ""
        } else {
            preset.startLang
        }
        val rec = app.repository.createNewRecordingSession(
            format = settings.audioFormat,
            modelId = if (useGroqLive) settings.groqModelId else preset.modelId,
            languagePresetId = preset.id,
            startLang = initialLang,
            folderId = folderId
        )

        currentRecording = rec
        totalSpoolBytes.set(0L)
        processedSpoolBytes.set(0L)
        localDecodedSpoolBytes.set(0L)
        captureClosedFlag.set(false)
        isPausedFlag.set(false)
        pausedByAudioFocus.set(false)
        isRecordingFlag.set(true)
        requestedPresetSwitch = null

        waveformHistory.clear()
        repeat(72) { waveformHistory.addLast(0f) }
        committedList.clear()
        paragraphAccumulator = ParagraphAccumulator(autoParagraph = settings.autoParagraph)
        localParagraphBreaks.clear()
        currentParagraphEntityId = 0L
        nextOrderIndex = 0
        timeOffsetSec = 0f

        _uiState.value = LiveRecordingUiState(
            isActive = true,
            isPaused = false,
            recordingId = rec.id,
            title = rec.title,
            durationMs = 0L,
            modelId = if (useGroqLive) settings.groqModelId else preset.modelId,
            languagePresetId = preset.id,
            activeLanguage = initialLang,
            isGroqEngine = useGroqLive,
            speakerDiarization = settings.speakerDiarization,
            groqModelId = settings.groqModelId,
            asrStatusText = if (useGroqLive) {
                "Groq · ${settings.groqModelId}"
            } else {
                strings.loadingModelProgress
            },
            waveform = waveformHistory.toList(),
            finishedRecordingId = null
        )

        acquireWakeLock()
        requestAudioFocus()
        updateNotification(strings.notifRecordingActive, isPaused = false)

        val captureFinished = CompletableDeferred<Unit>()
        captureThread = Thread({
            try {
                runCaptureLoop(rec, settings.audioFormat, settings.useRawMicrophone, settings.distantSpeechBoost)
            } catch (e: Exception) {
                _uiState.update { it.copy(asrError = e.message) }
            } finally {
                captureClosedFlag.set(true)
                synchronized(spoolLock) { spoolLock.notifyAll() }
                captureFinished.complete(Unit)
            }
        }, "Stenograf-AudioCapture").apply { start() }

        if (useGroqLive) {
            val commitLock = Mutex()
            val cloudUnavailable = AtomicBoolean(false)
            val cloud = serviceScope.launch(Dispatchers.IO) {
                runGroqLiveLoop(
                    rec, settings.groqModelId, settings.speakerDiarization,
                    commitLock, cloudUnavailable
                )
            }
            app.applicationScope.launch(Dispatchers.IO) {
                captureFinished.await()
                try {
                    if (!cloudUnavailable.get()) withTimeoutOrNull(5_000L) { cloud.join() }
                } finally {
                    cloud.cancel()
                    // An already-started DB batch may complete. Network and speaker work
                    // hold no lock and cannot delay finalization.
                    withContext(NonCancellable) {
                        commitLock.withLock { finalizeRecordingInDatabase(rec) }
                    }
                }
            }
        } else {
            Thread({
                runAsrLoop(rec, preset.id, settings.asrThreads, settings.asrBeam, settings.speakerDiarization)
            }, "Stenograf-TinyASR").start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(
        rec: RecordingEntity,
        audioFormat: AudioFormat,
        useRawMic: Boolean,
        distantSpeechBoost: Boolean
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (stopRequested.get()) return

        val desiredRate = when (audioFormat) {
            AudioFormat.WAV_16K -> 16_000
            AudioFormat.M4A_48K, AudioFormat.WAV_48K -> 48_000
        }

        val (initialRecord, actualRate) = openAudioRecord(desiredRate, useRawMic)
        if (initialRecord == null) {
            val s = AppLocaleManager.currentStrings()
            _uiState.update { state ->
                state.copy(
                    asrError = s.micAccessFailed,
                    statusNotice = s.micPermissionRequired
                )
            }
            stopRecording()
            return
        }
        var record: AudioRecord = initialRecord

        val audioFile = File(rec.audioPath)
        val spoolFile = File(rec.asrSpoolPath)
        val resampler = StreamingResamplerTo16k(actualRate)

        val isWav = audioFormat != AudioFormat.M4A_48K
        var wavOut: BufferedOutputStream? = null
        var m4aWriter: M4aAudioWriter? = null
        var spoolOut: BufferedOutputStream? = null

        var audioDataBytes = 0L
        var capturedSamples = 0L
        var lastHeaderSyncMs = System.currentTimeMillis()
        var lastDbProgressMs = lastHeaderSyncMs
        var lastStorageCheckMs = lastHeaderSyncMs
        var zeroReadCount = 0

        try {
            if (isWav) {
                wavOut = BufferedOutputStream(FileOutputStream(audioFile), 64 * 1024)
                wavOut.write(WavHelper.createHeader(actualRate, 1, 16, 0L))
            } else {
                m4aWriter = M4aAudioWriter(audioFile, actualRate, 128_000)
            }
            spoolOut = BufferedOutputStream(FileOutputStream(spoolFile), 64 * 1024)

            val frameSamples = (actualRate * 40) / 1000 // 40ms frame
            val readBuf = ShortArray(frameSamples)
            val byteBuf = ByteArray(frameSamples * 2)
            var asrScratch = ShortArray(frameSamples)
            val asrNormalizer = if (distantSpeechBoost) SpeechNormalizer(sampleRate = 16_000) else null
            var spoolWriteBuf = ByteArray(4096)

            record.startRecording()

            while (!stopRequested.get()) {
                if (isPausedFlag.get()) {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        runCatching { record.stop() }
                        synchronized(waveformHistory) {
                            waveformHistory.clear()
                            repeat(72) { waveformHistory.addLast(0f) }
                        }
                    }
                    Thread.sleep(60L)
                    continue
                } else if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { record.startRecording() }
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        runCatching { record.release() }
                        val (reopened, _) = openAudioRecord(actualRate, useRawMic)
                        if (reopened != null) {
                            record = reopened
                            runCatching { record.startRecording() }
                        }
                        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            pauseRecording(
                                byAudioFocus = false,
                                notice = AppLocaleManager.currentStrings().micLostNotice
                            )
                            Thread.sleep(80L)
                            continue
                        }
                    }
                }

                val read = record.read(readBuf, 0, frameSamples, AudioRecord.READ_NON_BLOCKING)
                if (isPausedFlag.get() || stopRequested.get()) continue
                if (read < 0) {
                    zeroReadCount++
                    if (zeroReadCount > 5) {
                        pauseRecording(
                            byAudioFocus = false,
                            notice = AppLocaleManager.currentStrings().micLostNotice
                        )
                        zeroReadCount = 0
                    }
                    Thread.sleep(50L)
                    continue
                }
                if (read == 0) {
                    Thread.sleep(12L)
                    continue
                }
                zeroReadCount = 0

                // 1. Waveform level (perceptual curve so quiet distant dictation visibly animates meter)
                var sumSq = 0.0
                for (i in 0 until read) {
                    val v = readBuf[i].toDouble() / 32768.0
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / read).toFloat()
                val level = (sqrt(rms.toDouble()).toFloat() * 1.15f).coerceIn(0.02f, 1.0f)
                synchronized(waveformHistory) {
                    if (waveformHistory.size >= 72) waveformHistory.removeFirst()
                    waveformHistory.addLast(level)
                }

                // 2. Write original audio (never blocked by ASR)
                if (isWav) {
                    ByteBuffer.wrap(byteBuf, 0, read * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(readBuf, 0, read)
                    wavOut!!.write(byteBuf, 0, read * 2)
                    audioDataBytes += read * 2L
                } else {
                    m4aWriter!!.writePcm16(readBuf, read)
                }

                // 3. Write 16kHz PCM16 spool for TinyASR (adaptive AGC for distant speech when enabled)
                var pcm16k = if (actualRate == 16_000) readBuf else resampler.process(readBuf, read)
                val pcm16kCount = if (actualRate == 16_000) read else pcm16k.size
                if (pcm16kCount > 0) {
                    if (asrNormalizer != null) {
                        if (pcm16k === readBuf) {
                            if (asrScratch.size < pcm16kCount) asrScratch = ShortArray(pcm16kCount)
                            System.arraycopy(readBuf, 0, asrScratch, 0, pcm16kCount)
                            pcm16k = asrScratch
                        }
                        asrNormalizer.processInPlace(pcm16k, pcm16kCount)
                    }
                    val bytesNeeded = pcm16kCount * 2
                    if (bytesNeeded > spoolWriteBuf.size) {
                        spoolWriteBuf = ByteArray(bytesNeeded)
                    }
                    ByteBuffer.wrap(spoolWriteBuf, 0, bytesNeeded)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(pcm16k, 0, pcm16kCount)
                    spoolOut.write(spoolWriteBuf, 0, bytesNeeded)
                    spoolOut.flush()
                    totalSpoolBytes.addAndGet(bytesNeeded.toLong())
                    synchronized(spoolLock) {
                        spoolLock.notifyAll()
                    }
                }

                capturedSamples += read
                val durationMs = (capturedSamples * 1000L) / actualRate
                val wfCopy = synchronized(waveformHistory) { waveformHistory.toList() }
                _uiState.update { state ->
                    val queueCursor = if (state.isGroqEngine) processedSpoolBytes.get() else localDecodedSpoolBytes.get()
                    state.copy(
                        durationMs = durationMs,
                        waveform = wfCopy,
                        backlogSeconds = (totalSpoolBytes.get() - queueCursor).coerceAtLeast(0L) / 32_000f
                    )
                }

                val nowMs = System.currentTimeMillis()
                if (isWav && nowMs - lastHeaderSyncMs >= 5_000L) {
                    lastHeaderSyncMs = nowMs
                    wavOut?.flush()
                    WavHelper.updateHeaderSizes(audioFile, audioDataBytes)
                }

                if (nowMs - lastDbProgressMs >= 2_500L) {
                    lastDbProgressMs = nowMs
                    val durSnapshot = durationMs
                    val totSpool = totalSpoolBytes.get()
                    val procSpool = processedSpoolBytes.get()
                    val fSize = audioFile.length()
                    val lang = _uiState.value.activeLanguage
                    serviceScope.launch {
                        app.repository.updateRecordingProgress(
                            id = rec.id,
                            durationMs = durSnapshot,
                            fileSizeBytes = fSize,
                            totalAsrBytes = totSpool,
                            processedAsrBytes = procSpool,
                            detectedLang = lang
                        )
                    }
                    val sNotif = AppLocaleManager.currentStrings()
                    updateNotification(
                        if (isPausedFlag.get()) "${sNotif.recordingPaused} · ${AsrTextFormatter.formatDurationMs(durSnapshot)}"
                        else "${sNotif.notifRecordingActive} · ${AsrTextFormatter.formatDurationMs(durSnapshot)}",
                        isPaused = isPausedFlag.get()
                    )
                }

                if (nowMs - lastStorageCheckMs >= 10_000L) {
                    lastStorageCheckMs = nowMs
                    val freeBytes = app.repository.getAvailableInternalStorageBytes()
                    val sWarn = AppLocaleManager.currentStrings()
                    if (freeBytes < 25L * 1024L * 1024L) {
                        _uiState.update { state ->
                            state.copy(
                                lowStorageWarning = true,
                                statusNotice = sWarn.criticalStorageStop
                            )
                        }
                        stopRecording()
                        break
                    } else if (freeBytes < 100L * 1024L * 1024L) {
                        _uiState.update { state ->
                            state.copy(
                                lowStorageWarning = true,
                                statusNotice = sWarn.lowStorageWarn
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            _uiState.update { state ->
                state.copy(
                    asrError = "${AppLocaleManager.currentStrings().audioWriteError}: ${t.message}"
                )
            }
        } finally {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
            runCatching {
                wavOut?.flush()
                wavOut?.close()
                if (isWav && audioDataBytes > 0L) {
                    WavHelper.updateHeaderSizes(audioFile, audioDataBytes)
                }
            }
            runCatching { m4aWriter?.close() }
            runCatching {
                spoolOut?.flush()
                spoolOut?.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(desiredRate: Int, useRawMic: Boolean): Pair<AudioRecord?, Int> {
        val sources = if (useRawMic) {
            listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }
        val rates = listOf(desiredRate, 16_000, 48_000, 44_100).distinct()

        for (source in sources) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AndroidAudioFormat.CHANNEL_IN_MONO,
                    AndroidAudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val bufSize = maxOf(minBuf * 3, rate * 2) // >= 1s ring in AudioRecord
                val rec = runCatching {
                    AudioRecord(
                        source,
                        rate,
                        AndroidAudioFormat.CHANNEL_IN_MONO,
                        AndroidAudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                }.getOrNull()
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec to rate
                }
                runCatching { rec?.release() }
            }
        }
        return null to desiredRate
    }

    private fun runAsrLoop(
        rec: RecordingEntity,
        initialPresetId: String,
        requestedThreads: Int,
        beam: Int,
        speakerDiarization: Boolean
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2)
        val threads = if (requestedThreads > 0) requestedThreads else 2
        activeThreadCount = threads

        var activePreset = TinyAsrCatalog.findPreset(initialPresetId)
        var modelLease: ModelLease? = null
        var sessionHandle = 0L
        var raf: RandomAccessFile? = null
        val speakerSession = if (speakerDiarization) {
            SpeakerAttributionSession(assets, speakerLabel()) {
                _uiState.update { it.copy(asrError = AppLocaleManager.currentStrings().speakerDiarizationUnavailable) }
            }
        } else null

        fun statusLabelFor(preset: com.stenograf.app.asr.LanguagePreset): String {
            val sNow = AppLocaleManager.currentStrings()
            return "${sNow.recordingLocalStatus} · ${preset.modelId.uppercase()} · $threads ${sNow.threadsWord}"
        }

        try {
            val initStrings = AppLocaleManager.currentStrings()
            val initialLease = runBlocking {
                app.modelManager.acquireModelLease(activePreset.modelId, threads)
            }
            modelLease = initialLease
            sessionHandle = TinyAsrNative.nativeSessionCreate(
                initialLease.handle,
                activePreset.langsCsv,
                activePreset.startLang,
                beam
            )
            if (sessionHandle == 0L) {
                throw IllegalStateException(initStrings.asrSessionError)
            }

            _uiState.update { state ->
                state.copy(
                    asrStatusText = statusLabelFor(activePreset),
                    activeLanguage = activePreset.startLang,
                    asrError = null
                )
            }

            val spoolFile = File(rec.asrSpoolPath)
            while (!spoolFile.exists() && !captureClosedFlag.get()) {
                Thread.sleep(20L)
            }
            if (!spoolFile.exists()) return

            val audio = RandomAccessFile(spoolFile, "r")
            raf = audio
            val labeler = speakerSession?.let { session ->
                LocalSpeakerLabeler(0L) { start, count ->
                    audio.seek(start)
                    val pcm = ByteArray(count)
                    audio.readFully(pcm)
                    session.analyze(pcm, start, destroyed::get)
                }
            }
            val commitSpeech: (SpeechSegment) -> Unit = { speech ->
                commitLocalSpeech(rec.id, speech.words, speech.speaker)
            }
            val enqueueSpeech: (List<TranscriptWord>) -> Unit = { words ->
                if (labeler == null) commitLocalSpeech(rec.id, words, null) else labeler.offer(words)
            }
            fun publishSpeakers(finished: Boolean = false) {
                labeler?.drain(totalSpoolBytes.get(), finished, commitSpeech)
                val draft = labeler?.draftWords().orEmpty()
                _uiState.update { it.copy(pendingSpeakerWords = draft) }
            }

            val chunkShorts = 640 * 4 // 160 ms
            val byteChunk = ByteArray(chunkShorts * 2)
            val shortChunk = ShortArray(chunkShorts)
            var readCursor = 0L

            while (true) {
                // Handle runtime language preset switch
                val switchPresetId = requestedPresetSwitch
                if (switchPresetId != null) {
                    requestedPresetSwitch = null
                    val newPreset = TinyAsrCatalog.findPreset(switchPresetId)
                    if (newPreset.id != activePreset.id) {
                        val finishJson = TinyAsrNative.nativeSessionFinish(sessionHandle)
                        handleAsrEvents(finishJson, enqueueSpeech)
                        publishSpeakers()
                        TinyAsrNative.nativeSessionFree(sessionHandle)
                        sessionHandle = 0L

                        val sNow = AppLocaleManager.currentStrings()
                        if (newPreset.modelId != activePreset.modelId) {
                            _uiState.update { it.copy(asrStatusText = "${sNow.switchingModel} (${newPreset.modelId.uppercase()})") }
                            val newLease = runBlocking {
                                app.modelManager.acquireModelLease(newPreset.modelId, threads)
                            }
                            runBlocking { app.modelManager.releaseModelLease(modelLease) }
                            modelLease = newLease
                        }
                        activePreset = newPreset
                        timeOffsetSec = (readCursor / 32_000f)
                        localParagraphBreaks.add(LocalParagraphBreak(timeOffsetSec))
                        sessionHandle = TinyAsrNative.nativeSessionCreate(
                            modelLease!!.handle,
                            activePreset.langsCsv,
                            activePreset.startLang,
                            beam
                        )
                        _uiState.update { state ->
                            state.copy(
                                modelId = activePreset.modelId,
                                languagePresetId = activePreset.id,
                                activeLanguage = activePreset.startLang,
                                asrStatusText = statusLabelFor(activePreset)
                            )
                        }
                    }
                }

                val available = totalSpoolBytes.get() - readCursor
                if (available >= 1280L || (captureClosedFlag.get() && available >= 2L)) {
                    val toReadBytes = minOf(available, byteChunk.size.toLong()).toInt() and 0x7FFFFFFE
                    if (toReadBytes >= 2) {
                        raf.seek(readCursor)
                        val got = raf.read(byteChunk, 0, toReadBytes)
                        if (got >= 2) {
                            val samplesCount = got / 2
                            ByteBuffer.wrap(byteChunk, 0, samplesCount * 2)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(shortChunk, 0, samplesCount)
                            readCursor += samplesCount * 2L

                            val json = TinyAsrNative.nativeSessionPushPcm16(sessionHandle, shortChunk, 0, samplesCount)
                            localDecodedSpoolBytes.set(readCursor)
                            handleAsrEvents(json, enqueueSpeech)
                            publishSpeakers()
                            val committedBytes = if (labeler == null) readCursor else {
                                val end = committedList.lastOrNull()?.endSec ?: 0f
                                ((end * LocalSpeakerLabeler.PCM_BYTES_PER_SECOND).toLong() and -2L)
                                    .coerceIn(0L, readCursor)
                            }
                            processedSpoolBytes.set(committedBytes)
                            val backlog = ((totalSpoolBytes.get() - readCursor).coerceAtLeast(0L)) / 32_000f
                            _uiState.update { it.copy(backlogSeconds = backlog) }
                            continue
                        }
                    }
                }

                if (captureClosedFlag.get() && totalSpoolBytes.get() - readCursor < 2L) {
                    break
                }

                synchronized(spoolLock) {
                    if (!captureClosedFlag.get() && totalSpoolBytes.get() - readCursor < 1280L) {
                        spoolLock.wait(120L)
                    }
                }
            }

            if (sessionHandle != 0L) {
                val finishJson = TinyAsrNative.nativeSessionFinish(sessionHandle)
                handleAsrEvents(finishJson, enqueueSpeech)
            }
            publishSpeakers(finished = true)
            processedSpoolBytes.set(readCursor)
            _uiState.update { state ->
                state.copy(
                    openWords = emptyList(),
                    tailWords = emptyList(),
                    isEngineProcessing = false,
                    backlogSeconds = 0f
                )
            }
        } catch (t: Throwable) {
            val sErr = AppLocaleManager.currentStrings()
            _uiState.update { state ->
                state.copy(
                    asrError = t.message ?: sErr.asrSessionError,
                    asrStatusText = sErr.asrPausedSavingAudio,
                    isEngineProcessing = false
                )
            }
        } finally {
            runCatching { raf?.close() }
            runCatching { speakerSession?.close() }
            if (sessionHandle != 0L) {
                TinyAsrNative.nativeSessionFree(sessionHandle)
            }
            runBlocking { app.modelManager.releaseModelLease(modelLease) }
            captureThread?.join()
            runBlocking { finalizeRecordingInDatabase(rec) }
        }
    }

    private suspend fun runGroqLiveLoop(
        rec: RecordingEntity,
        modelId: String,
        speakerDiarization: Boolean,
        commitLock: Mutex,
        cloudUnavailable: AtomicBoolean
    ) {
        val worker = currentCoroutineContext()
        var speakerWarning: String? = null
        val speakers = if (speakerDiarization) {
            SpeakerAttributionSession(assets, speakerLabel()) {
                speakerWarning = AppLocaleManager.currentStrings().speakerDiarizationUnavailable
            }
        } else null
        try {
            val spool = File(rec.asrSpoolPath)
            while (!spool.exists() && !captureClosedFlag.get()) delay(40L)
            if (!spool.exists()) return
            RandomAccessFile(spool, "r").use { input ->
                drainGroqLiveAudio(
                    input = input,
                    availableBytes = totalSpoolBytes::get,
                    captureClosed = captureClosedFlag::get,
                    capturePaused = isPausedFlag::get,
                    stopRequested = stopRequested::get,
                    transcribe = { pcm ->
                        _uiState.update { it.copy(isEngineProcessing = !it.isGroqRetrying) }
                        val latestSettings = app.preferences.settingsFlow.first()
                        GroqAsrClient.transcribeChunk(
                            latestSettings.groqApiKey,
                            modelId,
                            pcm,
                            languageMode = latestSettings.groqLanguageMode,
                            retryOnce = false
                        )
                    },
                    onRetry = { error ->
                        cloudUnavailable.set(true)
                        _uiState.update {
                            it.copy(
                                asrError = GroqAsrClient.errorMessage(error),
                                isGroqRetrying = true,
                                isEngineProcessing = false
                            )
                        }
                    }
                ) { offsetBytes, pcm, parsed ->
                    cloudUnavailable.set(false)
                    val segments = if (speakers != null && parsed.segments.isNotEmpty()) {
                        speakers.analyze(pcm, offsetBytes) { !worker.isActive }.assign(parsed.segments)
                    } else parsed.segments

                    commitLock.withLock {
                        worker.ensureActive()
                        val offset = offsetBytes.toFloat() / GroqAsrClient.PCM_BYTES_PER_SECOND
                        val batch = appendSpeechParagraphs(
                            committedList.lastOrNull(),
                            segments.map { it.toParagraph(rec.id, 0, offset) },
                            nextOrderIndex
                        )
                        withContext(NonCancellable) {
                            val processed = offsetBytes + pcm.size
                            val ids = app.repository.commitLiveTranscript(rec.id, batch.newParagraphs, processed, batch.updatedLast)
                            batch.updatedLast?.let { committedList[committedList.lastIndex] = it }
                            committedList.addAll(batch.newParagraphs.zip(ids) { paragraph, id -> paragraph.copy(id = id) })
                            nextOrderIndex += batch.newParagraphs.size
                            processedSpoolBytes.set(processed)
                            if (!destroyed.get()) {
                                _uiState.update {
                                    it.copy(
                                        activeLanguage = parsed.language.ifBlank { it.activeLanguage },
                                        committedParagraphs = if (batch.updatedLast != null || batch.newParagraphs.isNotEmpty()) committedList.toList()
                                            else it.committedParagraphs,
                                        asrError = speakerWarning,
                                        isGroqRetrying = false,
                                        backlogSeconds = (totalSpoolBytes.get() - processed).coerceAtLeast(0L) /
                                            GroqAsrClient.PCM_BYTES_PER_SECOND.toFloat(),
                                        asrStatusText = if (it.isFinishing) it.asrStatusText else "Groq · $modelId",
                                        isEngineProcessing = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!destroyed.get() && worker.isActive) {
                _uiState.update {
                    it.copy(
                        asrError = if (e is IOException) GroqAsrClient.errorMessage(e) else e.message ?: "Groq недоступен.",
                        asrStatusText = AppLocaleManager.currentStrings().asrPausedSavingAudio,
                        isGroqRetrying = false,
                        isEngineProcessing = false
                    )
                }
            }
        } finally {
            speakers?.close()
        }
    }

    private fun commitLocalSpeech(recordingId: Long, words: List<TranscriptWord>, speaker: String?) {
        if (words.isEmpty()) return
        var cursor = 0
        while (cursor < words.size) {
            val mark = localParagraphBreaks.peek() ?: break
            val next = (cursor until words.size).firstOrNull { words[it].end >= mark.atSec } ?: break
            if (next > cursor) commitWords(recordingId, words.subList(cursor, next), speaker)
            localParagraphBreaks.poll()
            paragraphAccumulator.requestParagraphBreak(mark.label)
            cursor = next
        }
        if (cursor < words.size) commitWords(recordingId, words.subList(cursor, words.size), speaker)
    }

    private fun commitWords(recordingId: Long, shifted: List<TranscriptWord>, speaker: String?) {
        if (shifted.isEmpty()) return
        val update = paragraphAccumulator.acceptCommit(shifted, speaker)
        update.updatedLastParagraph?.let { draft ->
            val updatedEntity = ParagraphEntity(
                id = currentParagraphEntityId,
                recordingId = recordingId,
                orderIndex = (nextOrderIndex - 1).coerceAtLeast(0),
                startSec = draft.startSec,
                endSec = draft.endSec,
                text = draft.text,
                wordsJson = AsrJsonParser.encodeWords(draft.words),
                isBookmark = draft.isBookmark,
                bookmarkLabel = draft.bookmarkLabel,
                speaker = draft.speaker
            )
            runBlocking { app.repository.updateParagraph(updatedEntity) }
            if (committedList.isNotEmpty()) {
                committedList[committedList.lastIndex] = updatedEntity
            }
        }
        update.newParagraph?.let { draft ->
            val newEntity = ParagraphEntity(
                recordingId = recordingId,
                orderIndex = nextOrderIndex++,
                startSec = draft.startSec,
                endSec = draft.endSec,
                text = draft.text,
                wordsJson = AsrJsonParser.encodeWords(draft.words),
                isBookmark = draft.isBookmark,
                bookmarkLabel = draft.bookmarkLabel,
                speaker = draft.speaker
            )
            val rowId = runBlocking { app.repository.insertParagraph(newEntity) }
            currentParagraphEntityId = rowId
            committedList.add(newEntity.copy(id = rowId))
        }
        _uiState.update { state ->
            state.copy(
                committedParagraphs = committedList.toList()
            )
        }
    }

    private fun handleAsrEvents(rawJson: String?, onCommit: (List<TranscriptWord>) -> Unit) {
        val events = AsrJsonParser.parseEvents(rawJson)
        if (events.isEmpty()) return

        for (ev in events) {
            when (ev) {
                is AsrEvent.Commit -> {
                    val shifted = if (timeOffsetSec == 0f) ev.words else ev.words.map {
                        it.copy(start = it.start + timeOffsetSec, end = it.end + timeOffsetSec)
                    }
                    _uiState.update { it.copy(openWords = emptyList(), tailWords = emptyList()) }
                    onCommit(shifted)
                }
                is AsrEvent.Partial -> {
                    val openShifted = if (timeOffsetSec == 0f) ev.open else ev.open.map {
                        it.copy(start = it.start + timeOffsetSec, end = it.end + timeOffsetSec)
                    }
                    val tailShifted = if (timeOffsetSec == 0f) ev.tail else ev.tail.map {
                        it.copy(start = it.start + timeOffsetSec, end = it.end + timeOffsetSec)
                    }
                    _uiState.update { state ->
                        state.copy(
                            openWords = openShifted,
                            tailWords = tailShifted
                        )
                    }
                }
                is AsrEvent.Language -> {
                    _uiState.update { it.copy(activeLanguage = ev.lang) }
                }
                is AsrEvent.Eou -> Unit
            }
        }
    }

    private suspend fun finalizeRecordingInDatabase(rec: RecordingEntity) {
        setLiveAiEnabled(false)
        val audioFile = File(rec.audioPath)
        val spoolFile = File(rec.asrSpoolPath)
        val totSpool = totalSpoolBytes.get()
        val procSpool = processedSpoolBytes.get()
        val fullyProcessed = procSpool >= (totSpool - 3200L).coerceAtLeast(0L)

        val state = destroyedSnapshot ?: _uiState.value
        val finalDur = state.durationMs.coerceAtLeast(totSpool * 1000L / GroqAsrClient.PCM_BYTES_PER_SECOND)
        var failure: String? = null
        try {
            val latest = app.repository.getRecording(rec.id) ?: return
            val paragraphs = app.repository.getParagraphs(rec.id)
            val wordCount = paragraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
            val preview = paragraphs.asSequence().map { it.text }.take(20).joinToString(" ").take(220)
            app.repository.updateRecording(
                latest.copy(
                    status = RecordingStatus.COMPLETED.name,
                    durationMs = finalDur,
                    fileSizeBytes = if (audioFile.exists()) audioFile.length() else 0L,
                    asrSpoolPath = if (fullyProcessed) "" else rec.asrSpoolPath,
                    totalAsrBytes = totSpool,
                    processedAsrBytes = procSpool,
                    modelId = state.modelId,
                    languagePresetId = state.languagePresetId,
                    detectedLanguage = state.activeLanguage,
                    hasTranscript = paragraphs.isNotEmpty(),
                    wordCount = wordCount,
                    previewText = preview,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            if (fullyProcessed && spoolFile.absolutePath != audioFile.absolutePath) spoolFile.delete()
        } catch (e: Exception) {
            failure = e.message ?: AppLocaleManager.currentStrings().audioWriteError
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (!destroyed.get()) {
                    releaseAudioFocus()
                    releaseWakeLock()
                    val notice = failure ?: _uiState.value.asrError ?: if (state.isGroqEngine && !fullyProcessed) {
                        "Аудио сохранено. Оставшийся фрагмент можно расшифровать в библиотеке."
                    } else null
                    _uiState.value = LiveRecordingUiState(finishedRecordingId = rec.id, asrError = notice)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    currentRecording = null
                    isRecordingFlag.set(false)
                }
            }
        }
    }

    fun pauseRecording(byAudioFocus: Boolean = false, notice: String? = null) {
        if (!isRecordingFlag.get() || isPausedFlag.get() || stopRequested.get()) return
        val strings = AppLocaleManager.currentStrings()
        isPausedFlag.set(true)
        pausedByAudioFocus.set(byAudioFocus)
        if (!byAudioFocus) {
            releaseAudioFocus()
        }
        _uiState.update { state ->
            state.copy(
                isPaused = true,
                statusNotice = notice ?: strings.pausedPrompt,
                waveform = List(72) { 0f }
            )
        }
        updateNotification("${strings.recordingPaused} · ${AsrTextFormatter.formatDurationMs(_uiState.value.durationMs)}", isPaused = true)
    }

    fun resumeRecording() {
        if (!isRecordingFlag.get() || !isPausedFlag.get() || stopRequested.get()) return
        val strings = AppLocaleManager.currentStrings()
        pausedByAudioFocus.set(false)
        requestAudioFocus()
        isPausedFlag.set(false)
        _uiState.update { state ->
            state.copy(
                isPaused = false,
                statusNotice = null
            )
        }
        updateNotification("${strings.notifRecordingActive} · ${AsrTextFormatter.formatDurationMs(_uiState.value.durationMs)}", isPaused = false)
    }

    fun addBookmark(customLabel: String? = null) {
        if (!isRecordingFlag.get()) return
        val strings = AppLocaleManager.currentStrings()
        val label = customLabel?.takeIf { it.isNotBlank() }
            ?: "${strings.bookmarkDefaultLabel} ${AsrTextFormatter.formatDurationMs(_uiState.value.durationMs)}"
        if (!_uiState.value.isGroqEngine) {
            localParagraphBreaks.add(LocalParagraphBreak(_uiState.value.durationMs / 1000f, label))
        }
        _uiState.update { it.copy(statusNotice = "${strings.bookmarkAddedNotice}: $label") }
    }

    @Synchronized
    internal fun setLiveAiEnabled(enabled: Boolean, model: TranscriptReviewModel = TranscriptReviewModel.Fast) {
        if (!enabled) {
            liveAiGeneration.incrementAndGet()
            liveAiJob?.cancel()
            liveAiJob = null
            _uiState.update { it.copy(liveAiEnabled = false, liveAiWorking = false) }
            return
        }
        val record = currentRecording ?: return
        val snapshot = _uiState.value
        if (!snapshot.isActive || snapshot.isFinishing || snapshot.recordingId != record.id || liveAiJob?.isActive == true) return
        val seen = snapshot.committedParagraphs.dropLast(1).map { it.id }.toMutableSet()
        val generation = liveAiGeneration.incrementAndGet()
        _uiState.update { it.copy(liveAiEnabled = true) }
        liveAiJob = serviceScope.launch(Dispatchers.IO) {
            try {
                app.repository.observeParagraphs(record.id).collect { paragraphs ->
                    for ((index, paragraph) in paragraphs.dropLast(1).withIndex()) {
                        if (!seen.add(paragraph.id) || paragraph.endSec <= paragraph.startSec) continue
                        val state = _uiState.value
                        if (state.recordingId != record.id || state.isFinishing) return@collect
                        val settings = app.preferences.settingsFlow.first()
                        if (settings.groqApiKey.isBlank()) throw IllegalStateException("Groq API key is missing")
                        _uiState.update { it.copy(liveAiWorking = true) }
                        try {
                            val liveAiLang = if (state.isGroqEngine) {
                                settings.groqLanguageMode
                            } else {
                                GroqAsrClient.resolveReviewLanguageMode(forceRussian = state.languagePresetId == "ru_only")
                            }
                            val result = app.reTranscribeWorker.reviewAudio(
                                record, paragraph, settings.groqApiKey, settings.groqModelId,
                                model,
                                liveAiLang,
                                paragraphs.getOrNull(index - 1)?.text.orEmpty(),
                                paragraphs.getOrNull(index + 1)?.text.orEmpty(),
                                "", {}
                            )
                            coroutineContext.ensureActive()
                            if (result.proposal.uncertain.isEmpty() && result.proposal.correctedText != paragraph.text &&
                                !_uiState.value.isFinishing && liveAiGeneration.get() == generation
                            ) {
                                app.repository.applyTranscriptReview(paragraph, result.proposal.correctedText, result.recognizedWords)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is java.io.IOException) throw e
                        } finally {
                            if (liveAiGeneration.get() == generation) _uiState.update { it.copy(liveAiWorking = false) }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                val russian = AppLocaleManager.currentLanguage.value.code == "ru"
                _uiState.update {
                    it.copy(statusNotice = if (russian) "Автопроверка ИИ остановлена. Проверьте ключ и связь Groq."
                    else "Automatic AI review stopped. Check your Groq key and connection.")
                }
            } finally {
                _uiState.update {
                    if (it.recordingId == record.id && liveAiGeneration.get() == generation) {
                        it.copy(liveAiEnabled = false, liveAiWorking = false)
                    } else it
                }
            }
        }
    }

    fun switchLanguagePreset(presetId: String) {
        if (!isRecordingFlag.get()) return
        requestedPresetSwitch = presetId
    }

    fun stopRecording() {
        setLiveAiEnabled(false)
        if (!stopRequested.compareAndSet(false, true)) return
        if (!isRecordingFlag.get() && !sessionStarting.get()) {
            stopSelf()
            return
        }
        val strings = AppLocaleManager.currentStrings()
        isPausedFlag.set(false)
        _uiState.update { state ->
            state.copy(
                isFinishing = true,
                asrStatusText = strings.finishingTranscript
            )
        }
        updateNotification(strings.notifSaving, isPaused = false)
    }

    private fun requestAudioFocus() {
        synchronized(audioFocusLock) {
            releaseAudioFocusLocked()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            var createdReq: AudioFocusRequest? = null
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusRequest !== createdReq) return@setOnAudioFocusChangeListener
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            if (isRecordingFlag.get() && !isPausedFlag.get()) {
                                pauseRecording(
                                    byAudioFocus = true,
                                    notice = AppLocaleManager.currentStrings().audioFocusLostNotice
                                )
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (isRecordingFlag.get() && isPausedFlag.get() && pausedByAudioFocus.get()) {
                                resumeRecording()
                            }
                        }
                    }
                }
                .build()
            createdReq = req
            focusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun releaseAudioFocus() {
        synchronized(audioFocusLock) {
            releaseAudioFocusLocked()
        }
    }

    private fun releaseAudioFocusLocked() {
        val prev = focusRequest
        focusRequest = null
        prev?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stenograf::RecordingWakeLock").apply {
            setReferenceCounted(false)
            acquire(12L * 60L * 60L * 1000L) // safety cap 12h
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl ->
            runCatching { if (wl.isHeld) wl.release() }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val strings = AppLocaleManager.currentStrings()
        val channel = NotificationChannel(
            CHANNEL_ID,
            strings.notifChannelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = strings.notifChannelDesc
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String, isPaused: Boolean): Notification {
        val strings = AppLocaleManager.currentStrings()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingForegroundService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bookmarkIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingForegroundService::class.java).apply {
                action = ACTION_BOOKMARK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = currentRecording?.title ?: strings.appName
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0,
                if (isPaused) strings.resumeButton else strings.pauseButton,
                pauseOrResumeIntent
            )
            .addAction(0, strings.bookmarkButton, bookmarkIntent)
            .addAction(0, strings.stopButton, stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String, isPaused: Boolean) {
        createNotificationChannel()
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText, isPaused))
    }

    override fun onDestroy() {
        destroyed.set(true)
        stopRequested.set(true)
        val id = currentRecording?.id
        destroyedSnapshot = _uiState.value.takeIf { it.recordingId == id }
        _uiState.update { if (it.isActive && it.recordingId == id) LiveRecordingUiState() else it }
        synchronized(spoolLock) { spoolLock.notifyAll() }
        releaseAudioFocus()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
