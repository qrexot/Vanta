package com.stenograf.app.service

import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.SpeechSegment
import com.stenograf.app.asr.TranscriptWord
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.delay

internal suspend fun drainGroqLiveAudio(
    input: RandomAccessFile,
    availableBytes: () -> Long,
    captureClosed: () -> Boolean,
    stopRequested: () -> Boolean,
    transcribe: suspend (ByteArray) -> GroqAsrClient.Transcript,
    onRetry: (IOException) -> Unit,
    capturePaused: () -> Boolean = { false },
    commit: suspend (Long, ByteArray, GroqAsrClient.Transcript) -> Unit
) {
    var cursor = 0L
    var precedingWords = emptyList<TranscriptWord>()
    while (true) {
        val closed = captureClosed()
        val flushing = closed || capturePaused() || stopRequested()
        val available = (availableBytes() - cursor) and -2L
        if (available < 2L && closed) return
        if (available < 2L || (!flushing && available < SEARCH_END + CONTEXT)) {
            delay(80L)
            continue
        }

        val ahead = ByteArray(minOf(available, SEARCH_END + CONTEXT).toInt())
        input.seek(cursor)
        input.readFully(ahead)
        val owned = if (flushing && ahead.size <= SEARCH_END) ahead.size else findSpeechBoundary(ahead)
        val pcm = ahead.copyOf(owned)
        val prefixSize = minOf(cursor, CONTEXT).toInt()
        val packet = ByteArray(prefixSize + minOf(ahead.size, owned + CONTEXT.toInt()))
        if (prefixSize > 0) {
            input.seek(cursor - prefixSize)
            input.readFully(packet, 0, prefixSize)
        }
        ahead.copyInto(packet, prefixSize, endIndex = packet.size - prefixSize)

        var backoffMs = 1_000L
        val parsed = retryGroqChunk(stopRequested, transcribe = { transcribe(packet) }) { error ->
            onRetry(error)
            delay(GroqAsrClient.reconnectDelayMs(error, backoffMs))
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
        val offset = cursor.toFloat() / RATE
        val trimmed = ownTranscriptWords(parsed, prefixSize.toFloat() / RATE, owned.toFloat() / RATE, precedingWords, offset)
        commit(cursor, pcm, trimmed)
        precedingWords = trimmed.segments.flatMap { it.words }.takeLast(6).map { it.copy(start = it.start + offset, end = it.end + offset) }
        cursor += owned
    }
}

private const val RATE = GroqAsrClient.PCM_BYTES_PER_SECOND
private const val CONTEXT = 24_000L // 750 ms on each side
private const val SEARCH_START = 11L * RATE
private const val SEARCH_END = 13L * RATE

internal fun findSpeechBoundary(pcm: ByteArray): Int {
    val target = minOf(pcm.size, GroqAsrClient.LIVE_CHUNK_BYTES) and -2
    if (pcm.size < SEARCH_END) return target
    val frame = 1_280 // 40 ms of PCM
    val energies = (SEARCH_START.toInt() until SEARCH_END.toInt() step frame).map { start ->
        var energy = 0L
        for (i in start until start + frame step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            energy += sample.toLong() * sample
        }
        energy / (frame / 2)
    }
    val silenceFloor = maxOf(180L * 180L, energies.sorted()[energies.size * 3 / 4] / 20)
    val quiet = (1 until energies.lastIndex).filter { i ->
        energies[i - 1] < silenceFloor && energies[i] < silenceFloor && energies[i + 1] < silenceFloor
    }.minByOrNull { kotlin.math.abs(SEARCH_START + it * frame - target) }
    return quiet?.let { SEARCH_START.toInt() + it * frame + frame / 2 } ?: target
}

internal fun ownTranscriptWords(
    parsed: GroqAsrClient.Transcript,
    prefix: Float,
    duration: Float,
    precedingWords: List<TranscriptWord> = emptyList(),
    offset: Float = 0f
): GroqAsrClient.Transcript {
    val candidates = parsed.segments.flatMap { it.words }.filter { (it.start + it.end) / 2f in prefix..<(prefix + duration) }
    fun normalized(word: TranscriptWord) = word.text.lowercase().trim { !it.isLetterOrDigit() }
    val repeated = (minOf(precedingWords.size, candidates.size) downTo 1).firstOrNull { count ->
        precedingWords.takeLast(count).zip(candidates.take(count)).all { (old, new) ->
            val key = normalized(old)
            key.isNotEmpty() && key == normalized(new) &&
                kotlin.math.abs((old.start + old.end - new.start - new.end) / 2f - offset + prefix) <= 0.3f
        }
    } ?: 0
    var remove = repeated
    val segments = parsed.segments.mapNotNull { segment ->
        if (segment.words.isEmpty()) {
            if ((segment.startSec + segment.endSec) / 2f !in prefix..<(prefix + duration)) null
            else segment.copy(startSec = (segment.startSec - prefix).coerceAtLeast(0f), endSec = (segment.endSec - prefix).coerceAtMost(duration))
        } else {
            val owned = segment.words.filter { (it.start + it.end) / 2f in prefix..<(prefix + duration) }
            val skipped = minOf(remove, owned.size)
            remove -= skipped
            val words = owned.drop(skipped).map {
                it.copy(start = (it.start - prefix).coerceIn(0f, duration), end = (it.end - prefix).coerceIn(0f, duration))
            }
            if (words.isEmpty()) null else SpeechSegment.fromWords(words, segment.speaker).let {
                if (words.size == segment.words.size) it.copy(text = segment.text) else it
            }
        }
    }
    return parsed.copy(segments = segments)
}

private suspend fun retryGroqChunk(
    stopRequested: () -> Boolean,
    transcribe: suspend () -> GroqAsrClient.Transcript,
    waitToRetry: suspend (IOException) -> Unit
): GroqAsrClient.Transcript {
    while (true) {
        try {
            return transcribe()
        } catch (error: IOException) {
            if (stopRequested()) throw error
            waitToRetry(error)
        }
    }
}
