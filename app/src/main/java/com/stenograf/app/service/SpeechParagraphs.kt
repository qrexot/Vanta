package com.stenograf.app.service

import com.stenograf.app.asr.SpeechSegment
import com.stenograf.app.asr.SpeakerAttributionSession
import com.stenograf.app.asr.LocalSpeakerLabeler
import java.io.RandomAccessFile
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.i18n.AppLocaleManager

internal fun SpeechSegment.toParagraph(recordingId: Long, index: Int, offset: Float) =
    ParagraphEntity(
        recordingId = recordingId,
        orderIndex = index,
        startSec = offset + startSec,
        endSec = offset + endSec,
        text = text,
        wordsJson = AsrJsonParser.encodeWords(words.map { it.copy(start = it.start + offset, end = it.end + offset) }),
        speaker = speaker
    )

internal data class SpeechParagraphBatch(
    val updatedLast: ParagraphEntity?,
    val newParagraphs: List<ParagraphEntity>
)

internal fun appendSpeechParagraphs(
    previous: ParagraphEntity?,
    incoming: List<ParagraphEntity>,
    startOrder: Int
): SpeechParagraphBatch {
    var last = previous
    val added = mutableListOf<ParagraphEntity>()
    for (paragraph in incoming) {
        val prior = added.lastOrNull() ?: last
        if (prior != null && continuesSpeechParagraph(prior, paragraph)) {
            val leftWords = AsrJsonParser.parseWords(prior.wordsJson)
            val rightWords = AsrJsonParser.parseWords(paragraph.wordsJson)
            val merged = prior.copy(
                endSec = maxOf(prior.endSec, paragraph.endSec),
                text = prior.text.trimEnd() + " " + paragraph.text.trimStart(),
                wordsJson = if (leftWords.isNotEmpty() && rightWords.isNotEmpty()) {
                    AsrJsonParser.encodeWords(leftWords + rightWords)
                } else ""
            )
            if (added.isEmpty()) last = merged else added[added.lastIndex] = merged
        } else {
            added.add(paragraph.copy(orderIndex = startOrder + added.size))
        }
    }
    return SpeechParagraphBatch(last?.takeIf { it != previous }, added)
}

internal fun continuesSpeechParagraph(left: ParagraphEntity, right: ParagraphEntity): Boolean {
    if (left.isBookmark || right.isBookmark || left.speaker != right.speaker) return false
    val gap = right.startSec - left.endSec
    if (gap !in -0.7f..2.4f) return false
    val size = left.text.length + right.text.length
    val duration = right.endSec - left.startSec
    val sentenceEnded = left.text.trimEnd().trimEnd('"', '»', '”', ')').lastOrNull()?.let { it in ".?!…" } == true
    return if (sentenceEnded) size <= 430 && duration <= 34f else size <= 650 && duration <= 48f
}

internal fun speakerLabel(): String =
    if (AppLocaleManager.currentLanguage.value.code == "ru") "Спикер" else "Speaker"

internal fun SpeakerAttributionSession.seedFromTranscript(
    audio: RandomAccessFile,
    pcmBase: Long,
    committedBytes: Long,
    paragraphs: List<ParagraphEntity>,
    checkActive: () -> Unit
) {
    val bytesPerSec = LocalSpeakerLabeler.PCM_BYTES_PER_SECOND
    val examples = paragraphs.filter { !it.speaker.isNullOrBlank() && !it.isBookmark }.groupBy { it.speaker!! }
    for ((name, turns) in examples) {
        for (turn in turns.sortedByDescending { it.endSec - it.startSec }.take(2)) {
            checkActive()
            val start = ((turn.startSec * bytesPerSec).toLong() and -2L).coerceIn(0L, committedBytes)
            val end = ((turn.endSec * bytesPerSec).toLong() and -2L).coerceIn(start, committedBytes)
            val count = minOf(4 * bytesPerSec, end - start).toInt()
            val pcm = ByteArray(count)
            audio.seek(pcmBase + start)
            audio.readFully(pcm)
            seed(name, pcm)
        }
    }
}
