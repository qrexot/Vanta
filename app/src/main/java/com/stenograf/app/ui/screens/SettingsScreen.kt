package com.stenograf.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stenograf.app.R
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.ModelInstallStatus
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.prefs.AppSettings
import com.stenograf.app.data.prefs.AsrEngineMode
import com.stenograf.app.data.prefs.ThemeMode
import com.stenograf.app.data.repo.StorageBreakdown
import com.stenograf.app.i18n.AppLanguage
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.i18n.LocalExtraStrings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.components.GlassScaffold
import com.stenograf.app.ui.components.SectionHeader
import com.stenograf.app.ui.components.SegmentedStorageBar
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.StorageSegment
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDangerButton
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaInfoCallout
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaSwitch
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.components.VantaTopBar
import com.stenograf.app.ui.components.formatStorageSize
import kotlinx.coroutines.launch
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.pressScale
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    modelStatuses: List<ModelInstallStatus>,
    storage: StorageBreakdown,
    onBack: (() -> Unit)? = null,
    bottomDockClearance: Dp = 0.dp,
    onSelectUiLanguage: (String) -> Unit,
    onSelectLanguagePreset: (String) -> Unit,
    onSelectAudioFormat: (AudioFormat) -> Unit,
    onToggleRawMic: (Boolean) -> Unit,
    onToggleDistantSpeechBoost: (Boolean) -> Unit,
    onSelectThreads: (Int) -> Unit,
    onSelectBeam: (Int) -> Unit,
    onToggleAutoParagraph: (Boolean) -> Unit,
    onToggleShowTimestamps: (Boolean) -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleReaderSerif: (Boolean) -> Unit,
    onSelectAsrEngine: (AsrEngineMode) -> Unit,
    onUpdateGroqApiKey: (String) -> Unit,
    onSelectGroqModel: (String) -> Unit,
    onSelectGroqLanguageMode: (String) -> Unit = {},
    onToggleSpeakers: (Boolean) -> Unit,
    onOpenModelManager: () -> Unit,
    onClearCache: () -> Unit,
    onResetAllData: () -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val extra = LocalExtraStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberVantaHaptics()
    val listState = rememberLazyListState()

    var showUiLangSheet by remember { mutableStateOf(false) }
    var uiLangQuery by remember { mutableStateOf("") }
    var showPresetSheet by remember { mutableStateOf(false) }
    var showAudioFmtSheet by remember { mutableStateOf(false) }
    var showBeamSheet by remember { mutableStateOf(false) }
    var showThreadsSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }

    var showResetStep1 by remember { mutableStateOf(false) }
    var showResetStep2 by remember { mutableStateOf(false) }
    var showEngineSheet by remember { mutableStateOf(false) }
    var showGroqModelSheet by remember { mutableStateOf(false) }
    var showGroqLangSheet by remember { mutableStateOf(false) }
    var groqKeyDraft by remember { mutableStateOf(settings.groqApiKey) }
    LaunchedEffect(settings.groqApiKey) {
        if (settings.groqApiKey != groqKeyDraft.trim()) {
            groqKeyDraft = settings.groqApiKey
        }
    }
    var showGroqKeyPlain by remember { mutableStateOf(false) }
    var isVerifyingKey by remember { mutableStateOf(false) }
    var verifyStatusText by remember { mutableStateOf<String?>(null) }
    var verifyStatusOk by remember { mutableStateOf(false) }
    var availableGroqModels by remember { mutableStateOf(GroqAsrClient.SUPPORTED_MODELS) }
    val scope = rememberCoroutineScope()

    val selectedUiLang = AppLanguage.fromCode(settings.uiLanguageCode)
    val resolvedSystemLang = remember(settings.uiLanguageCode) { AppLocaleManager.resolveLanguage("system") }
    val currentPreset = TinyAsrCatalog.findPreset(settings.languagePresetId)
    val installedModelsCount = modelStatuses.count { it.isInstalled }

    GlassScaffold(
        topBar = {
            VantaTopBar(
                onBack = onBack,
                title = s.settingsTitle
            )
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
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 1. Interface & Appearance
            item(key = "sec_interface") {
                SectionHeader(s.sectionInterface)
                SettingsCard {
                    val langValue = if (selectedUiLang == AppLanguage.SYSTEM) {
                        "${s.uiLanguageSystem} (${resolvedSystemLang.nativeName})"
                    } else {
                        selectedUiLang.nativeName
                    }
                    SettingValueRow(
                        title = s.uiLanguageTitle,
                        icon = Icons.Rounded.Translate,
                        iconBg = Color(0xFF3390EC),
                        value = langValue,
                        subtitle = s.uiLanguageSubtitle,
                        onClick = {
                            haptics.tick()
                            uiLangQuery = ""
                            showUiLangSheet = true
                        }
                    )
                    SettingsDivider()
                    SettingValueRow(
                        title = s.themeTitle,
                        icon = Icons.Rounded.Palette,
                        iconBg = Color(0xFF8E63E6),
                        value = settings.themeMode.label(s),
                        onClick = {
                            haptics.tick()
                            showThemeSheet = true
                        }
                    )
                    SettingsDivider()
                    SettingSwitchRow(
                        title = "${extra.fontStyleLabel}: ${extra.fontSerifLabel}",
                        subtitle = "${extra.fontSansLabel} / ${extra.fontSerifLabel}",
                        icon = Icons.Rounded.FormatSize,
                        iconBg = Color(0xFF5C6BC0),
                        checked = settings.readerSerif,
                        onCheckedChange = {
                            haptics.tick()
                            onToggleReaderSerif(it)
                        }
                    )
                }
            }

            // 2. Models Manager Entry
            item(key = "sec_models") {
                SectionHeader(s.sectionModels)
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.click()
                                onOpenModelManager()
                            }
                            .padding(horizontal = Spacing.lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(colors.accentSoft)
                                .border(0.75.dp, colors.accent.copy(alpha = 0.25f), RoundedCornerShape(Radius.sm)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Layers, contentDescription = null, tint = colors.accent, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.manageModelsTitle,
                                color = colors.textPrimary,
                                style = VantaType.cardTitle
                            )
                            Text(
                                text = s.manageModelsSubtitle,
                                color = colors.textSecondary,
                                style = VantaType.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "$installedModelsCount/${modelStatuses.size}",
                                color = colors.accent,
                                style = VantaType.monoMetric
                            )
                            Text(
                                text = formatStorageSize(storage.modelsBytes),
                                color = colors.textMuted,
                                style = VantaType.monoBadge
                            )
                        }
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
                    }
                }
            }

            // 2.5. Cloud AI Engine (Groq API BYOK)
            item(key = "sec_groq") {
                SectionHeader("Groq API · Облачное распознавание")
                SettingsCard {
                    SettingValueRow(
                        title = "Движок распознавания",
                        icon = Icons.Rounded.AutoAwesome,
                        iconBg = Color(0xFF6366F1),
                        value = if (settings.asrEngineMode == AsrEngineMode.GROQ_API) {
                            "Groq API (${settings.groqModelId})"
                        } else {
                            "TinyASR (Локально · Офлайн)"
                        },
                        subtitle = "Выберите, через что идёт распознавание в реальном времени и импорте",
                        onClick = {
                            haptics.tick()
                            showEngineSheet = true
                        }
                    )
                    SettingsDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingLeadingIcon(icon = Icons.Rounded.Key, badgeColor = Color(0xFF0EA5E9))
                            Spacer(Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Groq API-ключ",
                                    color = colors.textPrimary,
                                    style = VantaType.rowLabel
                                )
                                Text(
                                    text = "console.groq.com · API Keys",
                                    color = colors.textSecondary,
                                    style = VantaType.bodySmall
                                )
                            }
                            val hasKey = groqKeyDraft.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.pill))
                                    .background(if (hasKey) colors.successSoft else colors.surfaceRecessed)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (hasKey) "Задан" else "Не задан",
                                    color = if (hasKey) colors.success else colors.textMuted,
                                    style = VantaType.monoBadge
                                )
                            }
                        }

                        OutlinedTextField(
                            value = groqKeyDraft,
                            onValueChange = {
                                groqKeyDraft = it
                                verifyStatusText = null
                                verifyStatusOk = false
                                onUpdateGroqApiKey(it)
                            },
                            placeholder = { Text("Вставьте ключ gsk_…", color = colors.textMuted, style = VantaType.bodySmall) },
                            singleLine = true,
                            textStyle = VantaType.body.copy(color = colors.textPrimary),
                            visualTransformation = if (showGroqKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                val clip = clipboard.getText()?.text?.trim().orEmpty()
                                                if (clip.isNotBlank()) {
                                                    haptics.tick()
                                                    groqKeyDraft = clip
                                                    verifyStatusText = null
                                                    verifyStatusOk = false
                                                    onUpdateGroqApiKey(clip)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentPaste,
                                            contentDescription = "Вставить",
                                            tint = colors.accent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .clickable { showGroqKeyPlain = !showGroqKeyPlain },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (showGroqKeyPlain) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = null,
                                            tint = colors.textMuted,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    if (groqKeyDraft.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    groqKeyDraft = ""
                                                    verifyStatusText = null
                                                    verifyStatusOk = false
                                                    onUpdateGroqApiKey("")
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Очистить",
                                                tint = colors.textMuted,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(Radius.md),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.surfaceRecessed,
                                unfocusedContainerColor = colors.surfaceRecessed,
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.borderStrong,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                cursorColor = colors.accent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VantaTonalButton(
                                label = if (isVerifyingKey) "Проверка ключа..." else "Проверить ключ и модели",
                                enabled = groqKeyDraft.isNotBlank() && !isVerifyingKey,
                                onClick = {
                                    haptics.click()
                                    isVerifyingKey = true
                                    verifyStatusText = null
                                    scope.launch {
                                        val modelsRes = GroqAsrClient.fetchAvailableModels(groqKeyDraft)
                                        isVerifyingKey = false
                                        modelsRes.onSuccess { list ->
                                            availableGroqModels = list
                                            val resolved = list.firstOrNull { it.first == settings.groqModelId }?.first
                                                ?: list.firstOrNull { it.first == GroqAsrClient.DEFAULT_MODEL_ID }?.first
                                                ?: list.first().first
                                            if (resolved != settings.groqModelId) {
                                                onSelectGroqModel(resolved)
                                            }
                                            verifyStatusOk = true
                                            verifyStatusText = "Ключ активен · модель: $resolved"
                                        }.onFailure { err ->
                                            verifyStatusOk = false
                                            verifyStatusText = err.message ?: "Ошибка проверки ключа"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (isVerifyingKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent
                                )
                            } else if (verifyStatusOk) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.success,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        if (!verifyStatusText.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(if (verifyStatusOk) colors.successSoft else colors.destructiveSoft)
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            ) {
                                Text(
                                    text = verifyStatusText!!,
                                    color = if (verifyStatusOk) colors.success else colors.destructive,
                                    style = VantaType.bodySmall
                                )
                            }
                        }
                    }
                    SettingsDivider()
                    SettingValueRow(
                        title = "Модель Groq",
                        icon = Icons.Rounded.Layers,
                        iconBg = Color(0xFF8B5CF6),
                        value = availableGroqModels.firstOrNull { it.first == settings.groqModelId }?.second
                            ?: settings.groqModelId,
                        onClick = {
                            haptics.tick()
                            showGroqModelSheet = true
                        }
                    )
                    SettingsDivider()
                    SettingValueRow(
                        title = "Язык распознавания (Groq)",
                        icon = Icons.Rounded.Translate,
                        iconBg = Color(0xFF0EA5E9),
                        value = GroqAsrClient.languageLabel(settings.groqLanguageMode),
                        onClick = {
                            haptics.tick()
                            showGroqLangSheet = true
                        }
                    )

                }
            }

            // 3. Speech Recognition (TinyASR & Transcript)
            item(key = "sec_asr") {
                SectionHeader(s.sectionAsr)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    VantaInfoCallout(text = s.asrLanguageSeparationNote)
                    SettingsCard {
                        SettingSwitchRow(
                            title = s.speakerDiarizationTitle,
                            subtitle = s.speakerDiarizationSubtitle,
                            icon = Icons.Rounded.RecordVoiceOver,
                            iconBg = Color(0xFF10B981),
                            checked = settings.speakerDiarization,
                            onCheckedChange = {
                                haptics.tick()
                                onToggleSpeakers(it)
                            }
                        )
                        SettingsDivider()
                        SettingValueRow(
                            title = s.defaultAsrPresetTitle,
                            icon = Icons.Rounded.Language,
                            iconBg = Color(0xFF0097A7),
                            value = currentPreset.label(s),
                            onClick = {
                                haptics.tick()
                                showPresetSheet = true
                            }
                        )
                        SettingsDivider()
                        SettingValueRow(
                            title = s.decodingAccuracyTitle,
                            icon = Icons.Rounded.Tune,
                            iconBg = Color(0xFF26A69A),
                            value = if (settings.asrBeam == 1) s.fastGreedy1 else s.accuracyBeam4,
                            onClick = {
                                haptics.tick()
                                showBeamSheet = true
                            }
                        )
                        SettingsDivider()
                        SettingValueRow(
                            title = s.asrThreadsTitle,
                            icon = Icons.Rounded.Memory,
                            iconBg = Color(0xFF42A5F5),
                            value = if (settings.asrThreads == 0) s.threadsAuto else "${settings.asrThreads} ${s.threadsCount}",
                            onClick = {
                                haptics.tick()
                                showThreadsSheet = true
                            }
                        )
                        SettingsDivider()
                        SettingSwitchRow(
                            title = s.autoParagraphTitle,
                            subtitle = s.autoParagraphSubtitle,
                            icon = Icons.AutoMirrored.Rounded.Notes,
                            iconBg = Color(0xFF66BB6A),
                            checked = settings.autoParagraph,
                            onCheckedChange = {
                                haptics.tick()
                                onToggleAutoParagraph(it)
                            }
                        )
                        SettingsDivider()
                        SettingSwitchRow(
                            title = s.showTimestampsTitle,
                            subtitle = s.showTimestampsSubtitle,
                            icon = Icons.Rounded.Schedule,
                            iconBg = Color(0xFF7E57C2),
                            checked = settings.showTimestamps,
                            onCheckedChange = {
                                haptics.tick()
                                onToggleShowTimestamps(it)
                            }
                        )
                    }
                }
            }

            // 4. Audio Recording
            item(key = "sec_audio") {
                SectionHeader(s.sectionAudio)
                SettingsCard {
                    SettingValueRow(
                        title = s.audioQualityTitle,
                        icon = Icons.Rounded.GraphicEq,
                        iconBg = Color(0xFFEF6C00),
                        value = settings.audioFormat.label(s),
                        onClick = {
                            haptics.tick()
                            showAudioFmtSheet = true
                        }
                    )
                    SettingsDivider()
                    SettingSwitchRow(
                        title = s.rawMicTitle,
                        subtitle = s.rawMicSubtitle,
                        icon = Icons.Rounded.MicNone,
                        iconBg = Color(0xFFE53935),
                        checked = settings.useRawMicrophone,
                        onCheckedChange = {
                            haptics.tick()
                            onToggleRawMic(it)
                        }
                    )
                    SettingsDivider()
                    SettingSwitchRow(
                        title = extra.distantSpeechBoostTitle,
                        subtitle = extra.distantSpeechBoostSubtitle,
                        icon = Icons.Rounded.Tune,
                        iconBg = Color(0xFFFB8C00),
                        checked = settings.distantSpeechBoost,
                        onCheckedChange = {
                            haptics.tick()
                            onToggleDistantSpeechBoost(it)
                        }
                    )
                    SettingsDivider()
                    SettingSwitchRow(
                        title = s.keepScreenOnTitle,
                        subtitle = s.keepScreenOnSubtitle,
                        icon = Icons.Rounded.Visibility,
                        iconBg = Color(0xFF3949AB),
                        checked = settings.keepScreenOnRecording,
                        onCheckedChange = {
                            haptics.tick()
                            onToggleKeepScreenOn(it)
                        }
                    )
                }
            }

            // 5. Isolated Storage Manager
            item(key = "sec_storage") {
                SectionHeader(s.sectionStorage)
                SettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Storage, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = s.manageStorageTitle,
                                    color = colors.textPrimary,
                                    style = VantaType.cardTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = formatStorageSize(storage.totalAppBytes),
                                color = colors.textPrimary,
                                style = VantaType.monoMetric
                            )
                        }

                        SegmentedStorageBar(
                            segments = listOf(
                                StorageSegment(s.storageBreakdownAudio, storage.audioBytes, colors.accent),
                                StorageSegment(s.storageBreakdownModels, storage.modelsBytes, colors.success),
                                StorageSegment(s.storageBreakdownCache, storage.cacheAndDownloadsBytes, colors.warning),
                                StorageSegment(s.storageBreakdownDb, storage.databaseBytes, colors.textMuted)
                            )
                        )

                        StorageLegendRow(s.storageBreakdownAudio, storage.audioBytes, colors.accent)
                        StorageLegendRow(s.storageBreakdownModels, storage.modelsBytes, colors.success)
                        StorageLegendRow(s.storageBreakdownCache, storage.cacheAndDownloadsBytes, colors.warning)
                        StorageLegendRow(s.storageBreakdownDb, storage.databaseBytes, colors.textMuted)
                        StorageLegendRow(s.storageBreakdownFree, storage.freeDeviceBytes, null)

                        val hasCache = storage.cacheAndDownloadsBytes > 0L
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VantaTonalButton(
                                label = if (hasCache) {
                                    "${s.clearCacheButton} (${formatStorageSize(storage.cacheAndDownloadsBytes)})"
                                } else {
                                    extra.cacheEmptyLabel
                                },
                                enabled = hasCache,
                                onClick = {
                                    haptics.confirm()
                                    onClearCache()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            VantaDangerButton(
                                label = s.resetAllDataButton,
                                icon = Icons.Rounded.DeleteForever,
                                outlined = true,
                                onClick = {
                                    haptics.heavy()
                                    showResetStep1 = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 6. Developer (@trelvo)
            item(key = "sec_developer") {
                Spacer(Modifier.height(Spacing.xs))
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(pressedScale = 0.985f)
                            .clickable {
                                haptics.click()
                                val launchedTg = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=trelvo"))
                                    )
                                }.isSuccess
                                if (!launchedTg) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/trelvo"))
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = Spacing.lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_telegram_logo),
                            contentDescription = "Telegram",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                Text(
                                    text = "@trelvo",
                                    color = colors.textPrimary,
                                    style = VantaType.cardTitle
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(colors.accentSoft)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = extra.developerRoleLabel,
                                        color = colors.accent,
                                        style = VantaType.monoBadge
                                    )
                                }
                            }
                            Text(
                                text = extra.developerTelegramSubtitle,
                                color = colors.textSecondary,
                                style = VantaType.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "@trelvo Telegram",
                                tint = colors.accent,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            item(key = "nav_spacer") {
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        // Searchable UI Language picker sheet (all 40 languages)
        if (showUiLangSheet) {
            val filteredLangs = remember(uiLangQuery) {
                val q = uiLangQuery.trim().lowercase()
                if (q.isEmpty()) AppLanguage.selectableLanguages
                else AppLanguage.selectableLanguages.filter {
                    it.nativeName.lowercase().contains(q) || it.code.lowercase().contains(q)
                }
            }
            VantaBottomSheet(
                onDismissRequest = { showUiLangSheet = false },
                title = s.uiLanguageTitle,
                headerIcon = Icons.Rounded.Language
            ) {
                VantaSearchField(
                    value = uiLangQuery,
                    onValueChange = { uiLangQuery = it },
                    placeholder = s.searchHint
                )
                Spacer(Modifier.height(Spacing.sm))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                ) {
                    if (uiLangQuery.isBlank()) {
                        item(key = "lang_system") {
                            SheetOptionRow(
                                selected = selectedUiLang == AppLanguage.SYSTEM,
                                title = s.uiLanguageSystem,
                                subtitle = resolvedSystemLang.nativeName,
                                onClick = {
                                    haptics.tick()
                                    onSelectUiLanguage(AppLanguage.SYSTEM.code)
                                    showUiLangSheet = false
                                }
                            )
                        }
                    }
                    items(filteredLangs, key = { it.code }) { lang ->
                        val subtitle = if (lang.isRtl) "${lang.code} · RTL" else lang.code
                        SheetOptionRow(
                            selected = selectedUiLang == lang,
                            title = lang.nativeName,
                            subtitle = subtitle,
                            onClick = {
                                haptics.tick()
                                onSelectUiLanguage(lang.code)
                                showUiLangSheet = false
                            }
                        )
                    }
                }
            }
        }

        if (showPresetSheet) {
            val installedPresets = remember(modelStatuses) {
                TinyAsrCatalog.PRESETS.filter { p ->
                    modelStatuses.any { it.spec.id == p.modelId && it.isInstalled } ||
                        TinyAsrCatalog.findModel(p.modelId).bundledInApk
                }
            }
            VantaBottomSheet(
                onDismissRequest = { showPresetSheet = false },
                title = s.defaultAsrPresetTitle,
                headerIcon = Icons.Rounded.Translate
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(installedPresets, key = { it.id }) { preset ->
                        SheetOptionRow(
                            selected = preset.id == settings.languagePresetId,
                            title = preset.label(s),
                            subtitle = preset.modelId,
                            onClick = {
                                haptics.tick()
                                onSelectLanguagePreset(preset.id)
                                showPresetSheet = false
                            }
                        )
                    }
                    item {
                        Spacer(Modifier.height(Spacing.md))
                        VantaTonalButton(
                            label = s.manageModelsTitle,
                            onClick = {
                                showPresetSheet = false
                                onOpenModelManager()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (showAudioFmtSheet) {
            VantaBottomSheet(
                onDismissRequest = { showAudioFmtSheet = false },
                title = s.audioQualityTitle,
                headerIcon = Icons.Rounded.GraphicEq
            ) {
                for (fmt in AudioFormat.entries) {
                    SheetOptionRow(
                        selected = settings.audioFormat == fmt,
                        title = fmt.label(s),
                        onClick = {
                            haptics.tick()
                            onSelectAudioFormat(fmt)
                            showAudioFmtSheet = false
                        }
                    )
                }
            }
        }

        if (showBeamSheet) {
            VantaBottomSheet(
                onDismissRequest = { showBeamSheet = false },
                title = s.decodingAccuracyTitle,
                headerIcon = Icons.Rounded.Tune
            ) {
                SheetOptionRow(
                    selected = settings.asrBeam == 4,
                    title = s.decodingAccuracyBeam,
                    onClick = {
                        haptics.tick()
                        onSelectBeam(4)
                        showBeamSheet = false
                    }
                )
                SheetOptionRow(
                    selected = settings.asrBeam == 1,
                    title = s.decodingAccuracyGreedy,
                    onClick = {
                        haptics.tick()
                        onSelectBeam(1)
                        showBeamSheet = false
                    }
                )
            }
        }

        if (showThreadsSheet) {
            VantaBottomSheet(
                onDismissRequest = { showThreadsSheet = false },
                title = s.asrThreadsTitle,
                headerIcon = Icons.Rounded.Memory
            ) {
                val opts = listOf(
                    0 to s.threadsAutoLong,
                    1 to s.threadsSingleLowHeat,
                    2 to "2 ${s.threadsCount}",
                    4 to "4 ${s.threadsCount}"
                )
                for ((n, label) in opts) {
                    SheetOptionRow(
                        selected = settings.asrThreads == n,
                        title = label,
                        onClick = {
                            haptics.tick()
                            onSelectThreads(n)
                            showThreadsSheet = false
                        }
                    )
                }
            }
        }

        if (showEngineSheet) {
            VantaBottomSheet(
                onDismissRequest = { showEngineSheet = false },
                title = "Движок распознавания",
                headerIcon = Icons.Rounded.AutoAwesome
            ) {
                SheetOptionRow(
                    selected = settings.asrEngineMode == AsrEngineMode.LOCAL_TINYASR,
                    title = "TinyASR (Локально · Офлайн)",
                    subtitle = "Работает полностью без интернета и без лимитов",
                    onClick = {
                        haptics.tick()
                        onSelectAsrEngine(AsrEngineMode.LOCAL_TINYASR)
                        showEngineSheet = false
                    }
                )
                SheetOptionRow(
                    selected = settings.asrEngineMode == AsrEngineMode.GROQ_API,
                    title = "Groq API (Свой ключ · Спикеры)",
                    subtitle = "Whisper распознаёт речь блоками во время записи. Спикеры определяются на устройстве.",
                    onClick = {
                        haptics.tick()
                        onSelectAsrEngine(AsrEngineMode.GROQ_API)
                        showEngineSheet = false
                    }
                )
            }
        }

        if (showGroqModelSheet) {
            VantaBottomSheet(
                onDismissRequest = { showGroqModelSheet = false },
                title = "Модель Groq",
                headerIcon = Icons.Rounded.AutoAwesome
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(availableGroqModels, key = { it.first }) { (id, label) ->
                        SheetOptionRow(
                            selected = settings.groqModelId == id,
                            title = label,
                            subtitle = id,
                            onClick = {
                                haptics.tick()
                                onSelectGroqModel(id)
                                showGroqModelSheet = false
                            }
                        )
                    }
                }
            }
        }

        if (showGroqLangSheet) {
            VantaBottomSheet(
                onDismissRequest = { showGroqLangSheet = false },
                title = "Язык распознавания (Groq)",
                subtitle = "«Русский + English (Авто)» позволяет Whisper свободно переключаться между русским и английским в одной записи.",
                headerIcon = Icons.Rounded.Translate
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(GroqAsrClient.SUPPORTED_LANGUAGE_MODES, key = { it.first }) { (code, label) ->
                        SheetOptionRow(
                            selected = settings.groqLanguageMode == code,
                            title = label,
                            subtitle = when (code) {
                                "multi" -> "Смешанная речь RU + EN без принудительной транслитерации"
                                "auto" -> "Автоопределение среди ~99 языков"
                                else -> "Принудительно: $code"
                            },
                            onClick = {
                                haptics.tick()
                                onSelectGroqLanguageMode(code)
                                showGroqLangSheet = false
                            }
                        )
                    }
                }
            }
        }

        if (showThemeSheet) {
            VantaBottomSheet(
                onDismissRequest = { showThemeSheet = false },
                title = s.themeTitle,
                headerIcon = Icons.Rounded.Palette
            ) {
                for (mode in ThemeMode.entries) {
                    SheetOptionRow(
                        selected = settings.themeMode == mode,
                        title = mode.label(s),
                        onClick = {
                            haptics.tick()
                            onSelectTheme(mode)
                            showThemeSheet = false
                        }
                    )
                }
            }
        }

        // Strict double-confirmation for Reset All App Data
        if (showResetStep1) {
            VantaDialog(
                onDismissRequest = { showResetStep1 = false },
                title = s.resetAllDataTitle,
                icon = Icons.Rounded.DeleteForever,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                message = s.resetAllDataMessage,
                confirmLabel = s.resetAllDataConfirm,
                isDestructive = true,
                onConfirm = {
                    showResetStep1 = false
                    showResetStep2 = true
                },
                dismissLabel = s.cancel,
                onDismiss = { showResetStep1 = false }
            )
        }

        if (showResetStep2) {
            VantaDialog(
                onDismissRequest = { showResetStep2 = false },
                title = s.resetAllDataConfirm,
                icon = Icons.Rounded.DeleteForever,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                message = s.resetAllDataMessage,
                confirmLabel = s.delete,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    showResetStep2 = false
                    onResetAllData()
                },
                dismissLabel = s.cancel,
                onDismiss = { showResetStep2 = false }
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    val colors = LocalStenografColors.current
    HorizontalDivider(
        color = colors.border,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 66.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalStenografColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .vantaSurfaceCard(colors, radius = Radius.lg, elevated = false),
        content = content
    )
}

@Composable
private fun SettingLeadingIcon(
    icon: ImageVector,
    badgeColor: Color = LocalStenografColors.current.accent
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(badgeColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingValueRow(
    title: String,
    value: String,
    icon: ImageVector,
    iconBg: Color = LocalStenografColors.current.accent,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val colors = LocalStenografColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingLeadingIcon(icon = icon, badgeColor = iconBg)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = VantaType.rowLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    style = VantaType.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                color = colors.accent,
                style = VantaType.bodySmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBg: Color = LocalStenografColors.current.accent,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalStenografColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = Spacing.lg, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingLeadingIcon(icon = icon, badgeColor = iconBg)
        Spacer(Modifier.width(Spacing.md))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md)
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = VantaType.rowLabel
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.textSecondary,
                style = VantaType.bodySmall
            )
        }
        VantaSwitch(checked = checked)
    }
}

@Composable
private fun StorageLegendRow(label: String, bytes: Long, dotColor: Color?) {
    val colors = LocalStenografColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = label,
                color = if (dotColor != null) colors.textPrimary else colors.textSecondary,
                style = VantaType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatStorageSize(bytes),
            color = colors.textSecondary,
            style = VantaType.monoMetric
        )
    }
}
