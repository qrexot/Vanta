package com.stenograf.app.asr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class OfflineGigaAmV4Result(
    val language: String?,
    val words: List<TranscriptWord>
)

@Serializable
private data class RawOfflineGigaAmV4Result(
    val language: String? = null,
    val words: List<TranscriptWord> = emptyList()
)

object GigaAmV4Native {
    init {
        System.loadLibrary("tinyasr_jni")
    }

    external fun nativeLoadOnline(
        bundleDir: String,
        threads: Int,
        errOut: ByteArray
    ): Long

    external fun nativeFreeOnline(handle: Long)

    external fun nativeStreamCreate(onlineHandle: Long): Long

    external fun nativeStreamPushPcm16(
        streamHandle: Long,
        pcm: ShortArray,
        offset: Int,
        length: Int
    ): String?

    external fun nativeStreamFinish(streamHandle: Long): String?

    external fun nativeStreamFree(streamHandle: Long)

    external fun nativeLoadOffline(
        bundleDir: String,
        threads: Int,
        errOut: ByteArray
    ): Long

    external fun nativeFreeOffline(handle: Long)

    external fun nativeOfflineTranscribePcm16(
        offlineHandle: Long,
        pcm: ShortArray,
        offset: Int,
        length: Int,
        errOut: ByteArray
    ): String?
}

internal object GigaAmV4JsonParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decodeError(errOut: ByteArray, fallback: String = ""): String {
        val raw = errOut.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
        return raw.ifBlank { fallback }
    }

    fun parseOfflineResult(rawJson: String?): OfflineGigaAmV4Result? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val raw = json.decodeFromString<RawOfflineGigaAmV4Result>(rawJson)
            OfflineGigaAmV4Result(
                language = raw.language?.takeIf { it.isNotBlank() },
                words = raw.words
            )
        } catch (_: Throwable) {
            null
        }
    }
}
