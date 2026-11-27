package com.stenograf.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.service.continuesSpeechParagraph
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.audio.PlayerState
import com.stenograf.app.audio.WaveformPeakExtractor
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
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
import com.stenograf.app.ui.components.SectionHeader
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.StatusPill
import com.stenograf.app.ui.components.TranscriptReviewSheet
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaExportSheet
import com.stenograf.app.ui.components.VantaIconButton
import com.stenograf.app.ui.components.VantaParagraphEditorSheet
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaProgressBanner
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaSheetActionRow
import com.stenograf.app.ui.components.VantaTextField
import com.stenograf.app.ui.components.VantaTopBar
import com.stenograf.app.ui.components.WaveformScrubberWithBookmarks
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onShowMessage: (String) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val groqAvailable = groqApiKey.isNotBlank()
    val russian = AppLocaleManager.currentLanguage.value.code == "ru"
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

    var showMoreSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showReTranscribeSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        VantaSheetActionRow(
                            icon = Icons.Rounded.AutoFixHigh,
                            label = if (russian) "Перепроверить всю запись с ИИ" else "Review entire recording with AI",
                            onClick = {
                                showMoreSheet = false
                                wholeReviewOriginal = paragraphs
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
            val selectedPreset = TinyAsrCatalog.findPreset(reTransPresetId)
            val modelInstalled = modelManager.isModelInstalled(selectedPreset.modelId) ||
                TinyAsrCatalog.findModel(selectedPreset.modelId).bundledInApk

            VantaBottomSheet(
                onDismissRequest = { showReTranscribeSheet = false },
                title = s.reTranscribe,
                subtitle = s.reTranscribeDesc,
                headerIcon = Icons.Rounded.AutoFixHigh
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (groqAvailable) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                                    .padding(Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Text(
                                    text = "Groq API ($groqModelId)",
                                    color = colors.accent,
                                    style = VantaType.cardTitle
                                )
                                Text(
                                    text = if (russian) "Облачная расшифровка Whisper. Разбивка по спикерам выполняется на устройстве, если она включена в настройках."
                                    else "Cloud Whisper transcription. Speaker diarization runs on device if enabled in settings.",
                                    color = colors.textSecondary,
                                    style = VantaType.bodySmall
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                                ) {
                                    listOf(
                                        "auto" to "Авто",
                                        "multi" to "RU + EN",
                                        "ru" to "RU",
                                        "en" to "EN",
                                        "es" to "ES",
                                        "de" to "DE",
                                        "fr" to "FR",
                                        "uk" to "UK"
                                    ).forEach { (code, pillLabel) ->
                                        StatusPill(
                                            label = pillLabel,
                                            containerColor = if (reTransGroqLang == code) colors.accentSoft else colors.surface,
                                            contentColor = if (reTransGroqLang == code) colors.accent else colors.textSecondary,
                                            onClick = {
                                                haptics.tick()
                                                reTransGroqLang = code
                                            }
                                        )
                                    }
                                }
                                VantaPrimaryButton(
                                    label = if (russian) "Расшифровать через Groq API" else "Transcribe with Groq API",
                                    icon = Icons.Rounded.AutoFixHigh,
                                    onClick = {
                                        haptics.confirm()
                                        showReTranscribeSheet = false
                                        onReTranscribeWithGroq(false, reTransGroqLang)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }

                    item {
                        SectionHeader(s.decodingAccuracyTitle)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            StatusPill(
                                label = s.accuracyBeam4,
                                containerColor = if (reTransBeam == 4) colors.accentSoft else colors.surface,
                                contentColor = if (reTransBeam == 4) colors.accent else colors.textSecondary,
                                onClick = { reTransBeam = 4 }
                            )
                            StatusPill(
                                label = s.fastGreedy1,
                                containerColor = if (reTransBeam == 1) colors.accentSoft else colors.surface,
                                contentColor = if (reTransBeam == 1) colors.accent else colors.textSecondary,
                                onClick = { reTransBeam = 1 }
                            )
                        }

                        Spacer(Modifier.height(Spacing.sm))
                        SectionHeader(s.reTranscribeModelLang)
                    }

                    items(TinyAsrCatalog.PRESETS.size) { idx ->
                        val preset = TinyAsrCatalog.PRESETS[idx]
                        val inst = modelManager.isModelInstalled(preset.modelId) ||
                            TinyAsrCatalog.findModel(preset.modelId).bundledInApk
                        SheetOptionRow(
                            selected = preset.id == reTransPresetId,
                            title = preset.label(s),
                            subtitle = if (inst) preset.modelId else "${preset.modelId} · ${s.modelAvailableLabel}",
                            onClick = {
                                haptics.tick()
                                reTransPresetId = preset.id
                            }
                        )
                    }

                    item {
                        Spacer(Modifier.height(Spacing.md))
                        if (modelInstalled) {
                            VantaPrimaryButton(
                                label = s.startAction,
                                onClick = {
                                    haptics.confirm()
                                    showReTranscribeSheet = false
                                    onReTranscribe(reTransPresetId, reTransBeam, false)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(
                                    text = s.modelRequiredInstallPrompt,
                                    color = colors.warning,
                                    style = VantaType.bodySmall
                                )
                                VantaPrimaryButton(
                                    label = s.manageModelsTitle,
                                    onClick = {
                                        showReTranscribeSheet = false
                                        onOpenModelManager()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailDocHeader(
    recording: RecordingEntity,
    showTimestamps: Boolean,
    autoScrollEnabled: Boolean,
    readerSerif: Boolean,
    readerFontSizeTier: Int,
    onToggleTimestamps: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onToggleFontFamily: () -> Unit,
    onCycleFontSize: () -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current

    val date = remember(recording.createdAtMs) {
        SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
    }
    val duration = remember(recording.durationMs) {
        AsrTextFormatter.formatDurationMs(recording.durationMs)
    }
    val language = remember(recording.detectedLanguage) {
        recording.detectedLanguage.takeIf { it.isNotBlank() }?.let { TinyAsrCatalog.languageNativeName(it) }.orEmpty()
    }
    val metaLine = remember(date, duration, language, recording.wordCount, s.wordsShort) {
        listOfNotNull(
            date,
            duration,
            language.takeIf { it.isNotBlank() },
            "${recording.wordCount} ${s.wordsShort}"
        ).joinToString(" · ")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs, bottom = Spacing.xs)
            .vantaSurfaceCard(colors, radius = Radius.lg, elevated = false)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = recording.title,
            color = colors.textPrimary,
            style = VantaType.docTitle
        )
        Text(
            text = metaLine,
            color = colors.textSecondary,
            style = VantaType.monoMetric
        )

        // Reader Quick Toolbar
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            ReaderToggleChip(
                icon = Icons.Rounded.Schedule,
                label = s.showTimestampsTitle,
                active = showTimestamps,
                onClick = onToggleTimestamps
            )
            ReaderToggleChip(
                icon = Icons.Rounded.Sync,
                label = extra.autoScrollLabel,
                active = autoScrollEnabled,
                onClick = onToggleAutoScroll
            )
            ReaderToggleChip(
                icon = Icons.Rounded.FormatSize,
                label = if (readerSerif) extra.fontSerifLabel else extra.fontSansLabel,
                active = readerSerif,
                onClick = onToggleFontFamily
            )
            ReaderToggleChip(
                icon = null,
                label = when (readerFontSizeTier) {
                    0 -> "A·S"
                    1 -> "A·M"
                    else -> "A·L"
                },
                active = false,
                onClick = onCycleFontSize
            )
        }
    }
}

@Composable
private fun ReaderToggleChip(
    icon: ImageVector?,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalStenografColors.current
    val bg by animateColorAsState(
        targetValue = if (active) colors.accentSoft else colors.surface,
        animationSpec = Motion.fastTween(),
        label = "reader_chip_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) colors.accent.copy(alpha = 0.4f) else colors.border,
        animationSpec = Motion.fastTween(),
        label = "reader_chip_border"
    )
    val tint = if (active) colors.accent else colors.textSecondary
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier = Modifier
            .pressScale(pressedScale = 0.95f)
            .clip(shape)
            .background(bg)
            .border(0.75.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        }
        Text(
            text = label,
            color = tint,
            style = VantaType.bodySmall.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParagraphBlock(
    paragraph: ParagraphEntity,
    showTimestamp: Boolean,
    continuesTurn: Boolean,
    showUnclearSpeaker: Boolean,
    searchQuery: String,
    isCurrentSearchMatch: Boolean,
    playerState: State<PlayerState>?,
    isPlaying: Boolean,
    isActiveParagraph: Boolean,
    transcriptStyle: TextStyle,
    onSeekToMs: (Long) -> Unit,
    onLongClick: () -> Unit
) {
    val colors = LocalStenografColors.current
    val words = remember(paragraph.wordsJson) { AsrJsonParser.parseWords(paragraph.wordsJson) }
    val activeWordIdx by remember(words, playerState, isPlaying) {
        derivedStateOf {
            if (playerState == null || !isPlaying || words.isEmpty()) -1
            else {
                val posSec = playerState.value.positionMs / 1000f
                words.indexOfFirst { posSec >= it.start && posSec <= (it.end + 0.12f) }
            }
        }
    }
    val blockBg by animateColorAsState(
        targetValue = if (isActiveParagraph) colors.surfaceHighlight else Color.Transparent,
        animationSpec = Motion.mediumTween(),
        label = "para_bg"
    )
    val activeBarAlpha by animateFloatAsState(
        targetValue = if (isActiveParagraph) 1f else 0f,
        animationSpec = Motion.mediumTween(),
        label = "para_bar_alpha"
    )

    // Unified AnnotatedString supporting simultaneous Word-Seek + Karaoke + Multi-word Search Highlight!
    val annotated = remember(
        paragraph.text,
        words,
        activeWordIdx,
        searchQuery,
        isCurrentSearchMatch,
        colors
    ) {
        buildAnnotatedString {
            if (words.isEmpty()) {
                val txt = paragraph.text
                if (searchQuery.isEmpty()) {
                    append(txt)
                } else {
                    var idx = 0
                    while (idx < txt.length) {
                        val hit = txt.indexOf(searchQuery, idx, ignoreCase = true)
                        if (hit < 0) {
                            append(txt.substring(idx))
                            break
                        }
                        if (hit > idx) append(txt.substring(idx, hit))
                        withStyle(
                            SpanStyle(
                                background = if (isCurrentSearchMatch) colors.warning.copy(alpha = 0.35f) else colors.warningSoft,
                                color = colors.warning,
                                fontWeight = FontWeight.SemiBold
                            )
                        ) {
                            append(txt.substring(hit, hit + searchQuery.length))
                        }
                        idx = hit + searchQuery.length
                    }
                }
            } else {
                // Pre-build full string to locate exact multi-word search match ranges
                val wordRanges = ArrayList<Triple<Int, Int, Float>>(words.size)
                val sb = StringBuilder()
                words.forEachIndexed { idx, w ->
                    val token = w.text.trim()
                    if (token.isNotEmpty()) {
                        if (sb.isNotEmpty() && idx > 0 && AsrTextFormatter.needsSpaceBefore(w)) {
                            sb.append(' ')
                        }
                        val st = sb.length
                        sb.append(token)
                        wordRanges.add(Triple(st, sb.length, w.start))
                    }
                }
                val fullText = sb.toString()

                val matchRanges = mutableListOf<IntRange>()
                if (searchQuery.isNotEmpty()) {
                    var cursor = 0
                    while (cursor < fullText.length) {
                        val hit = fullText.indexOf(searchQuery, cursor, ignoreCase = true)
                        if (hit < 0) break
                        matchRanges.add(hit until (hit + searchQuery.length))
                        cursor = hit + searchQuery.length
                    }
                }

                append(fullText)

                // Word annotations + active karaoke highlight
                wordRanges.forEachIndexed { rIdx, (st, en, wordStartSec) ->
                    addStringAnnotation(
                        tag = "WORD_SEC",
                        annotation = wordStartSec.toString(),
                        start = st,
                        end = en
                    )
                    if (rIdx == activeWordIdx) {
                        addStyle(
                            style = SpanStyle(
                                color = colors.textPrimary,
                                background = colors.highlightWord
                            ),
                            start = st,
                            end = en
                        )
                    }
                }

                // Search highlight spans layered cleanly on top
                for (range in matchRanges) {
                    addStyle(
                        style = SpanStyle(
                            background = if (isCurrentSearchMatch) colors.warning.copy(alpha = 0.35f) else colors.warningSoft,
                            color = colors.warning,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = range.first,
                        end = range.last + 1
                    )
                }
            }
        }
    }

    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (continuesTurn) 0.dp else Spacing.sm)
            .clip(RoundedCornerShape(Radius.md))
            .background(blockBg)
            .drawBehind {
                if (activeBarAlpha > 0.01f) {
                    val barW = 3.dp.toPx()
                    drawRoundRect(
                        color = colors.accent.copy(alpha = activeBarAlpha),
                        topLeft = Offset(0f, 6.dp.toPx()),
                        size = Size(barW, (size.height - 12.dp.toPx()).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }
            .combinedClickable(
                onClick = { onSeekToMs((paragraph.startSec * 1000f).toLong()) },
                onLongClick = onLongClick
            )
            .padding(
                start = if (isActiveParagraph) Spacing.md + 4.dp else Spacing.xs,
                end = Spacing.xs,
                top = if (continuesTurn) 0.dp else Spacing.sm,
                bottom = 3.dp
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (paragraph.isBookmark && !paragraph.bookmarkLabel.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.warningSoft)
                        .border(0.75.dp, colors.warning.copy(alpha = 0.3f), RoundedCornerShape(Radius.pill))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bookmark,
                        contentDescription = null,
                        tint = colors.warning,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = paragraph.bookmarkLabel,
                        color = colors.warning,
                        style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = colors.warning.copy(alpha = 0.25f),
                    thickness = 0.75.dp
                )
            }
        }

        val speakerLabel = paragraph.speaker ?: LocalStrings.current.unclearSpeaker.takeIf { showUnclearSpeaker }
        if (!continuesTurn && (showTimestamp || speakerLabel != null)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (speakerLabel != null) {
                    val speakerColor = remember(paragraph.speaker) {
                        val palette = listOf(
                            Color(0xFF3390EC),
                            Color(0xFF2EAD5B),
                            Color(0xFF8E63E6),
                            Color(0xFFEF6C00),
                            Color(0xFF0097A7),
                            Color(0xFFE91E63)
                        )
                        val idx = (paragraph.speaker.hashCode() and 0x7FFFFFFF) % palette.size
                        if (paragraph.speaker == null) colors.textMuted else palette[idx]
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(speakerColor.copy(alpha = if (colors.isDark) 0.22f else 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = speakerLabel,
                            color = speakerColor,
                            style = VantaType.monoBadge
                        )
                    }
                }
                if (showTimestamp) {
                    Text(
                        text = AsrTextFormatter.formatTimestampSec(paragraph.startSec),
                        color = if (isActiveParagraph) colors.accent else colors.textMuted,
                        style = VantaType.monoMetric,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xxs))
                            .clickable { onSeekToMs((paragraph.startSec * 1000f).toLong()) }
                            .padding(vertical = 1.dp)
                    )
                }
            }
        }

        Text(
            text = annotated,
            style = transcriptStyle.copy(color = colors.textPrimary),
            onTextLayout = { textLayout = it },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(annotated, onLongClick) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val layout = textLayout ?: return@detectTapGestures
                            val charOffset = layout.getOffsetForPosition(tapOffset)
                            val hit = annotated.getStringAnnotations("WORD_SEC", charOffset, charOffset).firstOrNull()
                            val sec = hit?.item?.toFloatOrNull()
                            if (sec != null) {
                                onSeekToMs((sec * 1000f).toLong())
                            } else {
                                onSeekToMs((paragraph.startSec * 1000f).toLong())
                            }
                        },
                        onLongPress = { onLongClick() }
                    )
                }
        )
    }
}

@Composable
private fun VantaAudioPlayerDock(
    playerState: State<PlayerState>,
    waveformPeaks: List<Float>,
    bookmarks: List<BookmarkMarker>,
    fallbackDurationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onSeekByMs: (Long) -> Unit,
    onJumpBookmark: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleLoudnessBoost: () -> Unit
) {
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val colors = LocalStenografColors.current
    val state = playerState.value
    val duration = if (state.durationMs > 0L) state.durationMs else fallbackDurationMs.coerceAtLeast(1L)
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    var showSpeedStrip by remember { mutableStateOf(false) }

    val currentSec = state.positionMs / 1000f
    val prevBookmarkSec = remember(bookmarks, currentSec) {
        bookmarks.lastOrNull { it.sec < currentSec - 1.5f }?.sec
    }
    val nextBookmarkSec = remember(bookmarks, currentSec) {
        bookmarks.firstOrNull { it.sec > currentSec + 0.8f }?.sec
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
        if (state.error != null) {
            Text(
                text = state.error,
                color = colors.destructive,
                style = VantaType.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Precision Waveform Scrubber with on-track amber bookmark pins
        WaveformScrubberWithBookmarks(
            positionMs = state.positionMs,
            durationMs = duration,
            isPlaying = state.isPlaying,
            waveformPeaks = waveformPeaks,
            bookmarks = bookmarks,
            onSeekToMs = onSeekToMs
        )

        // Expandable inline segmented speed strip (replaces ugly DropdownMenu)
        AnimatedVisibility(
            visible = showSpeedStrip,
            enter = expandVertically(Motion.fastTween()) + fadeIn(Motion.fastTween()),
            exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.surfaceRecessed)
                    .border(0.75.dp, colors.border, RoundedCornerShape(Radius.pill))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                speeds.forEach { spd ->
                    val selected = spd == state.speed
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (selected) colors.accent else Color.Transparent)
                            .clickable {
                                onSetSpeed(spd)
                                showSpeedStrip = false
                            }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${spd}×",
                            color = if (selected) colors.onAccent else colors.textSecondary,
                            style = VantaType.monoBadge
                        )
                    }
                }
            }
        }

        // Timecodes + Speed & Loudness Boost Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = AsrTextFormatter.formatDurationMs(state.positionMs),
                color = colors.textPrimary,
                style = VantaType.monoMetric
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = "${state.speed}×",
                    containerColor = if (showSpeedStrip) colors.accentSoft else colors.surfaceRecessed,
                    contentColor = if (showSpeedStrip) colors.accent else colors.textSecondary,
                    onClick = { showSpeedStrip = !showSpeedStrip }
                )
                StatusPill(
                    label = if (state.loudnessBoost) "+12 dB" else "+0 dB",
                    dotColor = if (state.loudnessBoost) colors.accent else null,
                    containerColor = if (state.loudnessBoost) colors.accentSoft else colors.surfaceRecessed,
                    contentColor = colors.textSecondary,
                    onClick = onToggleLoudnessBoost
                )
            }
            Text(
                text = AsrTextFormatter.formatDurationMs(duration),
                color = colors.textSecondary,
                style = VantaType.monoMetric
            )
        }

        // Studio Transport Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = Spacing.xxs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bookmarks.isNotEmpty()) {
                VantaIconButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = extra.prevBookmark,
                    tint = if (prevBookmarkSec != null) colors.warning else colors.textMuted.copy(alpha = 0.45f),
                    onClick = { prevBookmarkSec?.let(onJumpBookmark) }
                )
            }

            VantaIconButton(
                icon = Icons.Rounded.Replay10,
                contentDescription = "-10s",
                tint = colors.textPrimary,
                onClick = { onSeekByMs(-10_000L) }
            )

            Box(
                modifier = Modifier
                    .size(54.dp)
                    .pressScale(enabled = state.isReady, pressedScale = 0.91f)
                    .clip(CircleShape)
                    .background(if (state.isReady) colors.accent else colors.surfaceHighlight)
                    .clickable(enabled = state.isReady) { onTogglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = { fadeIn(Motion.fastTween()) togetherWith fadeOut(Motion.fastTween()) },
                    label = "play_pause_icon"
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) s.pauseButton else s.resumeButton,
                        tint = if (state.isReady) colors.onAccent else colors.textMuted,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            VantaIconButton(
                icon = Icons.Rounded.Forward10,
                contentDescription = "+10s",
                tint = colors.textPrimary,
                onClick = { onSeekByMs(10_000L) }
            )

            if (bookmarks.isNotEmpty()) {
                VantaIconButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = extra.nextBookmark,
                    tint = if (nextBookmarkSec != null) colors.warning else colors.textMuted.copy(alpha = 0.45f),
                    onClick = { nextBookmarkSec?.let(onJumpBookmark) }
                )
            }
        }
        }
    }
}
