package com.stenograf.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.service.ReTranscribeWorker.AudioReview
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.LiveRecordingUiState
import com.stenograf.app.ui.components.GlassScaffold
import com.stenograf.app.ui.components.vantaGlass
import com.stenograf.app.ui.components.AnimatedRecordingDot
import com.stenograf.app.ui.components.rememberLiveTextReveal
import com.stenograf.app.ui.components.LiveWaveformView
import com.stenograf.app.ui.components.SpeakerPill
import com.stenograf.app.ui.components.StatusPill
import com.stenograf.app.ui.components.VantaIconButton
import com.stenograf.app.ui.components.VantaLinearProgressTrack
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordingScreen(
    state: LiveRecordingUiState,
    hasCloudKey: Boolean,
    reviewModelId: String,
    onSelectReviewModel: (TranscriptReviewModel) -> Unit,
    onSetLiveAiEnabled: (Boolean, TranscriptReviewModel) -> Unit,
    onOpenGroqSettings: () -> Unit,
    onRecognizeBlock: suspend (ParagraphEntity, TranscriptReviewModel?, Boolean, String?, String, String, String, (Float) -> Unit) -> AudioReview,
    onApplyBlock: suspend (ParagraphEntity, String, List<TranscriptWord>?) -> Boolean,
    waveform: Flow<List<Float>>,
    showTimestamps: Boolean,
    keepScreenOn: Boolean,
    readerSerif: Boolean,
    readerFontSizeTier: Int,
    modelManager: ModelManager,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onAddBookmark: (String?) -> Unit,
    onCycleFontSize: () -> Unit,
    onSwitchLanguagePreset: (String) -> Unit,
    onOpenModelManager: () -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val view = LocalView.current
    val haptics = rememberVantaHaptics()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var teleprompterHudVisible by remember { mutableStateOf(true) }
    var autoFollow by remember { mutableStateOf(true) }
    val activeLang by AppLocaleManager.currentLanguage.collectAsState()
    val russian = activeLang.code == "ru"
    var reviewBlock by remember { mutableStateOf<ParagraphEntity?>(null) }
    var confirmLiveAi by remember { mutableStateOf(false) }
    var liveAiModel by rememberSaveable { mutableStateOf(TranscriptReviewModel.Fast) }
    var showLangSheet by remember { mutableStateOf(false) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkNote by remember { mutableStateOf("") }
    var missingModelIdPrompt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fullScreen, teleprompterHudVisible) {
        if (fullScreen && teleprompterHudVisible) {
            delay(3500L)
            teleprompterHudVisible = false
        }
    }

    DisposableEffect(keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (keepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    BackHandler(enabled = fullScreen) { fullScreen = false }
    DisposableEffect(view, fullScreen) {
        val window = (view.context as? Activity)?.window
        if (!fullScreen || window == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val systemBars = WindowInsetsCompat.Type.systemBars()
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(systemBars)
            onDispose {
                controller.show(systemBars)
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }

    val transcriptStyle = remember(readerSerif, readerFontSizeTier, fullScreen) {
        VantaType.transcriptStyle(
            serif = readerSerif,
            sizeTier = readerFontSizeTier,
            teleprompter = fullScreen
        )
    }

    val isDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragging) {
        if (isDragging) autoFollow = false
    }
    LaunchedEffect(autoFollow) {
        if (!autoFollow) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            if (last != null && last.index == layout.totalItemsCount - 1) {
                (last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset).coerceAtLeast(0)
            } else if (layout.totalItemsCount > 0) {
                Int.MAX_VALUE
            } else {
                0
            }
        }.collect { overflow ->
            if (overflow > 0 && !isDragging) {
                val last = listState.layoutInfo.totalItemsCount - 1
                if (overflow == Int.MAX_VALUE && last >= 0) {
                    listState.scrollToItem(last)
                } else if (overflow != Int.MAX_VALUE) {
                    listState.animateScrollBy(overflow.toFloat(), Motion.fastTween())
                }
            }
        }
    }

    val activePreset = TinyAsrCatalog.findPreset(state.languagePresetId)
    val draftWords = remember(state.pendingSpeakerWords, state.openWords) {
        state.pendingSpeakerWords + state.openWords
    }
    val bookmarkCount = remember(state.committedParagraphs) {
        state.committedParagraphs.count { it.isBookmark }
    }
    val pauseBtnBorder by animateColorAsState(
        targetValue = if (state.isPaused) colors.accent.copy(alpha = 0.6f) else colors.borderStrong,
        animationSpec = Motion.mediumTween(),
        label = "pause_border"
    )
    val compactHeight = LocalConfiguration.current.screenHeightDp < 520

    GlassScaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Floating scroll-to-live pill
                AnimatedVisibility(
                    visible = !autoFollow,
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
                                autoFollow = true
                                scope.launch {
                                    val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                    listState.animateScrollToItem(last)
                                }
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = s.jumpToLiveText,
                            color = colors.textPrimary,
                            style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
                if (!fullScreen) {
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
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                        // Top accessory rail: Language Switcher Pill + One-Tap Instant Bookmark Pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 42.dp)
                                        .pressScale(pressedScale = 0.97f)
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(if (state.isGroqEngine) colors.accentSoft else colors.surfaceElevated)
                                        .border(
                                            0.75.dp,
                                            if (state.isGroqEngine) colors.accent.copy(alpha = 0.35f) else colors.border,
                                            RoundedCornerShape(Radius.pill)
                                        )
                                        .clickable(enabled = !state.isGroqEngine && !state.isFinishing) {
                                            haptics.tick()
                                            showLangSheet = true
                                        }
                                        .padding(horizontal = Spacing.md, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Translate,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(
                                        text = when {
                                            state.isGroqEngine -> "Groq · ${state.groqModelId}"
                                            state.modelId == TinyAsrCatalog.GIGAAM_V4 -> "GigaAM v4 · ${state.activeLanguage.uppercase()}"
                                            else -> "TinyASR · ${state.activeLanguage.uppercase()}"
                                        },
                                        color = if (state.isGroqEngine) colors.accent else colors.textPrimary,
                                        style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!state.isGroqEngine) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = colors.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                            // Split Bookmark control: 1-tap instant bookmark + quick edit note icon
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 42.dp)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(colors.warningSoft)
                                    .border(0.75.dp, colors.warning.copy(alpha = 0.35f), RoundedCornerShape(Radius.pill)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .pressScale(pressedScale = 0.95f)
                                        .combinedClickable(
                                            onClick = {
                                                haptics.tick()
                                                onAddBookmark(null)
                                            },
                                            onLongClick = {
                                                haptics.heavy()
                                                bookmarkNote = ""
                                                showBookmarkDialog = true
                                            }
                                        )
                                        .padding(start = Spacing.md, end = Spacing.sm, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BookmarkAdd,
                                        contentDescription = s.bookmarkButton,
                                        tint = colors.warning,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = s.bookmarkButton,
                                        color = colors.warning,
                                        style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(0.75.dp)
                                        .height(18.dp)
                                        .background(colors.warning.copy(alpha = 0.3f))
                                )
                                Box(
                                    modifier = Modifier
                                        .pressScale(pressedScale = 0.9f)
                                        .clickable {
                                            haptics.tick()
                                            bookmarkNote = ""
                                            showBookmarkDialog = true
                                        }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = extra.editNoteAction,
                                        tint = colors.warning,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // Ergonomic transport bar: wider Pause/Resume + distinct Stop & Save
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    haptics.click()
                                    if (state.isPaused) onResume() else onPause()
                                },
                                enabled = !state.isFinishing,
                                shape = RoundedCornerShape(Radius.md),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isPaused) colors.accentSoft else colors.surfaceElevated,
                                    contentColor = if (state.isPaused) colors.accent else colors.textPrimary,
                                    disabledContainerColor = colors.surfaceRecessed,
                                    disabledContentColor = colors.textMuted
                                ),
                                border = BorderStroke(1.dp, pauseBtnBorder),
                                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 50.dp)
                                    .pressScale(enabled = !state.isFinishing, pressedScale = 0.97f)
                            ) {
                                AnimatedContent(
                                    targetState = state.isPaused,
                                    transitionSpec = { fadeIn(Motion.fastTween()) togetherWith fadeOut(Motion.fastTween()) },
                                    label = "pause_resume_content"
                                ) { paused ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (paused) s.resumeButton else s.pauseButton,
                                            style = VantaType.rowLabel.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    haptics.confirm()
                                    onStop()
                                },
                                enabled = !state.isFinishing,
                                shape = RoundedCornerShape(Radius.md),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.liveRed,
                                    contentColor = Color.White,
                                    disabledContainerColor = colors.surfaceHighlight,
                                    disabledContentColor = colors.textMuted
                                ),
                                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(0.95f)
                                    .heightIn(min = 50.dp)
                                    .pressScale(enabled = !state.isFinishing, pressedScale = 0.97f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = s.stopButton,
                                    style = VantaType.rowLabel.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        }
                    }
                } else {
                    // Fullscreen Teleprompter Auto-hiding Floating HUD
                    AnimatedVisibility(
                        visible = teleprompterHudVisible,
                        enter = fadeIn(Motion.fastTween()),
                        exit = fadeOut(Motion.fastTween()),
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = Spacing.lg)
                    ) {
                        Row(
                            modifier = Modifier
                                .vantaGlass()
                                .padding(horizontal = Spacing.sm, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            VantaIconButton(
                                icon = if (state.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (state.isPaused) s.resumeButton else s.pauseButton,
                                tint = if (state.isPaused) colors.accent else colors.textPrimary,
                                containerColor = colors.surface,
                                size = 42.dp,
                                onClick = {
                                    haptics.click()
                                    if (state.isPaused) onResume() else onPause()
                                }
                            )
                            VantaIconButton(
                                icon = Icons.Rounded.BookmarkAdd,
                                contentDescription = s.bookmarkButton,
                                tint = colors.warning,
                                containerColor = colors.warningSoft,
                                size = 42.dp,
                                onClick = {
                                    haptics.tick()
                                    onAddBookmark(null)
                                }
                            )
                            VantaIconButton(
                                icon = Icons.Rounded.FormatSize,
                                contentDescription = extra.fontStyleLabel,
                                tint = colors.textSecondary,
                                containerColor = colors.surface,
                                size = 42.dp,
                                onClick = {
                                    haptics.tick()
                                    onCycleFontSize()
                                }
                            )
                            VantaIconButton(
                                icon = Icons.Rounded.FullscreenExit,
                                contentDescription = s.exitFullScreenTranscript,
                                tint = colors.textPrimary,
                                containerColor = colors.surface,
                                size = 42.dp,
                                onClick = { fullScreen = false }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (fullScreen) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                        )
                    } else {
                        Modifier.statusBarsPadding()
                    }
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Area
                if (fullScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        AnimatedRecordingDot(isPaused = state.isPaused)
                        Text(
                            text = AsrTextFormatter.formatDurationMs(state.durationMs),
                            color = colors.textPrimary,
                            style = VantaType.monoMetric
                        )
                        if (bookmarkCount > 0) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(colors.warningSoft)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Rounded.Bookmark, null, tint = colors.warning, modifier = Modifier.size(11.dp))
                                Text(bookmarkCount.toString(), color = colors.warning, style = VantaType.monoBadge)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        VantaIconButton(
                            icon = Icons.Rounded.FullscreenExit,
                            contentDescription = s.exitFullScreenTranscript,
                            tint = colors.textPrimary,
                            onClick = { fullScreen = false }
                        )
                    }
                    HorizontalDivider(color = colors.border, thickness = 0.75.dp)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // Tier 1: Back + Live Engine Status Pill + Bookmark Count + Fullscreen
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            VantaIconButton(
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = s.back,
                                tint = colors.textPrimary,
                                onClick = onBack
                            )

                            val queueWarning = state.isGroqEngine && !state.isFinishing &&
                                (state.isGroqRetrying || state.backlogSeconds >= 24f)
                            var showLocalQueue by remember(state.recordingId) { mutableStateOf(false) }
                            val monitorLocalQueue = state.isActive && !state.isGroqEngine &&
                                !state.isPaused && !state.isFinishing
                            val localQueuePending = state.backlogSeconds >= (if (showLocalQueue) 1.5f else 3f)
                            LaunchedEffect(monitorLocalQueue, localQueuePending) {
                                if (!monitorLocalQueue) {
                                    showLocalQueue = false
                                } else if (showLocalQueue != localQueuePending) {
                                    delay(2_000L)
                                    showLocalQueue = localQueuePending
                                }
                            }
                            val backlog = AsrTextFormatter.formatDurationMs((state.backlogSeconds * 1000).toLong())
                            val statusText = when {
                                state.isFinishing -> s.notifSaving
                                state.isGroqRetrying -> "${s.groqRetryStatus} · $backlog"
                                queueWarning -> "${s.groqCatchUpStatus} · $backlog"
                                else -> state.asrStatusText.ifBlank { s.preparingModel }
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .pressScale(pressedScale = 0.97f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(
                                        when {
                                            state.isFinishing -> colors.accentSoft
                                            queueWarning -> colors.warningSoft
                                            else -> colors.surface
                                        }
                                    )
                                    .border(0.75.dp, colors.border, RoundedCornerShape(Radius.pill))
                                    .clickable(enabled = !state.isGroqEngine && !state.isFinishing) {
                                        haptics.tick()
                                        showLangSheet = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = statusText,
                                        color = when {
                                            state.isFinishing -> colors.accent
                                            queueWarning -> colors.warning
                                            else -> colors.textSecondary
                                        },
                                        style = VantaType.monoBadge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (monitorLocalQueue && showLocalQueue) {
                                        Text(
                                            text = "${s.queueLabel} · $backlog",
                                            color = colors.warning,
                                            style = VantaType.monoBadge,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            if (bookmarkCount > 0) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(colors.warningSoft)
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Rounded.Bookmark, null, tint = colors.warning, modifier = Modifier.size(12.dp))
                                    Text(bookmarkCount.toString(), color = colors.warning, style = VantaType.monoBadge)
                                }
                            }

                            VantaIconButton(
                                icon = Icons.Rounded.Fullscreen,
                                contentDescription = s.expandLiveTranscript,
                                tint = colors.textPrimary,
                                onClick = {
                                    haptics.tick()
                                    teleprompterHudVisible = true
                                    fullScreen = true
                                }
                            )
                        }

                        // Tier 2: Recessed Studio Acoustic Monitor Band
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.lg))
                                .background(colors.surfaceRecessed)
                                .border(0.75.dp, colors.border, RoundedCornerShape(Radius.lg))
                                .padding(horizontal = Spacing.md, vertical = if (compactHeight) 8.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    AnimatedRecordingDot(isPaused = state.isPaused)
                                    Text(
                                        text = when {
                                            state.isFinishing -> s.notifSaving
                                            state.isPaused -> s.recordingPaused
                                            else -> s.activeRecordingBanner
                                        },
                                        color = if (state.isPaused) colors.warning else colors.liveRed,
                                        style = VantaType.monoBadge,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = AsrTextFormatter.formatDurationMs(state.durationMs),
                                    color = colors.textPrimary,
                                    style = if (compactHeight) {
                                        VantaType.heroTimer.copy(fontSize = VantaType.docTitle.fontSize, lineHeight = VantaType.docTitle.lineHeight)
                                    } else {
                                        VantaType.heroTimer
                                    }
                                )
                            }

                            LiveWaveformView(
                                waveform = waveform,
                                isActive = state.isActive,
                                isPaused = state.isPaused || state.isFinishing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (compactHeight) 36.dp else 52.dp)
                            )
                        }

                        // Single clean warning notice
                        AnimatedVisibility(
                            visible = !state.statusNotice.isNullOrBlank() && state.statusNotice != s.pausedPrompt,
                            enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                            exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                        ) {
                            state.statusNotice?.let { notice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.sm))
                                        .background(colors.warningSoft)
                                        .border(0.75.dp, colors.warning.copy(alpha = 0.3f), RoundedCornerShape(Radius.sm))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(colors.warning)
                                    )
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        text = notice,
                                        color = colors.warning,
                                        style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Single clean error notice (never duplicated)
                        AnimatedVisibility(
                            visible = !state.asrError.isNullOrBlank(),
                            enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                            exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                        ) {
                            state.asrError?.let { err ->
                                val noticeColor = if (state.isGroqRetrying) colors.warning else colors.destructive
                                val background = if (state.isGroqRetrying) colors.warningSoft else colors.destructiveSoft
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.sm))
                                        .background(background)
                                        .border(0.75.dp, noticeColor.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (state.isGroqRetrying) "$err\n${s.groqRetryBody}" else err,
                                        color = noticeColor,
                                        style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Live transcript feed
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (fullScreen) {
                                Modifier.clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) {
                                    teleprompterHudVisible = !teleprompterHudVisible
                                }
                            } else Modifier
                        ),
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.lg,
                        bottom = innerPadding.calculateBottomPadding() + Spacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (fullScreen) Spacing.xl else Spacing.lg)
                ) {
                    if (state.committedParagraphs.isEmpty() && draftWords.isEmpty() && state.tailWords.isEmpty()) {
                        item(key = "live_empty_cue") {
                            val failedToStart = !state.isActive && !state.isFinishing && state.recordingId == 0L && !state.asrError.isNullOrBlank()
                            val isLoading = !failedToStart && (state.asrStatusText == s.loadingModelProgress || state.asrStatusText.isBlank())
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                if (failedToStart) {
                                    Text(
                                        text = state.asrError.orEmpty(),
                                        color = colors.destructive,
                                        style = VantaType.body
                                    )
                                    VantaTonalButton(label = s.back, onClick = onBack)
                                } else {
                                    if (isLoading) {
                                        VantaLinearProgressTrack(progress = null, modifier = Modifier.width(120.dp))
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                        ) {
                                            AnimatedRecordingDot(isPaused = state.isPaused)
                                            Text(
                                                text = if (state.isPaused) s.pausedPrompt else extra.listeningPrompt,
                                                color = if (state.isPaused) colors.warning else colors.accent,
                                                style = VantaType.monoMetric
                                            )
                                        }
                                    }
                                    Text(
                                        text = when {
                                            isLoading -> s.loadingModelProgress
                                            state.isPaused -> s.pausedPrompt
                                            else -> s.speakPrompt
                                        },
                                        color = colors.textMuted,
                                        style = transcriptStyle
                                    )
                                }
                            }
                        }
                    }

                    if (!fullScreen && state.isActive && !state.isFinishing) {
                        item(key = "live_ai_controls") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(
                                    label = when {
                                        state.liveAiWorking -> if (russian) "ИИ проверяет…" else "AI reviewing…"
                                        state.liveAiEnabled -> if (russian) "ИИ включён" else "AI on"
                                        else -> if (russian) "ИИ в записи" else "Live AI"
                                    },
                                    containerColor = if (state.liveAiEnabled) colors.accentSoft else colors.surface,
                                    onClick = {
                                        if (state.liveAiEnabled) onSetLiveAiEnabled(false, liveAiModel)
                                        else if (hasCloudKey) confirmLiveAi = true
                                        else onOpenGroqSettings()
                                    }
                                )
                                if (!state.isGroqEngine && state.languagePresetId != "ru_only") {
                                    StatusPill(
                                        label = if (russian) "Только русский" else "Russian only",
                                        onClick = {
                                            haptics.tick()
                                            onSwitchLanguagePreset("ru_only")
                                        }
                                    )
                                }
                            }
                        }
                    }

                    items(state.committedParagraphs, key = { "${it.orderIndex}_${it.id}" }) { para ->
                        val canReview = !fullScreen && !state.isFinishing &&
                            para.id != state.committedParagraphs.lastOrNull()?.id && para.endSec > para.startSec
                        Column(
                            modifier = Modifier.animateItem(
                                fadeInSpec = Motion.mediumTween(),
                                fadeOutSpec = null,
                                placementSpec = null
                            ).then(
                                if (canReview) Modifier.fillMaxWidth().clickable(
                                    onClickLabel = if (russian) "Распознать блок заново" else "Recognize block again"
                                ) { haptics.tick(); reviewBlock = para } else Modifier
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            if (para.isBookmark) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bookmark,
                                        contentDescription = null,
                                        tint = colors.warning,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = para.bookmarkLabel ?: s.bookmarkDefaultLabel,
                                        color = colors.warning,
                                        style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = colors.warning.copy(alpha = 0.28f),
                                        thickness = 0.75.dp
                                    )
                                }
                            }
                            val speakerLabel = para.speaker ?: s.unclearSpeaker.takeIf { state.speakerDiarization }
                            if (showTimestamps || speakerLabel != null || canReview) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                                ) {
                                    if (speakerLabel != null) {
                                        SpeakerPill(speakerKey = para.speaker, label = speakerLabel)
                                    }
                                    if (showTimestamps) {
                                        Text(
                                            text = AsrTextFormatter.formatTimestampSec(para.startSec),
                                            color = colors.textMuted,
                                            style = VantaType.monoMetric
                                        )
                                    }
                                    if (canReview) {
                                        Spacer(Modifier.weight(1f))
                                        Icon(
                                            Icons.Rounded.AutoFixHigh,
                                            contentDescription = null,
                                            tint = colors.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = rememberLiveTextReveal(AnnotatedString(para.text), colors.textPrimary),
                                color = colors.textPrimary,
                                style = transcriptStyle
                            )
                        }
                    }

                    if (draftWords.isNotEmpty() || state.tailWords.isNotEmpty()) {
                        item(key = "live_draft") {
                            val liveAnnotated = remember(draftWords, state.tailWords, colors) {
                                buildAnnotatedString {
                                    if (draftWords.isNotEmpty()) {
                                        withStyle(SpanStyle(color = colors.textPrimary, background = colors.accentSoft)) {
                                            append(AsrTextFormatter.joinWords(draftWords))
                                        }
                                    }
                                    if (state.tailWords.isNotEmpty()) {
                                        if (draftWords.isNotEmpty() && AsrTextFormatter.needsSpaceBefore(state.tailWords.first())) {
                                            append(" ")
                                        }
                                        withStyle(SpanStyle(color = colors.textSecondary)) {
                                            append(AsrTextFormatter.joinWords(state.tailWords))
                                        }
                                    }
                                }
                            }
                            val revealed = rememberLiveTextReveal(liveAnnotated, colors.textPrimary, animateInitial = true)
                            Text(
                                text = if (state.isPaused || state.isFinishing) revealed else buildAnnotatedString {
                                    append(revealed)
                                    appendInlineContent("live_cursor", alternateText = "\u200b")
                                },
                                inlineContent = liveCursorContent,
                                style = transcriptStyle
                            )
                        }
                    }
                }
            }

        }

        LiveRecordingModals(
            confirmLiveAi = confirmLiveAi,
            liveAiModel = liveAiModel,
            onSelectLiveAiModel = { liveAiModel = it },
            onDismissConfirmLiveAi = { confirmLiveAi = false },
            onEnableLiveAi = { model -> onSetLiveAiEnabled(true, model) },
            reviewBlock = reviewBlock,
            onDismissReviewBlock = { reviewBlock = null },
            state = state,
            russian = russian,
            hasCloudKey = hasCloudKey,
            reviewModelId = reviewModelId,
            onSelectReviewModel = onSelectReviewModel,
            onOpenGroqSettings = onOpenGroqSettings,
            onRecognizeBlock = onRecognizeBlock,
            onApplyBlock = onApplyBlock,
            showLangSheet = showLangSheet,
            onDismissLangSheet = { showLangSheet = false },
            modelManager = modelManager,
            onSwitchLanguagePreset = onSwitchLanguagePreset,
            missingModelIdPrompt = missingModelIdPrompt,
            onMissingModelPrompt = { missingModelIdPrompt = it },
            onOpenModelManager = onOpenModelManager,
            showBookmarkDialog = showBookmarkDialog,
            bookmarkNote = bookmarkNote,
            onBookmarkNoteChange = { bookmarkNote = it },
            onDismissBookmarkDialog = { showBookmarkDialog = false },
            onAddBookmark = onAddBookmark
        )
    }
}
