package com.stenograf.app

import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.repo.RecordingRepository
import com.stenograf.app.service.appendSpeechParagraphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroqTranscriptTest {
    @Test
    fun mergesSentencesAcrossChunksAndKeepsSpeakerChanges() {
        val previous = paragraph("Добрый день.", 10f, 12f, "Спикер 1").copy(id = 71, orderIndex = 5)
        val result = appendSpeechParagraphs(
            previous,
            listOf(
                paragraph("Продолжим.", 12f, 14f, "Спикер 1"),
                paragraph("Хорошо.", 14.2f, 15f, "Спикер 2"),
                paragraph("Я готов.", 15.1f, 16f, "Спикер 2"),
                paragraph("Начинаем.", 16.1f, 18f, "Спикер 1")
            ),
            startOrder = 6
        )
        val updated = requireNotNull(result.updatedLast)
        assertEquals(71L, updated.id)
        assertEquals("Добрый день. Продолжим.", updated.text)
        assertEquals(listOf(10f, 12f), AsrJsonParser.parseWords(updated.wordsJson).map { it.start })
        assertEquals(listOf("Хорошо. Я готов.", "Начинаем."), result.newParagraphs.map { it.text })
        assertEquals(listOf(6, 7), result.newParagraphs.map { it.orderIndex })
    }

    @Test
    fun respectsLongPausesUnknownSpeakersAndBookmarks() {
        val previous = paragraph("Раз.", 0f, 2f, "Спикер 1")
        val result = appendSpeechParagraphs(
            previous,
            listOf(
                paragraph("Два.", 6f, 8f, "Спикер 1"),
                paragraph("Три.", 8.1f, 9f, null),
                paragraph("Четыре.", 9.1f, 10f, null).copy(isBookmark = true),
                paragraph("Пять.", 10.1f, 11f, null)
            ),
            startOrder = 1
        )
        assertNull(result.updatedLast)
        assertEquals(listOf("Два.", "Три.", "Четыре.", "Пять."), result.newParagraphs.map { it.text })
    }

    @Test
    fun llmRewrittenParagraphsDropWordTimingsWhileOneToOneSpellingEditsKeepThem() {
        val original = ParagraphEntity(
            recordingId = 1,
            orderIndex = 0,
            startSec = 10f,
            endSec = 14f,
            text = "Привит мир",
            wordsJson = AsrJsonParser.encodeWords(
                listOf(
                    TranscriptWord("Привит", 10.2f, 11.1f),
                    TranscriptWord("мир", 12.8f, 13.7f)
                )
            )
        )

        // GPT-OSS rewrite (preserveWordTimings = false) must clear word timestamps -> block-only highlight
        val gptReviewed = RecordingRepository.buildEditedParagraph(original, "Привет, мир!", preserveWordTimings = false)
        assertEquals("", gptReviewed.wordsJson)

        // Manual edit changing word count also clears approximate word timings
        val reshaped = RecordingRepository.buildEditedParagraph(original, "Привет всем в мире!", preserveWordTimings = true)
        assertEquals("", reshaped.wordsJson)

        // 1:1 spelling edit preserves real word timestamps
        val typoFix = RecordingRepository.buildEditedParagraph(original, "Привет мир", preserveWordTimings = true)
        val keptWords = AsrJsonParser.parseWords(typoFix.wordsJson)
        assertEquals(listOf("Привет", "мир"), keptWords.map { it.text })
        assertEquals(10.2f, keptWords[0].start, 0.001f)
        assertEquals(12.8f, keptWords[1].start, 0.001f)
    }

    private fun paragraph(text: String, start: Float, end: Float, speaker: String?) = ParagraphEntity(
        recordingId = 1,
        orderIndex = 0,
        startSec = start,
        endSec = end,
        text = text,
        wordsJson = AsrJsonParser.encodeWords(listOf(TranscriptWord(text, start, end))),
        speaker = speaker
    )
}
