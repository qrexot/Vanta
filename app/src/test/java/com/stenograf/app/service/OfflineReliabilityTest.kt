package com.stenograf.app.service

import com.stenograf.app.audio.AlignedCaptureFilter
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.model.ParagraphEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OfflineReliabilityTest {
    @Test
    fun checkpointsUseDecodedSamplesAndSkipTheHeader() {
        assertEquals(128_000L, resumePcmOffset(128_001L, 0L, 352_000L))
        assertEquals(128_044L, resumePcmOffset(128_001L, 44L, 352_044L))
        assertEquals(352_044L, resumePcmOffset(900_000L, 44L, 352_045L))
        assertEquals(44L, resumePcmOffset(-20L, 44L, 352_044L))
    }

    @Test
    fun actualWavHeaderControlsWhetherAudioCanBypassTheDecoder() {
        val file = File.createTempFile("capture-format", ".wav")
        try {
            file.writeBytes(WavHelper.createHeader(44_100))
            assertEquals(44_100, WavHelper.monoPcm16SampleRate(file))
            file.writeBytes(WavHelper.createHeader(16_000))
            assertEquals(16_000, WavHelper.monoPcm16SampleRate(file))
            file.writeBytes(WavHelper.createHeader(16_000, channels = 2))
            assertNull(WavHelper.monoPcm16SampleRate(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun replacingTextKeepsBookmarksAndOnlyTransfersUnambiguousSpeakerNames() {
        val old = listOf(
            paragraph(0f, 5f, "Светлана", id = 24L).copy(isBookmark = true, bookmarkLabel = "Вопрос"),
            paragraph(5f, 7f, "Иван"),
            paragraph(20f, 20f, null).copy(text = "", isBookmark = true)
        )
        val result = preserveTranscriptAnnotations(old, listOf(paragraph(1f, 4f, null), paragraph(4f, 6f, null)))
        assertEquals(7f, transcriptSpeechEnd(old))
        assertEquals(listOf(0, 1, 2, 3), result.map { it.orderIndex })
        assertEquals(24L, result.first().id)
        assertEquals("Вопрос", result.first().bookmarkLabel)
        assertEquals("Светлана", result[1].speaker)
        assertNull(result[2].speaker)
    }

    @Test
    fun streamingFilterFailureReleasesPendingSamplesInOrderAndBypassesNextInput() {
        var calls = 0
        var failures = 0
        val filter = AlignedCaptureFilter(
            process = { _, _ -> if (calls++ == 0) shortArrayOf(10) else error("inference failed") },
            flush = { error("a failed filter must not flush") },
            reset = { error("a failed filter must not restart") },
            onFailure = { failures++ }
        )
        assertArrayEquals(shortArrayOf(10), filter.push(shortArrayOf(1, 2, 3), 3))
        assertArrayEquals(shortArrayOf(2, 3, 4, 5), filter.push(shortArrayOf(4, 5, 99), 2))
        filter.restart()
        assertArrayEquals(shortArrayOf(6), filter.push(shortArrayOf(6), 1))
        assertArrayEquals(shortArrayOf(), filter.drain())
        assertEquals(1, failures)
    }

    @Test
    fun pauseDrainsSamplesEvenWhenTheFilterProducesAShortTail() {
        var resets = 0
        val filter = AlignedCaptureFilter(
            process = { _, _ -> shortArrayOf() },
            flush = { shortArrayOf(10) },
            reset = { resets++ },
            onFailure = { throw AssertionError(it) }
        )
        assertArrayEquals(shortArrayOf(), filter.push(shortArrayOf(1, 2), 2))
        assertArrayEquals(shortArrayOf(10, 2), filter.drain())
        filter.restart()
        assertEquals(1, resets)
        assertArrayEquals(shortArrayOf(), filter.drain())
    }

    private fun paragraph(from: Float, to: Float, speaker: String?, id: Long = 0L) = ParagraphEntity(
        id = id, recordingId = 1L, orderIndex = 0, startSec = from, endSec = to,
        text = "Реплика", wordsJson = "", speaker = speaker
    )
}
