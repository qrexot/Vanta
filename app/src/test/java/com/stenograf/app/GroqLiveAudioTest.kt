package com.stenograf.app

import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.service.drainGroqLiveAudio
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GroqLiveAudioTest {
    @get:Rule val files = TemporaryFolder()
    private val chunkBytes = GroqAsrClient.LIVE_CHUNK_BYTES
    private val emptyTranscript = GroqAsrClient.Transcript("", emptyList())

    @Test
    fun looksForARealPauseAndKeepsOnlyWordsOwnedByTheChunk() {
        val pcm = ByteArray(13 * 32_000) { 12 }
        pcm.fill(0, 11 * 32_000 + 8_000, 11 * 32_000 + 16_000)
        val cut = com.stenograf.app.service.findSpeechBoundary(pcm)
        assertEquals(true, cut in (11 * 32_000 + 8_000)..(11 * 32_000 + 16_000))
        val words = listOf(
            com.stenograf.app.asr.TranscriptWord("left", 0.1f, 0.4f),
            com.stenograf.app.asr.TranscriptWord("boundary", 0.65f, 1f),
            com.stenograf.app.asr.TranscriptWord("right", 2.8f, 3f)
        )
        val result = com.stenograf.app.service.ownTranscriptWords(
            GroqAsrClient.Transcript("en", listOf(com.stenograf.app.asr.SpeechSegment.fromWords(words))),
            prefix = 0.75f, duration = 2f
        )
        assertEquals("boundary", result.segments.single().text)
        assertEquals(0f, result.segments.single().words.single().start)
    }

    @Test
    fun retriesSameAudioAndCommitsCatchUpInOrderWhileCaptureContinues() = runTest {
        val spool = files.newFile()
        spool.writeBytes(ByteArray(chunkBytes) { 1 })
        var available = chunkBytes.toLong()
        var closed = false

        val capture = launch {
            delay(500)
            spool.appendBytes(ByteArray(chunkBytes) { 2 })
            available += chunkBytes
            delay(1_000)
            spool.appendBytes(byteArrayOf(3, 3))
            available += 2
            closed = true
        }

        val attempts = mutableListOf<Byte>()
        val commits = mutableListOf<Pair<Long, Byte>>()
        var retries = 0
        RandomAccessFile(spool, "r").use { input ->
            drainGroqLiveAudio(input, { available }, { closed }, { false }, transcribe = { pcm ->
                val marker = pcm.first()
                attempts.add(marker)
                if (attempts.size <= 2) throw IOException("Groq unavailable")
                emptyTranscript
            }, onRetry = { retries++ }) { offset, pcm, _ ->
                commits.add(offset to pcm.first())
            }
        }

        capture.join()
        assertEquals(2, retries)
        assertEquals(listOf<Byte>(1, 1, 1, 1), attempts)
        assertEquals(listOf(0L to 1.toByte(), chunkBytes.toLong() to 2.toByte()), commits)
    }

    @Test
    fun cancellingDuringAnOutageLeavesAudioUnprocessed() = runTest {
        val spool = files.newFile()
        spool.writeBytes(ByteArray(2 * chunkBytes) { 1 })
        var attempts = 0
        var committed = 0
        val worker = launch {
            RandomAccessFile(spool, "r").use { input ->
                drainGroqLiveAudio(input, spool::length, { false }, { false }, transcribe = {
                    attempts++
                    throw IOException("Offline")
                }, onRetry = {}) { _, _, _ -> committed++ }
            }
        }

        advanceTimeBy(2_500)
        worker.cancelAndJoin()
        assertEquals(2, attempts)
        assertEquals(0, committed)
        assertEquals(2L * chunkBytes, spool.length())
    }
}
