package com.stenograf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.ModelDownloadPhase
import com.stenograf.app.asr.ModelDownloadProgress
import com.stenograf.app.asr.ModelInstallStatus
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TinyAsrModelSpec
import com.stenograf.app.audio.AudioTreatment
import com.stenograf.app.data.prefs.RecognitionAudioFilter
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.i18n.AppStrings
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.components.GlassScaffold
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaIconButton
import com.stenograf.app.ui.components.VantaLinearProgressTrack
import com.stenograf.app.ui.components.VantaPrimaryButton
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.components.VantaTopBar
import com.stenograf.app.ui.components.formatStorageSize
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.vantaSurfaceCard

private enum class ModelCategory { SPEECH, FILTERS }

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
    onDeleteModel: (String) -> Unit,
    recognitionFilter: RecognitionAudioFilter,
    onSelectRecognitionFilter: (RecognitionAudioFilter) -> Unit
) {
    val colors = LocalStenografColors.current
    val strings = LocalStrings.current
    val language by AppLocaleManager.currentLanguage.collectAsState()
    val russian = language.code == "ru"
    var category by rememberSaveable { mutableStateOf(ModelCategory.SPEECH) }
    var search by rememberSaveable { mutableStateOf("") }
    var showRecognitionFilter by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<TinyAsrModelSpec?>(null) }
    var delete by remember { mutableStateOf<TinyAsrModelSpec?>(null) }
    var languagePicker by remember { mutableStateOf<TinyAsrModelSpec?>(null) }

    val preset = TinyAsrCatalog.findPreset(activePresetId)
    val speechModels = modelStatuses.filter { AudioTreatment.forModel(it.spec.id) == null }
        .sortedBy { status ->
            when {
                status.spec.id == preset.modelId -> 0
                status.spec.id == "en-ru-uk" -> 1
                status.spec.bundledInApk -> 2
                status.isInstalled -> 3
                status.spec.id == TinyAsrCatalog.GIGAAM_V4 -> 4
                else -> 5
            }
        }
    val shownSpeech = speechModels.filter { status ->
        status.spec.matchesQuery(search, strings) ||
            (russian && search.isNotBlank() && speechSummary(status.spec, true).contains(search.trim(), ignoreCase = true))
    }
    val statusesById = modelStatuses.associateBy { it.spec.id }

    GlassScaffold(topBar = {
        VantaTopBar(onBack = onBack, title = LocalExtraStrings.current.modelsShort) {
            if (isCheckingUpdates) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.padding(Spacing.sm))
            } else {
                VantaIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = if (russian) "Проверить обновления моделей" else "Check model updates",
                    onClick = onCheckUpdates
                )
            }
        }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = padding.calculateTopPadding() + Spacing.xs,
                bottom = padding.calculateBottomPadding() + bottomDockClearance + Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "category") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    ModelTab(
                        label = if (russian) "Распознавание" else "Transcription",
                        selected = category == ModelCategory.SPEECH,
                        modifier = Modifier.weight(1f),
                        onClick = { category = ModelCategory.SPEECH }
                    )
                    ModelTab(
                        label = if (russian) "Фильтры" else "Audio filters",
                        selected = category == ModelCategory.FILTERS,
                        modifier = Modifier.weight(1f),
                        onClick = { category = ModelCategory.FILTERS }
                    )
                }
            }

            if (category == ModelCategory.SPEECH) {
                item(key = "active-preset") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            if (russian) "Офлайн для новых записей · ${preset.label(strings)}"
                            else "Offline for new recordings · ${preset.label(strings)}",
                            color = colors.textSecondary,
                            style = VantaType.bodySmall
                        )
                        VantaSearchField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = if (russian) "Найти язык или модель" else "Find a language or model"
                        )
                    }
                }
                if (shownSpeech.isEmpty()) item(key = "no-models") {
                    Text(
                        if (modelStatuses.isEmpty()) strings.preparingModel else strings.emptySearchTitle,
                        color = colors.textSecondary,
                        style = VantaType.body
                    )
                }
            } else {
                item(key = "filters-help") {
                    Text(
                        if (russian) "Чтобы очистить файл, нажмите «Фильтры» в плеере записи."
                        else "To clean an audio file, tap Filters in the recording player.",
                        color = colors.textSecondary,
                        style = VantaType.bodySmall
                    )
                }
                item(key = "built-in") {
                    val guide = AudioTreatment.QUICK_CLEANUP.guide(russian)
                    CatalogCard(
                        title = guide.title,
                        state = if (russian) "Встроено · ${guide.model}" else "Built in · ${guide.model}",
                        description = guide.description
                    )
                }
            }

            val rows = if (category == ModelCategory.SPEECH) shownSpeech
            else AudioTreatment.entries.mapNotNull { statusesById[it.modelId] }
            items(rows, key = { it.spec.id }) { status ->
                val spec = status.spec
                val treatment = AudioTreatment.forModel(spec.id)
                val guide = treatment?.guide(russian)
                val ready = spec.bundledInApk || status.isInstalled
                val active = preset.modelId == spec.id
                val fileOnly = spec.id == TinyAsrCatalog.GIGAAM
                val title = guide?.title ?: if (spec.id == TinyAsrCatalog.GIGAAM_V4) "GigaAM v4" else spec.localizedTitle(strings)
                val hasTransfer = status.downloadProgress?.phase?.let { it != ModelDownloadPhase.IDLE } == true
                val recommendation = when {
                    treatment == AudioTreatment.DEEP_FILTER_3 -> if (russian) "Для начала" else "Start here"
                    spec.id == "en-ru-uk" -> if (russian) "Рекомендуется" else "Recommended"
                    else -> null
                }
                val readiness = when {
                    spec.bundledInApk -> if (russian) "Встроено" else "Built in"
                    status.isInstalled -> if (russian) "Скачано" else "Downloaded"
                    status.isIncomplete -> null
                    hasTransfer -> null
                    else -> if (russian) "Нужно скачать" else "Download needed"
                }
                val tags = listOfNotNull(
                    readiness,
                    if (active) (if (russian) "Выбрана" else "Selected") else null,
                    recommendation,
                    guide?.model,
                    if (!ready && !hasTransfer) formatStorageSize(spec.expectedTotalBytes) else null
                )
                CatalogCard(
                    title = title,
                    state = tags.joinToString(" · "),
                    description = guide?.description ?: speechSummary(spec, russian),
                    menu = {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            VantaIconButton(
                                icon = Icons.Rounded.MoreVert,
                                contentDescription = if (russian) "Действия для $title" else "Actions for $title",
                                onClick = { expanded = true }
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (russian) "Как пользоваться" else "How to use") },
                                    onClick = { expanded = false; details = spec }
                                )
                                if (status.updateAvailable && !hasTransfer) DropdownMenuItem(
                                    text = { Text(strings.modelUpdateButton) },
                                    onClick = { expanded = false; onDownloadModel(spec.id) }
                                )
                                if (!spec.isOnnxModel && !hasTransfer) DropdownMenuItem(
                                    text = { Text(strings.modelImportButton) },
                                    onClick = { expanded = false; onImportModel(spec.id) }
                                )
                                if (status.isInstalled && !hasTransfer) DropdownMenuItem(
                                    text = { Text(strings.modelDeleteButton) },
                                    onClick = { expanded = false; delete = spec }
                                )
                            }
                        }
                    }
                ) {
                    val transfer = status.downloadProgress
                    if (transfer != null && transfer.phase != ModelDownloadPhase.IDLE) {
                        ModelTransferControls(
                            transfer, onResume = { onDownloadModel(spec.id) },
                            onPause = { onPauseDownload(spec.id) }, onCancel = { onCancelDownload(spec.id) }
                        )
                    } else if (!ready) {
                        VantaPrimaryButton(
                            label = if (status.isIncomplete) strings.modelResumeButton else strings.modelDownloadButton,
                            onClick = { onDownloadModel(spec.id) }
                        )
                    } else if (treatment != null || fileOnly) {
                        TextButton(onClick = { details = spec }) {
                            Text(if (russian) "Как применить" else "How to use", color = colors.accent)
                        }
                    } else {
                        val presets = TinyAsrCatalog.PRESETS.filter { it.modelId == spec.id }
                        if (presets.size > 1 || !active) VantaTonalButton(
                            label = if (presets.size > 1) {
                                if (russian) "Выбрать язык" else "Choose language"
                            } else {
                                if (russian) "Использовать офлайн" else "Use offline"
                            },
                            onClick = {
                                if (presets.size == 1) onSelectPreset(presets.single().id)
                                else languagePicker = spec
                            }
                        )
                    }
                }
            }

            if (category == ModelCategory.FILTERS) {
                item(key = "recognition-settings") {
                    TextButton(onClick = { showRecognitionFilter = true }) {
                        Text(
                            if (russian) "Настроить шумоподавление для текста" else "Configure noise filter for transcription",
                            color = colors.accent
                        )
                    }
                }
            }
        }

        if (showRecognitionFilter) RecognitionFilterSheet(
            current = recognitionFilter,
            russian = russian,
            onChoose = { filter -> onSelectRecognitionFilter(filter); showRecognitionFilter = false },
            onDismiss = { showRecognitionFilter = false }
        )
        languagePicker?.let { spec ->
            VantaBottomSheet(
                onDismissRequest = { languagePicker = null },
                title = if (russian) "Язык распознавания" else "Transcription language"
            ) {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    TinyAsrCatalog.PRESETS.filter { it.modelId == spec.id }.forEach { choice ->
                        SheetOptionRow(
                            selected = choice.id == activePresetId,
                            title = choice.label(strings),
                            onClick = { onSelectPreset(choice.id); languagePicker = null }
                        )
                    }
                }
            }
        }

        details?.let { spec ->
            val guide = AudioTreatment.forModel(spec.id)?.guide(russian)
            val status = statusesById[spec.id]
            val ready = spec.bundledInApk || status?.isInstalled == true
            val instructions = when {
                guide != null -> if (russian) "В плеере записи откройте «Фильтры» → «${guide.title}». Сравните фрагмент, затем примените фильтр."
                    else "In the recording player, choose Filters → ${guide.title}. Compare a sample, then apply the filter."
                spec.id == TinyAsrCatalog.GIGAAM -> if (russian) "Откройте сохранённую запись → Распознать заново → GigaAM v3."
                    else "Open a saved recording → Retranscribe → GigaAM v3."
                else -> if (russian) "Выберите модель или язык в этом каталоге. Выбор применяется к следующей офлайн-записи."
                    else "Select the model or language here. The choice applies to the next offline recording."
            }
            VantaBottomSheet(
                onDismissRequest = { details = null },
                title = guide?.title ?: spec.localizedTitle(strings)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!ready) Text(
                        (if (russian) "Сначала скачайте модель" else "Download the model first") + " · " +
                            formatStorageSize(spec.expectedTotalBytes),
                        color = colors.textSecondary, style = VantaType.bodySmall
                    )
                    Text(instructions, color = colors.textPrimary, style = VantaType.body)
                    Text(
                        listOfNotNull(guide?.model, status?.installedFormat ?: spec.formatVersion).joinToString(" · "),
                        color = colors.textSecondary, style = VantaType.bodySmall
                    )
                }
            }
        }

        delete?.let { spec ->
            VantaDialog(
                onDismissRequest = { delete = null },
                title = strings.modelDeleteTitle,
                icon = Icons.Rounded.DeleteOutline,
                message = strings.modelDeleteMessage,
                highlightText = spec.localizedTitle(strings),
                confirmLabel = strings.modelDeleteButton,
                dismissLabel = strings.cancel,
                isDestructive = true,
                onConfirm = { onDeleteModel(spec.id); delete = null },
                onDismiss = { delete = null }
            )
        }
    }
}

