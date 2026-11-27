package com.stenograf.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.TranscriptReviewModel
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.i18n.AppStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "stenograf_prefs")

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT;

    fun label(strings: AppStrings): String = when (this) {
        SYSTEM -> strings.themeSystem
        DARK -> strings.themeDark
        LIGHT -> strings.themeLight
    }
}

enum class AsrEngineMode {
    LOCAL_TINYASR,
    GROQ_API
}

data class AppSettings(
    val uiLanguageCode: String = "system",
    val languagePresetId: String = "ru_only",
    val audioFormat: AudioFormat = AudioFormat.WAV_16K,
    val useRawMicrophone: Boolean = true,
    val distantSpeechBoost: Boolean = true,
    val asrThreads: Int = 0, // 0 = auto (big cores)
    val asrBeam: Int = 4, // 4 = high accuracy beam search, 1 = greedy fast
    val autoParagraph: Boolean = true,
    val showTimestamps: Boolean = true,
    val keepScreenOnRecording: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val readerSerif: Boolean = false,
    val readerFontSizeTier: Int = 1,
    val asrEngineMode: AsrEngineMode = AsrEngineMode.LOCAL_TINYASR,
    val groqApiKey: String = "",
    val groqModelId: String = GroqAsrClient.DEFAULT_MODEL_ID,
    val groqLanguageMode: String = GroqAsrClient.DEFAULT_LANGUAGE_MODE,
    val transcriptReviewModelId: String = TranscriptReviewModel.Detailed.id,
    val speakerDiarization: Boolean = true
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val UI_LANGUAGE = stringPreferencesKey("ui_language_code")
        val LANG_PRESET = stringPreferencesKey("language_preset_id")
        val AUDIO_FORMAT = stringPreferencesKey("audio_format")
        val RAW_MIC = booleanPreferencesKey("use_raw_microphone")
        val DISTANT_SPEECH_BOOST = booleanPreferencesKey("distant_speech_boost")
        val ASR_THREADS = intPreferencesKey("asr_threads")
        val ASR_BEAM = intPreferencesKey("asr_beam")
        val AUTO_PARAGRAPH = booleanPreferencesKey("auto_paragraph")
        val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_recording")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val READER_SERIF = booleanPreferencesKey("reader_serif")
        val READER_FONT_SIZE_TIER = intPreferencesKey("reader_font_size_tier")
        val ASR_ENGINE_MODE = stringPreferencesKey("asr_engine_mode")
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        val GROQ_MODEL_ID = stringPreferencesKey("groq_model_id")
        val GROQ_LANGUAGE_MODE = stringPreferencesKey("groq_language_mode")
        val TRANSCRIPT_REVIEW_MODEL = stringPreferencesKey("transcript_review_model")
        val SPEAKER_DIARIZATION = booleanPreferencesKey("speaker_diarization")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            uiLanguageCode = prefs[Keys.UI_LANGUAGE] ?: "system",
            languagePresetId = prefs[Keys.LANG_PRESET] ?: "ru_only",
            audioFormat = runCatching {
                AudioFormat.valueOf(prefs[Keys.AUDIO_FORMAT] ?: AudioFormat.WAV_16K.name)
            }.getOrDefault(AudioFormat.WAV_16K),
            useRawMicrophone = prefs[Keys.RAW_MIC] ?: true,
            distantSpeechBoost = prefs[Keys.DISTANT_SPEECH_BOOST] ?: true,
            asrThreads = prefs[Keys.ASR_THREADS] ?: 0,
            asrBeam = prefs[Keys.ASR_BEAM] ?: 4,
            autoParagraph = prefs[Keys.AUTO_PARAGRAPH] ?: true,
            showTimestamps = prefs[Keys.SHOW_TIMESTAMPS] ?: true,
            keepScreenOnRecording = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name)
            }.getOrDefault(ThemeMode.DARK),
            readerSerif = prefs[Keys.READER_SERIF] ?: false,
            readerFontSizeTier = (prefs[Keys.READER_FONT_SIZE_TIER] ?: 1).coerceIn(0, 2),
            asrEngineMode = when (prefs[Keys.ASR_ENGINE_MODE]) {
                "GEMINI_API", AsrEngineMode.GROQ_API.name -> AsrEngineMode.GROQ_API
                else -> AsrEngineMode.LOCAL_TINYASR
            },
            groqApiKey = prefs[Keys.GROQ_API_KEY] ?: "",
            groqModelId = GroqAsrClient.resolveModel(prefs[Keys.GROQ_MODEL_ID]),
            groqLanguageMode = GroqAsrClient.resolveLanguageMode(prefs[Keys.GROQ_LANGUAGE_MODE]),
            transcriptReviewModelId = TranscriptReviewModel.resolve(prefs[Keys.TRANSCRIPT_REVIEW_MODEL]).id,
            speakerDiarization = prefs[Keys.SPEAKER_DIARIZATION]
                ?: prefs[booleanPreferencesKey("groq_speaker_diarization")]
                ?: prefs[booleanPreferencesKey("gemini_speaker_diarization")] ?: true
        )
    }

    internal suspend fun setTranscriptReviewModel(model: TranscriptReviewModel) {
        context.dataStore.edit { it[Keys.TRANSCRIPT_REVIEW_MODEL] = model.id }
    }

    suspend fun setUiLanguageCode(code: String) {
        context.dataStore.edit { it[Keys.UI_LANGUAGE] = code }
    }

    suspend fun setLanguagePreset(id: String) {
        context.dataStore.edit { it[Keys.LANG_PRESET] = id }
    }

    suspend fun setAudioFormat(format: AudioFormat) {
        context.dataStore.edit { it[Keys.AUDIO_FORMAT] = format.name }
    }

    suspend fun setUseRawMicrophone(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RAW_MIC] = enabled }
    }

    suspend fun setDistantSpeechBoost(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DISTANT_SPEECH_BOOST] = enabled }
    }

    suspend fun setAsrThreads(threads: Int) {
        context.dataStore.edit { it[Keys.ASR_THREADS] = threads.coerceIn(0, 8) }
    }

    suspend fun setAsrBeam(beam: Int) {
        context.dataStore.edit { it[Keys.ASR_BEAM] = if (beam == 1) 1 else 4 }
    }

    suspend fun setAutoParagraph(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PARAGRAPH] = enabled }
    }

    suspend fun setShowTimestamps(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_TIMESTAMPS] = enabled }
    }

    suspend fun setKeepScreenOnRecording(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setAsrEngineMode(mode: AsrEngineMode) {
        context.dataStore.edit { it[Keys.ASR_ENGINE_MODE] = mode.name }
    }

    suspend fun setGroqApiKey(apiKey: String) {
        context.dataStore.edit {
            it.remove(stringPreferencesKey("gemini_api_key"))
            it[Keys.GROQ_API_KEY] = apiKey.trim()
        }
    }

    suspend fun setGroqModelId(modelId: String) {
        context.dataStore.edit { it[Keys.GROQ_MODEL_ID] = modelId }
    }

    suspend fun setGroqLanguageMode(mode: String) {
        context.dataStore.edit { it[Keys.GROQ_LANGUAGE_MODE] = GroqAsrClient.resolveLanguageMode(mode) }
    }

    suspend fun setSpeakerDiarization(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPEAKER_DIARIZATION] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setReaderSerif(enabled: Boolean) {
        context.dataStore.edit { it[Keys.READER_SERIF] = enabled }
    }

    suspend fun setReaderFontSizeTier(tier: Int) {
        context.dataStore.edit { it[Keys.READER_FONT_SIZE_TIER] = tier.coerceIn(0, 2) }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
