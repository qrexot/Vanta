package com.stenograf.app.asr

/**
 * Aligns TinyASR's incremental word commits with a bounded, cached speaker timeline.
 * Pending words remain available to the recording screen while the audio window fills.
 */
internal class LocalSpeakerLabeler(
    private val firstByte: Long,
    windowSeconds: Int = 12,
    private val analyze: (startByte: Long, byteCount: Int) -> SpeakerTimeline
) {
    private data class Window(val startByte: Long, val endByte: Long, val timeline: SpeakerTimeline)

    private val windowBytes = windowSeconds * PCM_BYTES_PER_SECOND
    private val pending = ArrayDeque<TranscriptWord>()
    private val windows = ArrayDeque<Window>()

    fun offer(words: List<TranscriptWord>) {
        pending.addAll(words)
    }

    fun draftWords(): List<TranscriptWord> = pending.toList()

    fun drain(
        availableBytes: Long,
        finished: Boolean = false,
        emit: (SpeechSegment) -> Unit
    ) {
        while (pending.isNotEmpty()) {
            val position = midpointByte(pending.first())
            val start = firstByte + (position - firstByte) / windowBytes * windowBytes
            val end = start + windowBytes
            if (end > availableBytes && !finished) return

            val processedEnd = minOf(end, availableBytes)
            val timeline = if (processedEnd <= start) SpeakerTimeline.Empty else {
                windows.firstOrNull { it.startByte == start && it.endByte == processedEnd }?.timeline
                    ?: analyze(start, (processedEnd - start).toInt()).also {
                        if (windows.size == 2) windows.removeFirst()
                        windows.addLast(Window(start, processedEnd, it))
                    }
            }
            val words = buildList {
                while (pending.isNotEmpty() && midpointByte(pending.first()) < end) {
                    add(pending.removeFirst())
                }
            }
            timeline.assign(listOf(SpeechSegment.fromWords(words)), start.toFloat() / PCM_BYTES_PER_SECOND)
                .forEach(emit)
        }
    }

    private fun midpointByte(word: TranscriptWord): Long =
        (((word.start + word.end) / 2f) * PCM_BYTES_PER_SECOND).toLong().coerceAtLeast(firstByte) and -2L

    companion object {
        const val PCM_BYTES_PER_SECOND = 32_000L
    }
}
