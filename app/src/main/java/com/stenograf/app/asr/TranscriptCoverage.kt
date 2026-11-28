package com.stenograf.app.asr

import com.stenograf.app.data.model.ParagraphEntity
import kotlin.math.ceil
import kotlin.math.floor

internal data class SpeechGap(val startMs: Long, val endMs: Long) {
    val startSec get() = startMs / 1000f
    val endSec get() = endMs / 1000f

    fun overlaps(other: SpeechGap): Boolean = startMs < other.endMs && other.startMs < endMs
}

internal object TranscriptCoverage {
    private const val WORD_PADDING_MS = 180L
    private const val MIN_GAP_MS = 1_200L
    private const val MAX_GAP_MS = 25_000L

    fun coveredRanges(paragraph: ParagraphEntity): List<SpeechGap> {
        if (paragraph.text.isBlank()) return emptyList()
        val words = splittableWords(paragraph)
        if (words.isEmpty()) return listOfNotNull(timeRange(paragraph.startSec, paragraph.endSec))
        return words.mapNotNull { timeRange(it.start, it.end) }
    }

    // Edited/legacy paragraphs may have missing or mismatched word timestamps.
    // In that case keep their whole interval covered and leave their text intact.
    fun splittableWords(paragraph: ParagraphEntity): List<TranscriptWord> {
        val words = AsrJsonParser.parseWords(paragraph.wordsJson)
        if (words.isEmpty() || AsrTextFormatter.joinWords(words) != paragraph.text.trim()) return emptyList()
        if (words.any { !it.start.isFinite() || !it.end.isFinite() || it.start < 0f || it.end < it.start }) {
            return emptyList()
        }
        if (words.zipWithNext().any { (a, b) -> a.start > b.start }) return emptyList()
        if (words.first().start < paragraph.startSec - 0.08f ||
            words.maxOf { it.end } > paragraph.endSec + 0.08f
        ) return emptyList()
        return words
    }

    fun missingSpeech(
        speech: List<SpeechGap>,
        paragraphs: List<ParagraphEntity>,
        limitMs: Long
    ): List<SpeechGap> {
        if (limitMs < MIN_GAP_MS) return emptyList()
        val covered = merge(paragraphs.flatMap(::coveredRanges).map {
            SpeechGap((it.startMs - WORD_PADDING_MS).coerceAtLeast(0L), it.endMs + WORD_PADDING_MS)
        })
        val voiced = merge(speech.mapNotNull {
            val start = it.startMs.coerceIn(0L, limitMs)
            val end = it.endMs.coerceIn(start, limitMs)
            SpeechGap(start, end).takeIf { end > start }
        })
        val result = mutableListOf<SpeechGap>()
        var coveredIndex = 0
        for (voice in voiced) {
            var cursor = voice.startMs
            while (coveredIndex < covered.size) {
                val known = covered[coveredIndex]
                if (known.endMs <= cursor) {
                    coveredIndex++
                    continue
                }
                if (known.startMs >= voice.endMs) break
                addGap(result, cursor, known.startMs)
                cursor = maxOf(cursor, known.endMs)
                if (cursor >= voice.endMs) break
                coveredIndex++
            }
            addGap(result, cursor, voice.endMs)
        }
        return result
    }

    private fun addGap(result: MutableList<SpeechGap>, start: Long, end: Long) {
        val length = end - start
        if (length < MIN_GAP_MS) return
        val parts = (length + MAX_GAP_MS - 1) / MAX_GAP_MS
        for (i in 0 until parts) {
            result += SpeechGap(start + length * i / parts, start + length * (i + 1) / parts)
        }
    }

    private fun merge(ranges: List<SpeechGap>): List<SpeechGap> {
        val merged = mutableListOf<SpeechGap>()
        for (range in ranges.sortedBy { it.startMs }) {
            val previous = merged.lastOrNull()
            if (previous == null || range.startMs > previous.endMs) {
                merged += range
            } else if (range.endMs > previous.endMs) {
                merged[merged.lastIndex] = previous.copy(endMs = range.endMs)
            }
        }
        return merged
    }

    fun timeRange(start: Float, end: Float): SpeechGap? {
        if (!start.isFinite() || !end.isFinite() || start < 0f || end < start) return null
        val from = floor(start.toDouble() * 1000).toLong()
        val to = maxOf(from + 1, ceil(end.toDouble() * 1000).toLong())
        return SpeechGap(from, to)
    }
}

/** Returns a chronological insertion plan using the current transcript. Existing IDs stay in place. */
internal fun planGapInsertion(
    recordingId: Long,
    paragraphs: List<ParagraphEntity>,
    gap: SpeechGap,
    words: List<TranscriptWord>
): List<ParagraphEntity>? {
    if (gap.startMs < 0L || gap.endMs <= gap.startMs || words.isEmpty()) return null
    if (words.any {
            it.text.isBlank() || !it.start.isFinite() || !it.end.isFinite() ||
                it.start < gap.startSec || it.end > gap.endSec || it.end < it.start
        } || words.zipWithNext().any { (a, b) -> a.start > b.start }
    ) return null
    val start = words.first().start
    val end = words.maxOf { it.end }
    val insertedRange = TranscriptCoverage.timeRange(start, end) ?: return null
    if (paragraphs.any { TranscriptCoverage.coveredRanges(it).any(insertedRange::overlaps) }) return null

    val inserted = ParagraphEntity(
        recordingId = recordingId, orderIndex = 0, startSec = start, endSec = end,
        text = AsrTextFormatter.joinWords(words), wordsJson = AsrJsonParser.encodeWords(words)
    )
    val updated = mutableListOf<ParagraphEntity>()
    for (paragraph in paragraphs) {
        if (paragraph.text.isNotBlank() && paragraph.startSec < start && paragraph.endSec > end) {
            val originalWords = TranscriptCoverage.splittableWords(paragraph)
            val splitAt = originalWords.indexOfFirst { it.start >= end }
            if (splitAt <= 0 || originalWords[splitAt - 1].end > start) return null
            val before = originalWords.subList(0, splitAt)
            val after = originalWords.subList(splitAt, originalWords.size)
            updated += paragraph.copy(
                endSec = before.maxOf { it.end }, text = AsrTextFormatter.joinWords(before),
                wordsJson = AsrJsonParser.encodeWords(before)
            )
            updated += paragraph.copy(
                id = 0, startSec = after.first().start,
                text = AsrTextFormatter.joinWords(after), wordsJson = AsrJsonParser.encodeWords(after),
                isBookmark = false, bookmarkLabel = null
            )
        } else {
            updated += paragraph
        }
    }
    if (updated.count { it.id == 0L } > 1) return null
    val index = updated.indexOfFirst { it.startSec > start }.let { if (it < 0) updated.size else it }
    updated.add(index, inserted)
    return updated.mapIndexed { order, paragraph ->
        if (paragraph.orderIndex == order) paragraph else paragraph.copy(orderIndex = order)
    }
}
