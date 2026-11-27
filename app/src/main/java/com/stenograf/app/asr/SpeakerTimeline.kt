package com.stenograf.app.asr

internal data class SpeechSegment(
    val startSec: Float,
    val endSec: Float,
    val text: String,
    val words: List<TranscriptWord>,
    val speaker: String? = null
) {
    companion object {
        fun fromWords(words: List<TranscriptWord>, speaker: String? = null) =
            SpeechSegment(words.first().start, words.maxOf { it.end }, AsrTextFormatter.joinWords(words), words, speaker)
    }
}

internal data class SpeakerTurn(val start: Float, val end: Float, val cluster: Int, val label: String?)

internal class SpeakerTimeline(private val turns: List<SpeakerTurn>) {
    private fun speakerAt(start: Float, end: Float): String? {
        val overlaps = turns.groupingBy { it.label ?: it.cluster }.fold(0f) { total, turn ->
            total + (minOf(end, turn.end) - maxOf(start, turn.start)).coerceAtLeast(0f)
        }.entries.sortedByDescending { it.value }
        val best = overlaps.firstOrNull() ?: return null
        val next = overlaps.getOrNull(1)?.value ?: 0f
        if (best.value >= (end - start) * 0.25f && best.value > next * 1.25f) {
            return best.key as? String
        }
        if (best.value == 0f) {
            val nearest = turns.minByOrNull { maxOf(it.start - end, start - it.end, 0f) }
            if (nearest != null && maxOf(nearest.start - end, start - nearest.end) <= 0.24f) return nearest.label
        }
        return null
    }

    private fun isSameVoiceGap(start: Float, end: Float, label: String): Boolean =
        turns.none { it.label != label && it.start < end && it.end > start } &&
            turns.any { it.label == label && it.start <= start + 0.35f && it.end >= end - 0.35f }

    fun assign(segments: List<SpeechSegment>, offsetSec: Float = 0f): List<SpeechSegment> {
        if (turns.isEmpty()) return segments
        return segments.flatMap { segment ->
            val words = segment.words
            val labels = words.map { speakerAt(it.start - offsetSec, it.end - offsetSec) }
            val known = labels.filterNotNull().toSet()
            val segmentSpeaker = speakerAt(segment.startSec - offsetSec, segment.endSec - offsetSec)
            if (known.size <= 1) {
                val knownWords = labels.count { it != null }
                val speaker = segmentSpeaker ?: known.singleOrNull()?.takeIf {
                    knownWords * 2 >= words.size || isSameVoiceGap(segment.startSec - offsetSec, segment.endSec - offsetSec, it)
                }
                return@flatMap listOf(segment.copy(speaker = speaker))
            }

            val groups = mutableListOf<Pair<String?, MutableList<TranscriptWord>>>()
            val nextKnown = arrayOfNulls<String>(words.size)
            var upcoming: String? = null
            for (index in words.indices.reversed()) {
                nextKnown[index] = upcoming
                upcoming = labels[index] ?: upcoming
            }
            for ((index, word) in words.withIndex()) {
                val before = groups.lastOrNull()
                val after = nextKnown[index]
                val speaker = labels[index] ?: if (before == null || before.first == after) after else before.first
                if (before != null && before.first == speaker) before.second.add(word)
                else groups.add(speaker to mutableListOf(word))
            }
            groups.map { (speaker, group) -> SpeechSegment.fromWords(group, speaker) }
        }
    }

    companion object {
        val Empty = SpeakerTimeline(emptyList())
    }
}
