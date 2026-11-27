package com.stenograf.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingSort
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.export.ExportFormat
import com.stenograf.app.i18n.AppStrings
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.service.LiveRecordingUiState
import com.stenograf.app.service.ProcessingStatus
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.stenograf.app.ui.components.AnimatedRecordingDot
import com.stenograf.app.ui.components.EmptyStateView
import com.stenograf.app.ui.components.recordingRemoval
import com.stenograf.app.ui.components.recordingRemovalHeader
import com.stenograf.app.ui.components.GlassScaffold
import com.stenograf.app.ui.components.VantaLinearProgressTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDangerButton
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaExportSheet
import com.stenograf.app.ui.components.VantaFolderEditorSheet
import com.stenograf.app.ui.components.VantaIconButton
import com.stenograf.app.ui.components.VantaProgressBanner
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaSelectionMark
import com.stenograf.app.ui.components.VantaSheetActionRow
import com.stenograf.app.ui.components.VantaTextField
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.components.formatStorageSize
import com.stenograf.app.ui.components.parseFolderColor
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val FOLDER_FILTER_UNCATEGORIZED = -1L

private data class RemovingRecording(
    val recording: RecordingEntity,
    val progress: Animatable<Float, AnimationVector1D>
)

@Composable
fun LibraryScreen(
    recordings: List<RecordingEntity>,
    allRecordingsCount: Int,
    scrollToTopRequest: Int,
    folderCounts: Map<Long?, Int>,
    bookmarkCounts: Map<Long, Int>,
    isLoading: Boolean,
    folders: List<FolderEntity>,
    selectedFolderId: Long?,
    searchQuery: String,
    sort: RecordingSort,
    liveState: LiveRecordingUiState,
    recoveredRecordings: List<RecordingEntity>,
    importProgress: ProcessingStatus?,
    bottomDockClearance: Dp = 0.dp,
    onSelectionModeChange: (Boolean) -> Unit = {},
    onSelectFolder: (Long?) -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (RecordingSort) -> Unit,
    onStartRecording: () -> Unit,
    onImportAudio: () -> Unit,
    onCancelImport: () -> Unit = {},
    onOpenRecording: (Long) -> Unit,
    onRenameRecording: (RecordingEntity, String) -> Unit,
    onMoveRecording: (RecordingEntity, Long?) -> Unit,
    onMoveRecordingsBatch: (List<RecordingEntity>, Long?) -> Unit,
    onDeleteRecordings: (List<RecordingEntity>) -> Job,
    onExportRecording: (RecordingEntity, ExportFormat) -> Unit,
    onSaveExportRecording: (RecordingEntity, ExportFormat) -> Unit,
    onCopyRecordingText: (RecordingEntity) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onUpdateFolder: (FolderEntity, String, String) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit,
    onDismissRecovered: () -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val haptics = rememberVantaHaptics()

    var showSortSheet by remember { mutableStateOf(false) }
    var folderEditorTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var showCreateFolderSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var moveTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var exportTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var actionTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var folderDeleteTarget by remember { mutableStateOf<FolderEntity?>(null) }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchMoveSheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(selectionMode) { onSelectionModeChange(selectionMode) }
    DisposableEffect(Unit) { onDispose { onSelectionModeChange(false) } }

    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    val removals = remember(selectedFolderId, searchQuery) { mutableStateMapOf<Long, RemovingRecording>() }
    val visibleRemovals = removals.toMap()
    val sortedRecordings = remember(recordings, sort, visibleRemovals) {
        val rows = (recordings + visibleRemovals.values.map { it.recording }).distinctBy { it.id }
        when (sort) {
            RecordingSort.DATE_DESC -> rows.sortedByDescending { it.createdAtMs }
            RecordingSort.DATE_ASC -> rows.sortedBy { it.createdAtMs }
            RecordingSort.DURATION_DESC -> rows.sortedByDescending { it.durationMs }
            RecordingSort.TITLE_ASC -> rows.sortedBy { it.title.lowercase() }
        }
    }

    val grouped = remember(sortedRecordings, sort, s) {
        if (sort == RecordingSort.DATE_DESC || sort == RecordingSort.DATE_ASC) {
            groupByDate(sortedRecordings, s)
        } else {
            listOf("" to sortedRecordings)
        }
    }

    val totalDuration = remember(recordings) { recordings.sumOf { it.durationMs } }
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val uncategorizedCount = folderCounts[null] ?: 0

    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) listState.animateScrollToItem(0)
    }

    fun deleteRecordings(items: List<RecordingEntity>) {
        val requested = items.filterNot { it.id in removals }
        if (requested.isEmpty()) return
        val visibleIds = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet()
        val visible = requested.filter { it.id in visibleIds }
        val progress = Animatable(0f)
        visible.forEach { removals[it.id] = RemovingRecording(it, progress) }
        val commit = onDeleteRecordings(requested)
        if (visible.isNotEmpty()) {
            scope.launch {
                try {
                    progress.animateTo(1f, tween(durationMillis = 340, easing = LinearEasing))
                    commit.join()
                } finally {
                    visible.forEach { removals.remove(it.id) }
                }
            }
        }
    }

    var searchExpanded by remember { mutableStateOf(searchQuery.isNotEmpty()) }

    BackHandler(enabled = searchExpanded && !selectionMode) {
        searchExpanded = false
        if (searchQuery.isNotEmpty()) onSearchChange("")
    }

    GlassScaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectionMode) {
                        VantaIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = s.close,
                            tint = colors.textPrimary,
                            size = 48.dp,
                            onClick = { selectedIds = emptySet() }
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = if (selectionMode) Spacing.xs else Spacing.md)) {
                        Text(
                            text = if (selectionMode) "${extra.selectedCountLabel}: ${selectedIds.size}" else s.recordingsTitle,
                            color = colors.textPrimary,
                            style = VantaType.pageTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!selectionMode && recordings.isNotEmpty()) {
                            Text(
                                text = "${recordings.size} · ${AsrTextFormatter.formatDurationMs(totalDuration)}",
                                color = colors.textSecondary,
                                style = VantaType.monoMetric
                            )
                        }
                    }
                    if (selectionMode) {
                        VantaIconButton(
                            icon = Icons.Rounded.DoneAll,
                            contentDescription = s.allFolders,
                            size = 48.dp,
                            active = selectedIds.size == sortedRecordings.size,
                            onClick = {
                                haptics.tick()
                                selectedIds = if (selectedIds.size == sortedRecordings.size) {
                                    emptySet()
                                } else {
                                    sortedRecordings.map { it.id }.toSet()
                                }
                            }
                        )
                    } else {
                        VantaIconButton(
                            icon = Icons.Rounded.Search,
                            contentDescription = s.search,
                            active = searchExpanded || searchQuery.isNotEmpty(),
                            size = 44.dp,
                            onClick = {
                                haptics.tick()
                                searchExpanded = !searchExpanded
                                if (!searchExpanded && searchQuery.isNotEmpty()) {
                                    onSearchChange("")
                                }
                            }
                        )
                        VantaIconButton(
                            icon = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = s.sortTitle,
                            active = showSortSheet || sort != RecordingSort.DATE_DESC,
                            size = 44.dp,
                            onClick = {
                                haptics.tick()
                                showSortSheet = true
                            }
                        )
                        VantaIconButton(
                            icon = Icons.Rounded.AudioFile,
                            contentDescription = s.importAudio,
                            size = 44.dp,
                            onClick = {
                                haptics.click()
                                onImportAudio()
                            }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = searchExpanded && !selectionMode,
                    enter = expandVertically(Motion.mediumTween(), expandFrom = Alignment.Top) + fadeIn(Motion.fastTween()),
                    exit = shrinkVertically(Motion.mediumTween(), shrinkTowards = Alignment.Top) + fadeOut(Motion.fastTween())
                ) {
                    VantaSearchField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = s.searchHint,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                    )
                }

                AnimatedVisibility(
                    visible = !selectionMode,
                    enter = expandVertically(Motion.mediumTween(), expandFrom = Alignment.Top) + fadeIn(Motion.fastTween()),
                    exit = shrinkVertically(Motion.mediumTween(), shrinkTowards = Alignment.Top) + fadeOut(Motion.fastTween())
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item(key = "folder_all") {
                            LibraryFolderTab(
                                label = s.allFolders,
                                count = allRecordingsCount,
                                dotColor = null,
                                selected = selectedFolderId == null,
                                onClick = {
                                    haptics.tick()
                                    onSelectFolder(null)
                                },
                                onLongClick = null
                            )
                        }
                        if (uncategorizedCount > 0 || selectedFolderId == FOLDER_FILTER_UNCATEGORIZED) {
                            item(key = "folder_uncategorized") {
                                LibraryFolderTab(
                                    label = extra.uncategorizedFolder,
                                    count = uncategorizedCount,
                                    dotColor = colors.textMuted,
                                    selected = selectedFolderId == FOLDER_FILTER_UNCATEGORIZED,
                                    onClick = {
                                        haptics.tick()
                                        onSelectFolder(
                                            if (selectedFolderId == FOLDER_FILTER_UNCATEGORIZED) null else FOLDER_FILTER_UNCATEGORIZED
                                        )
                                    },
                                    onLongClick = null
                                )
                            }
                        }
                        items(folders, key = { it.id }) { folder ->
                            LibraryFolderTab(
                                label = folder.name,
                                count = folderCounts[folder.id] ?: 0,
                                dotColor = parseFolderColor(folder.colorHex, colors.accent),
                                selected = selectedFolderId == folder.id,
                                onClick = {
                                    haptics.tick()
                                    onSelectFolder(if (selectedFolderId == folder.id) null else folder.id)
                                },
                                onLongClick = {
                                    haptics.heavy()
                                    folderEditorTarget = folder
                                }
                            )
                        }
                        item(key = "folder_new") {
                            Row(
                                modifier = Modifier
                                    .height(44.dp)
                                    .pressScale()
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .clickable {
                                        haptics.tick()
                                        showCreateFolderSheet = true
                                    }
                                    .padding(horizontal = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = s.newFolder,
                                    color = colors.accent,
                                    style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = liveState.isActive && !selectionMode,
                    enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                    exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (liveState.isPaused) colors.warningSoft else colors.accentSoft)
                            .clickable {
                                haptics.click()
                                onStartRecording()
                            }
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        AnimatedRecordingDot(isPaused = liveState.isPaused)
                        Text(
                            text = if (liveState.isPaused) s.recordingPaused else s.activeRecordingBanner,
                            color = colors.textPrimary,
                            style = VantaType.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = AsrTextFormatter.formatDurationMs(liveState.durationMs),
                            color = if (liveState.isPaused) colors.warning else colors.accent,
                            style = VantaType.monoMetric
                        )
                    }
                }

                HorizontalDivider(color = colors.border, thickness = 0.5.dp)
            }
        },
        bottomBar = {
            if (!keyboardVisible && selectionMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .navigationBarsPadding()
                ) {
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                    AnimatedVisibility(
                        visible = selectionMode,
                        enter = expandVertically(Motion.fastTween(), expandFrom = Alignment.Bottom) + fadeIn(Motion.fastTween()),
                        exit = shrinkVertically(Motion.fastTween(), shrinkTowards = Alignment.Bottom) + fadeOut(Motion.fastTween())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VantaTonalButton(
                                label = s.moveToFolder,
                                icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                                onClick = { showBatchMoveSheet = true },
                                modifier = Modifier.weight(1f)
                            )
                            VantaDangerButton(
                                label = "${s.delete} (${selectedIds.size})",
                                icon = Icons.Rounded.DeleteOutline,
                                onClick = { showBatchDeleteDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    ) { overlayPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = overlayPadding.calculateTopPadding(),
                    bottom = overlayPadding.calculateBottomPadding() + bottomDockClearance + 88.dp
                )
            ) {
                if (importProgress != null || recoveredRecordings.isNotEmpty()) {
                    item(key = "banners") {
                        Column(
                            modifier = Modifier.padding(top = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            AnimatedVisibility(
                                visible = importProgress != null,
                                enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                                exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                            ) {
                                importProgress?.let { status ->
                                    VantaProgressBanner(
                                        message = status.message,
                                        progress = status.progress,
                                        liveSnippet = status.liveSnippet,
                                        startedAtMs = status.startedAtMs,
                                        onCancel = onCancelImport,
                                        modifier = Modifier.padding(horizontal = Spacing.lg)
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = recoveredRecordings.isNotEmpty(),
                                enter = expandVertically(Motion.mediumTween()) + fadeIn(Motion.mediumTween()),
                                exit = shrinkVertically(Motion.fastTween()) + fadeOut(Motion.fastTween())
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.lg)
                                        .clip(RoundedCornerShape(Radius.lg))
                                        .background(colors.warningSoft)
                                        .border(0.75.dp, colors.warning.copy(alpha = 0.35f), RoundedCornerShape(Radius.lg))
                                        .padding(Spacing.md),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.warning)
                                    Spacer(Modifier.width(Spacing.sm))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${s.recoveredBannerTitle}: ${recoveredRecordings.size}",
                                            color = colors.textPrimary,
                                            style = VantaType.body.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            text = s.recoveredBannerBody,
                                            color = colors.textSecondary,
                                            style = VantaType.bodySmall
                                        )
                                    }
                                    VantaIconButton(
                                        icon = Icons.Rounded.Close,
                                        contentDescription = s.close,
                                        tint = colors.textMuted,
                                        size = 32.dp,
                                        iconSize = 16.dp,
                                        onClick = onDismissRecovered
                                    )
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    item(key = "loading") {
                        VantaLinearProgressTrack(
                            progress = null,
                            modifier = Modifier.fillMaxWidth().padding(Spacing.lg)
                        )
                    }
                } else if (sortedRecordings.isEmpty()) {
                    item(key = "empty") {
                        EmptyStateView(
                            icon = Icons.Rounded.Mic,
                            title = if (searchQuery.isBlank()) s.emptyLibraryTitle else s.emptySearchTitle,
                            subtitle = if (searchQuery.isBlank()) s.emptyLibrarySubtitle else s.emptySearchSubtitle,
                            actionLabel = if (searchQuery.isNotBlank()) s.close else null,
                            onAction = if (searchQuery.isNotBlank()) {
                                {
                                    onSearchChange("")
                                    searchExpanded = false
                                }
                            } else null
                        )
                    }
                } else {
                    grouped.forEach { (header, itemsInGroup) ->
                        if (header.isNotEmpty()) {
                            val removingGroup = visibleRemovals.isNotEmpty() && itemsInGroup.all { it.id in visibleRemovals }
                            item(key = "hdr_$header") {
                                val headerMotion = if (removingGroup) {
                                    Modifier.recordingRemovalHeader {
                                        itemsInGroup.minOf { visibleRemovals.getValue(it.id).progress.value }
                                    }
                                } else Modifier
                                Text(
                                    text = header,
                                    color = colors.textSecondary,
                                    style = VantaType.sectionLabel,
                                    modifier = Modifier
                                        .animateItem(
                                            placementSpec = if (visibleRemovals.isEmpty()) Motion.snappySpring() else null,
                                            fadeOutSpec = if (removingGroup) null else Motion.fastTween()
                                        )
                                        .fillMaxWidth()
                                        .then(headerMotion)
                                        .background(colors.bg)
                                        .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs)
                                )
                            }
                        }
                        items(itemsInGroup, key = { it.id }) { rec ->
                            val folder = rec.folderId?.let { foldersById[it] }
                            val isSelected = rec.id in selectedIds
                            val removal = visibleRemovals[rec.id]
                            RecordingCard(
                                recording = rec,
                                strings = s,
                                folder = folder,
                                bookmarkCount = bookmarkCounts[rec.id] ?: 0,
                                selectionMode = selectionMode,
                                selected = isSelected,
                                removing = removal != null,
                                onClick = {
                                    if (selectionMode) {
                                        haptics.tick()
                                        selectedIds = if (isSelected) selectedIds - rec.id else selectedIds + rec.id
                                    } else {
                                        onOpenRecording(rec.id)
                                    }
                                },
                                onLongClick = {
                                    haptics.heavy()
                                    selectedIds = if (isSelected) selectedIds - rec.id else selectedIds + rec.id
                                },
                                onOpenMenu = {
                                    haptics.click()
                                    actionTarget = rec
                                },
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = Motion.fastTween(),
                                        placementSpec = if (visibleRemovals.isEmpty()) Motion.snappySpring() else null,
                                        fadeOutSpec = if (removal == null) Motion.fastTween() else null
                                    )
                                    .then(
                                        if (removal == null) Modifier
                                        else Modifier.recordingRemoval(colors.bg, colors.textMuted) { removal.progress.value }
                                    )
                            )
                        }
                    }
                }
                if (!isLoading && sortedRecordings.isNotEmpty()) {
                    item(key = "nav_spacer") {
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }

            AnimatedVisibility(
                visible = !selectionMode && !keyboardVisible,
                enter = scaleIn(Motion.mediumTween(), initialScale = 0.88f) + fadeIn(Motion.fastTween()),
                exit = scaleOut(Motion.mediumTween(), targetScale = 0.88f) + fadeOut(Motion.fastTween()),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(
                        end = Spacing.lg,
                        bottom = overlayPadding.calculateBottomPadding() + bottomDockClearance + Spacing.md
                    )
            ) {
                FloatingActionButton(
                    onClick = {
                        haptics.confirm()
                        onStartRecording()
                    },
                    shape = CircleShape,
                    containerColor = when {
                        liveState.isPaused -> colors.warning
                        liveState.isActive -> colors.liveRed
                        else -> colors.accent
                    },
                    contentColor = colors.onAccent,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 3.dp,
                        pressedElevation = 5.dp
                    ),
                    modifier = Modifier.size(58.dp)
                ) {
                    Icon(
                        imageVector = if (liveState.isActive) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                        contentDescription = if (liveState.isActive) s.returnToRecording else s.newRecording,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // Sort bottom sheet
        if (showSortSheet) {
            VantaBottomSheet(
                onDismissRequest = { showSortSheet = false },
                title = s.sortTitle,
                headerIcon = Icons.AutoMirrored.Rounded.Sort
            ) {
                val opts = listOf(
                    RecordingSort.DATE_DESC to s.sortDateDesc,
                    RecordingSort.DATE_ASC to s.sortDateAsc,
                    RecordingSort.DURATION_DESC to s.sortDurationDesc,
                    RecordingSort.TITLE_ASC to s.sortTitleAsc
                )
                for ((mode, label) in opts) {
                    SheetOptionRow(
                        selected = sort == mode,
                        title = label,
                        onClick = {
                            haptics.tick()
                            onSortChange(mode)
                            showSortSheet = false
                        }
                    )
                }
            }
        }

        // Recording actions bottom sheet
        actionTarget?.let { rec ->
            val durStr = AsrTextFormatter.formatDurationMs(rec.durationMs)
            val sizeStr = formatStorageSize(rec.fileSizeBytes)
            VantaBottomSheet(
                onDismissRequest = { actionTarget = null },
                title = rec.title,
                subtitle = "$durStr · $sizeStr"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VantaSheetActionRow(
                        icon = Icons.Rounded.Edit,
                        label = s.rename,
                        onClick = {
                            renameTarget = rec
                            renameText = rec.title
                            actionTarget = null
                        }
                    )
                    VantaSheetActionRow(
                        icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                        label = s.moveToFolder,
                        onClick = {
                            moveTarget = rec
                            actionTarget = null
                        }
                    )
                    VantaSheetActionRow(
                        icon = Icons.Rounded.Share,
                        label = s.exportTitle,
                        onClick = {
                            exportTarget = rec
                            actionTarget = null
                        }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    VantaSheetActionRow(
                        icon = Icons.Rounded.DeleteOutline,
                        label = s.delete,
                        isDestructive = true,
                        onClick = {
                            deleteTarget = rec
                            actionTarget = null
                        }
                    )
                }
            }
        }

        // Unified Export & Save bottom sheet
        exportTarget?.let { rec ->
            VantaExportSheet(
                title = rec.title,
                onDismissRequest = { exportTarget = null },
                onShareExport = { fmt -> onExportRecording(rec, fmt) },
                onSaveExport = { fmt -> onSaveExportRecording(rec, fmt) },
                onCopyPlainText = if (rec.hasTranscript) {
                    { onCopyRecordingText(rec) }
                } else null
            )
        }

        // Move single recording to folder sheet
        moveTarget?.let { rec ->
            VantaBottomSheet(
                onDismissRequest = { moveTarget = null },
                title = s.moveToFolder,
                subtitle = rec.title,
                headerIcon = Icons.AutoMirrored.Rounded.DriveFileMove
            ) {
                SheetOptionRow(selected = rec.folderId == null, title = s.noFolder) {
                    haptics.tick()
                    onMoveRecording(rec, null)
                    moveTarget = null
                }
                for (f in folders) {
                    SheetOptionRow(selected = rec.folderId == f.id, title = f.name) {
                        haptics.tick()
                        onMoveRecording(rec, f.id)
                        moveTarget = null
                    }
                }
            }
        }

        // Batch move to folder sheet
        if (showBatchMoveSheet) {
            val selectedItems = sortedRecordings.filter { it.id in selectedIds }
            VantaBottomSheet(
                onDismissRequest = { showBatchMoveSheet = false },
                title = s.moveToFolder,
                subtitle = "${extra.selectedCountLabel}: ${selectedItems.size}",
                headerIcon = Icons.AutoMirrored.Rounded.DriveFileMove
            ) {
                SheetOptionRow(selected = false, title = s.noFolder) {
                    haptics.confirm()
                    onMoveRecordingsBatch(selectedItems, null)
                    selectedIds = emptySet()
                    showBatchMoveSheet = false
                }
                for (f in folders) {
                    SheetOptionRow(selected = false, title = f.name) {
                        haptics.confirm()
                        onMoveRecordingsBatch(selectedItems, f.id)
                        selectedIds = emptySet()
                        showBatchMoveSheet = false
                    }
                }
            }
        }

        if (showBatchDeleteDialog) {
            val selectedItems = sortedRecordings.filter { it.id in selectedIds }
            VantaDialog(
                onDismissRequest = { showBatchDeleteDialog = false },
                title = s.deleteRecordingTitle,
                icon = Icons.Rounded.DeleteOutline,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                highlightText = "${extra.selectedCountLabel}: ${selectedItems.size}",
                message = s.deleteRecordingMessage,
                confirmLabel = s.delete,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    deleteRecordings(selectedItems)
                    selectedIds = emptySet()
                    showBatchDeleteDialog = false
                },
                dismissLabel = s.cancel,
                onDismiss = { showBatchDeleteDialog = false }
            )
        }

        if (showCreateFolderSheet) {
            VantaFolderEditorSheet(
                existingFolder = null,
                onDismissRequest = { showCreateFolderSheet = false },
                onConfirm = { name, hex -> onCreateFolder(name, hex) }
            )
        }

        folderEditorTarget?.let { folder ->
            VantaFolderEditorSheet(
                existingFolder = folder,
                onDismissRequest = { folderEditorTarget = null },
                onConfirm = { name, hex -> onUpdateFolder(folder, name, hex) },
                onRequestDelete = { folderDeleteTarget = it }
            )
        }

        renameTarget?.let { rec ->
            VantaDialog(
                onDismissRequest = { renameTarget = null },
                title = s.renameRecordingTitle,
                icon = Icons.Rounded.Edit,
                confirmLabel = s.save,
                confirmEnabled = renameText.isNotBlank(),
                onConfirm = {
                    if (renameText.isNotBlank()) onRenameRecording(rec, renameText.trim())
                    renameTarget = null
                },
                dismissLabel = s.cancel,
                onDismiss = { renameTarget = null }
            ) {
                VantaTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    required = true
                )
            }
        }

        deleteTarget?.let { rec ->
            VantaDialog(
                onDismissRequest = { deleteTarget = null },
                title = s.deleteRecordingTitle,
                icon = Icons.Rounded.DeleteOutline,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                highlightText = rec.title,
                message = s.deleteRecordingMessage,
                confirmLabel = s.delete,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    deleteRecordings(listOf(rec))
                    deleteTarget = null
                },
                dismissLabel = s.cancel,
                onDismiss = { deleteTarget = null }
            )
        }

        folderDeleteTarget?.let { f ->
            VantaDialog(
                onDismissRequest = { folderDeleteTarget = null },
                title = "${s.deleteFolder}?",
                icon = Icons.Rounded.DeleteOutline,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                highlightText = f.name,
                confirmLabel = s.delete,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    onDeleteFolder(f)
                    folderDeleteTarget = null
                },
                dismissLabel = s.cancel,
                onDismiss = { folderDeleteTarget = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryFolderTab(
    label: String,
    count: Int,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val colors = LocalStenografColors.current
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textSecondary,
        animationSpec = Motion.fastTween(),
        label = "tab_text"
    )
    val badgeBg by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceRecessed,
        animationSpec = Motion.fastTween(),
        label = "tab_badge_bg"
    )
    val badgeText by animateColorAsState(
        targetValue = if (selected) colors.onAccent else colors.textMuted,
        animationSpec = Motion.fastTween(),
        label = "tab_badge_text"
    )
    val tabBackground by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surfaceRecessed.copy(alpha = 0.55f),
        animationSpec = Motion.mediumTween(),
        label = "folder_background"
    )

    Row(
        modifier = Modifier
            .height(44.dp)
            .pressScale()
            .clip(RoundedCornerShape(Radius.pill))
            .background(tabBackground)
            .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = VantaType.rowLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                color = badgeText,
                style = VantaType.monoBadge,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(badgeBg)
                    .padding(horizontal = Spacing.xs, vertical = 1.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordingCard(
    recording: RecordingEntity,
    strings: AppStrings,
    folder: FolderEntity?,
    bookmarkCount: Int,
    selectionMode: Boolean,
    selected: Boolean,
    removing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val time = remember(recording.createdAtMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(recording.createdAtMs))
    }
    val duration = remember(recording.durationMs) {
        AsrTextFormatter.formatDurationMs(recording.durationMs)
    }
    val language = remember(recording.languagePresetId, recording.modelId, recording.detectedLanguage) {
        if (GroqAsrClient.SUPPORTED_MODELS.any { it.first == recording.modelId } && recording.detectedLanguage.isNotBlank()) {
            recording.detectedLanguage.uppercase()
        } else {
            TinyAsrCatalog.findPreset(recording.languagePresetId).shortLabel
        }
    }
    val formatBadge = remember(recording.audioFormatEnum) {
        recording.audioFormatEnum.extension.uppercase()
    }
    val warning = when {
        recording.statusEnum == RecordingStatus.INTERRUPTED -> strings.statusRecovered
        recording.hasUnprocessedAudio -> strings.statusUnprocessedTail
        else -> null
    }
    val folderColor = folder?.let { parseFolderColor(it.colorHex, colors.accent) } ?: colors.accent
    val avatarBg = when {
        warning != null -> colors.warningSoft
        folder != null -> folderColor.copy(alpha = if (colors.isDark) 0.22f else 0.14f)
        else -> colors.accentSoft
    }
    val avatarTint = when {
        warning != null -> colors.warning
        folder != null -> folderColor
        else -> colors.accent
    }
    val rowBg by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surface,
        animationSpec = Motion.fastTween(),
        label = "rec_row_bg"
    )

    val cardShape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(cardShape)
            .background(rowBg)
            .border(0.75.dp, if (selected) colors.accent.copy(alpha = 0.42f) else colors.border, cardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(enabled = !removing, onClick = onClick, onLongClick = onLongClick)
                .semantics { if (selectionMode) this.selected = selected }
                .padding(start = 12.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(avatarBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (warning != null) Icons.Rounded.WarningAmber else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = avatarTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (selectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .padding(1.5.dp)
                    ) {
                        VantaSelectionMark(selected = selected)
                    }
                }
            }

            Spacer(Modifier.width(11.dp))

            Column(
                modifier = Modifier.weight(1f).padding(top = 1.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = recording.title,
                        color = colors.textPrimary,
                        style = VantaType.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = time,
                        color = colors.textMuted,
                        style = VantaType.monoMetric
                    )
                }

                if (recording.previewText.isNotBlank()) {
                    Text(
                        text = recording.previewText,
                        color = colors.textSecondary,
                        style = VantaType.body.copy(textDirection = TextDirection.Content),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Text(
                        text = "$duration · $language · $formatBadge",
                        color = colors.textMuted,
                        style = VantaType.monoMetric
                    )
                    if (folder != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(folderColor)
                            )
                            Text(
                                text = folder.name,
                                color = folderColor,
                                style = VantaType.monoMetric,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (bookmarkCount > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Bookmark,
                                contentDescription = strings.bookmarkButton,
                                tint = colors.warning,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(bookmarkCount.toString(), color = colors.warning, style = VantaType.monoBadge)
                        }
                    }
                    if (!recording.hasTranscript) {
                        Text(strings.statusNoTranscript, color = colors.textMuted, style = VantaType.bodySmall)
                    }
                }

                if (warning != null) {
                    Text(warning, color = colors.warning, style = VantaType.bodySmall)
                }
            }

            if (!selectionMode) {
                val menuInteraction = remember { MutableInteractionSource() }
                val pressed by menuInteraction.collectIsPressedAsState()
                val focused by menuInteraction.collectIsFocusedAsState()
                val shape = RoundedCornerShape(Radius.sm)
                val menuBackground by animateColorAsState(
                    targetValue = if (pressed) colors.surfaceHighlight else colors.surfaceRecessed,
                    animationSpec = Motion.fastTween(),
                    label = "recording_menu_background"
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = menuInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = { if (!removing) onOpenMenu() }
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(shape)
                            .background(menuBackground)
                            .border(1.dp, if (focused) colors.accent else Color.Transparent, shape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = strings.menu,
                            tint = if (pressed) colors.textPrimary else colors.textSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(Spacing.md))
            }
        }
    }
}

private fun groupByDate(list: List<RecordingEntity>, strings: AppStrings): List<Pair<String, List<RecordingEntity>>> {
    val todayCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayMs = todayCal.timeInMillis
    val yesterdayMs = todayMs - 86_400_000L
    val weekMs = todayMs - 6 * 86_400_000L
    val monthFmt = SimpleDateFormat("LLLL yyyy", Locale.getDefault())

    return list.groupBy { rec ->
        when {
            rec.createdAtMs >= todayMs -> strings.dateToday
            rec.createdAtMs >= yesterdayMs -> strings.dateYesterday
            rec.createdAtMs >= weekMs -> strings.dateThisWeek
            else -> monthFmt.format(Date(rec.createdAtMs))
        }
    }.entries.map { it.key to it.value }
}
