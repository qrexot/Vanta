package com.stenograf.app.asr

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptWord(
    val text: String,
    val start: Float,
    val end: Float,
    val join: Boolean = false
)

sealed interface AsrEvent {
    data class Commit(val words: List<TranscriptWord>) : AsrEvent
    data class Partial(val open: List<TranscriptWord>, val tail: List<TranscriptWord>) : AsrEvent
    data class Language(val lang: String) : AsrEvent
    data class Eou(val at: Float, val p: Float) : AsrEvent
}

@Serializable
private data class RawAsrEvent(
    val type: String,
    val words: List<TranscriptWord> = emptyList(),
    val open: List<TranscriptWord> = emptyList(),
    val tail: List<TranscriptWord> = emptyList(),
    val lang: String? = null,
    val at: Float? = null,
    val p: Float? = null
)

object AsrJsonParser {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseEvents(rawJson: String?): List<AsrEvent> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val list = json.decodeFromString<List<RawAsrEvent>>(rawJson)
            list.mapNotNull { item ->
                when (item.type) {
                    "commit" -> AsrEvent.Commit(item.words)
                    "partial" -> AsrEvent.Partial(item.open, item.tail)
                    "language" -> item.lang?.let { AsrEvent.Language(it) }
                    "eou" -> if (item.at != null && item.p != null) AsrEvent.Eou(item.at, item.p) else null
                    else -> null
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseWords(rawJson: String?): List<TranscriptWord> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<TranscriptWord>>(rawJson)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun encodeWords(words: List<TranscriptWord>): String {
        return json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TranscriptWord.serializer()), words)
    }
}
