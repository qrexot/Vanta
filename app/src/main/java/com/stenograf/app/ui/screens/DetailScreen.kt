package com.stenograf.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.asr.SpeechGap
import com.stenograf.app.service.continuesSpeechParagraph
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.audio.PlayerState
import com.stenograf.app.audio.WaveformPeakExtractor
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.export.ExportFormat
import com.stenograf.app.export.TranscriptExporter
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.ProcessingStatus
import com.stenograf.app.service.ReTranscribeWorker.AudioReview
import com.stenograf.app.ui.components.GlassScaffold
import com.stenograf.app.ui.components.vantaGlass
import com.stenograf.app.ui.components.BookmarkMarker
import com.stenograf.app.ui.components.EmptyStateView
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.TranscriptReviewSheet
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaExportSheet
import com.stenograf.app.ui.components.VantaIconButton
import com.stenograf.app.ui.components.VantaParagraphEditorSheet
import com.stenograf.app.ui.components.VantaProgressBanner
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaSheetActionRow
import com.stenograf.app.ui.components.VantaTextField
import com.stenograf.app.ui.components.VantaTopBar
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailScreen(
    recording: RecordingEntity?,
    paragraphs: List<ParagraphEntity>,
    playerState: State<PlayerState>,
    showTimestamps: Boolean,
    readerSerif: Boolean,
    readerFontSizeTier: Int,
    reTranscribeProgress: ProcessingStatus?,
    modelManager: ModelManager,
    groqApiKey: String,
    reviewModelId: String,
    onSelectReviewModel: (TranscriptReviewModel) -> Unit,
    onApplyTranscriptReview: suspend (ParagraphEntity, String, List<TranscriptWord>?) -> Boolean,
    onApplyWholeReview: suspend (List<ParagraphEntity>, AudioReview) -> Boolean,
    onRecognizeAudio: suspend (ParagraphEntity?, TranscriptReviewModel?, Boolean, String?, String, String, String, (Float) -> Unit) -> AudioReview,
    onOpenGroqSettings: () -> Unit,
    groqModelId: String = "whisper-large-v3-turbo",
    groqLanguageMode: String = GroqAsrClient.DEFAULT_LANGUAGE_MODE,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (onFailure: () -> Unit) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekToMs: (Long, Boolean) -> Unit,
    onSeekByMs: (Long) -> Unit,
    onPreviewSpeechGap: (SpeechGap) -> Unit,
    onStopSpeechPreview: () -> Unit,
    onInsertRecoveredSpeech: suspend (SpeechGap, List<TranscriptWord>) -> Boolean,
    onSetSpeed: (Float) -> Unit,
    onToggleLoudnessBoost: () -> Unit,
    onUpdateParagraphText: (ParagraphEntity, String) -> Unit,
    onToggleParagraphBookmark: (ParagraphEntity, String?) -> Unit,
    onToggleShowTimestamps: (Boolean) -> Unit,
    onToggleReaderSerif: (Boolean) -> Unit,
    onCycleReaderFontSize: () -> Unit,
    onReTranscribe: (presetId: String, beam: Int, onlyTail: Boolean) -> Unit,
    onReTranscribeWithGroq: (Boolean, String) -> Unit = { _, _ -> },
    onCancelReTranscribe: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onSaveExport: (ExportFormat) -> Unit,
    onShareAudio: () -> Unit,
    onSaveAudioAs: () -> Unit,
    onShareText: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onDownloadAudioModel: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    speakerProfiles: List<SpeakerProfileEntity> = emptyList(),
    onAssignSpeaker: (ParagraphEntity, String, Boolean, Boolean) -> Unit = { _, _, _, _ -> }
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val groqAvailable = groqApiKey.isNotBlank()
    val activeLang by AppLocaleManager.currentLanguage.collectAsState()
    val russian = activeLang.code == "ru"
    val reviewLabel = if (russian) "Распознать фрагмент заново" else "Recognize fragment again"
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberVantaHaptics()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchCursor by remember { mutableIntStateOf(0) }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var userScrolledAway by remember { mutableStateOf(false) }

    var audioToolsPosition by remember(recording?.id) { mutableStateOf<Long?>(null) }
    var showCoverage by remember(recording?.id) { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showReTranscribeSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    fun openAudioFilters() {
        audioToolsPosition = playerState.value.positionMs
        if (playerState.value.isPlaying) onTogglePlayPause()
    }

    var editingParagraph by remember { mutableStateOf<ParagraphEntity?>(null) }
    var contextParagraph by remember { mutableStateOf<ParagraphEntity?>(null) }
    var reviewParagraph by remember { mutableStateOf<ParagraphEntity?>(null) }
    var wholeReviewOriginal by remember { mutableStateOf<List<ParagraphEntity>?>(null) }
    var showReviewPicker by remember { mutableStateOf(false) }
    val reviewableParagraphs = remember(paragraphs) {
        paragraphs.filter { it.text.isNotBlank() && (!it.isBookmark || it.endSec > it.startSec) }
    }
    var bookmarkEditParagraph by remember { mutableStateOf<ParagraphEntity?>(null) }
    var bookmarkNoteInput by remember { mutableStateOf("") }
    var speakerAssignTarget by remember { mutableStateOf<ParagraphEntity?>(null) }
    var showRecordingSpeakersSheet by remember { mutableStateOf(false) }

    var reTransPresetId by remember(recording?.languagePresetId) {
        mutableStateOf(recording?.languagePresetId ?: "ru_en")
    }
    var reTransBeam by remember { mutableIntStateOf(4) }
    var reTransGroqLang by remember(groqLanguageMode) { mutableStateOf(groqLanguageMode) }

    var waveformPeaks by remember(recording?.audioPath) { mutableStateOf<List<Float>>(emptyList()) }
    LaunchedEffect(recording?.audioPath, recording?.fileSizeBytes) {
        val path = recording?.audioPath
        waveformPeaks = if (!path.isNullOrBlank()) {
            WaveformPeakExtractor.extractPeaks(context, path)
        } else {
            emptyList()
        }
    }

    val hasLabeledSpeakers = remember(paragraphs) { paragraphs.any { it.speaker != null } }
    val speakerTranscript = GroqAsrClient.SUPPORTED_MODELS.any { it.first == recording?.modelId } || hasLabeledSpeakers
    val continuingTurns = remember(paragraphs, speakerTranscript) {
        if (!speakerTranscript) emptySet() else buildSet {
            var anchor: ParagraphEntity? = null
            var last: ParagraphEntity? = null
            for ((index, current) in paragraphs.withIndex()) {
                val head = anchor
                val previous = last
                if (head != null && previous != null &&
                    current.endSec - head.startSec <= 34f && continuesSpeechParagraph(previous, current)
                ) {
                    add(index)
                } else {
                    anchor = current
                }
                last = current
            }
        }
    }
    val matchingIndices = remember(paragraphs, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList()
        else paragraphs.mapIndexedNotNull { idx, p ->
            if (p.text.contains(q, ignoreCase = true)) idx else null
        }
    }

    val isPlaying by remember(playerState) { derivedStateOf { playerState.value.isPlaying } }
    val activeParagraphIdx by remember(paragraphs, playerState) {
        derivedStateOf {
            val state = playerState.value
            val position = state.positionMs / 1000f
            if (!state.isPlaying && state.positionMs == 0L) -1
            else paragraphs.indexOfFirst { position >= it.startSec - 0.05f && position <= it.endSec + 0.55f }
        }
    }

    // Header item is index 0 inside LazyColumn when paragraphs are non-empty
    val headerItemCount = 1
    val isDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragging, isPlaying) {
        if (isDragging && isPlaying) {
            userScrolledAway = true
        }
    }
    LaunchedEffect(activeParagraphIdx, isPlaying, autoScrollEnabled, userScrolledAway) {
        if (isPlaying && autoScrollEnabled && !userScrolledAway && activeParagraphIdx >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(activeParagraphIdx + headerItemCount)
        }
    }

    val collapsedTitleAlpha = remember(listState) {
        {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 140f).coerceIn(0f, 1f)
        }
    }

    val bookmarkMarkers = remember(paragraphs) {
        paragraphs.filter { it.isBookmark }.map { BookmarkMarker(it.startSec) }
    }

    val transcriptStyle = remember(readerSerif, readerFontSizeTier) {
        VantaType.transcriptStyle(serif = readerSerif, sizeTier = readerFontSizeTier)
    }

    GlassScaffold(
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                VantaTopBar(
                    onBack = onBack,
                    title = recording?.title,
                    titleAlpha = collapsedTitleAlpha
                ) {
                    VantaIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = s.search,
                        active = showSearch,
                        onClick = {
                            haptics.tick()
                            showSearch = !showSearch
                            if (!showSearch) searchQuery = ""
                        }
                    )
                    VantaIconButton(
                        icon = Icons.Rounded.Share,
                        contentDescription = s.exportTitle,
                        onClick = {
                            haptics.click()
                            showExportSheet = true
                        }
                    )
                    VantaIconButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = s.menu,
                        onClick = {
                            haptics.click()
                            if (!deleting) showMoreSheet = true
                        }
                    )
                }

                // Inline Search Bar
                AnimatedVisibility(
                    visible = showSearch,
                    enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                    exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                ) {
                    VantaSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it; currentMatchCursor = 0 },
                        placeholder = s.searchInTextHint,
                        modifier = Modifier.background(colors.bg).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        trailingContent = {
                            if (matchingIndices.isNotEmpty()) {
                                Text(
                                    text = "${currentMatchCursor + 1}/${matchingIndices.size}",
                                    color = colors.textSecondary,
                                    style = VantaType.monoMetric,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                VantaIconButton(
                                    icon = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    size = 34.dp,
                                    iconSize = 18.dp,
                                    onClick = {
                                        haptics.tick()
                                        val next = (currentMatchCursor - 1 + matchingIndices.size) % matchingIndices.size
                                        currentMatchCursor = next
                                        scope.launch {
                                            listState.animateScrollToItem(matchingIndices[next] + headerItemCount)
                                        }
                                    }
                                )
                                VantaIconButton(
                                    icon = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    size = 34.dp,
                                    iconSize = 18.dp,
                                    onClick = {
                                        haptics.tick()
                                        val next = (currentMatchCursor + 1) % matchingIndices.size
                                        currentMatchCursor = next
                                        scope.launch {
                                            listState.animateScrollToItem(matchingIndices[next] + headerItemCount)
                                        }
                                    }
                                )
                            }
                        }
                    )
                }

                // Re-transcribe progress banner
                AnimatedVisibility(
                    visible = reTranscribeProgress != null,
                    enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                    exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                ) {
                    reTranscribeProgress?.let { status ->
                        VantaProgressBanner(
                            message = status.message,
                            progress = status.progress,
                            liveSnippet = status.liveSnippet,
                            startedAtMs = status.startedAtMs,
                            onCancel = onCancelReTranscribe,
                            modifier = Modifier.background(colors.bg).padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                        )
                    }
                }

                // Unprocessed audio tail banner
                if (recording != null && recording.hasUnprocessedAudio && reTranscribeProgress == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.bg)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(colors.warningSoft)
                            .border(0.75.dp, colors.warning.copy(alpha = 0.35f), RoundedCornerShape(Radius.md))
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.unprocessedTailTitle,
                                color = colors.textPrimary,
                                style = VantaType.body.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = s.unprocessedTailSubtitle,
                                color = colors.textSecondary,
                                style = VantaType.bodySmall
                            )
                        }
                        TextButton(onClick = {
                            if (groqAvailable && GroqAsrClient.SUPPORTED_MODELS.any { it.first == recording.modelId }) {
                                onReTranscribeWithGroq(true, groqLanguageMode)
                            } else {
                                onReTranscribe(recording.languagePresetId, 4, true)
                            }
                        }) {
                            Text(
                                text = s.processTailButton,
                                color = colors.warning,
                                style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Floating jump-back-to-audio pill when user scrolls away during playback
                AnimatedVisibility(
                    visible = isPlaying && autoScrollEnabled && userScrolledAway && activeParagraphIdx >= 0,
                    enter = fadeIn(Motion.fastTween()),
                    exit = fadeOut(Motion.fastTween()),
                    modifier = Modifier.padding(bottom = Spacing.xxs)
                ) {
                    Row(
                        modifier = Modifier
                            .pressScale(pressedScale = 0.95f)
                            .vantaGlass()
                            .clickable {
                                haptics.tick()
                                userScrolledAway = false
                                scope.launch {
                                    listState.animateScrollToItem((activeParagraphIdx + headerItemCount).coerceAtLeast(0))
                                }
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
                        Text(
                            text = extra.jumpToCurrentPosition,
                            color = colors.textPrimary,
                            style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
                VantaAudioPlayerDock(
                    playerState = playerState,
                    waveformPeaks = waveformPeaks,
                    bookmarks = bookmarkMarkers,
                    fallbackDurationMs = recording?.durationMs ?: 0L,
                    filterLabel = if (recording?.audioPath.isNullOrBlank()) null else if (russian) "Фильтры" else "Filters",
                    onOpenAudioFilters = ::openAudioFilters,
                    onTogglePlayPause = {
                        haptics.click()
                        onTogglePlayPause()
                    },
                    onSeekToMs = { ms -> onSeekToMs(ms, false) },
                    onSeekByMs = { delta ->
                        haptics.tick()
                        onSeekByMs(delta)
                    },
                    onJumpBookmark = { sec ->
                        haptics.tick()
                        onSeekToMs((sec * 1000f).toLong(), true)
                    },
                    onSetSpeed = { spd ->
                        haptics.tick()
                        onSetSpeed(spd)
                    },
                    onToggleLoudnessBoost = {
                        haptics.tick()
                        onToggleLoudnessBoost()
                    }
                )
            }
        }
    ) { overlayPadding ->
        if (paragraphs.isEmpty() && reTranscribeProgress == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = overlayPadding.calculateTopPadding(),
                    bottom = overlayPadding.calculateBottomPadding() + Spacing.xxl
                )
            ) {
                item(key = "doc_header_empty") {
                    recording?.let { rec ->
                        DetailDocHeader(
                            recording = rec,
                            showTimestamps = showTimestamps,
                            autoScrollEnabled = autoScrollEnabled,
                            readerSerif = readerSerif,
                            readerFontSizeTier = readerFontSizeTier,
                            onToggleTimestamps = { onToggleShowTimestamps(!showTimestamps) },
                            onToggleAutoScroll = { autoScrollEnabled = !autoScrollEnabled },
                            onToggleFontFamily = { onToggleReaderSerif(!readerSerif) },
                            onCycleFontSize = onCycleReaderFontSize
                        )
                    }
                }
                item(key = "empty_transcript") {
                    EmptyStateView(
                        icon = Icons.Rounded.AutoFixHigh,
                        title = s.noTranscriptTitle,
                        subtitle = s.noTranscriptSubtitle,
                        actionLabel = s.transcribeNow,
                        onAction = { showReTranscribeSheet = true }
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = overlayPadding.calculateTopPadding() + Spacing.xs,
                    bottom = overlayPadding.calculateBottomPadding() + Spacing.xxl
                )
            ) {
                item(key = "doc_header") {
                    recording?.let { rec ->
                        DetailDocHeader(
                            recording = rec,
                            showTimestamps = showTimestamps,
                            autoScrollEnabled = autoScrollEnabled,
                            readerSerif = readerSerif,
                            readerFontSizeTier = readerFontSizeTier,
                            onToggleTimestamps = {
                                haptics.tick()
                                onToggleShowTimestamps(!showTimestamps)
                            },
                            onToggleAutoScroll = {
                                haptics.tick()
                                autoScrollEnabled = !autoScrollEnabled
                                if (autoScrollEnabled) userScrolledAway = false
                            },
                            onToggleFontFamily = {
                                haptics.tick()
                                onToggleReaderSerif(!readerSerif)
                            },
                            onCycleFontSize = {
                                haptics.tick()
                                onCycleReaderFontSize()
                            }
                        )
                    }
                }

                itemsIndexed(paragraphs, key = { _, p -> p.id }) { idx, para ->
                    val isFocusedMatch = matchingIndices.getOrNull(currentMatchCursor) == idx
                    val isActive = idx == activeParagraphIdx
                    ParagraphBlock(
                        paragraph = para,
                        showTimestamp = showTimestamps,
                        continuesTurn = idx in continuingTurns,
                        showUnclearSpeaker = hasLabeledSpeakers,
                        searchQuery = searchQuery.trim(),
                        isCurrentSearchMatch = isFocusedMatch,
                        playerState = if (isActive) playerState else null,
                        isPlaying = isPlaying,
                        isActiveParagraph = isActive,
                        transcriptStyle = transcriptStyle,
                        onSeekToMs = { ms -> onSeekToMs(ms, true) },
                        onLongClick = {
                            haptics.heavy()
                            contextParagraph = para
                        },
                        onSpeakerClick = {
                            haptics.tick()
                            speakerAssignTarget = para
                        }
                    )
                }
            }
        }

        // Paragraph Context Actions Sheet (replaces 80 repetitive MoreVert buttons)
        contextParagraph?.let { para ->
            val timeRange = "${AsrTextFormatter.formatTimestampSec(para.startSec)} – ${AsrTextFormatter.formatTimestampSec(para.endSec.coerceAtLeast(para.startSec))}"
            VantaBottomSheet(
                onDismissRequest = { contextParagraph = null },
                title = timeRange,
                subtitle = para.text
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VantaSheetActionRow(
                        icon = Icons.Rounded.PlayArrow,
                        label = extra.playFromHereAction,
                        tint = colors.accent,
                        iconBg = colors.accentSoft,
                        onClick = {
                            haptics.click()
                            onSeekToMs((para.startSec * 1000f).toLong(), true)
                            contextParagraph = null
                        }
                    )
                    VantaSheetActionRow(
                        icon = Icons.Rounded.Edit,
                        label = s.editAction,
                        onClick = {
                            editingParagraph = para
                            contextParagraph = null
                        }
                    )
                    VantaSheetActionRow(
                        icon = Icons.Rounded.RecordVoiceOver,
                        label = extra.assignSpeakerTitle,
                        onClick = {
                            speakerAssignTarget = para
                            contextParagraph = null
                        }
                    )
                    if (reviewableParagraphs.any { it.id == para.id }) {
                        VantaSheetActionRow(
                            icon = Icons.Rounded.AutoFixHigh,
                            label = reviewLabel,
                            onClick = {
                                reviewParagraph = para
                                contextParagraph = null
                            }
                        )
                    }
                    VantaSheetActionRow(
                        icon = Icons.Rounded.ContentCopy,
                        label = extra.copySnippetAction,
                        onClick = {
                            clipboard.setText(AnnotatedString(para.text))
                            onShowMessage(s.copiedParagraph)
                            contextParagraph = null
                        }
                    )
                    VantaSheetActionRow(
                        icon = if (para.isBookmark) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd,
                        label = if (para.isBookmark) extra.removeBookmarkAction else extra.addBookmarkAction,
                        tint = colors.warning,
                        iconBg = colors.warningSoft,
                        onClick = {
                            if (para.isBookmark) {
                                haptics.click()
                                onToggleParagraphBookmark(para, null)
                                contextParagraph = null
                            } else {
                                bookmarkEditParagraph = para
                                bookmarkNoteInput = ""
                                contextParagraph = null
                            }
                        }
                    )
                    VantaSheetActionRow(
                        icon = Icons.Rounded.Share,
                        label = s.shareText,
                        onClick = {
                            onShareText(para.text)
                            contextParagraph = null
                        }
                    )
                }
            }
        }

        if (showReviewPicker) {
            VantaBottomSheet(
                onDismissRequest = { showReviewPicker = false },
                title = if (russian) "Выберите фрагмент" else "Choose a fragment",
                headerIcon = Icons.Rounded.AutoFixHigh
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(reviewableParagraphs, key = { it.id }) { paragraph ->
                        SheetOptionRow(
                            selected = false,
                            title = AsrTextFormatter.formatTimestampSec(paragraph.startSec),
                            subtitle = paragraph.text.take(160),
                            onClick = {
                                showReviewPicker = false
                                reviewParagraph = paragraph
                            }
                        )
                    }
                }
            }
        }

        reviewParagraph?.let { paragraph ->
            val position = reviewableParagraphs.indexOfFirst { it.id == paragraph.id }
            val previous = if (position < 0) "" else {
                reviewableParagraphs.subList((position - 3).coerceAtLeast(0), position)
                    .joinToString("\n") { it.text }
            }
            val following = if (position < 0) "" else {
                reviewableParagraphs.subList(position + 1, (position + 4).coerceAtMost(reviewableParagraphs.size))
                    .joinToString("\n") { it.text }
            }
            TranscriptReviewSheet(
                originalText = paragraph.text,
                preferredModelId = reviewModelId,
                hasCloudKey = groqAvailable,
                russian = russian,
                russianAudioByDefault = false,
                onSelectModel = onSelectReviewModel,
                onOpenSettings = {
                    reviewParagraph = null
                    onOpenGroqSettings()
                },
                onRecognize = { model, forceRussian, groqOverride, hint, progress ->
                    onRecognizeAudio(paragraph, model, forceRussian, groqOverride, previous, following, hint, progress)
                },
                onApply = { onApplyTranscriptReview(paragraph, it.proposal.correctedText, it.recognizedWords) },
                onListen = { onSeekToMs((paragraph.startSec * 1000f).toLong(), true) },
                onCopied = { onShowMessage(s.copiedParagraph) },
                onApplied = { onShowMessage(if (russian) "Правка сохранена" else "Edit saved") },
                onDismiss = { reviewParagraph = null }
            )
        }

        wholeReviewOriginal?.let { original ->
            val excerpt = remember(original) {
                original.asSequence().map { it.text }.filter { it.isNotBlank() }.take(4).joinToString("\n").take(800)
            }
            TranscriptReviewSheet(
                originalText = excerpt,
                wholeRecording = true,
                preferredModelId = reviewModelId,
                hasCloudKey = groqAvailable,
                russian = russian,
                russianAudioByDefault = false,
                onSelectModel = onSelectReviewModel,
                onOpenSettings = { wholeReviewOriginal = null; onOpenGroqSettings() },
                onRecognize = { model, forceRussian, groqOverride, hint, progress ->
                    onRecognizeAudio(null, model, forceRussian, groqOverride, "", "", hint, progress)
                },
                onApply = { onApplyWholeReview(original, it) },
                onCopied = { onShowMessage(s.copiedAllText) },
                onApplied = { onShowMessage(if (russian) "Расшифровка обновлена" else "Transcript updated") },
                onDismiss = { wholeReviewOriginal = null }
            )
        }

        // Add Bookmark Dialog on existing paragraph
        bookmarkEditParagraph?.let { para ->
            VantaDialog(
                onDismissRequest = { bookmarkEditParagraph = null },
                title = s.bookmarkDialogTitle,
                icon = Icons.Rounded.BookmarkAdd,
                iconTint = colors.warning,
                iconContainerColor = colors.warningSoft,
                confirmLabel = s.addButton,
                onConfirm = {
                    haptics.confirm()
                    onToggleParagraphBookmark(
                        para,
                        bookmarkNoteInput.trim().ifEmpty { "${s.bookmarkDefaultLabel} ${AsrTextFormatter.formatTimestampSec(para.startSec)}" }
                    )
                    bookmarkEditParagraph = null
                },
                dismissLabel = s.cancel,
                onDismiss = { bookmarkEditParagraph = null }
            ) {
                VantaTextField(
                    value = bookmarkNoteInput,
                    onValueChange = { bookmarkNoteInput = it },
                    label = s.bookmarkNoteLabel,
                    singleLine = true
                )
            }
        }

        // More options bottom sheet
        if (showMoreSheet) {
            VantaBottomSheet(
                onDismissRequest = { showMoreSheet = false },
                title = recording?.title ?: s.menu
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    VantaSheetActionRow(Icons.Rounded.ContentCopy, s.copyAll) {
                        val full = TranscriptExporter.buildPlainText(paragraphs, showTimestamps)
                        clipboard.setText(AnnotatedString(full))
                        onShowMessage(s.copiedAllText)
                        showMoreSheet = false
                    }
                    VantaSheetActionRow(Icons.Rounded.Share, s.shareText) {
                        onShareText(TranscriptExporter.buildPlainText(paragraphs, showTimestamps))
                        showMoreSheet = false
                    }
                    VantaSheetActionRow(Icons.Rounded.FileUpload, s.shareAudio) {
                        onShareAudio()
                        showMoreSheet = false
                    }
                    VantaSheetActionRow(Icons.Rounded.SaveAlt, "${s.saveFileAs} (${s.shareAudio})") {
                        onSaveAudioAs()
                        showMoreSheet = false
                    }
                    if (recording != null) {
                        if (recording.audioPath.isNotBlank()) {
                            VantaSheetActionRow(
                                Icons.Rounded.Tune,
                                if (russian) "Фильтры аудио" else "Audio filters"
                            ) {
                                showMoreSheet = false
                                openAudioFilters()
                            }
                            if (reTranscribeProgress == null &&
                                recording.statusEnum != RecordingStatus.RECORDING &&
                                recording.statusEnum != RecordingStatus.PAUSED
                            ) {
                                VantaSheetActionRow(
                                    Icons.Rounded.Search,
                                    if (russian) "Проверить полноту" else "Check transcript coverage"
                                ) {
                                    showMoreSheet = false
                                    showCoverage = true
                                }
                            }
                        }
                        VantaSheetActionRow(
                            icon = Icons.Rounded.AutoFixHigh,
                            label = if (russian) "Перепроверить всю запись с ИИ" else "Review entire recording with AI",
                            onClick = {
                                showMoreSheet = false
                                wholeReviewOriginal = paragraphs
                            }
                        )
                    }
                    if (paragraphs.isNotEmpty()) {
                        VantaSheetActionRow(
                            icon = Icons.Rounded.RecordVoiceOver,
                            label = extra.speakerProfilesInRecording,
                            onClick = {
                                showMoreSheet = false
                                showRecordingSpeakersSheet = true
                            }
                        )
                    }
                    if (reviewableParagraphs.isNotEmpty()) {
                        VantaSheetActionRow(
                            icon = Icons.Rounded.AutoFixHigh,
                            label = reviewLabel,
                            onClick = {
                                showMoreSheet = false
                                showReviewPicker = true
                            }
                        )
                    }
                    VantaSheetActionRow(
                        icon = Icons.Rounded.AutoFixHigh,
                        label = s.reTranscribe,
                        tint = colors.accent,
                        iconBg = colors.accentSoft
                    ) {
                        showReTranscribeSheet = true
                        showMoreSheet = false
                    }
                    VantaSheetActionRow(Icons.Rounded.Edit, s.rename) {
                        renameText = recording?.title ?: ""
                        showRenameDialog = true
                        showMoreSheet = false
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    VantaSheetActionRow(
                        icon = Icons.Rounded.DeleteOutline,
                        label = s.delete,
                        isDestructive = true
                    ) {
                        showDeleteDialog = true
                        showMoreSheet = false
                    }
                }
            }
        }

        if (showCoverage && recording != null && reTranscribeProgress == null) {
            TranscriptCoverageSheet(
                recording, paragraphs, modelManager, russian,
                playerReady = playerState.value.isReady,
                onPreview = onPreviewSpeechGap,
                onStopPreview = onStopSpeechPreview,
                onInsert = onInsertRecoveredSpeech,
                onOpenModels = {
                    showCoverage = false
                    onOpenModelManager()
                },
                onDismiss = { showCoverage = false }
            )
        }

        val toolsPosition = audioToolsPosition
        if (recording != null && toolsPosition != null) {
            RecordingAudioToolsSheet(
                recording, modelManager, toolsPosition, russian,
                onDownloadModel = onDownloadAudioModel,
                onDismiss = { audioToolsPosition = null }
            )
        }

        // Unified Export & Save sheet
        if (showExportSheet) {
            VantaExportSheet(
                title = recording?.title ?: s.appName,
                onDismissRequest = { showExportSheet = false },
                onShareExport = onExport,
                onSaveExport = onSaveExport,
                onCopyPlainText = if (paragraphs.isNotEmpty()) {
                    {
                        val full = TranscriptExporter.buildPlainText(paragraphs, showTimestamps)
                        clipboard.setText(AnnotatedString(full))
                        onShowMessage(s.copiedAllText)
                    }
                } else null
            )
        }

        // Re-transcribe bottom sheet (TinyASR or Groq API)
        if (showReTranscribeSheet) {
            DetailReTranscribeSheet(
                groqAvailable = groqAvailable,
                groqModelId = groqModelId,
                russian = russian,
                reTransPresetId = reTransPresetId,
                onSelectPresetId = { reTransPresetId = it },
                reTransBeam = reTransBeam,
                onSelectBeam = { reTransBeam = it },
                reTransGroqLang = reTransGroqLang,
                onSelectGroqLang = { reTransGroqLang = it },
                modelManager = modelManager,
                onDismiss = { showReTranscribeSheet = false },
                onReTranscribeWithGroq = { langMode -> onReTranscribeWithGroq(false, langMode) },
                onReTranscribeLocal = { presetId, beam -> onReTranscribe(presetId, beam, false) },
                onOpenModelManager = onOpenModelManager
            )
        }
        // IME-friendly Full Paragraph Editor Sheet
        editingParagraph?.let { para ->
            VantaParagraphEditorSheet(
                initialText = para.text,
                startSec = para.startSec,
                endSec = para.endSec,
                onDismissRequest = { editingParagraph = null },
                onSave = { updated ->
                    onUpdateParagraphText(para, updated)
                    editingParagraph = null
                }
            )
        }

        if (showRenameDialog) {
            VantaDialog(
                onDismissRequest = { showRenameDialog = false },
                title = s.renameRecordingTitle,
                icon = Icons.Rounded.Edit,
                confirmLabel = s.save,
                confirmEnabled = renameText.isNotBlank(),
                onConfirm = {
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                    showRenameDialog = false
                },
                dismissLabel = s.cancel,
                onDismiss = { showRenameDialog = false }
            ) {
                VantaTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    required = true
                )
            }
        }

        if (showDeleteDialog) {
            VantaDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = s.deleteRecordingTitle,
                icon = Icons.Rounded.DeleteOutline,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                highlightText = recording?.title,
                message = s.deleteRecordingMessage,
                confirmLabel = s.delete,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    showDeleteDialog = false
                    deleting = true
                    onDelete { deleting = false }
                },
                dismissLabel = s.cancel,
                onDismiss = { showDeleteDialog = false }
            )
        }

        if (showRecordingSpeakersSheet) {
            RecordingSpeakersOverviewSheet(
                paragraphs = paragraphs,
                onDismiss = { showRecordingSpeakersSheet = false },
                onSelectSpeakerSample = { samplePara -> speakerAssignTarget = samplePara }
            )
        }

        speakerAssignTarget?.let { targetPara ->
            val matchCount = remember(paragraphs, targetPara.speaker) {
                if (targetPara.speaker.isNullOrBlank()) 1
                else paragraphs.count { it.speaker == targetPara.speaker }
            }
            DetailSpeakerAssignSheet(
                targetParagraph = targetPara,
                allMatchingCount = matchCount,
                savedProfiles = speakerProfiles,
                onDismiss = { speakerAssignTarget = null },
                onApply = { newName, applyAll, enrollVoiceprint ->
                    onAssignSpeaker(targetPara, newName, applyAll, enrollVoiceprint)
                }
            )
        }
    }
}

