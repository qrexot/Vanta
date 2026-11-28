package com.stenograf.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.ModelInstallStatus
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.service.ReTranscribeWorker.AudioReview
import com.stenograf.app.audio.AudioPlayerController
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingSort
import com.stenograf.app.data.prefs.AppSettings
import com.stenograf.app.data.prefs.ThemeMode
import com.stenograf.app.export.ExportFormat
import com.stenograf.app.export.TranscriptExporter
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.LiveRecordingUiState
import com.stenograf.app.service.ProcessingStatus
import com.stenograf.app.service.RecordingForegroundService
import com.stenograf.app.ui.components.ImportAudioSheet
import com.stenograf.app.ui.components.LiquidGlassTab
import com.stenograf.app.ui.components.VantaLiquidGlassDock
import com.stenograf.app.ui.screens.DetailScreen
import com.stenograf.app.ui.screens.LibraryScreen
import com.stenograf.app.ui.screens.ModelManagerScreen
import com.stenograf.app.ui.screens.RecordingScreen
import com.stenograf.app.ui.screens.SettingsScreen
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.StenografTheme
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.vantaSurfaceCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

private data class PendingSafSave(val recordingId: Long, val format: ExportFormat?)

private val pendingSafSaveSaver = listSaver<PendingSafSave?, Any>(
    save = { pending ->
        if (pending == null) emptyList() else listOf(pending.recordingId, pending.format?.name.orEmpty())
    },
    restore = { values ->
        PendingSafSave(values[0] as Long, ExportFormat.entries.firstOrNull { it.name == values[1] })
    }
)

class MainActivity : ComponentActivity() {

    private var boundService: RecordingForegroundService? = null
    private var serviceBound = false
    private var incomingMediaUri by mutableStateOf<Uri?>(null)

    private fun extractIncomingMediaUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingMediaUri(intent)?.let { incomingMediaUri = it }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = (service as? RecordingForegroundService.LocalBinder)?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RecordingForegroundService::class.java)
        serviceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incomingMediaUri = extractIncomingMediaUri(intent)
        val app = application as StenografApp

        setContent {
            val settings by app.preferences.settingsFlow.collectAsState(initial = AppSettings())
            val activeLang by AppLocaleManager.currentLanguage.collectAsState()
            val activeStrings by AppLocaleManager.strings.collectAsState()
            val extraStrings by AppLocaleManager.extraStrings.collectAsState()

            val systemDark = isSystemInDarkTheme()
            val isDark = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT
                    ) { isDark },
                    navigationBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT
                    ) { isDark }
                )
                onDispose {}
            }

            LaunchedEffect(settings.uiLanguageCode) {
                AppLocaleManager.applyPreference(settings.uiLanguageCode)
            }

            val layoutDir = if (activeLang.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(
                LocalStrings provides activeStrings,
                LocalExtraStrings provides extraStrings,
                LocalLayoutDirection provides layoutDir
            ) {
                StenografTheme(themeMode = settings.themeMode) {
                    StenografRoot(
                        app = app,
                        settings = settings,
                        incomingMediaUri = incomingMediaUri,
                        onConsumeIncomingMediaUri = { incomingMediaUri = null },
                        getService = { boundService }
                    )
                }
            }
        }
    }
}

