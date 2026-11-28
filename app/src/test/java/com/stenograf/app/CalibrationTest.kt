package com.stenograf.app

import com.stenograf.app.asr.AsrWindowMerger
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.audio.SpeechNormalizer
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    @Test
    fun asrWindowMergerDropsDriftedOverlapDuplicatesWhileKeepingRealRepeats() {
        val recent = listOf(
            TranscriptWord("Добрый", 27.0f, 27.4f),
            TranscriptWord("день", 27.5f, 27.95f)
        )
        val windowWords = listOf(
            TranscriptWord("Добрый", 27.02f, 27.42f),
            TranscriptWord("день,", 27.85f, 28.18f), // shifted past coveredUntil = 27.95f
            TranscriptWord("коллеги", 28.25f, 28.75f),
            TranscriptWord("день", 29.2f, 29.6f)     // real later occurrence (> 0.65s away)
        )
        val merged = AsrWindowMerger.trimOverlapWords(windowWords, coveredUntilSec = 27.95f, recentEmittedWords = recent)
        assertEquals(listOf("коллеги", "день"), merged.map { it.text })
    }

    @Test
    fun speechNormalizerAdaptsDownwardQuicklyFromNoisyInitialFloorAndBoostsQuietSpeech() {
        // Simulate raw noisy initialNoiseFloor = 0.032f (pre-GTCRN) followed by clean quiet speech at ~0.012 RMS.
        val normalizer = SpeechNormalizer(
            sampleRate = 16_000,
            initialGain = 1.1f,
            initialNoiseFloor = 0.032f
        )

        // Feed 0.4s of low residual room noise (~0.0012 RMS) then 0.6s of quiet speech (~0.012 RMS)
        val frame = ShortArray(640) // 40 ms
        for (step in 0 until 10) {
            for (i in frame.indices) {
                frame[i] = ((sin(i * 0.2) * 50.0).toInt()).toShort()
            }
            normalizer.processInPlace(frame, frame.size)
        }

        var outputSumSq = 0.0
        for (step in 0 until 15) {
            for (i in frame.indices) {
                frame[i] = (sin(i * 0.17 + step) * 520.0).toInt().toShort()
            }
            normalizer.processInPlace(frame, frame.size)
            if (step >= 10) {
                for (s in frame) outputSumSq += (s.toDouble() * s.toDouble())
            }
        }
        val boostedRms = kotlin.math.sqrt(outputSumSq / (5 * frame.size)) / 32768.0
        assertTrue("Expected normalizer to quickly lower false noise floor and boost quiet speech, got rms=$boostedRms", boostedRms > 0.045)

        // Ensure peak never clips hard
        val loud = ShortArray(640) { i -> (sin(i * 0.11) * 28000.0).toInt().toShort() }
        normalizer.processInPlace(loud, loud.size)
        assertTrue(loud.all { abs(it.toInt()) <= 32767 })
    }
}
