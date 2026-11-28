package com.stenograf.app.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.prefs.AppSettings
import com.stenograf.app.i18n.LocalStrings
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Radius
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType
import com.stenograf.app.ui.theme.vantaSurfaceCard

/**
 * Modal bottom sheet letting the user choose between Groq cloud Whisper and local recognition
 * models/presets when importing an external audio or video URI.
 */
@Composable
internal fun ImportAudioSheet(
    importUri: Uri,
    settings: AppSettings,
    modelManager: ModelManager,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelManager: () -> Unit,
    onStartImport: (useGroqApi: Boolean, groqLanguageMode: String, presetId: String, beam: Int) -> Unit
) {
    val colors = LocalStenografColors.current
    val s = LocalStrings.current
    var importPresetId by remember(importUri) { mutableStateOf(settings.languagePresetId) }
    var importBeam by remember(importUri) { mutableIntStateOf(settings.asrBeam) }
    var importGroqLang by remember(importUri) { mutableStateOf(settings.groqLanguageMode) }
    val selectedPreset = TinyAsrCatalog.findPreset(importPresetId)
    val selectedModel = TinyAsrCatalog.findModel(selectedPreset.modelId)
    val modelInstalled = modelManager.isModelInstalled(selectedPreset.modelId) ||
        selectedModel.bundledInApk
    val groqReady = settings.groqApiKey.isNotBlank()

    VantaBottomSheet(
        onDismissRequest = onDismiss,
        title = s.importAudio,
        subtitle = s.reTranscribeDesc.takeIf { !selectedModel.isOnnxModel },
        headerIcon = Icons.Rounded.AutoFixHigh
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .vantaSurfaceCard(colors, radius = Radius.md, elevated = false)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        text = if (groqReady) "Groq API (${settings.groqModelId})" else "Groq API",
                        color = colors.accent,
                        style = VantaType.cardTitle
                    )
                    Text(
                        text = if (groqReady) {
                            "Быстрая облачная расшифровка Whisper."
                        } else {
                            "Укажите API-ключ Groq в настройках, чтобы использовать облачную расшифровку."
                        },
                        color = colors.textSecondary,
                        style = VantaType.bodySmall
                    )
                    if (groqReady) {
                        GroqLanguagePillRow(
                            selectedCode = importGroqLang,
                            onSelect = { importGroqLang = it }
                        )
                        VantaPrimaryButton(
                            label = "Расшифровать через Groq API",
                            icon = Icons.Rounded.AutoFixHigh,
                            onClick = {
                                onDismiss()
                                onStartImport(true, importGroqLang, importPresetId, importBeam)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        TextButton(onClick = {
                            onDismiss()
                            onOpenSettings()
                        }) {
                            Text(s.settingsTitle, color = colors.accent)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            item {
                if (!selectedModel.isOnnxModel) {
                    SectionHeader(s.decodingAccuracyTitle)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        StatusPill(
                            label = s.accuracyBeam4,
                            containerColor = if (importBeam == 4) colors.accentSoft else colors.surface,
                            contentColor = if (importBeam == 4) colors.accent else colors.textSecondary,
                            onClick = { importBeam = 4 }
                        )
                        StatusPill(
                            label = s.fastGreedy1,
                            containerColor = if (importBeam == 1) colors.accentSoft else colors.surface,
                            contentColor = if (importBeam == 1) colors.accent else colors.textSecondary,
                            onClick = { importBeam = 1 }
                        )
                    }

                    Spacer(Modifier.height(Spacing.sm))
                }
                SectionHeader(s.reTranscribeModelLang)
            }

            items(TinyAsrCatalog.FILE_PRESETS.size) { idx ->
                val preset = TinyAsrCatalog.FILE_PRESETS[idx]
                val inst = modelManager.isModelInstalled(preset.modelId) ||
                    TinyAsrCatalog.findModel(preset.modelId).bundledInApk
                SheetOptionRow(
                    selected = preset.id == importPresetId,
                    title = preset.label(s),
                    subtitle = if (inst) preset.modelId else "${preset.modelId} · ${s.modelAvailableLabel}",
                    onClick = { importPresetId = preset.id }
                )
            }

            item {
                Spacer(Modifier.height(Spacing.md))
                if (modelInstalled) {
                    VantaPrimaryButton(
                        label = s.startAction,
                        onClick = {
                            onDismiss()
                            onStartImport(false, importGroqLang, importPresetId, importBeam)
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
                                onDismiss()
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
