package com.stenograf.app.asr

import com.stenograf.app.audio.WavHelper
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal object GroqAsrClient {
    const val DEFAULT_MODEL_ID = "whisper-large-v3-turbo"
    const val DEFAULT_LANGUAGE_MODE = "multi"
    const val PCM_BYTES_PER_SECOND = 32_000
    const val LIVE_CHUNK_BYTES = 12 * PCM_BYTES_PER_SECOND

    private const val BILINGUAL_RU_EN_PROMPT =
        "Привет! Let's check the project settings, audio files, and API. Русский язык and English speech."

    val SUPPORTED_MODELS = listOf(
        DEFAULT_MODEL_ID to "Whisper Large v3 Turbo",
        "whisper-large-v3" to "Whisper Large v3"
    )

    val SUPPORTED_LANGUAGE_MODES = listOf(
        "multi" to "Русский + English (Авто)",
        "auto" to "Авто (все языки)",
        "ru" to "Русский",
        "en" to "English",
        "es" to "Español",
        "de" to "Deutsch",
        "fr" to "Français",
        "uk" to "Українська",
        "it" to "Italiano",
        "pt" to "Português",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "kk" to "Қазақша",
        "uz" to "Oʻzbekcha"
    )

    /**
     * Resolves the Groq Whisper language mode for audio re-recognition.
     * "Язык по записи" (forceRussian = false) never inherits TinyASR's "ru_only" lock;
     * it defaults to true multilingual auto-detection ("auto") unless the user picked an explicit override.
     */
    fun resolveReviewLanguageMode(
        forceRussian: Boolean,
        explicitOverride: String? = null
    ): String {
        if (forceRussian) return "ru"
        val cleaned = explicitOverride?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleaned != null) resolveLanguageMode(cleaned) else "auto"
    }

    /**
     * Resolves the local TinyASR preset for fragment re-recognition.
     * When "Язык по записи" is chosen on a recording created with the default "ru_only" preset,
     * widens to "ru_en" on the same bundled model so non-Russian phrases can be recognized.
     */
    fun resolveLocalReviewPreset(
        recordingPresetId: String,
        forceRussian: Boolean
    ): String {
        if (forceRussian) return "ru_only"
        val resolved = TinyAsrCatalog.findPreset(recordingPresetId).id
        return if (resolved == "ru_only") "ru_en" else resolved
    }

    private const val BASE_URL = "https://api.groq.com/openai/v1"
    private const val REQUEST_SPACING_MS = 3_100L
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val requestGate = Mutex()
    private var lastRequestNs: Long? = null

    data class Transcript(val language: String, val segments: List<SpeechSegment>)

    @Serializable
    private data class GroqWord(val word: String, val start: Float, val end: Float)
    @Serializable
    private data class GroqSegment(val text: String, val start: Float, val end: Float)
    @Serializable
    private data class GroqTranscript(
        val language: String = "",
        val text: String? = null,
        val segments: List<GroqSegment>? = null,
        val words: List<GroqWord> = emptyList()
    )
    @Serializable
    private data class GroqModel(val id: String)
    @Serializable
    private data class GroqModels(val data: List<GroqModel>)

    private class InvalidTranscript(cause: SerializationException) :
        IOException("Groq вернул неполный ответ. Аудио сохранено.", cause)

    private class InvalidKey : IOException("Укажите API-ключ Groq (gsk_…) в настройках.")

    private class HttpFailure(val status: Int, val retryAfterMs: Long?, message: String) : IOException(message)

    fun resolveModel(id: String?): String = SUPPORTED_MODELS.firstOrNull { it.first == id }?.first
        ?: DEFAULT_MODEL_ID

    fun resolveLanguageMode(mode: String?): String = SUPPORTED_LANGUAGE_MODES.firstOrNull { it.first == mode }?.first
        ?: DEFAULT_LANGUAGE_MODE

    fun languageLabel(mode: String?): String {
        val resolved = resolveLanguageMode(mode)
        return SUPPORTED_LANGUAGE_MODES.firstOrNull { it.first == resolved }?.second ?: "Русский + English (Авто)"
    }

    fun errorMessage(error: IOException): String = when (error) {
        is InvalidKey, is InvalidTranscript, is HttpFailure -> error.message ?: "Ошибка Groq."
        else -> "Нет связи с Groq. Проверьте подключение к интернету."
    }

    fun reconnectDelayMs(error: IOException, backoffMs: Long): Long = when (error) {
        is InvalidKey -> maxOf(backoffMs, 60_000L)
        is HttpFailure -> when (error.status) {
            429 -> maxOf(backoffMs, error.retryAfterMs ?: 60_000L)
            408, in 500..599 -> maxOf(backoffMs, error.retryAfterMs ?: 0L)
            else -> maxOf(backoffMs, 60_000L)
        }
        else -> backoffMs
    }

    suspend fun fetchAvailableModels(apiKey: String): Result<List<Pair<String, String>>> = try {
        val request = request("models", apiKey).build()
        val ids = json.decodeFromString<GroqModels>(execute(request)).data.map { it.id }.toSet()
        val available = SUPPORTED_MODELS.filter { it.first in ids }
        if (available.isEmpty()) throw IOException("Groq: для этого ключа нет доступных моделей Whisper.")
        Result.success(available)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun transcribeChunk(
        apiKey: String,
        modelId: String,
        pcm: ByteArray,
        languageMode: String = DEFAULT_LANGUAGE_MODE,
        retryOnce: Boolean = true
    ): Transcript {
        if (!hasSignal(pcm)) return Transcript("", emptyList())
        val wav = WavHelper.createHeader(16_000, 1, 16, pcm.size.toLong()) + pcm
        val request = buildTranscribeRequest(apiKey, modelId, wav, languageMode)
        val response = executeWithRetry(request, retryOnce = retryOnce, rateLimited = true)
        return try {
            parseTranscript(response, pcm.size.toFloat() / PCM_BYTES_PER_SECOND)
        } catch (e: SerializationException) {
            throw InvalidTranscript(e)
        }
    }

    internal fun buildTranscribeRequest(
        apiKey: String,
        modelId: String,
        wav: ByteArray,
        languageMode: String = DEFAULT_LANGUAGE_MODE
    ): Request {
        val resolvedLang = resolveLanguageMode(languageMode)
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", resolveModel(modelId))
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("temperature", "0")
        when (resolvedLang) {
            "multi" -> bodyBuilder.addFormDataPart("prompt", BILINGUAL_RU_EN_PROMPT)
            "auto" -> Unit
            else -> bodyBuilder.addFormDataPart("language", resolvedLang)
        }
        return request("audio/transcriptions", apiKey).post(bodyBuilder.build()).build()
    }

    internal suspend fun executeWithRetry(
        request: Request,
        retryOnce: Boolean = true,
        rateLimited: Boolean = false
    ): String {
        suspend fun send(): String = if (rateLimited) transcribeRequest(request) else execute(request)
        return try {
            send()
        } catch (e: IOException) {
            if (!retryOnce) throw e
            val wait = when (e) {
                is HttpFailure -> when {
                    e.status == 429 -> e.retryAfterMs?.takeIf { it in 0L..8_000L }
                    e.status in 500..599 -> 600L
                    else -> null
                }
                is InvalidKey -> null
                else -> 600L
            } ?: throw e
            delay(wait)
            send()
        }
    }

    internal fun parseTranscript(response: String, duration: Float): Transcript {
        val parsed = json.decodeFromString<GroqTranscript>(response)
        if (parsed.text == null && parsed.segments == null) {
            throw SerializationException("Missing transcript text and segments")
        }
        val wordsByTime = parsed.words.mapNotNull { word ->
            val text = word.word.trim()
            val start = word.start.coerceIn(0f, duration)
            val end = word.end.coerceIn(start, duration)
            if (text.isEmpty() || end <= start) null else TranscriptWord(text, start, end)
        }.sortedBy { it.start }
        val segments = parsed.segments.orEmpty().ifEmpty {
            listOf(GroqSegment(parsed.text.orEmpty(), 0f, duration))
        }
        val result = segments.mapNotNull { segment ->
            val start = segment.start.coerceIn(0f, duration)
            val end = segment.end.coerceIn(start, duration)
            val text = segment.text.trim()
            if (text.isEmpty() || end <= start) return@mapNotNull null
            val words = wordsByTime.filter { (it.start + it.end) / 2f in start..<end }
            SpeechSegment(start, end, text, words.ifEmpty { estimateWords(text, start, end) })
        }.sortedBy { it.startSec }
        return Transcript(normalizeLanguage(parsed.language), result)
    }

    private suspend fun transcribeRequest(request: Request): String {
        requestGate.withLock {
            lastRequestNs?.let {
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it)
                delay((REQUEST_SPACING_MS - elapsedMs).coerceAtLeast(0))
            }
            lastRequestNs = System.nanoTime()
        }
        return execute(request)
    }

    internal fun request(path: String, apiKey: String): Request.Builder {
        val key = apiKey.trim()
        if (!key.startsWith("gsk_") || key.any { it.isWhitespace() || it !in '!'..'~' }) {
            throw InvalidKey()
        }
        return Request.Builder().url("$BASE_URL/$path").header("Authorization", "Bearer $key")
    }

    internal suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) {
                            val message = when (it.code) {
                                401, 403 -> "Groq отклонил ключ. Проверьте его в настройках."
                                429 -> "Достигнут лимит Groq."
                                in 500..599 -> "Сервис Groq временно недоступен (HTTP ${it.code})."
                                else -> "Ошибка Groq (HTTP ${it.code})."
                            }
                            val retryAfterMs = it.header("Retry-After")?.toLongOrNull()
                                ?.takeIf { seconds -> seconds >= 0 }
                                ?.let(TimeUnit.SECONDS::toMillis)
                            throw HttpFailure(it.code, retryAfterMs, message)
                        }
                        it.body?.string() ?: throw IOException("Groq вернул пустой ответ.")
                    }
                    continuation.resume(body)
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun hasSignal(pcm: ByteArray): Boolean {
        // Require multiple samples above low room noise floor to skip dead silence without dropping quiet distant speech.
        var aboveFloor = 0
        for (i in 0 until pcm.size - 1 step 2) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            if (kotlin.math.abs(sample) > 40 && ++aboveFloor >= 48) return true
        }
        return false
    }

    private fun estimateWords(text: String, start: Float, end: Float): List<TranscriptWord> {
        val tokens = text.split(Regex("\\s+"))
        val span = (end - start) / tokens.sumOf { it.length }
        var cursor = start
        return tokens.map { token ->
            val next = (cursor + span * token.length).coerceAtMost(end)
            TranscriptWord(token, cursor, next).also { cursor = next }
        }
    }

    private val languageCodes = Locale.getISOLanguages().associateBy {
        Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)
    }

    private fun normalizeLanguage(language: String): String {
        val value = language.trim().lowercase(Locale.ROOT)
        return languageCodes[value] ?: value
    }
}