@Composable
private fun ModelTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalStenografColors.current
    VantaTonalButton(
        label = label, modifier = modifier, onClick = onClick,
        containerColor = if (selected) colors.accentSoft else colors.surfaceElevated,
        contentColor = if (selected) colors.accent else colors.textSecondary,
        borderColor = if (selected) colors.accent else colors.border
    )
}

@Composable
private fun CatalogCard(
    title: String,
    state: String,
    description: String,
    menu: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit = {}
) {
    val colors = LocalStenografColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, color = colors.textPrimary, style = VantaType.cardTitle)
                if (state.isNotBlank()) Text(state, color = colors.textSecondary, style = VantaType.bodySmall)
            }
            menu?.invoke()
        }
        Text(description, color = colors.textPrimary, style = VantaType.bodySmall)
        action()
    }
}

@Composable
internal fun ModelTransferControls(
    transfer: ModelDownloadProgress, onResume: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit
) {
    val strings = LocalStrings.current
    val colors = LocalStenografColors.current
    val verifying = transfer.phase == ModelDownloadPhase.VERIFYING
    val canPause = transfer.phase == ModelDownloadPhase.DOWNLOADING
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        VantaLinearProgressTrack(progress = transfer.fraction.takeUnless { verifying || transfer.totalBytes <= 0L })
        Text(
            if (verifying) strings.modelVerifyingLabel
            else "${formatStorageSize(transfer.downloadedBytes)} / ${formatStorageSize(transfer.totalBytes)}",
            color = colors.textSecondary, style = VantaType.bodySmall
        )
        transfer.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, color = colors.destructive, style = VantaType.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (!verifying) VantaTonalButton(
                label = when {
                    canPause -> strings.modelPauseButton
                    transfer.phase == ModelDownloadPhase.ERROR -> strings.retry
                    else -> strings.modelResumeButton
                },
                onClick = if (canPause) onPause else onResume
            )
            VantaIconButton(icon = Icons.Rounded.Close, contentDescription = strings.cancel, onClick = onCancel)
        }
    }
}

