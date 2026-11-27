package com.stenograf.app

import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.GroqTranscriptReviewer
import com.stenograf.app.asr.TranscriptReviewModel
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqTranscriptReviewerTest {
    @Test
    fun keepsOnlyUnchangedUncertainExcerpts() {
        val original = "Позвони Ире в ниском."
        val answer = """{"corrected_text":"Позвони Ире в ниском.","uncertain":["ниском","Иван","ниском"]}"""
        val result = GroqTranscriptReviewer.parseReview(completion(answer), original)

        assertEquals(original, result.correctedText)
        assertEquals(listOf("ниском"), result.uncertain)
    }

    @Test
    fun rejectsTruncatedOrEmptyEdits() {
        val answer = """{"corrected_text":"Позвони.","uncertain":[]}"""
        assertThrows(IOException::class.java) {
            GroqTranscriptReviewer.parseReview(completion(answer, finish = "length"), "Позвони.")
        }
        assertThrows(IOException::class.java) {
            GroqTranscriptReviewer.parseReview(completion("""{"corrected_text":"  ","uncertain":[]}"""), "Позвони.")
        }
        assertThrows(IOException::class.java) {
            GroqTranscriptReviewer.parseReview(completion("""{"corrected_text":"Позвони."}"""), "Позвони.")
        }
    }

    @Test
    fun keepsModelSelectionIndependentOfSpeechRecognition() {
        assertEquals(TranscriptReviewModel.Fast, TranscriptReviewModel.resolve("openai/gpt-oss-20b"))
        assertEquals(TranscriptReviewModel.Detailed, TranscriptReviewModel.resolve("openai/gpt-oss-120b"))
        assertEquals(TranscriptReviewModel.Detailed, TranscriptReviewModel.resolve("whisper-large-v3-turbo"))
    }

    @Test
    fun sendsProofreadingToChatUsingContextAndStrictSchema() = runBlocking {
        MockWebServer().use { server ->
            val original = "Встреча в сриду"
            val answer = """{"corrected_text":"Встреча в среду.","uncertain":[]}"""
            server.enqueue(MockResponse().setBody(completion(answer)))

            val outgoing = GroqTranscriptReviewer.buildRequest(
                "gsk_test_key", TranscriptReviewModel.Detailed, original, "Обсудили даты.", "Начало в три.", "расписание"
            )
            assertEquals("/openai/v1/chat/completions", outgoing.url.encodedPath)
            val response = GroqAsrClient.execute(outgoing.newBuilder().url(server.url(outgoing.url.encodedPath)).build())
            assertEquals("Встреча в среду.", GroqTranscriptReviewer.parseReview(response, original).correctedText)

            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("openai/gpt-oss-120b", body.getValue("model").jsonPrimitive.content)
            val schema = body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject
            assertEquals("true", schema.getValue("strict").jsonPrimitive.content)
            val user = body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
            val context = Json.parseToJsonElement(user).jsonObject
            assertEquals(setOf("fragment", "before", "after", "hint"), context.keys)
            assertEquals("Обсудили даты.", context.getValue("before").jsonPrimitive.content)
            val systemPrompt = body.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(systemPrompt.contains("NEVER translate non-Russian speech into Russian"))
        }
    }

    private fun completion(content: String, finish: String = "stop"): String {
        val message = buildJsonObject { put("content", content) }
        return """{"choices":[{"finish_reason":"$finish","message":$message}]}"""
    }
}
