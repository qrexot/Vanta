package com.stenograf.app.service

import com.stenograf.app.data.model.ParagraphEntity

/** Checkpoints count decoded PCM bytes, independent of the source container and its header. */
internal fun resumePcmOffset(checkpoint: Long, pcmBase: Long, fileBytes: Long): Long =
    pcmBase + (checkpoint.coerceIn(0L, (fileBytes - pcmBase).coerceAtLeast(0L)) and -2L)

internal fun transcriptSpeechEnd(paragraphs: List<ParagraphEntity>): Float =
    paragraphs.asSequence().filter { it.text.isNotBlank() && it.endSec.isFinite() }
        .maxOfOrNull { it.endSec } ?: 0f

/** Keep manual metadata where the new transcript has a clear time match. */
internal fun preserveTranscriptAnnotations(
    original: List<ParagraphEntity>,
    replacement: List<ParagraphEntity>
): List<ParagraphEntity> {
    val speakers = original.filter { !it.speaker.isNullOrBlank() && it.endSec > it.startSec }
        .sortedBy { it.startSec }
    var firstActive = 0
    val restored = replacement.sortedBy { it.startSec }.map { paragraph ->
        while (firstActive < speakers.size && speakers[firstActive].endSec <= paragraph.startSec) firstActive++
        val duration = paragraph.endSec - paragraph.startSec
        if (duration <= 0f) return@map paragraph
        val names = mutableSetOf<String?>()
        var covered = 0f
        var coveredUntil = paragraph.startSec
        for (i in firstActive until speakers.size) {
            val candidate = speakers[i]
            if (candidate.startSec >= paragraph.endSec) break
            val start = maxOf(paragraph.startSec, candidate.startSec)
            val end = minOf(paragraph.endSec, candidate.endSec)
            if (end <= start) continue
            names += candidate.speaker
            covered += (end - maxOf(start, coveredUntil)).coerceAtLeast(0f)
            coveredUntil = maxOf(coveredUntil, end)
        }
        if (names.size == 1 && covered >= duration * 0.7f) paragraph.copy(speaker = names.single())
        else paragraph
    }
    val markers = original.filter { it.isBookmark }.map {
        it.copy(endSec = it.startSec, text = "", wordsJson = "", speaker = null)
    }
    return (markers + restored).sortedBy { it.startSec }
        .mapIndexed { index, paragraph -> paragraph.copy(orderIndex = index) }
}
