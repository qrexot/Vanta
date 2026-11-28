package com.stenograf.app

import com.stenograf.app.asr.GroqAsrClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqAsrClientTest {
    @Test
    fun parsesGroqWordTimestampsAndClampsToChunk() {
        val parsed = GroqAsrClient.parseTranscript(
            """
            {
              "language": "russian",
              "segments": [{"start":0.5,"end":13.0,"text":"Привет, мир!"}],
              "words": [
                {"start":0.55,"end":1.5,"word":"Привет,"},
                {"start":11.0,"end":12.75,"word":"мир!"}
              ]
            }
            """.trimIndent(),
            duration = 12f
        )

        assertEquals("ru", parsed.language)
        val segment = parsed.segments.single()
        assertEquals("Привет, мир!", segment.text)
        assertEquals(12f, segment.endSec, 0f)
        assertEquals(0.55f, segment.words.first().start, 0f)
        assertEquals(12f, segment.words.last().end, 0f)
    }

    @Test
    fun rejectsMissingTranscriptRatherThanTreatingItAsSilence() {
        assertThrows(SerializationException::class.java) {
            GroqAsrClient.parseTranscript("{}", duration = 12f)
        }
        assertTrue(GroqAsrClient.parseTranscript("""{"text":""}""", duration = 12f).segments.isEmpty())
    }

    @Test
    fun usesBackoffForOverloadAndHonorsQuotaReset() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "30"))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "3600"))
            server.enqueue(MockResponse().setResponseCode(429))
            val backoff = 5_000L
            assertEquals(backoff, GroqAsrClient.reconnectDelayMs(getFailure(server, "/busy"), backoff))
            assertEquals(30_000L, GroqAsrClient.reconnectDelayMs(getFailure(server, "/short-limit"), backoff))
            assertEquals(3_600_000L, GroqAsrClient.reconnectDelayMs(getFailure(server, "/long-limit"), backoff))
            assertEquals(60_000L, GroqAsrClient.reconnectDelayMs(getFailure(server, "/limit-without-reset"), backoff))
        }
    }

    @Test
    fun cancelsRequestWhenServerLeavesResponseOpen() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                GroqAsrClient.execute(request(server, "/"))
            }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            withTimeout(2_000L) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun recordingLanguageModeDoesNotForceRussianEvenWhenRecordingPresetWasRuOnly() {
        assertEquals("auto", GroqAsrClient.resolveReviewLanguageMode(forceRussian = false))
        assertEquals("auto", GroqAsrClient.resolveReviewLanguageMode(forceRussian = false, explicitOverride = null))
        assertEquals("es", GroqAsrClient.resolveReviewLanguageMode(forceRussian = false, explicitOverride = "es"))
        assertEquals("ru", GroqAsrClient.resolveReviewLanguageMode(forceRussian = true, explicitOverride = "es"))

        assertEquals("ru_en", GroqAsrClient.resolveLocalReviewPreset("ru_only", forceRussian = false))
        assertEquals("es_en", GroqAsrClient.resolveLocalReviewPreset("es_en", forceRussian = false))
        assertEquals("ru_only", GroqAsrClient.resolveLocalReviewPreset("es_en", forceRussian = true))
    }

    @Test
    fun autoModeOmitsLanguageAndBilingualPromptWhileExplicitModesIncludeThem() {
        val wav = ByteArray(100)
        fun bodyOf(langMode: String): String {
            val req = GroqAsrClient.buildTranscribeRequest("gsk_test_key", "whisper-large-v3-turbo", wav, langMode)
            val buffer = okio.Buffer()
            req.body!!.writeTo(buffer)
            return buffer.readUtf8()
        }

        val autoBody = bodyOf("auto")
        assertFalse(autoBody.contains("name=\"language\""))
        assertFalse(autoBody.contains("name=\"prompt\""))

        val spanishBody = bodyOf("es")
        assertTrue(spanishBody.contains("name=\"language\""))
        assertTrue(spanishBody.contains("\r\n\r\nes\r\n"))

        val multiBody = bodyOf("multi")
        assertFalse(multiBody.contains("name=\"language\""))
        assertTrue(multiBody.contains("name=\"prompt\""))
    }

    @Test
    fun filtersWhisperHallucinationsAndPreservesLastWordAtExactChunkEnd() {
        val parsed = GroqAsrClient.parseTranscript(
            """
            {
              "language": "ru",
              "segments": [
                {"start": 0.0, "end": 2.0, "text": "Субтитры сделал DimaTorzok", "no_speech_prob": 0.94, "avg_logprob": -0.92},
                {"start": 2.0, "end": 4.0, "text": "да да да да да да", "compression_ratio": 3.4},
                {"start": 4.0, "end": 4.5, "text": "...", "no_speech_prob": 0.1},
                {"start": 5.0, "end": 12.0, "text": "Привет конец", "no_speech_prob": 0.08, "avg_logprob": -0.21}
              ],
              "words": [
                {"start": 5.2, "end": 5.8, "word": "Привет"},
                {"start": 12.0, "end": 12.0, "word": "конец"}
              ]
            }
            """.trimIndent(),
            duration = 12f
        )

        val seg = parsed.segments.single()
        assertEquals("Привет конец", seg.text)
        assertEquals(listOf("Привет", "конец"), seg.words.map { it.text })
    }

    @Test
    fun skipsSilentElectricalHumAndClicksWhileAcceptingQuietSpeech() {
        val silence = ByteArray(16_000)
        assertFalse(GroqAsrClient.hasSignal(silence))

        // Single impulse click must not trigger a Whisper request
        val singleClick = ByteArray(16_000)
        singleClick[500] = 0x00
        singleClick[501] = 0x20
        assertFalse(GroqAsrClient.hasSignal(singleClick))

        // Steady faint -60 dBFS hum (amplitude ~30) should also stay gated
        val hum = ByteArray(16_000)
        for (i in 0 until hum.size step 2) {
            val v: Short = if ((i / 2) % 2 == 0) 30 else -30
            hum[i] = (v.toInt() and 0xFF).toByte()
            hum[i + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        assertFalse(GroqAsrClient.hasSignal(hum))

        // Quiet distant speech (~ -46 dBFS, peak 220) must pass
        val softSpeech = ByteArray(16_000)
        for (i in 0 until softSpeech.size step 2) {
            val phase = (i / 2) % 32
            val v = (kotlin.math.sin(phase * 2.0 * Math.PI / 32.0) * 220.0).toInt().toShort()
            softSpeech[i] = (v.toInt() and 0xFF).toByte()
            softSpeech[i + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        assertTrue(GroqAsrClient.hasSignal(softSpeech))
    }

    private suspend fun getFailure(server: MockWebServer, path: String): IOException {
        try {
            GroqAsrClient.execute(request(server, path))
            throw AssertionError("Expected a Groq HTTP error")
        } catch (e: IOException) {
            return e
        }
    }

    private fun request(server: MockWebServer, path: String): Request =
        Request.Builder().url(server.url(path)).build()
}
