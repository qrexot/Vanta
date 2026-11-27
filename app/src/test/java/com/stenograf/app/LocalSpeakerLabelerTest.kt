package com.stenograf.app

import com.stenograf.app.asr.LocalSpeakerLabeler
import com.stenograf.app.asr.ParagraphAccumulator
import com.stenograf.app.asr.SpeakerTimeline
import com.stenograf.app.asr.SpeakerTurn
import com.stenograf.app.asr.SpeechSegment
import com.stenograf.app.asr.TranscriptWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeakerLabelerTest {
    @Test
    fun keepsEarlyWordsAsDraftAndSplitsAtTheVoiceChange() {
        val calls = mutableListOf<Long>()
        val labeler = LocalSpeakerLabeler(0, windowSeconds = 8) { start, _ ->
            calls.add(start)
            SpeakerTimeline(listOf(SpeakerTurn(0f, 3f, 1, "Speaker 1"), SpeakerTurn(3f, 8f, 2, "Speaker 2")))
        }
        val early = TranscriptWord("Начало.", 1f, 2f)
        val late = TranscriptWord("Ответ.", 4f, 5f)
        val output = mutableListOf<SpeechSegment>()
        labeler.offer(listOf(early, late))
        labeler.drain(5L * 32_000) { output.add(it) }
        assertTrue(output.isEmpty())
        assertEquals(listOf(early, late), labeler.draftWords())
        labeler.drain(8L * 32_000) { output.add(it) }
        assertEquals(listOf("Speaker 1", "Speaker 2"), output.map { it.speaker })
        assertEquals(listOf(early, late), output.flatMap { it.words })
        assertEquals(listOf(0L), calls)
        assertTrue(labeler.draftWords().isEmpty())
    }

    @Test
    fun reusesTheTimelineAndFlushesAShortTailWithAbsoluteWordTimes() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val labeler = LocalSpeakerLabeler(16L * 32_000, windowSeconds = 8) { start, bytes ->
            calls.add(start to bytes)
            SpeakerTimeline(listOf(SpeakerTurn(0f, 8f, 1, "Speaker 2")))
        }
        val output = mutableListOf<SpeechSegment>()
        for (i in listOf(17, 18, 24)) {
            labeler.offer(listOf(TranscriptWord("Слово", i.toFloat(), i + 0.7f)))
            labeler.drain(25L * 32_000, finished = true) { output.add(it) }
        }
        assertEquals(listOf(16L * 32_000 to 8 * 32_000, 24L * 32_000 to 32_000), calls)
        assertEquals(listOf(17f, 18f, 24f), output.map { it.startSec })
        assertEquals(List(3) { "Speaker 2" }, output.map { it.speaker })
    }

    @Test
    fun distinguishesARealSpeakerChangeEvenWhenAutoParagraphsAreOff() {
        val accumulator = ParagraphAccumulator(autoParagraph = false)
        val one = listOf(TranscriptWord("Добрый", 1f, 1.3f))
        val two = listOf(TranscriptWord("вечер", 1.5f, 1.8f))
        assertEquals("Speaker 1", accumulator.acceptCommit(one, "Speaker 1").newParagraph?.speaker)
        assertNull(accumulator.acceptCommit(two, "Speaker 1").newParagraph)
        assertEquals("Speaker 2", accumulator.acceptCommit(two, "Speaker 2").newParagraph?.speaker)
    }

    @Test
    fun canSeparateTwoVoicesPreviouslyGroupedIntoOneCluster() {
        val timeline = SpeakerTimeline(listOf(SpeakerTurn(0f, 2f, 1, "Speaker 1"), SpeakerTurn(2f, 4f, 1, "Speaker 2")))
        val words = listOf(TranscriptWord("One", 1f, 1.5f), TranscriptWord("Two", 2.5f, 3f))
        assertEquals(listOf("Speaker 1", "Speaker 2"), timeline.assign(listOf(SpeechSegment.fromWords(words))).map { it.speaker })
    }

    @Test
    fun leavesAnIsolatedOverlappingWordUnassigned() {
        val timeline = SpeakerTimeline(listOf(SpeakerTurn(1f, 4f, 1, "Speaker 1"), SpeakerTurn(2f, 5f, 2, "Speaker 2")))
        val speech = SpeechSegment.fromWords(listOf(TranscriptWord("Да", 2.5f, 3f)))
        assertNull(timeline.assign(listOf(speech)).single().speaker)
    }
}
