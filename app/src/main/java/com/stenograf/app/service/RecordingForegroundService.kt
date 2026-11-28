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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.stenograf.app.MainActivity
import com.stenograf.app.R
import com.stenograf.app.StenografApp
import com.stenograf.app.asr.AsrEvent
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.GigaAmV4Native
import com.stenograf.app.asr.GigaAmV4OnlineLease
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
import com.stenograf.app.asr.mergeDetectedLanguages
import com.stenograf.app.audio.M4aAudioWriter
import com.stenograf.app.audio.NeuralSpeechEnhancer
import com.stenograf.app.audio.AlignedCaptureFilter
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

data class LiveRecordingUiState(
    val isActive: Boolean = false,
    val isStarting: Boolean = false,
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
    private val captureClosedFlag = AtomicBoolean(false)
    private val originalCaptureFailed = AtomicBoolean(false)
    private val capturedAudio = AtomicBoolean(false)
    private val spoolCaptureFailed = AtomicBoolean(false)
    private val asrCommitLock = Mutex()
    private val asrWritableRecordingId = AtomicLong(0L)

    @Volatile
    private var currentRecording: RecordingEntity? = null
    private data class PresetSwitch(val recordingId: Long, val presetId: String, val audioByte: Long)
    private val requestedPresetSwitch = AtomicReference<PresetSwitch?>(null)
    private val observedLiveLang = AtomicReference<String?>(null)

    private val waveformHistory = ArrayDeque<Float>(72)
    @Volatile
    private var paragraphAccumulator = ParagraphAccumulator()
    private data class LocalParagraphBreak(val atSec: Float, val label: String? = null)
    private val localParagraphBreaks = ConcurrentLinkedQueue<LocalParagraphBreak>()
    private val committedList = mutableListOf<ParagraphEntity>()
    private var currentParagraphEntityId: Long = 0L
    private var nextOrderIndex: Int = 0

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
                    val strings = AppLocaleManager.currentStrings()
                    _uiState.value = LiveRecordingUiState(isStarting = true)
                    try {
                        startForegroundCompat(strings.preparingModel, isPaused = false)
                    } catch (failure: Exception) {
                        sessionStarting.set(false)
                        reportStartFailure(failure)
                        return START_NOT_STICKY
                    }
                    serviceScope.launch {
                        try {
                            beginRecordingSession(folderId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            reportStartFailure(failure)
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

    private fun reportStartFailure(failure: Exception) {
        android.util.Log.w("Vanta", "Could not start microphone recording", failure)
        _uiState.value = LiveRecordingUiState(asrError = AppLocaleManager.currentStrings().micAccessFailed)
        stopSelf()
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
            _uiState.value = LiveRecordingUiState(asrError = strings.audioFileNotFound)
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

        val useGroqLive = settings.asrEngineMode == AsrEngineMode.GROQ_API && settings.groqApiKey.isNotBlank()
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
        val localDecodedSpoolBytes = AtomicLong()
        captureClosedFlag.set(false)
        originalCaptureFailed.set(false)
        capturedAudio.set(false)
        spoolCaptureFailed.set(false)
        isPausedFlag.set(false)
        pausedByAudioFocus.set(false)
        isRecordingFlag.set(true)
        requestedPresetSwitch.set(null)
        observedLiveLang.set(null)
        asrWritableRecordingId.set(rec.id)

        waveformHistory.clear()
        repeat(72) { waveformHistory.addLast(0f) }
        committedList.clear()
        paragraphAccumulator = ParagraphAccumulator(autoParagraph = settings.autoParagraph)
        localParagraphBreaks.clear()
        currentParagraphEntityId = 0L
        nextOrderIndex = 0

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

        runCatching { acquireWakeLock() }
        runCatching { requestAudioFocus() }
        if (!stopRequested.get()) runCatching { updateNotification(strings.notifRecordingActive, isPaused = false) }

        val captureFinished = CompletableDeferred<Unit>()
        Thread({
            try {
                runCaptureLoop(
                    rec = rec,
                    audioFormat = settings.audioFormat,
                    useRawMic = settings.useRawMicrophone,
                    distantSpeechBoost = settings.distantSpeechBoost,
                    neuralNoiseSuppression = settings.neuralNoiseSuppression,
                    fastEnhancer = settings.fastEnhancer,
                    localDecodedSpoolBytes = localDecodedSpoolBytes
                )
            } catch (e: Exception) {
                originalCaptureFailed.set(true)
                _uiState.update { it.copy(asrError = e.message) }
            } finally {
                captureClosedFlag.set(true)
                synchronized(spoolLock) { spoolLock.notifyAll() }
                captureFinished.complete(Unit)
            }
        }, "Stenograf-AudioCapture").apply { start() }

        val cloudUnavailable = AtomicBoolean(false)
        val localFinished = CompletableDeferred<Unit>()
        val cloud = if (useGroqLive) {
            serviceScope.launch(Dispatchers.IO) {
                runGroqLiveLoop(
                    rec = rec,
                    modelId = settings.groqModelId,
                    speakerDiarization = settings.speakerDiarization,
                    autoLearnSpeakers = settings.autoLearnSpeakers,
                    cloudUnavailable = cloudUnavailable
                )
            }
        } else {
            Thread({
                try {
                    runAsrLoop(
                        rec = rec,
                        initialPresetId = preset.id,
                        requestedThreads = settings.asrThreads,
                        beam = settings.asrBeam,
                        speakerDiarization = settings.speakerDiarization,
                        autoLearnSpeakers = settings.autoLearnSpeakers,
                        localDecodedSpoolBytes = localDecodedSpoolBytes
                    )
                } finally {
                    localFinished.complete(Unit)
                }
            }, "Stenograf-TinyASR").start()
            null
        }
        app.applicationScope.launch(Dispatchers.IO) {
            captureFinished.await()
            try {
                if (cloud == null) {
                    withTimeoutOrNull(1_500L) { localFinished.await() }
                } else if (!cloudUnavailable.get()) {
                    withTimeoutOrNull(2_500L) { cloud.join() }
                }
            } finally {
                cloud?.cancel()
                withContext(NonCancellable) {
                    asrCommitLock.withLock { asrWritableRecordingId.set(0L) }
                    synchronized(spoolLock) { spoolLock.notifyAll() }
                    finalizeRecordingInDatabase(rec)
                }
            }
        }
    }

    private fun isAsrOpen(recordingId: Long): Boolean = asrWritableRecordingId.get() == recordingId

    private fun updateAsrUi(recordingId: Long, update: (LiveRecordingUiState) -> LiveRecordingUiState) {
        _uiState.update { state ->
            if (isAsrOpen(recordingId) && state.recordingId == recordingId) update(state) else state
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop(
        rec: RecordingEntity,
        audioFormat: AudioFormat,
        useRawMic: Boolean,
        distantSpeechBoost: Boolean,
        neuralNoiseSuppression: Boolean,
        fastEnhancer: Boolean,
        localDecodedSpoolBytes: AtomicLong
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (stopRequested.get()) return

        val desiredRate = when (audioFormat) {
            AudioFormat.WAV_16K -> 16_000
            AudioFormat.M4A_48K, AudioFormat.WAV_48K -> 48_000
        }

        val (initialRecord, actualRate) = openAudioRecord(desiredRate, useRawMic)
        if (initialRecord == null) {
            originalCaptureFailed.set(true)
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
        var lastHeaderSyncMs = SystemClock.elapsedRealtime()
        var lastDbProgressMs = lastHeaderSyncMs
        var lastStorageCheckMs = lastHeaderSyncMs
        var zeroReadCount = 0
        val neuralEnhancer = if (neuralNoiseSuppression) NeuralSpeechEnhancer.tryCreate(assets, preferFast = fastEnhancer) else null
        val captureFilter = neuralEnhancer?.let { enhancer ->
            AlignedCaptureFilter(enhancer::processShorts, enhancer::flushShorts, enhancer::reset) { failure ->
                android.util.Log.w("Vanta", "Live speech filter failed; using unfiltered audio", failure)
            }
        }

        fun reportSpoolFailure(failure: Throwable) {
            spoolCaptureFailed.set(true)
            android.util.Log.w("Vanta", "Live transcription spool stopped; original capture continues", failure)
            val message = if (AppLocaleManager.currentLanguage.value.code == "ru") {
                "Живой текст приостановлен. Запись аудио продолжается."
            } else "Live transcription paused. Audio recording continues."
            _uiState.update { it.copy(asrError = message) }
        }

        try {
            if (isWav) {
                wavOut = BufferedOutputStream(FileOutputStream(audioFile), 64 * 1024)
                wavOut.write(WavHelper.createHeader(actualRate, 1, 16, 0L))
            } else {
                m4aWriter = M4aAudioWriter(audioFile, actualRate, 128_000)
            }
            spoolOut = try {
                BufferedOutputStream(FileOutputStream(spoolFile), 64 * 1024)
            } catch (failure: Exception) {
                reportSpoolFailure(failure)
                null
            }

            val frameSamples = (actualRate * 40) / 1000 // 40ms frame
            val readBuf = ShortArray(frameSamples)
            val byteBuf = ByteArray(frameSamples * 2)
            val asrNormalizer = if (distantSpeechBoost) SpeechNormalizer(sampleRate = 16_000) else null
            var spoolWriteBuf = ByteArray(4096)
            var wasPaused = false

            fun writeSpoolSamples(samples: ShortArray, count: Int) {
                if (count <= 0) return
                val outStream = spoolOut ?: return
                try {
                    asrNormalizer?.processInPlace(samples, count)
                    val bytesNeeded = count * 2
                    if (bytesNeeded > spoolWriteBuf.size) {
                        spoolWriteBuf = ByteArray(bytesNeeded)
                    }
                    ByteBuffer.wrap(spoolWriteBuf, 0, bytesNeeded)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(samples, 0, count)
                    outStream.write(spoolWriteBuf, 0, bytesNeeded)
                    outStream.flush()
                    totalSpoolBytes.addAndGet(bytesNeeded.toLong())
                    synchronized(spoolLock) {
                        spoolLock.notifyAll()
                    }
                } catch (failure: Exception) {
                    spoolOut = null
                    runCatching { outStream.close() }
                    reportSpoolFailure(failure)
                }
            }

            record.startRecording()

            while (!stopRequested.get()) {
                if (isPausedFlag.get()) {
                    if (!wasPaused) {
                        captureFilter?.drain()?.let { writeSpoolSamples(it, it.size) }
                    }
                    wasPaused = true
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        runCatching { record.stop() }
                        synchronized(waveformHistory) {
                            waveformHistory.clear()
                            repeat(72) { waveformHistory.addLast(0f) }
                        }
                    }
                    Thread.sleep(60L)
                    continue
                } else {
                    if (wasPaused) {
                        captureFilter?.restart()
                        asrNormalizer?.reset()
                        wasPaused = false
                    }
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        runCatching { record.startRecording() }
                        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            runCatching { record.release() }
                            val (reopened, _) = openAudioRecord(actualRate, useRawMic, allowFallbackRates = false)
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

                capturedSamples += read
                capturedAudio.set(true)
                if (!spoolCaptureFailed.get()) {
                    val pcm = if (actualRate == 16_000) readBuf else resampler.process(readBuf, read)
                    val count = if (actualRate == 16_000) read else pcm.size
                    if (count > 0) {
                        val enhanced = captureFilter?.push(pcm, count)
                        if (enhanced != null) {
                            writeSpoolSamples(enhanced, enhanced.size)
                        } else if (asrNormalizer != null && pcm === readBuf) {
                            writeSpoolSamples(pcm.copyOf(count), count)
                        } else {
                            writeSpoolSamples(pcm, count)
                        }
                    }
                }

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

                val nowMs = SystemClock.elapsedRealtime()
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
                    if (!stopRequested.get()) {
                        val sNotif = AppLocaleManager.currentStrings()
                        val paused = isPausedFlag.get()
                        val label = if (paused) sNotif.recordingPaused else sNotif.notifRecordingActive
                        updateNotification("$label · ${AsrTextFormatter.formatDurationMs(durSnapshot)}", isPaused = paused)
                    }
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
            captureFilter?.drain()?.let { writeSpoolSamples(it, it.size) }
        } catch (t: Throwable) {
            originalCaptureFailed.set(true)
            _uiState.update { state ->
                state.copy(
                    asrError = "${AppLocaleManager.currentStrings().audioWriteError}: ${t.message}"
                )
            }
        } finally {
            runCatching { neuralEnhancer?.close() }
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { record.release() }
            runCatching { wavOut?.close() }.onFailure { originalCaptureFailed.set(true) }
            runCatching {
                if (isWav && audioDataBytes > 0L) WavHelper.updateHeaderSizes(audioFile, audioDataBytes)
            }.onFailure { originalCaptureFailed.set(true) }
            runCatching { m4aWriter?.close() }.onFailure { originalCaptureFailed.set(true) }
            runCatching { spoolOut?.close() }.onFailure(::reportSpoolFailure)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(
        desiredRate: Int, useRawMic: Boolean, allowFallbackRates: Boolean = true
    ): Pair<AudioRecord?, Int> {
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
        val rates = if (allowFallbackRates) listOf(desiredRate, 16_000, 48_000, 44_100).distinct()
            else listOf(desiredRate)

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

    private fun createSpeakerSession(
        autoLearnSpeakers: Boolean,
        onUnavailable: () -> Unit
    ): SpeakerAttributionSession {
        val stored = runCatching { runBlocking { app.repository.getAllStoredVoiceprints() } }.getOrDefault(emptyList())
        return SpeakerAttributionSession(
            assets = assets,
            label = speakerLabel(),
            autoVoicePrefix = autoVoicePrefix(),
            autoLearnSpeakers = autoLearnSpeakers,
            storedProfiles = stored,
            onPersistUpdates = { updates ->
                runBlocking {
                    app.repository.persistLearnedVoiceprints(updates)
                }
            },
            onUnavailable = onUnavailable
        )
    }

    private sealed interface LiveAsrLease {
        val modelId: String

        data class Tiny(val lease: ModelLease) : LiveAsrLease {
            override val modelId: String get() = lease.modelId
        }

        data class GigaV4(val lease: GigaAmV4OnlineLease) : LiveAsrLease {
            override val modelId: String get() = lease.modelId
        }
    }

    private sealed interface LiveAsrSession {
        val handle: Long

        data class Tiny(override val handle: Long) : LiveAsrSession
        data class GigaV4(override val handle: Long) : LiveAsrSession
    }

    private suspend fun acquireLiveLease(preset: com.stenograf.app.asr.LanguagePreset, threads: Int): LiveAsrLease =
        if (preset.modelId == TinyAsrCatalog.GIGAAM_V4) {
            LiveAsrLease.GigaV4(app.modelManager.acquireGigaV4OnlineLease(threads.coerceIn(1, 4)))
        } else {
            LiveAsrLease.Tiny(app.modelManager.acquireModelLease(preset.modelId, threads))
        }

    private suspend fun releaseLiveLease(lease: LiveAsrLease?) {
        when (lease) {
            is LiveAsrLease.Tiny -> app.modelManager.releaseModelLease(lease.lease)
            is LiveAsrLease.GigaV4 -> app.modelManager.releaseGigaV4OnlineLease(lease.lease)
            null -> Unit
        }
    }

    private fun createLiveSession(
        lease: LiveAsrLease,
        preset: com.stenograf.app.asr.LanguagePreset,
        beam: Int
    ): LiveAsrSession? = when (lease) {
        is LiveAsrLease.Tiny -> TinyAsrNative.nativeSessionCreate(
            lease.lease.handle,
            preset.langsCsv,
            preset.startLang,
            beam
        ).takeIf { it != 0L }?.let(LiveAsrSession::Tiny)
        is LiveAsrLease.GigaV4 -> GigaAmV4Native.nativeStreamCreate(lease.lease.handle)
            .takeIf { it != 0L }
            ?.let(LiveAsrSession::GigaV4)
    }

    private fun pushLivePcm16(session: LiveAsrSession, pcm: ShortArray, samplesCount: Int): String? = when (session) {
        is LiveAsrSession.Tiny -> TinyAsrNative.nativeSessionPushPcm16(session.handle, pcm, 0, samplesCount)
        is LiveAsrSession.GigaV4 -> GigaAmV4Native.nativeStreamPushPcm16(session.handle, pcm, 0, samplesCount)
    }

    private fun finishLiveSession(session: LiveAsrSession): String? = when (session) {
        is LiveAsrSession.Tiny -> TinyAsrNative.nativeSessionFinish(session.handle)
        is LiveAsrSession.GigaV4 -> GigaAmV4Native.nativeStreamFinish(session.handle)
    }

    private fun freeLiveSession(session: LiveAsrSession?) {
        when (session) {
            is LiveAsrSession.Tiny -> if (session.handle != 0L) TinyAsrNative.nativeSessionFree(session.handle)
            is LiveAsrSession.GigaV4 -> if (session.handle != 0L) GigaAmV4Native.nativeStreamFree(session.handle)
            null -> Unit
        }
    }

    private fun runAsrLoop(
        rec: RecordingEntity,
        initialPresetId: String,
        requestedThreads: Int,
        beam: Int,
        speakerDiarization: Boolean,
        autoLearnSpeakers: Boolean,
        localDecodedSpoolBytes: AtomicLong
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2)
        val threads = if (requestedThreads > 0) requestedThreads else
            TinyAsrNative.nativePinToBigCores().coerceIn(2, 4)

        var activePreset = TinyAsrCatalog.findPreset(initialPresetId)
        var timeOffsetSec = 0f
        var modelLease: LiveAsrLease? = null
        var liveSession: LiveAsrSession? = null
        var raf: RandomAccessFile? = null
        val speakerSession = if (speakerDiarization) {
            createSpeakerSession(autoLearnSpeakers) {
                updateAsrUi(rec.id) { it.copy(asrError = AppLocaleManager.currentStrings().speakerDiarizationUnavailable) }
            }
        } else null

        fun statusLabelFor(preset: com.stenograf.app.asr.LanguagePreset): String {
            val sNow = AppLocaleManager.currentStrings()
            val modelLabel = if (preset.modelId == TinyAsrCatalog.GIGAAM_V4) "GigaAM v4" else preset.modelId.uppercase()
            return "${sNow.recordingLocalStatus} · $modelLabel · $threads ${sNow.threadsWord}"
        }

        fun commitSpeech(words: List<TranscriptWord>, speaker: String?) {
            if (!isAsrOpen(rec.id)) return
            runBlocking {
                asrCommitLock.withLock {
                    if (isAsrOpen(rec.id)) commitLocalSpeech(rec.id, words, speaker, localDecodedSpoolBytes.get())
                }
            }
        }

        try {
            val initStrings = AppLocaleManager.currentStrings()
            val initialLease = runBlocking { acquireLiveLease(activePreset, threads) }
            modelLease = initialLease
            if (!isAsrOpen(rec.id)) return
            liveSession = createLiveSession(initialLease, activePreset, beam)
                ?: throw IllegalStateException(initStrings.asrSessionError)

            updateAsrUi(rec.id) { state ->
                state.copy(
                    asrStatusText = statusLabelFor(activePreset),
                    activeLanguage = activePreset.startLang,
                    asrError = null
                )
            }

            val spoolFile = File(rec.asrSpoolPath)
            while (!spoolFile.exists() && !captureClosedFlag.get() && isAsrOpen(rec.id)) {
                Thread.sleep(20L)
            }
            if (!spoolFile.exists() || !isAsrOpen(rec.id)) return

            val audio = RandomAccessFile(spoolFile, "r")
            raf = audio
            val labeler = speakerSession?.let { session ->
                LocalSpeakerLabeler(0L) { start, count ->
                    audio.seek(start)
                    val pcm = ByteArray(count)
                    audio.readFully(pcm)
                    session.analyze(pcm, start) { destroyed.get() || !isAsrOpen(rec.id) }
                }
            }
            val commitSpeech: (SpeechSegment) -> Unit = { speech ->
                commitSpeech(speech.words, speech.speaker)
            }
            val enqueueSpeech: (List<TranscriptWord>) -> Unit = { words ->
                if (labeler == null) commitSpeech(words, null) else labeler.offer(words)
            }
            fun publishSpeakers(finished: Boolean = false) {
                if (!isAsrOpen(rec.id)) return
                labeler?.drain(totalSpoolBytes.get(), finished, commitSpeech)
                val draft = labeler?.draftWords().orEmpty()
                updateAsrUi(rec.id) { it.copy(pendingSpeakerWords = draft) }
            }

            val chunkShorts = 640 * 4 // 160 ms
            val byteChunk = ByteArray(chunkShorts * 2)
            val shortChunk = ShortArray(chunkShorts)
            var readCursor = 0L

            while (isAsrOpen(rec.id)) {
                val pendingSwitch = requestedPresetSwitch.get()?.takeIf {
                    it.recordingId == rec.id && !stopRequested.get()
                }
                if (pendingSwitch != null && readCursor >= pendingSwitch.audioByte) {
                    if (!requestedPresetSwitch.compareAndSet(pendingSwitch, null)) continue
                    val newPreset = TinyAsrCatalog.findPreset(pendingSwitch.presetId)
                    if (newPreset.id != activePreset.id) {
                        val sNow = AppLocaleManager.currentStrings()
                        val oldLease = checkNotNull(modelLease)
                        val oldSession = checkNotNull(liveSession)
                        val newLease = if (newPreset.modelId == activePreset.modelId) oldLease else {
                            val switchBadge = if (newPreset.modelId == TinyAsrCatalog.GIGAAM_V4) "GigaAM v4" else newPreset.modelId.uppercase()
                            updateAsrUi(rec.id) { if (it.isFinishing) it else it.copy(asrStatusText = "${sNow.switchingModel} ($switchBadge)") }
                            try {
                                runBlocking { acquireLiveLease(newPreset, threads) }
                            } catch (failure: Exception) {
                                updateAsrUi(rec.id) { if (it.isFinishing) it else it.copy(asrStatusText = statusLabelFor(activePreset), asrError = failure.message ?: sNow.asrSessionError) }
                                continue
                            }
                        }
                        var replacementSession: LiveAsrSession? = null
                        try {
                            if (stopRequested.get() || !isAsrOpen(rec.id)) continue
                            replacementSession = createLiveSession(newLease, newPreset, beam)
                            if (replacementSession == null) {
                                updateAsrUi(rec.id) { if (it.isFinishing) it else it.copy(asrStatusText = statusLabelFor(activePreset), asrError = sNow.asrSessionError) }
                                continue
                            }
                            val finishJson = finishLiveSession(oldSession)
                            if (!isAsrOpen(rec.id)) return
                            handleAsrEvents(rec.id, finishJson, timeOffsetSec, enqueueSpeech)
                            publishSpeakers()
                            freeLiveSession(oldSession)
                            liveSession = replacementSession
                            replacementSession = null
                            modelLease = newLease
                            if (newLease !== oldLease) runBlocking { releaseLiveLease(oldLease) }
                        } finally {
                            freeLiveSession(replacementSession)
                            if (newLease !== modelLease) runBlocking { releaseLiveLease(newLease) }
                        }
                        activePreset = newPreset
                        timeOffsetSec = readCursor / 32_000f
                        runBlocking {
                            asrCommitLock.withLock {
                                if (isAsrOpen(rec.id)) localParagraphBreaks.add(LocalParagraphBreak(timeOffsetSec))
                            }
                        }
                        updateAsrUi(rec.id) { state ->
                            state.copy(
                                modelId = activePreset.modelId,
                                languagePresetId = activePreset.id,
                                activeLanguage = activePreset.startLang,
                                asrStatusText = if (state.isFinishing) state.asrStatusText else statusLabelFor(activePreset),
                                asrError = null
                            )
                        }
                    }
                    continue
                }

                val available = totalSpoolBytes.get() - readCursor
                val untilSwitch = pendingSwitch?.let { it.audioByte - readCursor } ?: Long.MAX_VALUE
                if (available >= minOf(1280L, untilSwitch) ||
                    (captureClosedFlag.get() && available >= 2L)) {
                    val toReadBytes = minOf(available, untilSwitch, byteChunk.size.toLong()).toInt() and 0x7FFFFFFE
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

                            val json = pushLivePcm16(checkNotNull(liveSession), shortChunk, samplesCount)
                            if (!isAsrOpen(rec.id)) return
                            localDecodedSpoolBytes.set(readCursor)
                            handleAsrEvents(rec.id, json, timeOffsetSec, enqueueSpeech)
                            publishSpeakers()
                            val backlog = ((totalSpoolBytes.get() - readCursor).coerceAtLeast(0L)) / 32_000f
                            updateAsrUi(rec.id) { it.copy(backlogSeconds = backlog) }
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

            if (!isAsrOpen(rec.id)) return
            liveSession?.let { activeSession ->
                val finishJson = finishLiveSession(activeSession)
                if (!isAsrOpen(rec.id)) return
                handleAsrEvents(rec.id, finishJson, timeOffsetSec, enqueueSpeech)
            }
            publishSpeakers(finished = true)
            runBlocking {
                asrCommitLock.withLock {
                    if (isAsrOpen(rec.id)) processedSpoolBytes.set(readCursor)
                }
            }
            updateAsrUi(rec.id) { state ->
                state.copy(
                    openWords = emptyList(),
                    tailWords = emptyList(),
                    isEngineProcessing = false,
                    backlogSeconds = 0f
                )
            }
        } catch (t: Throwable) {
            val sErr = AppLocaleManager.currentStrings()
            updateAsrUi(rec.id) { state ->
                state.copy(
                    asrError = t.message ?: sErr.asrSessionError,
                    asrStatusText = sErr.asrPausedSavingAudio,
                    isEngineProcessing = false
                )
            }
        } finally {
            runCatching { raf?.close() }
            runCatching { speakerSession?.close() }
            freeLiveSession(liveSession)
            liveSession = null
            runBlocking { releaseLiveLease(modelLease) }
        }
    }

    private suspend fun runGroqLiveLoop(
        rec: RecordingEntity,
        modelId: String,
        speakerDiarization: Boolean,
        autoLearnSpeakers: Boolean,
        cloudUnavailable: AtomicBoolean
    ) {
        val worker = currentCoroutineContext()
        var speakerWarning: String? = null
        val speakers = if (speakerDiarization) {
            createSpeakerSession(autoLearnSpeakers) {
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
                        updateAsrUi(rec.id) { it.copy(isEngineProcessing = !it.isGroqRetrying) }
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
                        updateAsrUi(rec.id) {
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

                    asrCommitLock.withLock {
                        worker.ensureActive()
                        if (!isAsrOpen(rec.id)) return@withLock
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
                                updateAsrUi(rec.id) {
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
                updateAsrUi(rec.id) {
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

    private suspend fun commitLocalSpeech(recordingId: Long, words: List<TranscriptWord>, speaker: String?, decodedBytes: Long) {
        if (words.isEmpty()) return
        var cursor = 0
        while (cursor < words.size) {
            val mark = localParagraphBreaks.peek() ?: break
            val next = (cursor until words.size).firstOrNull { words[it].end >= mark.atSec } ?: break
            if (next > cursor) commitWords(recordingId, words.subList(cursor, next), speaker, decodedBytes)
            localParagraphBreaks.poll()
            paragraphAccumulator.requestParagraphBreak(mark.label)
            cursor = next
        }
        if (cursor < words.size) commitWords(recordingId, words.subList(cursor, words.size), speaker, decodedBytes)
    }

    private suspend fun commitWords(recordingId: Long, shifted: List<TranscriptWord>, speaker: String?, decodedBytes: Long) {
        if (shifted.isEmpty()) return
        val update = paragraphAccumulator.acceptCommit(shifted, speaker)
        // Only durable words advance the recovery point. A native push may still be holding the rest.
        val processed = ((shifted.last().end * GroqAsrClient.PCM_BYTES_PER_SECOND).toLong() and -2L)
            .coerceIn(processedSpoolBytes.get(), decodedBytes)
        var revised: ParagraphEntity? = null
        var added: ParagraphEntity? = null
        update.updatedLastParagraph?.let { draft ->
            revised = ParagraphEntity(
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
        }
        update.newParagraph?.let { draft ->
            added = ParagraphEntity(
                recordingId = recordingId,
                orderIndex = nextOrderIndex,
                startSec = draft.startSec,
                endSec = draft.endSec,
                text = draft.text,
                wordsJson = AsrJsonParser.encodeWords(draft.words),
                isBookmark = draft.isBookmark,
                bookmarkLabel = draft.bookmarkLabel,
                speaker = draft.speaker
            )
        }
        if (revised == null && added == null) return
        val ids = app.repository.commitLiveTranscript(recordingId, listOfNotNull(added), processed, revised)
        processedSpoolBytes.set(processed)
        revised?.let { committedList[committedList.lastIndex] = it }
        added?.let {
            currentParagraphEntityId = ids.single()
            nextOrderIndex++
            committedList.add(it.copy(id = currentParagraphEntityId))
        }
        updateAsrUi(recordingId) { state ->
            state.copy(
                committedParagraphs = committedList.toList()
            )
        }
    }

    private fun handleAsrEvents(recordingId: Long, rawJson: String?, timeOffsetSec: Float, onCommit: (List<TranscriptWord>) -> Unit) {
        val events = AsrJsonParser.parseEvents(rawJson)
        if (events.isEmpty()) return

        for (ev in events) {
            when (ev) {
                is AsrEvent.Commit -> {
                    val shifted = if (timeOffsetSec == 0f) ev.words else ev.words.map {
                        it.copy(start = it.start + timeOffsetSec, end = it.end + timeOffsetSec)
                    }
                    updateAsrUi(recordingId) { it.copy(openWords = emptyList(), tailWords = emptyList()) }
                    onCommit(shifted)
                }
                is AsrEvent.Partial -> {
                    val openShifted = if (timeOffsetSec == 0f) ev.open else ev.open.map {
                        it.copy(start = it.start + timeOffsetSec, end = it.end + timeOffsetSec)
                    }
                    val tailShifted = if (timeOffsetSec == 0f) ev.tail else ev.tail.map {
                        it.copy(start = it.start + timeOffsetSec, end = it.end + timeOffsetSec)
                    }
                    updateAsrUi(recordingId) { state ->
                        state.copy(
                            openWords = openShifted,
                            tailWords = tailShifted
                        )
                    }
                }
                is AsrEvent.Language -> {
                    val merged = mergeDetectedLanguages(observedLiveLang.get(), ev.lang)
                    observedLiveLang.set(merged)
                    updateAsrUi(recordingId) { it.copy(activeLanguage = merged ?: ev.lang) }
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
        val state = destroyedSnapshot ?: _uiState.value
        val finalDur = state.durationMs.coerceAtLeast(totSpool * 1000L / GroqAsrClient.PCM_BYTES_PER_SECOND)
        val totalAudioBytes = if (spoolCaptureFailed.get()) maxOf(totSpool, finalDur * 32L) else totSpool
        val fullyProcessed = procSpool >= (totalAudioBytes - 3200L).coerceAtLeast(0L)
        val captureFailed = originalCaptureFailed.get()
        val clearSpool = fullyProcessed && !captureFailed
        var failure: String? = null
        var savedRecordingId: Long? = null
        try {
            if (!capturedAudio.get() && totSpool == 0L) {
                app.repository.discardEmptyCapture(rec)
                failure = state.asrError ?: AppLocaleManager.currentStrings().audioFileNotFound
                return
            }
            val bookmarks = localParagraphBreaks.mapNotNull { mark ->
                val label = mark.label ?: return@mapNotNull null
                ParagraphEntity(
                    recordingId = rec.id, orderIndex = nextOrderIndex++, startSec = mark.atSec, endSec = mark.atSec,
                    text = "", wordsJson = "", isBookmark = true, bookmarkLabel = label
                )
            }
            val saved = app.repository.completeCapture(rec.id, bookmarks) { latest ->
                latest.copy(
                    status = if (captureFailed) RecordingStatus.INTERRUPTED.name else RecordingStatus.COMPLETED.name,
                    durationMs = finalDur,
                    fileSizeBytes = if (audioFile.isFile) audioFile.length() else 0L,
                    asrSpoolPath = when {
                        clearSpool -> ""
                        spoolCaptureFailed.get() && !spoolFile.isFile -> rec.audioPath
                        else -> rec.asrSpoolPath
                    },
                    totalAsrBytes = totalAudioBytes,
                    processedAsrBytes = procSpool,
                    modelId = state.modelId,
                    languagePresetId = state.languagePresetId,
                    detectedLanguage = observedLiveLang.get() ?: state.activeLanguage
                )
            }
            if (saved) {
                savedRecordingId = rec.id
                if (clearSpool && spoolFile.absolutePath != audioFile.absolutePath) spoolFile.delete()
                app.applicationScope.launch { runCatching { app.repository.backfillLearnedVoiceSamples(rec.id) } }
            }
        } catch (e: Exception) {
            failure = e.message ?: AppLocaleManager.currentStrings().audioWriteError
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (!destroyed.get()) {
                    releaseAudioFocus()
                    releaseWakeLock()
                    val notice = failure ?: _uiState.value.asrError ?: if (captureFailed) {
                        AppLocaleManager.currentStrings().audioWriteError
                    } else if (!fullyProcessed) {
                        if (AppLocaleManager.currentLanguage.value.code == "ru") {
                            "Аудио сохранено. Оставшийся фрагмент можно распознать в записи."
                        } else "Audio saved. You can transcribe the remaining audio from the recording."
                    } else null
                    _uiState.value = LiveRecordingUiState(finishedRecordingId = savedRecordingId, asrError = notice)
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
        if (!isPausedFlag.compareAndSet(true, false) || stopRequested.get()) return
        _uiState.update { state ->
            state.copy(
                isPaused = false,
                statusNotice = null
            )
        }
        updateNotification("${strings.notifRecordingActive} · ${AsrTextFormatter.formatDurationMs(_uiState.value.durationMs)}", isPaused = false)
    }

    fun addBookmark(customLabel: String? = null) {
        if (!isRecordingFlag.get() || stopRequested.get()) return
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
                                "",
                                neuralNoiseSuppression = settings.neuralNoiseSuppression,
                                onProgress = {}
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
        val state = _uiState.value
        if (!isRecordingFlag.get() || stopRequested.get() || state.isGroqEngine) return
        requestedPresetSwitch.set(PresetSwitch(state.recordingId, presetId, totalSpoolBytes.get()))
        synchronized(spoolLock) { spoolLock.notifyAll() }
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
                asrStatusText = strings.notifSaving
            )
        }
        updateNotification(strings.notifSaving, isPaused = false)
        synchronized(spoolLock) { spoolLock.notifyAll() }
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
        _uiState.update { state ->
            if (state.isStarting || (state.isActive && state.recordingId == id)) LiveRecordingUiState() else state
        }
        synchronized(spoolLock) { spoolLock.notifyAll() }
        releaseAudioFocus()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
