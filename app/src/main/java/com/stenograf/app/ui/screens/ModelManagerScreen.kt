package com.stenograf.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.ModelDownloadPhase
import com.stenograf.app.asr.ModelInstallStatus
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TinyAsrModelSpec
import com.stenograf.app.i18n.AppStrings
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.components.GlassScaffold
import com.stenograf.app.ui.components.EmptyStateView
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.StatusPill
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaIconButton
import com.stenograf.app.ui.components.VantaInfoCallout
import com.stenograf.app.ui.components.VantaLinearProgressTrack
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.components.VantaTopBar
import com.stenograf.app.ui.components.formatEtaDuration
import com.stenograf.app.ui.components.formatStorageSize
import com.stenograf.app.ui.components.formatTransferSpeed
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Motion
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard
import java.util.Locale

private enum class ModelFilter { ALL, INSTALLED, AVAILABLE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelManagerScreen(
    modelStatuses: List<ModelInstallStatus>,
    activePresetId: String,
    onSelectPreset: (String) -> Unit,
    isCheckingUpdates: Boolean,
    onBack: (() -> Unit)? = null,
    bottomDockClearance: Dp = 0.dp,
    onCheckUpdates: () -> Unit,
    onDownloadModel: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onImportModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val haptics = rememberVantaHaptics()
    val listState = rememberLazyListState()

    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ModelFilter.ALL) }
    var deleteConfirmModel by remember { mutableStateOf<ModelInstallStatus?>(null) }
    var presetPickerModel by remember { mutableStateOf<TinyAsrModelSpec?>(null) }

    val filtered = remember(modelStatuses, searchQuery, filter, s) {
        modelStatuses.filter { st ->
            val matchesFilter = when (filter) {
                ModelFilter.ALL -> true
                ModelFilter.INSTALLED -> st.isInstalled
                ModelFilter.AVAILABLE -> !st.isInstalled
            }
            matchesFilter && st.spec.matchesQuery(searchQuery, s)
        }
    }

    val installedCount = remember(modelStatuses) { modelStatuses.count { it.isInstalled } }
    val availableCount = modelStatuses.size - installedCount
    val totalModelsBytes = remember(modelStatuses) {
        modelStatuses.filter { it.isInstalled }.sumOf {
            if (it.sizeBytes > 0L) it.sizeBytes else (it.spec.sizeMb * 1024f * 1024f).toLong()
        }
    }
    val activePreset = remember(activePresetId) { TinyAsrCatalog.findPreset(activePresetId) }

    GlassScaffold(
        topBar = {
            VantaTopBar(
                onBack = onBack,
                title = s.modelManagerTitle
            ) {
                if (isCheckingUpdates) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent
                        )
                    }
                } else {
                    VantaIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = s.modelUpdateButton,
                        onClick = {
                            haptics.tick()
                            onCheckUpdates()
                        }
                    )
                }
            }
        }
    ) { overlayPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = overlayPadding.calculateTopPadding() + Spacing.xs,
                bottom = Spacing.xxl + bottomDockClearance
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item(key = "storage-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .vantaSurfaceCard(colors, radius = Radius.lg, elevated = false)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = extra.totalModelsStorageLabel,
                                color = colors.textSecondary,
                                style = VantaType.bodySmall
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = formatStorageSize(totalModelsBytes),
                                color = colors.textPrimary,
                                style = VantaType.sheetTitle.copy(
                                    fontFamily = VantaType.monoMetric.fontFamily,
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(colors.accentSoft)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Rounded.Translate, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                            Text(
                                text = activePreset.shortLabel,
                                color = colors.accent,
                                style = VantaType.monoBadge
                            )
                        }
                    }
                }
            }

            item(key = "catalog-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    VantaInfoCallout(text = s.modelAsrNote)

                    VantaSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = s.modelSearchHint
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        val chips = listOf(
                            Triple(ModelFilter.ALL, s.modelFilterAll, modelStatuses.size),
                            Triple(ModelFilter.INSTALLED, s.modelFilterInstalled, installedCount),
                            Triple(ModelFilter.AVAILABLE, s.modelFilterAvailable, availableCount)
                        )
                        for ((f, label, count) in chips) {
                            val active = filter == f
                            val bg by animateColorAsState(
                                targetValue = if (active) colors.accentSoft else colors.surface,
                                animationSpec = Motion.fastTween(),
                                label = "m_filter_bg"
                            )
                            val borderColor by animateColorAsState(
                                targetValue = if (active) colors.accent.copy(alpha = 0.42f) else colors.border,
                                animationSpec = Motion.fastTween(),
                                label = "m_filter_border"
                            )
                            Row(
                                modifier = Modifier
                                    .pressScale(pressedScale = 0.95f)
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(bg)
                                    .border(0.75.dp, borderColor, RoundedCornerShape(Radius.pill))
                                    .clickable {
                                        haptics.tick()
                                        filter = f
                                    }
                                    .padding(horizontal = Spacing.md, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (active) colors.accent else colors.textSecondary,
                                    style = VantaType.bodySmall.copy(
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                )
                                Text(
                                    text = count.toString(),
                                    color = if (active) colors.accent else colors.textMuted,
                                    style = VantaType.monoBadge
                                )
                            }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "models_empty") {
                    EmptyStateView(
                        icon = Icons.Rounded.Storage,
                        title = s.emptySearchTitle,
                        subtitle = s.emptySearchSubtitle
                    )
                }
            } else {
                items(filtered, key = { it.spec.id }) { st ->
                    val isModelActive = activePreset.modelId == st.spec.id
                    ModelCatalogCard(
                        status = st,
                        isActiveModel = isModelActive,
                        strings = s,
                        onDownload = {
                            haptics.click()
                            onDownloadModel(st.spec.id)
                        },
                        onPause = {
                            haptics.tick()
                            onPauseDownload(st.spec.id)
                        },
                        onCancel = {
                            haptics.tick()
                            onCancelDownload(st.spec.id)
                        },
                        onImport = {
                            haptics.click()
                            onImportModel(st.spec.id)
                        },
                        onSelectPresetForModel = {
                            haptics.tick()
                            val presets = TinyAsrCatalog.PRESETS.filter { it.modelId == st.spec.id }
                            if (presets.size == 1) {
                                onSelectPreset(presets.first().id)
                            } else if (presets.isNotEmpty()) {
                                presetPickerModel = st.spec
                            }
                        },
                        onRequestDelete = {
                            haptics.heavy()
                            deleteConfirmModel = st
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item(key = "nav_spacer") {
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        // Preset picker sheet for installed model
        presetPickerModel?.let { spec ->
            val modelPresets = remember(spec.id) { TinyAsrCatalog.PRESETS.filter { it.modelId == spec.id } }
            VantaBottomSheet(
                onDismissRequest = { presetPickerModel = null },
                title = spec.localizedTitle(s),
                headerIcon = Icons.Rounded.Translate
            ) {
                for (preset in modelPresets) {
                    SheetOptionRow(
                        selected = preset.id == activePresetId,
                        title = preset.label(s),
                        subtitle = preset.shortLabel,
                        onClick = {
                            haptics.confirm()
                            onSelectPreset(preset.id)
                            presetPickerModel = null
                        }
                    )
                }
            }
        }

        deleteConfirmModel?.let { st ->
            VantaDialog(
                onDismissRequest = { deleteConfirmModel = null },
                title = s.modelDeleteTitle,
                icon = Icons.Rounded.DeleteOutline,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                highlightText = st.spec.localizedTitle(s),
                message = s.modelDeleteMessage,
                confirmLabel = s.modelDeleteButton,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    onDeleteModel(st.spec.id)
                    deleteConfirmModel = null
                },
                dismissLabel = s.cancel,
                onDismiss = { deleteConfirmModel = null }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCatalogCard(
    status: ModelInstallStatus,
    isActiveModel: Boolean,
    strings: AppStrings,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onSelectPresetForModel: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalStenografColors.current
    val extra = LocalExtraStrings.current
    val spec = status.spec
    val dl = status.downloadProgress
    var langsExpanded by remember(spec.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .vantaSurfaceCard(colors, radius = Radius.lg, elevated = false)
            .animateContentSize(animationSpec = Motion.mediumTween())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // Title + Status pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = spec.localizedTitle(strings),
                    color = colors.textPrimary,
                    style = VantaType.cardTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val sizeText = if (status.isInstalled && status.sizeBytes > 0L) {
                    formatStorageSize(status.sizeBytes)
                } else {
                    String.format(Locale.US, "%.1f MB", spec.sizeMb)
                }
                val fmtLabel = status.installedFormat ?: spec.formatVersion
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    MetaTagBadge(text = spec.id)
                    MetaTagBadge(text = fmtLabel)
                    MetaTagBadge(text = sizeText)
                }
            }

            when {
                dl?.phase == ModelDownloadPhase.DOWNLOADING -> StatusPill(
                    label = strings.modelDownloadingLabel,
                    dotColor = colors.accent,
                    containerColor = colors.accentSoft,
                    contentColor = colors.accent
                )
                dl?.phase == ModelDownloadPhase.VERIFYING -> StatusPill(
                    label = strings.modelVerifyingLabel,
                    dotColor = colors.accent,
                    containerColor = colors.accentSoft,
                    contentColor = colors.accent
                )
                dl?.phase == ModelDownloadPhase.PAUSED -> StatusPill(
                    label = strings.modelPausedLabel,
                    dotColor = colors.warning,
                    containerColor = colors.warningSoft,
                    contentColor = colors.warning
                )
                status.updateAvailable -> StatusPill(
                    label = strings.modelUpdateButton,
                    dotColor = colors.warning,
                    containerColor = colors.warningSoft,
                    contentColor = colors.warning
                )
                status.isInstalled -> StatusPill(
                    label = if (spec.bundledInApk) strings.modelBuiltInLabel else strings.modelInstalledLabel,
                    dotColor = colors.success,
                    containerColor = colors.successSoft,
                    contentColor = colors.success
                )
                else -> StatusPill(label = strings.modelAvailableLabel)
            }
        }

        // Expandable Supported ASR Language Chips
        val displayedLangs = if (langsExpanded) spec.languages else spec.languages.take(6)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            for (langCode in displayedLangs) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(colors.surfaceElevated)
                        .padding(horizontal = Spacing.sm, vertical = 4.dp)
                ) {
                    Text(
                        text = TinyAsrCatalog.languageNativeName(langCode),
                        color = colors.textSecondary,
                        style = VantaType.bodySmall,
                        maxLines = 1
                    )
                }
            }
            if (!langsExpanded && spec.languages.size > 6) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(colors.accentSoft)
                        .clickable { langsExpanded = true }
                        .padding(horizontal = Spacing.sm, vertical = 4.dp)
                ) {
                    Text(
                        text = "+${spec.languages.size - 6}",
                        color = colors.accent,
                        style = VantaType.monoBadge
                    )
                }
            }
        }

        // Active transfer / pause / error progress block
        if (dl != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(colors.surfaceRecessed)
                    .border(0.75.dp, colors.border, RoundedCornerShape(Radius.md))
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (dl.phase == ModelDownloadPhase.VERIFYING) {
                    VantaLinearProgressTrack(progress = null)
                    Text(strings.modelVerifyingLabel, color = colors.textSecondary, style = VantaType.bodySmall)
                } else {
                    VantaLinearProgressTrack(
                        progress = dl.fraction,
                        fillColor = if (dl.phase == ModelDownloadPhase.ERROR) colors.destructive else colors.accent
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatStorageSize(dl.downloadedBytes)} / ${formatStorageSize(dl.totalBytes)}",
                            color = colors.textSecondary,
                            style = VantaType.monoMetric
                        )
                        val speedStr = formatTransferSpeed(dl.speedBytesPerSec)
                        val etaStr = formatEtaDuration(dl.etaSeconds)
                        val meta = listOf(speedStr, etaStr).filter { it.isNotEmpty() }.joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                color = colors.accent,
                                style = VantaType.monoMetric
                            )
                        }
                    }
                    if (!dl.errorMessage.isNullOrBlank()) {
                        Text(
                            text = dl.errorMessage,
                            color = colors.destructive,
                            style = VantaType.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                dl?.phase == ModelDownloadPhase.DOWNLOADING -> {
                    VantaTonalButton(
                        label = strings.modelPauseButton,
                        icon = Icons.Rounded.Pause,
                        onClick = onPause,
                        modifier = Modifier.weight(1f)
                    )
                    VantaIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = strings.cancel,
                        tint = colors.destructive,
                        containerColor = colors.destructiveSoft,
                        onClick = onCancel
                    )
                }
                dl?.phase == ModelDownloadPhase.PAUSED || dl?.phase == ModelDownloadPhase.ERROR -> {
                    VantaPrimaryButton(
                        label = if (dl.phase == ModelDownloadPhase.ERROR) strings.retry else strings.modelResumeButton,
                        icon = Icons.Rounded.PlayArrow,
                        onClick = onDownload,
                        modifier = Modifier.weight(1f)
                    )
                    VantaIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = strings.cancel,
                        tint = colors.textMuted,
                        containerColor = colors.surfaceElevated,
                        onClick = onCancel
                    )
                }
                dl?.phase == ModelDownloadPhase.VERIFYING -> {
                    Spacer(Modifier.weight(1f))
                }
                else -> {
                    if (!status.isInstalled || status.updateAvailable) {
                        VantaPrimaryButton(
                            label = if (status.updateAvailable) strings.modelUpdateButton else strings.modelDownloadButton,
                            icon = Icons.Rounded.Download,
                            containerColor = if (status.updateAvailable) colors.warning else colors.accent,
                            contentColor = if (status.updateAvailable) colors.onWarning else colors.onAccent,
                            onClick = onDownload,
                            modifier = Modifier.weight(1f)
                        )
                        VantaTonalButton(
                            label = strings.modelImportButton,
                            icon = Icons.Rounded.FileUpload,
                            onClick = onImport
                        )
                    } else {
                        VantaTonalButton(
                            label = extra.selectPresetAction,
                            icon = if (isActiveModel) Icons.Rounded.CheckCircle else Icons.Rounded.Translate,
                            containerColor = if (isActiveModel) colors.accentSoft else colors.surfaceElevated,
                            contentColor = if (isActiveModel) colors.accent else colors.textPrimary,
                            borderColor = if (isActiveModel) colors.accent.copy(alpha = 0.4f) else colors.borderStrong,
                            onClick = onSelectPresetForModel,
                            modifier = Modifier.weight(1f)
                        )
                        VantaIconButton(
                            icon = Icons.Rounded.FileUpload,
                            contentDescription = strings.modelImportButton,
                            tint = colors.textPrimary,
                            containerColor = colors.surfaceElevated,
                            size = 46.dp,
                            onClick = onImport
                        )
                    }

                    if (status.isInstalled) {
                        VantaIconButton(
                            icon = Icons.Rounded.DeleteOutline,
                            contentDescription = strings.modelDeleteButton,
                            tint = colors.destructive,
                            containerColor = colors.destructiveSoft,
                            size = 46.dp,
                            onClick = onRequestDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaTagBadge(text: String) {
    val colors = LocalStenografColors.current
    val shape = RoundedCornerShape(Radius.xs)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.surfaceRecessed)
            .border(0.5.dp, colors.border, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = colors.textSecondary,
            style = VantaType.monoBadge
        )
    }
}
