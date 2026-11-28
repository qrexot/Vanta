package com.stenograf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.ModelInstallStatus
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.data.prefs.AppSettings
import com.stenograf.app.data.prefs.AsrEngineMode
import com.stenograf.app.data.prefs.ThemeMode
import com.stenograf.app.i18n.AppLanguage
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.SpeakerProfilesSheet
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.components.VantaDialog
import com.stenograf.app.ui.components.VantaSearchField
import com.stenograf.app.ui.components.VantaSwitch
import com.stenograf.app.ui.components.VantaTonalButton
import com.stenograf.app.ui.components.formatStorageSize
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.rememberVantaHaptics
import com.stenograf.app.ui.theme.vantaSurfaceCard

internal enum class SettingsModal {
    UI_LANG,
    PRESET,
    AUDIO_FORMAT,
    BEAM,
    THREADS,
    ENGINE,
    GROQ_MODEL,
    GROQ_LANG,
    THEME,
    SPEAKER_PROFILES,
    RESET_STEP1,
    RESET_STEP2
}

@Composable
internal fun SettingsModalHost(
    activeModal: SettingsModal?,
    onSetModal: (SettingsModal?) -> Unit,
    uiLangQuery: String,
    onUiLangQueryChange: (String) -> Unit,
    selectedUiLang: AppLanguage,
    resolvedSystemLang: AppLanguage,
    settings: AppSettings,
    modelStatuses: List<ModelInstallStatus>,
    availableGroqModels: List<Pair<String, String>>,
    speakerProfiles: List<SpeakerProfileEntity>,
    isRecordingActive: Boolean,
    onSelectUiLanguage: (String) -> Unit,
    onSelectLanguagePreset: (String) -> Unit,
    onSelectAudioFormat: (AudioFormat) -> Unit,
    onSelectBeam: (Int) -> Unit,
    onSelectThreads: (Int) -> Unit,
    onSelectAsrEngine: (AsrEngineMode) -> Unit,
    onSelectGroqModel: (String) -> Unit,
    onSelectGroqLanguageMode: (String) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onEnrollPcmVoiceProfile: suspend (String, String, ByteArray, Long?) -> Boolean,
    onUpdateVoiceProfileDetails: (Long, String, String, Boolean) -> Unit,
    onMergeVoiceProfiles: (Long, Long) -> Unit,
    onEnsureVoiceSampleClip: suspend (Long) -> SpeakerProfileEntity?,
    onOpenSourceRecording: (Long) -> Unit,
    onDeleteVoiceProfile: (Long) -> Unit,
    onShowMessage: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onResetAllData: () -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    val haptics = rememberVantaHaptics()

    when (activeModal) {
        null -> Unit
        // Searchable UI Language picker sheet (all 40 languages)
        SettingsModal.UI_LANG -> {
            val filteredLangs = remember(uiLangQuery) {
                val q = uiLangQuery.trim().lowercase()
                if (q.isEmpty()) AppLanguage.selectableLanguages
                else AppLanguage.selectableLanguages.filter {
                    it.nativeName.lowercase().contains(q) || it.code.lowercase().contains(q)
                }
            }
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
                title = s.uiLanguageTitle,
                headerIcon = Icons.Rounded.Language
            ) {
                VantaSearchField(
                    value = uiLangQuery,
                    onValueChange = { onUiLangQueryChange(it) },
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
                                    onSetModal(null)
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
                                onSetModal(null)
                            }
                        )
                    }
                }
            }
        }

        SettingsModal.PRESET -> {
            val installedPresets = remember(modelStatuses) {
                TinyAsrCatalog.PRESETS.filter { p ->
                    modelStatuses.any { it.spec.id == p.modelId && it.isInstalled } ||
                        TinyAsrCatalog.findModel(p.modelId).bundledInApk
                }
            }
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
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
                                onSetModal(null)
                            }
                        )
                    }
                    item {
                        Spacer(Modifier.height(Spacing.md))
                        VantaTonalButton(
                            label = s.manageModelsTitle,
                            onClick = {
                                onSetModal(null)
                                onOpenModelManager()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        SettingsModal.AUDIO_FORMAT -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
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
                            onSetModal(null)
                        }
                    )
                }
            }
        }

        SettingsModal.BEAM -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
                title = s.decodingAccuracyTitle,
                headerIcon = Icons.Rounded.Tune
            ) {
                SheetOptionRow(
                    selected = settings.asrBeam == 4,
                    title = s.decodingAccuracyBeam,
                    onClick = {
                        haptics.tick()
                        onSelectBeam(4)
                        onSetModal(null)
                    }
                )
                SheetOptionRow(
                    selected = settings.asrBeam == 1,
                    title = s.decodingAccuracyGreedy,
                    onClick = {
                        haptics.tick()
                        onSelectBeam(1)
                        onSetModal(null)
                    }
                )
            }
        }

        SettingsModal.THREADS -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
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
                            onSetModal(null)
                        }
                    )
                }
            }
        }

        SettingsModal.ENGINE -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
                title = "Движок распознавания",
                headerIcon = Icons.Rounded.AutoAwesome
            ) {
                SheetOptionRow(
                    selected = settings.asrEngineMode == AsrEngineMode.LOCAL_TINYASR,
                    title = "Локально (TinyASR / GigaAM · Офлайн)",
                    subtitle = "Работает полностью без интернета и без лимитов",
                    onClick = {
                        haptics.tick()
                        onSelectAsrEngine(AsrEngineMode.LOCAL_TINYASR)
                        onSetModal(null)
                    }
                )
                SheetOptionRow(
                    selected = settings.asrEngineMode == AsrEngineMode.GROQ_API,
                    title = "Groq API (Свой ключ · Спикеры)",
                    subtitle = "Whisper распознаёт речь блоками во время записи. Спикеры определяются на устройстве.",
                    onClick = {
                        haptics.tick()
                        onSelectAsrEngine(AsrEngineMode.GROQ_API)
                        onSetModal(null)
                    }
                )
            }
        }

        SettingsModal.GROQ_MODEL -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
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
                                onSetModal(null)
                            }
                        )
                    }
                }
            }
        }

        SettingsModal.GROQ_LANG -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
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
                                onSetModal(null)
                            }
                        )
                    }
                }
            }
        }

        SettingsModal.THEME -> {
            VantaBottomSheet(
                onDismissRequest = { onSetModal(null) },
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
                            onSetModal(null)
                        }
                    )
                }
            }
        }

        // Strict double-confirmation for Reset All App Data
        SettingsModal.RESET_STEP1 -> {
            VantaDialog(
                onDismissRequest = { onSetModal(null) },
                title = s.resetAllDataTitle,
                icon = Icons.Rounded.DeleteForever,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                message = s.resetAllDataMessage,
                confirmLabel = s.resetAllDataConfirm,
                isDestructive = true,
                onConfirm = {
                    onSetModal(SettingsModal.RESET_STEP2)
                },
                dismissLabel = s.cancel,
                onDismiss = { onSetModal(null) }
            )
        }

        SettingsModal.RESET_STEP2 -> {
            VantaDialog(
                onDismissRequest = { onSetModal(null) },
                title = s.resetAllDataConfirm,
                icon = Icons.Rounded.DeleteForever,
                iconTint = colors.destructive,
                iconContainerColor = colors.destructiveSoft,
                message = s.resetAllDataMessage,
                confirmLabel = s.delete,
                isDestructive = true,
                onConfirm = {
                    haptics.heavy()
                    onSetModal(null)
                    onResetAllData()
                },
                dismissLabel = s.cancel,
                onDismiss = { onSetModal(null) }
            )
        }
        SettingsModal.SPEAKER_PROFILES -> {
            SpeakerProfilesSheet(
                profiles = speakerProfiles,
                useNeuralDenoiser = settings.neuralNoiseSuppression,
                isRecordingActive = isRecordingActive,
                onDismiss = { onSetModal(null) },
                onEnrollPcmSample = onEnrollPcmVoiceProfile,
                onUpdateProfileDetails = onUpdateVoiceProfileDetails,
                onMergeProfiles = onMergeVoiceProfiles,
                onEnsureSampleClip = onEnsureVoiceSampleClip,
                onOpenRecording = onOpenSourceRecording,
                onDeleteProfile = onDeleteVoiceProfile,
                onShowMessage = onShowMessage
            )
        }
    }
}

@Composable
internal fun SettingsDivider() {
    val colors = LocalStenografColors.current
    HorizontalDivider(
        color = colors.border,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 66.dp)
    )
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalStenografColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .vantaSurfaceCard(colors, radius = Radius.lg, elevated = false),
        content = content
    )
}

@Composable
internal fun SettingLeadingIcon(
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
internal fun SettingValueRow(
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
internal fun SettingSwitchRow(
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
internal fun StorageLegendRow(label: String, bytes: Long, dotColor: Color?) {
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