@Composable
private fun StenografRoot(
    app: StenografApp,
    settings: AppSettings,
    incomingMediaUri: Uri?,
    onConsumeIncomingMediaUri: () -> Unit,
    getService: () -> RecordingForegroundService?
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val s = LocalStrings.current
    val extraStrings = LocalExtraStrings.current
    val navigationDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1

    val recordingSummaryFlow = remember {
        RecordingForegroundService.uiState.map { state ->
            LiveRecordingUiState(
                isActive = state.isActive,
                isStarting = state.isStarting,
                isFinishing = state.isFinishing,
                isPaused = state.isPaused,
                durationMs = (state.durationMs / 1000L) * 1000L,
                waveform = emptyList(),
                finishedRecordingId = state.finishedRecordingId,
                asrError = state.asrError
            )
        }.distinctUntilChanged()
    }

    var selectedFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(RecordingSort.DATE_DESC) }

    var importJob by remember { mutableStateOf<Job?>(null) }
    var importProgress by remember { mutableStateOf<ProcessingStatus?>(null) }
    var pendingImportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var reTranscribeJob by remember { mutableStateOf<Job?>(null) }
    val transcriptionMutex = remember { Mutex() }
    var transcribingRecordingId by remember { mutableStateOf<Long?>(null) }
    var resettingApp by remember { mutableStateOf(false) }
    var reTranscribeProgress by remember { mutableStateOf<ProcessingStatus?>(null) }

    var pendingStartRecording by rememberSaveable { mutableStateOf(false) }
    var pendingModelImportTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSafSave by rememberSaveable(stateSaver = pendingSafSaveSaver) {
        mutableStateOf<PendingSafSave?>(null)
    }
    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var isCheckingUpdates by remember { mutableStateOf(false) }

    val activeFolderForNew = selectedFolderId?.takeIf { it > 0L }

    fun openRecording(requestStart: Boolean) {
        try {
            if (requestStart) RecordingForegroundService.startNewRecording(context, activeFolderForNew)
            navController.navigate("recording") { launchSingleTop = true }
        } catch (failure: Exception) {
            android.util.Log.w("Vanta", "Could not open recording", failure)
            scope.launch { snackbarHostState.showSnackbar(s.micAccessFailed) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && pendingStartRecording) {
            pendingStartRecording = false
            openRecording(requestStart = true)
        } else if (!micGranted) {
            pendingStartRecording = false
            scope.launch { snackbarHostState.showSnackbar(s.micPermissionRequired) }
        }
    }

    suspend fun recognizeAudio(
        record: RecordingEntity,
        paragraph: ParagraphEntity?,
        model: TranscriptReviewModel?,
        forceRussian: Boolean,
        groqLanguageOverride: String?,
        previous: String,
        following: String,
        hint: String,
        onProgress: (Float) -> Unit
    ): AudioReview {
        val active = RecordingForegroundService.uiState.value
        val recent = app.repository.getRecording(record.id) ?: error(s.audioFileNotFound)
        val current = if (active.recordingId == recent.id) recent.copy(languagePresetId = active.languagePresetId) else recent
        val latestSettings = app.preferences.settingsFlow.first()
        if (model == null) {
            val preset = GroqAsrClient.resolveLocalReviewPreset(current.languagePresetId, forceRussian)
            return app.reTranscribeWorker.recognizeBlockLocally(
                current, requireNotNull(paragraph), preset, latestSettings.neuralNoiseSuppression, onProgress
            )
        }
        val languageMode = GroqAsrClient.resolveReviewLanguageMode(forceRussian, groqLanguageOverride)
        return app.reTranscribeWorker.reviewAudio(
            current, paragraph, latestSettings.groqApiKey, latestSettings.groqModelId,
            model, languageMode, previous, following, hint, latestSettings.neuralNoiseSuppression, onProgress
        )
    }

    fun runReTranscribeJob(
        recordingId: Long,
        block: suspend (AppSettings, (ProcessingStatus) -> Unit) -> Result<Unit>
    ) {
        if (resettingApp) return
        val previous = reTranscribeJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                previous?.cancelAndJoin()
                val latestSettings = app.preferences.settingsFlow.first()
                val res = transcriptionMutex.withLock {
                    block(latestSettings) { status ->
                        if (reTranscribeJob === currentJob) reTranscribeProgress = status
                    }
                }
                storageRefreshTick++
                res.onSuccess {
                    snackbarHostState.showSnackbar(s.reTranscribeDone)
                }.onFailure { err ->
                    snackbarHostState.showSnackbar(err.message ?: s.asrSessionError)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbarHostState.showSnackbar(failure.message ?: s.asrSessionError)
            } finally {
                if (reTranscribeJob === currentJob) {
                    reTranscribeProgress = null
                    reTranscribeJob = null
                    transcribingRecordingId = null
                }
            }
        }
        transcribingRecordingId = recordingId
        reTranscribeProgress = ProcessingStatus(0f, s.loadingModelProgress)
        reTranscribeJob = job
        job.start()
    }

    fun launchLocalReTranscribe(
        rec: RecordingEntity,
        presetId: String,
        beam: Int,
        onlyTail: Boolean
    ) = runReTranscribeJob(rec.id) { latestSettings, onProgress ->
        app.reTranscribeWorker.reTranscribeRecording(
            recording = rec,
            presetId = presetId,
            requestedThreads = latestSettings.asrThreads,
            beam = beam,
            autoParagraph = latestSettings.autoParagraph,
            onlyUnprocessedTail = onlyTail,
            distantSpeechBoost = latestSettings.distantSpeechBoost,
            neuralNoiseSuppression = latestSettings.neuralNoiseSuppression,
            speakerDiarization = latestSettings.speakerDiarization,
            autoLearnSpeakers = latestSettings.autoLearnSpeakers,
            onProgress = onProgress
        )
    }

    fun launchGroqReTranscribe(
        rec: RecordingEntity,
        onlyTail: Boolean,
        languageModeOverride: String? = null
    ) = runReTranscribeJob(rec.id) { latestSettings, onProgress ->
        app.reTranscribeWorker.reTranscribeWithGroq(
            recording = rec,
            apiKey = latestSettings.groqApiKey,
            modelId = latestSettings.groqModelId,
            languageMode = languageModeOverride ?: latestSettings.groqLanguageMode,
            speakerDiarization = latestSettings.speakerDiarization,
            neuralNoiseSuppression = latestSettings.neuralNoiseSuppression,
            autoLearnSpeakers = latestSettings.autoLearnSpeakers,
            onlyUnprocessedTail = onlyTail,
            onProgress = onProgress
        )
    }

    fun launchStreamingImportTranscribe(
        rec: RecordingEntity,
        useGroq: Boolean,
        groqLanguageMode: String,
        presetId: String,
        beam: Int
    ) = runReTranscribeJob(rec.id) { latestSettings, onProgress ->
        app.reTranscribeWorker.streamTranscribeImportedRecording(
            recording = rec,
            useGroqApi = useGroq,
            groqApiKey = latestSettings.groqApiKey,
            groqModelId = latestSettings.groqModelId,
            groqLanguageMode = groqLanguageMode,
            presetId = presetId,
            requestedThreads = latestSettings.asrThreads,
            beam = beam,
            autoParagraph = latestSettings.autoParagraph,
            distantSpeechBoost = latestSettings.distantSpeechBoost,
            neuralNoiseSuppression = latestSettings.neuralNoiseSuppression,
            speakerDiarization = latestSettings.speakerDiarization,
            autoLearnSpeakers = latestSettings.autoLearnSpeakers,
            onProgress = onProgress
        )
    }

    fun importMediaUri(
        uri: Uri,
        useGroqApi: Boolean,
        groqLanguageMode: String = settings.groqLanguageMode,
        presetId: String = settings.languagePresetId,
        beam: Int = settings.asrBeam
    ) {
        if (resettingApp) return
        val previous = importJob
        val importStartedAt = System.currentTimeMillis()
        val copying = ProcessingStatus(0f, s.importAudio, startedAtMs = importStartedAt, isIndeterminate = true)
        importProgress = copying
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                previous?.cancelAndJoin()
                val latestSettings = app.preferences.settingsFlow.first()
                val useGroq = useGroqApi && latestSettings.groqApiKey.isNotBlank()
                val modelId = if (useGroq) latestSettings.groqModelId else TinyAsrCatalog.findPreset(presetId).modelId
                val res = app.reTranscribeWorker.prepareImportedRecording(
                    uri = uri,
                    presetId = presetId,
                    folderId = activeFolderForNew,
                    modelId = modelId,
                    onProgress = { fraction ->
                        if (importJob === currentJob) {
                            importProgress = copying.copy(progress = fraction, isIndeterminate = false)
                        }
                    }
                )
                storageRefreshTick++
                res.onSuccess { rec ->
                    if (importJob === currentJob) {
                        importProgress = null
                        importJob = null
                    }
                    navController.navigate("detail/${rec.id}") {
                        launchSingleTop = true
                    }
                    launchStreamingImportTranscribe(
                        rec,
                        useGroq = useGroq,
                        groqLanguageMode = groqLanguageMode,
                        presetId = presetId,
                        beam = beam
                    )
                }.onFailure { err ->
                    snackbarHostState.showSnackbar("${s.importError}: ${err.message}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbarHostState.showSnackbar(failure.message ?: s.importError)
            } finally {
                if (importJob === currentJob) {
                    importProgress = null
                    importJob = null
                }
            }
        }
        importJob = job
        job.start()
    }

    LaunchedEffect(incomingMediaUri) {
        val uri = incomingMediaUri ?: return@LaunchedEffect
        onConsumeIncomingMediaUri()
        if (navController.currentDestination?.route != "library") {
            navController.popBackStack("library", inclusive = false)
        }
        pendingImportUri = uri
    }

    val audioImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    val modelImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val targetId = pendingModelImportTargetId
        pendingModelImportTargetId = null
        if (uri != null && targetId != null) {
            scope.launch {
                val res = app.modelManager.importModelFromUri(uri, targetId)
                storageRefreshTick++
                res.onSuccess {
                    snackbarHostState.showSnackbar(s.modelImportedNotice)
                }.onFailure { err ->
                    snackbarHostState.showSnackbar("${s.modelCorruptError}: ${err.message}")
                }
            }
        }
    }

    val safCreateDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingSafSave
        pendingSafSave = null
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null && pending != null) {
            scope.launch {
                try {
                    val recording = app.repository.getRecording(pending.recordingId) ?: error(s.audioFileNotFound)
                    val format = pending.format
                    if (format == null) {
                        TranscriptExporter.copyAudioFileToUri(context, File(recording.audioPath), uri)
                    } else {
                        TranscriptExporter.exportToUri(
                            context, uri, recording, app.repository.getParagraphs(recording.id),
                            format, settings.showTimestamps
                        )
                    }
                    snackbarHostState.showSnackbar(s.exportSavedSuccess)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    snackbarHostState.showSnackbar(failure.message ?: s.exportError)
                }
            }
        }
    }

    fun launchSafSave(pending: PendingSafSave, name: String, mimeType: String) {
        pendingSafSave = pending
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            safCreateDocLauncher.launch(intent)
        } catch (failure: Exception) {
            pendingSafSave = null
            scope.launch { snackbarHostState.showSnackbar(failure.message ?: s.exportError) }
        }
    }

    LaunchedEffect(navController) {
        RecordingForegroundService.uiState.map { it.finishedRecordingId to it.asrError }
            .distinctUntilChanged().collect { (finishedId, error) ->
                if (finishedId != null) {
                    RecordingForegroundService.clearFinishedEvent()
                    storageRefreshTick++
                    if (navController.currentDestination?.route == "recording") {
                        navController.navigate("detail/$finishedId") { popUpTo("library") }
                    }
                    error?.let { scope.launch { snackbarHostState.showSnackbar(it) } }
                }
            }
    }

    fun startRecordingFlow() {
        if (resettingApp || pendingStartRecording) return
        val live = RecordingForegroundService.uiState.value
        if (live.isStarting || live.isActive || live.isFinishing) {
            openRecording(requestStart = false)
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!hasMic || needNotif) {
            pendingStartRecording = true
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permissionLauncher.launch(perms)
        } else {
            openRecording(requestStart = true)
        }
    }

    fun exportAndShareRecording(rec: RecordingEntity, format: ExportFormat) {
        scope.launch {
            try {
                val paras = app.repository.getParagraphs(rec.id)
                val outFile = TranscriptExporter.exportToFile(
                    context = context,
                    recording = rec,
                    paragraphs = paras,
                    format = format,
                    includeTimestamps = settings.showTimestamps
                )
                TranscriptExporter.shareFile(context, outFile, format.mimeType, "${s.exportTitle} ${format.ext.uppercase()}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbarHostState.showSnackbar(failure.message ?: s.exportError)
            }
        }
    }

    fun saveExportWithSaf(rec: RecordingEntity, format: ExportFormat) {
        val suggestedName = "${TranscriptExporter.safeBaseName(rec.title)}.${format.ext}"
        launchSafSave(PendingSafSave(rec.id, format), suggestedName, format.mimeType)
    }

    val libraryPage: @Composable (Int, Dp, (Boolean) -> Unit) -> Unit = { scrollToTopRequest, bottomDockClearance, onSelectionModeChange ->
        val recovered by app.recoveredRecordings.collectAsStateWithLifecycle()
        val foldersFlow = remember(app) { app.repository.observeFolders() }
        val folders by foldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val allFlow = remember(app) { app.repository.observeRecordings(null, "") }
        val allRecordings by allFlow.collectAsStateWithLifecycle(initialValue = null)
        val loadedRecordings = if (selectedFolderId == null && searchQuery.isBlank()) {
            allRecordings
        } else {
            val filteredFlow = remember(app, selectedFolderId, searchQuery) {
                app.repository.observeRecordings(selectedFolderId, searchQuery)
            }
            val filtered by filteredFlow.collectAsStateWithLifecycle(initialValue = null)
            filtered
        }
        val recordings = loadedRecordings.orEmpty()
        val folderCounts = remember(allRecordings) { allRecordings.orEmpty().groupingBy { it.folderId }.eachCount() }
        val bookmarkFlow = remember(app) { app.repository.observeBookmarkCounts() }
        val bookmarkRows by bookmarkFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val bookmarkCounts = remember(bookmarkRows) { bookmarkRows.associate { it.recordingId to it.bookmarkCount } }
        val liveState by recordingSummaryFlow.collectAsStateWithLifecycle(initialValue = LiveRecordingUiState())

        LibraryScreen(
            recordings = recordings,
            allRecordingsCount = allRecordings.orEmpty().size,
            scrollToTopRequest = scrollToTopRequest,
            folderCounts = folderCounts,
            bookmarkCounts = bookmarkCounts,
            isLoading = loadedRecordings == null,
            folders = folders,
            selectedFolderId = selectedFolderId,
            searchQuery = searchQuery,
            sort = sort,
            liveState = liveState,
            recoveredRecordings = recovered,
            importProgress = importProgress,
            bottomDockClearance = bottomDockClearance,
            onSelectionModeChange = onSelectionModeChange,
            onSelectFolder = { selectedFolderId = it },
            onSearchChange = { searchQuery = it },
            onSortChange = { sort = it },
            onStartRecording = {
                if (liveState.isActive) {
                    navController.navigate("recording") { launchSingleTop = true }
                } else {
                    startRecordingFlow()
                }
            },
            onImportAudio = {
                audioImportLauncher.launch(arrayOf("audio/*", "video/*"))
            },
            onCancelImport = {
                importJob?.cancel()
            },
            onOpenRecording = { id -> navController.navigate("detail/$id") },
            onRenameRecording = { rec, title ->
                scope.launch { app.repository.renameRecording(rec.id, title) }
            },
            onMoveRecording = { rec, folderId ->
                scope.launch { app.repository.moveRecordingToFolder(rec.id, folderId) }
            },
            onMoveRecordingsBatch = { list, folderId ->
                scope.launch {
                    list.forEach { app.repository.moveRecordingToFolder(it.id, folderId) }
                }
            },
            onDeleteRecordings = { list ->
                scope.launch {
                    try {
                        if (list.any { it.id == transcribingRecordingId }) reTranscribeJob?.cancelAndJoin()
                        transcriptionMutex.withLock {
                            list.forEach { app.repository.deleteRecording(it) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        scope.launch { snackbarHostState.showSnackbar(e.localizedMessage ?: s.deleteRecordingTitle) }
                    } finally {
                        storageRefreshTick++
                    }
                }
            },
            onExportRecording = { rec, fmt -> exportAndShareRecording(rec, fmt) },
            onSaveExportRecording = { rec, fmt -> saveExportWithSaf(rec, fmt) },
            onCopyRecordingText = { rec ->
                scope.launch {
                    val paras = app.repository.getParagraphs(rec.id)
                    val txt = TranscriptExporter.buildPlainText(paras, settings.showTimestamps)
                    clipboardManager.setText(AnnotatedString(txt))
                    snackbarHostState.showSnackbar(s.copiedAllText)
                }
            },
            onCreateFolder = { name, colorHex ->
                scope.launch { app.repository.createFolder(name, colorHex) }
            },
            onUpdateFolder = { folder, name, colorHex ->
                scope.launch { app.repository.updateFolder(folder, name, colorHex) }
            },
            onDeleteFolder = { folder ->
                scope.launch {
                    if (selectedFolderId == folder.id) selectedFolderId = null
                    app.repository.deleteFolder(folder)
                }
            },
            onDismissRecovered = { app.dismissRecoveredNotice() }
        )
    
    }

    val dlStates by app.modelManager.downloadStates.collectAsState()
    val updates by app.modelManager.updatesAvailable.collectAsState()
    var modelStatuses by remember { mutableStateOf<List<ModelInstallStatus>>(emptyList()) }
    LaunchedEffect(dlStates, updates, storageRefreshTick) {
        modelStatuses = withContext(Dispatchers.IO) { app.modelManager.getAllModelStatuses() }
    }
    var storageBreakdown by remember {
        mutableStateOf(com.stenograf.app.data.repo.StorageBreakdown(0L, 0L, 0L, 0L, 0L))
    }
    LaunchedEffect(storageRefreshTick) {
        storageBreakdown = withContext(Dispatchers.IO) { app.repository.getStorageBreakdown(app.modelManager) }
    }
    val speakerProfiles by remember(app) { app.repository.observeSpeakerProfiles() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val liveUiState by RecordingForegroundService.uiState.collectAsStateWithLifecycle()

    val settingsPage: @Composable ((() -> Unit)?, Dp, () -> Unit) -> Unit = { onBack, bottomDockClearance, openModels ->
        SettingsScreen(
            settings = settings,
            modelStatuses = modelStatuses,
            storage = storageBreakdown,
            onBack = onBack,
            bottomDockClearance = bottomDockClearance,
            onSelectUiLanguage = { code ->
                AppLocaleManager.applyPreference(code)
                scope.launch { app.preferences.setUiLanguageCode(code) }
            },
            onSelectLanguagePreset = { id -> scope.launch { app.preferences.setLanguagePreset(id) } },
            onSelectAudioFormat = { fmt -> scope.launch { app.preferences.setAudioFormat(fmt) } },
            onToggleRawMic = { v -> scope.launch { app.preferences.setUseRawMicrophone(v) } },
            onToggleDistantSpeechBoost = { v -> scope.launch { app.preferences.setDistantSpeechBoost(v) } },
            onSelectThreads = { n -> scope.launch { app.preferences.setAsrThreads(n) } },
            onSelectBeam = { b -> scope.launch { app.preferences.setAsrBeam(b) } },
            onToggleAutoParagraph = { v -> scope.launch { app.preferences.setAutoParagraph(v) } },
            onToggleShowTimestamps = { v -> scope.launch { app.preferences.setShowTimestamps(v) } },
            onToggleKeepScreenOn = { v -> scope.launch { app.preferences.setKeepScreenOnRecording(v) } },
            onSelectTheme = { m -> scope.launch { app.preferences.setThemeMode(m) } },
            onToggleReaderSerif = { v -> scope.launch { app.preferences.setReaderSerif(v) } },
            onSelectAsrEngine = { mode -> scope.launch { app.preferences.setAsrEngineMode(mode) } },
            onUpdateGroqApiKey = { key -> scope.launch { app.preferences.setGroqApiKey(key) } },
            onSelectGroqModel = { modelId -> scope.launch { app.preferences.setGroqModelId(modelId) } },
            onSelectGroqLanguageMode = { mode -> scope.launch { app.preferences.setGroqLanguageMode(mode) } },
            onToggleSpeakers = { enabled -> scope.launch { app.preferences.setSpeakerDiarization(enabled) } },
            onSelectRecognitionAudioFilter = { filter -> scope.launch { app.preferences.setRecognitionAudioFilter(filter) } },
            onToggleAutoLearnSpeakers = { enabled -> scope.launch { app.preferences.setAutoLearnSpeakers(enabled) } },
            speakerProfiles = speakerProfiles,
            isRecordingActive = liveUiState.isActive,
            onEnrollPcmVoiceProfile = { name, tagOrPhone, pcmBytes, existingId ->
                app.repository.enrollOrReinforceFromPcm16(
                    name = name,
                    tagOrPhone = tagOrPhone,
                    pcm16kBytes = pcmBytes,
                    existingProfileId = existingId,
                    sourceTitle = com.stenograf.app.i18n.AppLocaleManager.currentExtraStrings().micEnrollSourceTitle
                ) != null
            },
            onUpdateVoiceProfileDetails = { id, newName, tagOrPhone, renamePast ->
                scope.launch { app.repository.updateSpeakerProfileDetails(id, newName, tagOrPhone, renamePast) }
            },
            onMergeVoiceProfiles = { targetId, sourceId ->
                scope.launch { app.repository.mergeSpeakerProfiles(targetId, sourceId, renameInRecordings = true) }
            },
            onEnsureVoiceSampleClip = { id ->
                app.repository.ensureProfileAudioSample(id)
            },
            onOpenSourceRecording = { recId ->
                navController.navigate("detail/$recId")
            },
            onDeleteVoiceProfile = { id ->
                scope.launch { app.repository.deleteSpeakerProfile(id) }
            },
            onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onOpenModelManager = openModels,
            onClearCache = {
                scope.launch {
                    if (!resettingApp) {
                        app.repository.clearTemporaryCache(app.modelManager)
                        storageRefreshTick++
                        snackbarHostState.showSnackbar(s.cacheClearedNotice)
                    }
                }
            },
            onResetAllData = {
                scope.launch {
                    if (resettingApp) return@launch
                    val live = RecordingForegroundService.uiState.value
                    if (live.isStarting || live.isActive || live.isFinishing) {
                        snackbarHostState.showSnackbar(
                            if (AppLocaleManager.currentLanguage.value.code == "ru") {
                                "Сначала завершите запись."
                            } else "Finish the recording first."
                        )
                        return@launch
                    }
                    resettingApp = true
                    try {
                        importJob?.cancelAndJoin()
                        reTranscribeJob?.cancelAndJoin()
                        transcriptionMutex.withLock {
                            app.repository.resetAllAppData(app.modelManager, app.preferences)
                        }
                        storageRefreshTick++
                        snackbarHostState.showSnackbar(s.resetAllDataDone)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        snackbarHostState.showSnackbar(failure.message ?: s.asrSessionError)
                    } finally {
                        resettingApp = false
                    }
                }
            }
        )
    
    }

    fun downloadModel(modelId: String) {
        if (resettingApp) return
        scope.launch {
            val result = app.modelManager.downloadModel(modelId)
            storageRefreshTick++
            result.onFailure { failure ->
                if (failure !is CancellationException) {
                    snackbarHostState.showSnackbar(failure.message ?: s.modelDownloadError)
                }
            }
        }
    }

    val modelsPage: @Composable ((() -> Unit)?, Dp) -> Unit = { onBack, bottomDockClearance ->
        ModelManagerScreen(
            modelStatuses = modelStatuses,
            activePresetId = settings.languagePresetId,
            onSelectPreset = { id ->
                scope.launch { app.preferences.setLanguagePreset(id) }
            },
            isCheckingUpdates = isCheckingUpdates,
            onBack = onBack,
            bottomDockClearance = bottomDockClearance,
            onCheckUpdates = {
                scope.launch {
                    isCheckingUpdates = true
                    app.modelManager.checkForModelUpdates()
                    isCheckingUpdates = false
                    storageRefreshTick++
                }
            },
            recognitionFilter = settings.recognitionAudioFilter,
            onSelectRecognitionFilter = { filter -> scope.launch { app.preferences.setRecognitionAudioFilter(filter) } },
            onDownloadModel = ::downloadModel,
            onPauseDownload = { modelId ->
                app.modelManager.pauseDownload(modelId)
            },
            onCancelDownload = { modelId ->
                app.modelManager.cancelDownload(modelId)
                storageRefreshTick++
            },
            onImportModel = { modelId ->
                pendingModelImportTargetId = modelId
                modelImportLauncher.launch(arrayOf("*/*"))
            },
            onDeleteModel = { modelId ->
                scope.launch {
                    val res = app.modelManager.deleteModel(modelId)
                    storageRefreshTick++
                    res.onSuccess {
                        snackbarHostState.showSnackbar(s.modelDeletedNotice)
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(err.message ?: s.modelCannotDeleteActive)
                    }
                }
            }
        )
    
    }

    val snackbarColors = LocalStenografColors.current
    val navEntry by navController.currentBackStackEntryAsState()
    val onLibraryTabShell = navEntry?.destination?.route == "library"
    var librarySelectionMode by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.md,
                        bottom = if (onLibraryTabShell && !librarySelectionMode && !keyboardVisible) 80.dp else Spacing.md
                    )
            ) { data ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(snackbarColors.surfaceElevated)
                        .border(0.75.dp, snackbarColors.borderStrong, RoundedCornerShape(Radius.md))
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                ) {
                    Text(
                        text = data.visuals.message,
                        color = snackbarColors.textPrimary,
                        style = VantaType.body.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        }
    ) { outerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(outerPadding)) {
            NavHost(
                navController = navController,
                startDestination = "library",
                modifier = Modifier.clipToBounds(),
                enterTransition = {
                    slideInHorizontally(Motion.screenTween()) { navigationDirection * it / 4 } + fadeIn(Motion.mediumTween())
                },
                exitTransition = {
                    slideOutHorizontally(Motion.screenTween()) { -navigationDirection * it / 10 } + fadeOut(Motion.fastTween())
                },
                popEnterTransition = {
                    slideInHorizontally(Motion.screenTween()) { -navigationDirection * it / 10 } + fadeIn(Motion.fastTween())
                },
                popExitTransition = {
                    slideOutHorizontally(Motion.screenTween()) { navigationDirection * it / 4 } + fadeOut(Motion.mediumTween())
                }
            ) {
                composable("library") {
                    val pager = rememberPagerState { 3 }
                    var scrollToTopRequest by remember { mutableIntStateOf(0) }
                    val dockVisible = !(pager.currentPage == 0 && librarySelectionMode) && !keyboardVisible
                    val dockClearance = 76.dp
                    val backdropLayer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rememberGraphicsLayer() else null
                    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
                    val goToTab: (Int) -> Unit = { tab ->
                        scope.launch {
                            pager.animateScrollToPage(tab, animationSpec = Motion.tabSpring())
                        }
                    }
                    BackHandler(enabled = pager.settledPage != 0) { goToTab(0) }
                    Box(Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = pager,
                            beyondViewportPageCount = 2,
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                                .then(
                                    if (backdropLayer != null) {
                                        Modifier.drawWithContent {
                                            backdropLayer.record {
                                                this@drawWithContent.drawContent()
                                            }
                                            drawLayer(backdropLayer)
                                        }
                                    } else Modifier
                                ),
                            key = { it }
                        ) { page ->
                            when (page) {
                                0 -> libraryPage(scrollToTopRequest, dockClearance) { librarySelectionMode = it }
                                1 -> modelsPage(null, dockClearance)
                                2 -> settingsPage(null, dockClearance) { goToTab(1) }
                            }
                        }
                        AnimatedVisibility(
                            visible = dockVisible,
                            enter = slideInVertically(Motion.mediumTween()) { it } + fadeIn(Motion.fastTween()),
                            exit = slideOutVertically(Motion.fastTween()) { it } + fadeOut(Motion.fastTween()),
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) {
                            VantaLiquidGlassDock(
                                tabs = listOf(
                                    LiquidGlassTab(s.recordingsTitle, Icons.Rounded.FolderOpen),
                                    LiquidGlassTab(extraStrings.modelsShort, Icons.Rounded.Layers),
                                    LiquidGlassTab(s.settings, Icons.Rounded.Settings)
                                ),
                                pagePositionProvider = { pager.currentPage + pager.currentPageOffsetFraction },
                                settledIndex = pager.settledPage,
                                onSelect = { tab ->
                                    if (tab == 0 && pager.settledPage == 0) scrollToTopRequest++
                                    else goToTab(tab)
                                },
                                backdropLayer = backdropLayer,
                                backdropOriginProvider = { backdropOrigin }
                            )
                        }
                    }
                }

                composable("recording") {
                    val source = RecordingForegroundService.uiState
                    val controls = remember(source) {
                        source.map { state ->
                            state.copy(
                                durationMs = state.durationMs / 1_000L * 1_000L,
                                backlogSeconds = (state.backlogSeconds * 2).toInt() / 2f,
                                waveform = emptyList(),
                                committedParagraphs = emptyList()
                            )
                        }.distinctUntilChanged()
                    }
                    val waveform = remember(source) { source.map { it.waveform }.distinctUntilChanged() }
                    val recordingState by controls.collectAsStateWithLifecycle(
                        initialValue = source.value.copy(waveform = emptyList(), committedParagraphs = emptyList())
                    )
                    val liveParagraphFlow = remember(recordingState.recordingId) {
                        val id = recordingState.recordingId
                        if (id > 0L) app.repository.observeParagraphs(id)
                        else kotlinx.coroutines.flow.flowOf(emptyList())
                    }
                    val liveParagraphs by liveParagraphFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                    RecordingScreen(
                        state = recordingState.copy(committedParagraphs = liveParagraphs),
                        hasCloudKey = settings.groqApiKey.isNotBlank(),
                        reviewModelId = settings.transcriptReviewModelId,
                        onSelectReviewModel = { model -> scope.launch { app.preferences.setTranscriptReviewModel(model) } },
                        onSetLiveAiEnabled = { enabled, model ->
                            getService()?.setLiveAiEnabled(enabled, model)
                        },
                        onOpenGroqSettings = { navController.navigate("settings") },
                        onRecognizeBlock = { fragment, model, russian, groqOverride, before, after, hint, progress ->
                            val rec = app.repository.getRecording(fragment.recordingId) ?: error(s.audioFileNotFound)
                            recognizeAudio(rec, fragment, model, russian, groqOverride, before, after, hint, progress)
                        },
                        onApplyBlock = app.repository::applyTranscriptReview,
                        waveform = waveform,
                        showTimestamps = settings.showTimestamps,
                        keepScreenOn = settings.keepScreenOnRecording,
                        readerSerif = settings.readerSerif,
                        readerFontSizeTier = settings.readerFontSizeTier,
                        modelManager = app.modelManager,
                        onBack = { navController.popBackStack() },
                        onPause = {
                            getService()?.pauseRecording()
                                ?: RecordingForegroundService.sendAction(context, RecordingForegroundService.ACTION_PAUSE)
                        },
                        onResume = {
                            getService()?.resumeRecording()
                                ?: RecordingForegroundService.sendAction(context, RecordingForegroundService.ACTION_RESUME)
                        },
                        onStop = {
                            getService()?.stopRecording()
                                ?: RecordingForegroundService.sendAction(context, RecordingForegroundService.ACTION_STOP)
                        },
                        onAddBookmark = { note ->
                            val svc = getService()
                            if (svc != null) svc.addBookmark(note)
                            else RecordingForegroundService.sendAction(context, RecordingForegroundService.ACTION_BOOKMARK)
                        },
                        onCycleFontSize = {
                            scope.launch {
                                app.preferences.setReaderFontSizeTier((settings.readerFontSizeTier + 1) % 3)
                            }
                        },
                        onSwitchLanguagePreset = { presetId ->
                            scope.launch { app.preferences.setLanguagePreset(presetId) }
                            getService()?.switchLanguagePreset(presetId)
                        },
                        onOpenModelManager = { navController.navigate("models") }
                    )
                }

                composable(
                    route = "detail/{recordingId}",
                    arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
                ) { backStackEntry ->
                    val recId = backStackEntry.arguments?.getLong("recordingId") ?: 0L
                    val recordingFlow = remember(recId, app) { app.repository.observeRecording(recId) }
                    val recording by recordingFlow.collectAsStateWithLifecycle(initialValue = null)
                    val paragraphFlow = remember(recId, app) { app.repository.observeParagraphs(recId) }
                    val paragraphs by paragraphFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                    val player = remember { AudioPlayerController() }
                    val playerState = player.state.collectAsStateWithLifecycle()

                    LaunchedEffect(recording?.audioPath) {
                        val path = recording?.audioPath
                        if (path.isNullOrBlank()) player.release() else player.load(path)
                    }

                    DisposableEffect(Unit) {
                        onDispose { player.release() }
                    }

                    DetailScreen(
                        recording = recording,
                        paragraphs = paragraphs,
                        playerState = playerState,
                        showTimestamps = settings.showTimestamps,
                        readerSerif = settings.readerSerif,
                        readerFontSizeTier = settings.readerFontSizeTier,
                        reTranscribeProgress = reTranscribeProgress.takeIf { transcribingRecordingId == recId },
                        modelManager = app.modelManager,
                        onDownloadAudioModel = ::downloadModel,
                        groqApiKey = settings.groqApiKey,
                        reviewModelId = settings.transcriptReviewModelId,
                        onSelectReviewModel = { model ->
                            scope.launch { app.preferences.setTranscriptReviewModel(model) }
                        },
                        onApplyTranscriptReview = app.repository::applyTranscriptReview,
                        onApplyWholeReview = { original, reviewed ->
                            app.repository.applyFullAudioReview(
                                recId, original, reviewed.paragraphs, reviewed.processedAudioBytes, reviewed.detectedLanguage
                            )
                        },
                        onRecognizeAudio = { fragment, model, russian, groqOverride, before, after, hint, progress ->
                            recognizeAudio(requireNotNull(recording), fragment, model, russian, groqOverride, before, after, hint, progress)
                        },
                        onOpenGroqSettings = { navController.navigate("settings") },
                        groqModelId = settings.groqModelId,
                        groqLanguageMode = settings.groqLanguageMode,
                        onBack = { navController.popBackStack() },
                        onRename = { newTitle ->
                            scope.launch { app.repository.renameRecording(recId, newTitle) }
                        },
                        onDelete = { onFailure ->
                            val rec = recording
                            if (rec == null) {
                                onFailure()
                            } else {
                                player.release()
                                scope.launch {
                                    try {
                                        if (transcribingRecordingId == rec.id) reTranscribeJob?.cancelAndJoin()
                                        transcriptionMutex.withLock { app.repository.deleteRecording(rec) }
                                        storageRefreshTick++
                                        if (navController.currentBackStackEntry == backStackEntry) navController.popBackStack()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        onFailure()
                                        snackbarHostState.showSnackbar(e.localizedMessage ?: s.deleteRecordingTitle)
                                    }
                                }
                            }
                        },
                        onTogglePlayPause = { player.togglePlayPause() },
                        onSeekToMs = { ms, autoPlay -> player.seekTo(ms, autoPlay) },
                        onSeekByMs = { delta -> player.seekBy(delta) },
                        onPreviewSpeechGap = { gap -> player.previewRange(gap.startMs, gap.endMs) },
                        onStopSpeechPreview = player::stopPreview,
                        onInsertRecoveredSpeech = { gap, words ->
                            val rec = recording
                            rec != null && transcribingRecordingId != rec.id &&
                                app.repository.insertRecoveredSpeech(rec, gap, words)
                        },
                        onSetSpeed = { spd -> player.setSpeed(spd) },
                        onToggleLoudnessBoost = { player.toggleLoudnessBoost() },
                        onUpdateParagraphText = { para, text ->
                            scope.launch {
                                try {
                                    if (!app.repository.updateParagraphTextAndRefreshSummary(para, text)) {
                                        snackbarHostState.showSnackbar(s.transcriptChangedRetry)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    snackbarHostState.showSnackbar(failure.message ?: s.asrSessionError)
                                }
                            }
                        },
                        onToggleParagraphBookmark = { para, label ->
                            scope.launch { app.repository.toggleParagraphBookmark(para, label) }
                        },
                        onToggleShowTimestamps = { enabled ->
                            scope.launch { app.preferences.setShowTimestamps(enabled) }
                        },
                        onToggleReaderSerif = { enabled ->
                            scope.launch { app.preferences.setReaderSerif(enabled) }
                        },
                        onCycleReaderFontSize = {
                            scope.launch {
                                app.preferences.setReaderFontSizeTier((settings.readerFontSizeTier + 1) % 3)
                            }
                        },
                        onReTranscribe = { presetId, beam, onlyTail ->
                            val rec = recording ?: return@DetailScreen
                            launchLocalReTranscribe(rec, presetId, beam, onlyTail)
                        },
                        onReTranscribeWithGroq = { onlyTail, langMode ->
                            val rec = recording ?: return@DetailScreen
                            launchGroqReTranscribe(rec, onlyTail, langMode)
                        },
                        onCancelReTranscribe = {
                            if (transcribingRecordingId == recId) reTranscribeJob?.cancel()
                        },
                        onExport = { fmt ->
                            recording?.let { exportAndShareRecording(it, fmt) }
                        },
                        onSaveExport = { fmt ->
                            recording?.let { saveExportWithSaf(it, fmt) }
                        },
                        onShareAudio = {
                            val rec = recording
                            if (rec != null && rec.audioPath.isNotBlank()) {
                                val file = File(rec.audioPath)
                                if (file.isFile && file.length() > 0L) {
                                    try {
                                        TranscriptExporter.shareFile(context, file, TranscriptExporter.mediaMimeType(file), s.shareAudio)
                                    } catch (failure: Exception) {
                                        scope.launch { snackbarHostState.showSnackbar(failure.message ?: s.exportError) }
                                    }
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar(s.audioFileNotFound) }
                                }
                            }
                        },
                        onSaveAudioAs = {
                            val rec = recording
                            if (rec != null && rec.audioPath.isNotBlank()) {
                                val file = File(rec.audioPath)
                                if (file.isFile && file.length() > 0L) {
                                    val name = TranscriptExporter.safeBaseName(rec.title)
                                    val suggestedName = if (file.extension.isBlank()) name else "$name.${file.extension}"
                                    launchSafSave(PendingSafSave(rec.id, null), suggestedName, TranscriptExporter.mediaMimeType(file))
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar(s.audioFileNotFound) }
                                }
                            }
                        },
                        onShareText = { txt ->
                            TranscriptExporter.shareText(context, recording?.title ?: s.appName, txt)
                        },
                        onOpenModelManager = { navController.navigate("models") },
                        onShowMessage = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                        speakerProfiles = speakerProfiles,
                        onAssignSpeaker = { para, newName, applyAll, enrollVoiceprint ->
                            scope.launch {
                                app.repository.assignRecordingSpeakerAndLearn(
                                    recordingId = recId,
                                    targetParagraph = para,
                                    oldSpeaker = para.speaker,
                                    newName = newName,
                                    applyToAllMatching = applyAll,
                                    enrollVoiceprint = enrollVoiceprint
                                )
                            }
                        }
                    )
                }

                composable("settings") {
                    settingsPage({ navController.popBackStack() }, 0.dp) { navController.navigate("models") }
                }
                composable("models") {
                    modelsPage({ navController.popBackStack() }, 0.dp)
                }
            }

            pendingImportUri?.let { importUri ->
                ImportAudioSheet(
                    importUri = importUri,
                    settings = settings,
                    modelManager = app.modelManager,
                    onDismiss = { pendingImportUri = null },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenModelManager = { navController.navigate("models") },
                    onStartImport = { useGroqApi, groqLanguageMode, presetId, beam ->
                        importMediaUri(
                            uri = importUri,
                            useGroqApi = useGroqApi,
                            groqLanguageMode = groqLanguageMode,
                            presetId = presetId,
                            beam = beam
                        )
                    }
                )
            }
        }
    }
}
