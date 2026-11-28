package com.stenograf.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stenograf.app.audio.AudioTreatment
import com.stenograf.app.data.prefs.RecognitionAudioFilter
import com.stenograf.app.ui.components.SheetOptionRow
import com.stenograf.app.ui.components.VantaBottomSheet
import com.stenograf.app.ui.theme.LocalStenografColors
import com.stenograf.app.ui.theme.Spacing
import com.stenograf.app.ui.theme.VantaType

internal data class AudioFilterGuide(val title: String, val model: String, val description: String)

internal fun AudioTreatment.guide(russian: Boolean): AudioFilterGuide = when (this) {
    AudioTreatment.QUICK_CLEANUP -> AudioFilterGuide(
        if (russian) "Быстрая очистка" else "Quick cleanup", "GTCRN",
        if (russian) "Для гула, вентилятора и ровного фонового шума. Уже в приложении."
        else "For hum, fans and steady background noise. Built in."
    )
    AudioTreatment.DEEP_FILTER_3 -> AudioFilterGuide(
        if (russian) "Фоновый шум" else "Background noise", "DeepFilterNet3",
        if (russian) "Начните с него, если речь заглушает шум улицы или вентиляции."
        else "Start here when traffic or ventilation makes speech hard to hear."
    )
    AudioTreatment.ROOM_ECHO -> AudioFilterGuide(
        if (russian) "Эхо в помещении" else "Room echo", "FRCRN",
        if (russian) "Попробуйте для лекции в гулкой аудитории, где голос отдаётся эхом."
        else "Try this for a lecture in a reverberant room where the voice echoes."
    )
    AudioTreatment.TWO_VOICES -> AudioFilterGuide(
        if (russian) "Два голоса одновременно" else "Two overlapping voices", "MossFormer2",
        if (russian) "Разделяет до 6 секунд от позиции плеера на две дорожки."
        else "Splits up to six seconds from the playhead into two tracks."
    )
    AudioTreatment.DEEP_FILTER_2 -> AudioFilterGuide(
        if (russian) "Альтернативная очистка" else "Alternative cleanup", "DeepFilterNet2",
        if (russian) "Попробуйте, если результат DeepFilterNet3 звучит неестественно."
        else "Try it if the result from DeepFilterNet3 sounds unnatural."
    )
}

internal fun RecognitionAudioFilter.title(russian: Boolean): String = when (this) {
    RecognitionAudioFilter.STANDARD -> if (russian) "Стандартный · рекомендуется" else "Standard · recommended"
    RecognitionAudioFilter.FAST -> if (russian) "Быстрый" else "Fast"
    RecognitionAudioFilter.OFF -> if (russian) "Выключен" else "Off"
}

internal fun RecognitionAudioFilter.description(russian: Boolean): String = when (this) {
    RecognitionAudioFilter.STANDARD -> if (russian) "GTCRN · для речи на фоне ровного шума."
        else "GTCRN · for speech with steady background noise."
    RecognitionAudioFilter.FAST -> if (russian) "FastEnhancer · попробуйте, если текст отстаёт во время записи."
        else "FastEnhancer · try this when live text falls behind."
    RecognitionAudioFilter.OFF -> if (russian) "Подходит для чистого звука или если фильтр искажает речь."
        else "Use with clean audio or when filtering distorts speech."
}


@Composable
internal fun RecognitionFilterSheet(
    current: RecognitionAudioFilter,
    russian: Boolean,
    onChoose: (RecognitionAudioFilter) -> Unit,
    onDismiss: () -> Unit
) {
    VantaBottomSheet(
        onDismissRequest = onDismiss,
        title = if (russian) "Шумоподавление для текста" else "Noise filter for transcription",
        subtitle = if (russian) "Все режимы встроены. Применяются для распознавания речи."
        else "All modes are built in and apply to speech recognition."
    ) {
        RecognitionAudioFilter.entries.forEach { filter ->
            SheetOptionRow(
                selected = filter == current,
                title = filter.title(russian),
                subtitle = filter.description(russian),
                onClick = { onChoose(filter) }
            )
        }
        Text(
            if (russian) "Для очистки файла нажмите «Фильтры» в плеере записи."
            else "To clean the audio file, tap Filters in the recording player.",
            color = LocalStenografColors.current.textSecondary,
            style = VantaType.bodySmall,
            modifier = Modifier.padding(top = Spacing.md)
        )
    }
}
