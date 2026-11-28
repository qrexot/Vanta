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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stenograf.app.R
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.ModelInstallStatus
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.data.prefs.AppSettings
import com.stenograf.app.data.prefs.RecognitionAudioFilter
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
import com.stenograf.app.ui.components.StorageSegment
import com.stenograf.app.ui.components.VantaDangerButton
import com.stenograf.app.ui.components.VantaInfoCallout
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
    onSelectRecognitionAudioFilter: (RecognitionAudioFilter) -> Unit = {},
    onToggleAutoLearnSpeakers: (Boolean) -> Unit = {},
    speakerProfiles: List<SpeakerProfileEntity> = emptyList(),
    isRecordingActive: Boolean = false,
    onEnrollPcmVoiceProfile: suspend (String, String, ByteArray, Long?) -> Boolean = { _, _, _, _ -> false },
    onUpdateVoiceProfileDetails: (Long, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    onMergeVoiceProfiles: (Long, Long) -> Unit = { _, _ -> },
    onEnsureVoiceSampleClip: suspend (Long) -> SpeakerProfileEntity? = { null },
    onOpenSourceRecording: (Long) -> Unit = {},
    onDeleteVoiceProfile: (Long) -> Unit = {},
    onShowMessage: (String) -> Unit = {},
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

    var activeModal by remember { mutableStateOf<SettingsModal?>(null) }
    var showAudioFilter by remember { mutableStateOf(false) }
    var uiLangQuery by remember { mutableStateOf("") }
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
                            activeModal = SettingsModal.UI_LANG
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
                            activeModal = SettingsModal.THEME
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
                            "Локально (TinyASR / GigaAM · Офлайн)"
                        },
                        subtitle = "Выберите, через что идёт распознавание в реальном времени и импорте",
                        onClick = {
                            haptics.tick()
                            activeModal = SettingsModal.ENGINE
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
                            activeModal = SettingsModal.GROQ_MODEL
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
                            activeModal = SettingsModal.GROQ_LANG
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
                        SettingSwitchRow(
                            title = extra.autoLearnSpeakersTitle,
                            subtitle = extra.autoLearnSpeakersSubtitle,
                            icon = Icons.Rounded.AutoAwesome,
                            iconBg = Color(0xFF14B8A6),
                            checked = settings.autoLearnSpeakers,
                            onCheckedChange = {
                                haptics.tick()
                                onToggleAutoLearnSpeakers(it)
                            }
                        )
                        SettingsDivider()
                        SettingValueRow(
                            title = extra.speakerProfilesTitle,
                            subtitle = extra.speakerProfilesSubtitle,
                            icon = Icons.Rounded.RecordVoiceOver,
                            iconBg = Color(0xFF059669),
                            value = speakerProfiles.size.toString(),
                            onClick = {
                                haptics.tick()
                                activeModal = SettingsModal.SPEAKER_PROFILES
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
                                activeModal = SettingsModal.PRESET
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
                                activeModal = SettingsModal.BEAM
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
                                activeModal = SettingsModal.THREADS
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
                            activeModal = SettingsModal.AUDIO_FORMAT
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
                    val russian = (if (selectedUiLang == AppLanguage.SYSTEM) resolvedSystemLang else selectedUiLang).code == "ru"
                    SettingValueRow(
                        title = if (russian) "Шумоподавление для текста" else "Noise filter for transcription",
                        value = settings.recognitionAudioFilter.title(russian),
                        subtitle = if (russian) "Встроено · для новой записи и распознавания исходного аудио." else "Built in · for new recordings and transcription from original audio.",
                        icon = Icons.Rounded.AutoAwesome,
                        iconBg = Color(0xFF0EA5E9),
                        onClick = { showAudioFilter = true }
                    )
                    SettingsDivider()
                    SettingSwitchRow(
                        title = if (russian) "Усилить тихий голос для текста" else extra.distantSpeechBoostTitle,
                        subtitle = if (russian) "Оставьте включённым для лекций, если преподаватель далеко." else extra.distantSpeechBoostSubtitle,
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
                                    activeModal = SettingsModal.RESET_STEP1
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

        if (showAudioFilter) {
            val russian = (if (selectedUiLang == AppLanguage.SYSTEM) resolvedSystemLang else selectedUiLang).code == "ru"
            RecognitionFilterSheet(
                current = settings.recognitionAudioFilter,
                russian = russian,
                onChoose = { filter -> haptics.tick(); onSelectRecognitionAudioFilter(filter); showAudioFilter = false },
                onDismiss = { showAudioFilter = false }
            )
        }

        SettingsModalHost(
            activeModal = activeModal,
            onSetModal = { activeModal = it },
            uiLangQuery = uiLangQuery,
            onUiLangQueryChange = { uiLangQuery = it },
            selectedUiLang = selectedUiLang,
            resolvedSystemLang = resolvedSystemLang,
            settings = settings,
            modelStatuses = modelStatuses,
            availableGroqModels = availableGroqModels,
            speakerProfiles = speakerProfiles,
            isRecordingActive = isRecordingActive,
            onSelectUiLanguage = onSelectUiLanguage,
            onSelectLanguagePreset = onSelectLanguagePreset,
            onSelectAudioFormat = onSelectAudioFormat,
            onSelectBeam = onSelectBeam,
            onSelectThreads = onSelectThreads,
            onSelectAsrEngine = onSelectAsrEngine,
            onSelectGroqModel = onSelectGroqModel,
            onSelectGroqLanguageMode = onSelectGroqLanguageMode,
            onSelectTheme = onSelectTheme,
            onEnrollPcmVoiceProfile = onEnrollPcmVoiceProfile,
            onUpdateVoiceProfileDetails = onUpdateVoiceProfileDetails,
            onMergeVoiceProfiles = onMergeVoiceProfiles,
            onEnsureVoiceSampleClip = onEnsureVoiceSampleClip,
            onOpenSourceRecording = onOpenSourceRecording,
            onDeleteVoiceProfile = onDeleteVoiceProfile,
            onShowMessage = onShowMessage,
            onOpenModelManager = onOpenModelManager,
            onResetAllData = onResetAllData
        )
    }
}