private fun speechSummary(spec: TinyAsrModelSpec, russian: Boolean): String = when (spec.id) {
    "en-ru-uk" -> if (russian) "Быстрый старт для русского, английского и украинского. Подходит для лекций и заметок."
        else "Quick start for Russian, English and Ukrainian lectures and notes."
    "fr-de" -> if (russian) "Для французской и немецкой речи. Можно начать сразу." else "For French and German speech. Ready to use."
    TinyAsrCatalog.GIGAAM_V4 -> if (russian) "Для русской и английской речи. Работает в записи и с файлами, занимает больше места."
        else "For Russian and English, live or from files. Uses more storage."
    TinyAsrCatalog.GIGAAM -> if (russian) "Вариант для повторной расшифровки русской речи в сохранённом файле."
        else "An alternative for retranscribing Russian speech in a saved file."
    "en-es-it-pt" -> if (russian) "Одна модель для английского, испанского, итальянского и португальского."
        else "One model for English, Spanish, Italian and Portuguese speech."
    "zh" -> if (russian) "Китайская речь с выбором упрощённого или традиционного письма."
        else "Chinese speech with a choice of simplified or traditional writing."
    "ar" -> if (russian) "Для арабской речи, включая разговорные диалекты." else "For Arabic speech, including spoken dialects."
    "uz" -> if (russian) "Узбекская речь с выбором латиницы или кириллицы." else "Uzbek speech in Latin or Cyrillic script."
    "en-fa" -> if (russian) "Для персидской речи, в том числе с английскими вставками." else "For Persian speech, including English phrases."
    "en-km" -> if (russian) "Для кхмерской речи, в том числе с английскими вставками." else "For Khmer speech, including English phrases."
    "id-ms" -> if (russian) "Для индонезийской и малайской речи." else "For Indonesian and Malay speech."
    else -> spec.languages.joinToString(" · ", transform = TinyAsrCatalog::languageNativeName)
}
