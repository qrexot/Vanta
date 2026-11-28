package com.stenograf.app

import com.stenograf.app.asr.SpeakerEmbeddingHelper
import com.stenograf.app.asr.VoiceprintMath
import com.stenograf.app.audio.SpeechAnalysisStats
import com.stenograf.app.audio.SpeechNormalizer
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.data.prefs.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class SpeakerVoiceprintMathTest {

    @Test
    fun `normalize and cosine compute unit vectors cleanly`() {
        val raw = floatArrayOf(3f, 4f, 0f, 0f)
        val norm = VoiceprintMath.normalize(raw)
        assertNotNull(norm)
        assertEquals(0.6f, norm!![0], 1e-4f)
        assertEquals(0.8f, norm[1], 1e-4f)
        assertEquals(1.0f, VoiceprintMath.cosine(norm, norm), 1e-4f)
        assertNull(VoiceprintMath.normalize(floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun `mergeEmbedding refines centroid and preserves diverse prototypes capped at MAX_PROTOTYPES`() {
        val v1 = VoiceprintMath.normalize(floatArrayOf(1f, 0f, 0f, 0f))!!
        var centroid = v1
        var protos = listOf(v1)
        var samples = 1

        // Feed distinct normalized vectors to verify prototype buffer bounds & weight progression
        val candidates = listOf(
            floatArrayOf(0.8f, 0.6f, 0f, 0f),
            floatArrayOf(0.75f, 0f, 0.66f, 0f),
            floatArrayOf(0.72f, 0f, 0f, 0.69f),
            floatArrayOf(0.68f, 0.45f, 0.45f, 0.35f),
            floatArrayOf(0.60f, 0.50f, 0.45f, 0.43f)
        )
        for (c in candidates) {
            val next = VoiceprintMath.mergeEmbedding(centroid, protos, samples, c)
            assertNotNull(next)
            centroid = next!!.first
            protos = next.second
            samples = next.third
        }

        assertEquals(6, samples)
        assertTrue(protos.size <= VoiceprintMath.MAX_PROTOTYPES)
        // Similarity against centroid must stay high for the first speaker condition too
        assertTrue(VoiceprintMath.similarity(centroid, protos, v1) > 0.85f)
    }

    @Test
    fun `mergeStoredProfiles combines weighted centroids cleanly`() {
        val target = VoiceprintMath.normalize(floatArrayOf(1f, 0f, 0f, 0f))!!
        val source = VoiceprintMath.normalize(floatArrayOf(0f, 1f, 0f, 0f))!!
        val merged = VoiceprintMath.mergeStoredProfiles(
            targetCentroid = target,
            targetPrototypes = listOf(target),
            targetSamples = 3,
            sourceCentroid = source,
            sourcePrototypes = listOf(source),
            sourceSamples = 1
        )
        assertNotNull(merged)
        assertEquals(4, merged!!.third)
        assertTrue(merged.first[0] > merged.first[1])
        assertEquals(2, merged.second.size)
    }

    @Test
    fun `embedding and prototypes survive JSON round trip`() {
        val vec = VoiceprintMath.normalize(floatArrayOf(0.25f, -0.5f, 0.75f, 0.1f))!!
        val entity = SpeakerProfileEntity(
            id = 42L,
            name = "Алексей",
            embeddingJson = VoiceprintMath.encodeEmbedding(vec),
            prototypesJson = VoiceprintMath.encodePrototypes(listOf(vec)),
            sampleCount = 5,
            isAutoLearned = false
        )
        val restored = VoiceprintMath.toStoredVoiceprint(entity)
        assertNotNull(restored)
        assertEquals(42L, restored!!.id)
        assertEquals("Алексей", restored.name)
        assertEquals(5, restored.sampleCount)
        assertEquals(1.0f, VoiceprintMath.cosine(vec, restored.centroid), 1e-4f)
        assertEquals(1, restored.prototypes.size)
    }

    @Test
    fun `app settings enable neural denoiser and voice learning by default`() {
        val defaults = AppSettings()
        assertTrue(defaults.neuralNoiseSuppression)
        assertTrue(defaults.autoLearnSpeakers)
    }

    @Test
    fun `passthrough window preserves exact length when normalizer disabled`() {
        val raw = File.createTempFile("raw_pass", ".pcm")
        val out = File.createTempFile("out_pass", ".pcm")
        try {
            val samples = ShortArray(3200) { idx -> (idx % 200).toShort() }
            val bytes = ByteArray(samples.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            raw.writeBytes(bytes)

            val written = SpeechNormalizer.writeNormalizedPcm16Window(
                srcPcm = raw,
                startByte = 0L,
                endByte = raw.length(),
                dstPcm = out,
                stats = SpeechAnalysisStats(0.002f, SpeechNormalizer.DEFAULT_TARGET_RMS, 1f),
                enhancer = null,
                applyNormalizer = false
            )
            assertEquals(raw.length(), written)
            assertEquals(raw.length(), out.length())
            val outShorts = ShortArray(samples.size)
            ByteBuffer.wrap(out.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(outShorts)
            for (i in samples.indices) {
                assertTrue(abs(samples[i] - outShorts[i]) <= 1)
            }
        } finally {
            raw.delete()
            out.delete()
        }
    }

    @Test
    fun `trainingProgress and tiers reflect progressive sample growth`() {
        val t1 = VoiceprintMath.trainingTier(sampleCount = 1, speechSeconds = 2.5f, prototypeCount = 1)
        val t2 = VoiceprintMath.trainingTier(sampleCount = 3, speechSeconds = 12f, prototypeCount = 3)
        val t3 = VoiceprintMath.trainingTier(sampleCount = 10, speechSeconds = 40f, prototypeCount = 7)
        assertEquals(1, t1)
        assertEquals(2, t2)
        assertEquals(3, t3)
        assertTrue(
            VoiceprintMath.trainingProgress(1, 2.5f, 1) <
                VoiceprintMath.trainingProgress(4, 14f, 4)
        )
    }

    @Test
    fun `trimToActiveSpeech removes long silence while preserving voiced speech`() {
        val sr = 16_000
        // 1.0s silence + 1.5s clean speech sine + 1.0s silence = 3.5s total
        val total = FloatArray((3.5f * sr).toInt())
        for (i in sr until (2.5f * sr).toInt()) {
            total[i] = sin(i * 2.0 * Math.PI * 220.0 / sr).toFloat() * 0.12f
        }
        val trimmed = SpeakerEmbeddingHelper.trimToActiveSpeech(total)
        assertTrue("Expected silence stripped below 2.2s, was ${trimmed.size / 16000f}s", trimmed.size < (2.2f * sr).toInt())
        assertTrue("Expected speech kept above 1.2s, was ${trimmed.size / 16000f}s", trimmed.size >= (1.2f * sr).toInt())
    }
}
