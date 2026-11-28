package com.stenograf.app

import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.SpeechGap
import com.stenograf.app.asr.TranscriptCoverage
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.asr.planGapInsertion
import com.stenograf.app.data.model.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCoverageTest {
    private fun paragraph(id: Long, vararg words: TranscriptWord): ParagraphEntity = ParagraphEntity(
        id = id, recordingId = 8, orderIndex = (id - 1).toInt(),
        startSec = words.first().start, endSec = words.maxOf { it.end },
        text = AsrTextFormatter.joinWords(words.toList()), wordsJson = AsrJsonParser.encodeWords(words.toList()),
        speaker = "Светлана Давыдовна"
    )

    @Test
    fun scanFindsGapInsideParagraphAndIgnoresSilenceAndUnprocessedTail() {
        val paragraphs = listOf(paragraph(1, TranscriptWord("До", 1f, 2f), TranscriptWord("после", 7f, 8f)))
        val gaps = TranscriptCoverage.missingSpeech(
            listOf(SpeechGap(1_000, 8_000), SpeechGap(15_000, 22_000)),
            paragraphs,
            limitMs = 10_000
        )
        assertEquals(listOf(SpeechGap(2_180, 6_820)), gaps)
    }

    @Test
    fun legacyParagraphWithoutValidWordTimingStaysCovered() {
        val original = paragraph(1, TranscriptWord("Старый", 1f, 2f), TranscriptWord("текст", 7f, 8f))
        val edited = original.copy(text = "Подпись вручную")
        val range = SpeechGap(2_500, 6_000)
        assertTrue(TranscriptCoverage.missingSpeech(listOf(range), listOf(edited), 10_000).isEmpty())
        assertNull(planGapInsertion(8, listOf(edited), range, listOf(TranscriptWord("Новый", 3f, 4f))))
    }

    @Test
    fun longIntervalsSplitIntoGigaAmSizedWindows() {
        val gaps = TranscriptCoverage.missingSpeech(listOf(SpeechGap(1_000, 71_000)), emptyList(), 70_000)
        assertEquals(3, gaps.size)
        assertEquals(1_000L, gaps.first().startMs)
        assertEquals(70_000L, gaps.last().endMs)
        assertTrue(gaps.all { it.endMs - it.startMs in 1_200L..25_000L })
        assertTrue(gaps.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
    }

    @Test
    fun insertionKeepsSpeakerAndBookmarkWhenSplittingParagraph() {
        val old = paragraph(1, TranscriptWord("До", 1f, 2f), TranscriptWord("После", 7f, 8f))
            .copy(isBookmark = true, bookmarkLabel = "Начало")
        val next = paragraph(2, TranscriptWord("Дальше", 9f, 10f))
        val words = listOf(TranscriptWord("Новая реплика", 3f, 4f))
        val gap = SpeechGap(2_200, 6_800)
        val planned = requireNotNull(planGapInsertion(8, listOf(old, next), gap, words))
        assertEquals(listOf("До", "Новая реплика", "После", "Дальше"), planned.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), planned.map { it.orderIndex })
        assertEquals(1L, planned[0].id)
        assertEquals(2L, planned[3].id)
        assertEquals(old.speaker, planned[0].speaker)
        assertEquals(old.speaker, planned[2].speaker)
        assertNull(planned[1].speaker)
        assertEquals("Начало", planned.first().bookmarkLabel)
        assertFalse(planned[2].isBookmark)
        assertEquals(AsrJsonParser.encodeWords(words), planned[1].wordsJson)
        assertEquals(next.copy(orderIndex = 3), planned.last())
    }

    @Test
    fun insertionRejectsOverlappingOrOutOfRangeWordsAndKeepsExistingMarker() {
        val old = paragraph(1, TranscriptWord("До", 1f, 2f))
        val marker = old.copy(id = 2, orderIndex = 1, startSec = 3f, endSec = 3f,
            text = "", wordsJson = "", isBookmark = true, bookmarkLabel = "Вопрос")
        val inside = SpeechGap(1_500, 5_000)
        assertNull(planGapInsertion(8, listOf(old, marker), inside, listOf(TranscriptWord("Дубль", 1.6f, 2.5f))))
        assertNull(planGapInsertion(8, listOf(old, marker), inside, listOf(TranscriptWord("Вне", 3f, 6f))))
        val planned = planGapInsertion(8, listOf(old, marker), inside, listOf(TranscriptWord("Ответ", 3.1f, 4f)))
        assertNotNull(planned)
        assertEquals(marker, planned?.get(1))
    }
}
