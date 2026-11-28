package com.stenograf.app

import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.BundleFileSpec
import com.stenograf.app.asr.GigaAmV4JsonParser
import com.stenograf.app.asr.GroqAsrClient
import com.stenograf.app.asr.ModelDownloadPhase
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptCoverage
import com.stenograf.app.asr.mergeDetectedLanguages
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.i18n.StringsEn
import com.stenograf.app.i18n.StringsRu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GigaAmV4CatalogAndBundleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun catalogExposesSingleVisibleGigaAmV4ModelInBothLiveAndFilePresets() {
        val v4Models = TinyAsrCatalog.DOWNLOAD_MODELS.filter { it.id == TinyAsrCatalog.GIGAAM_V4 }
        assertEquals(1, v4Models.size)

        val spec = v4Models.single()
        assertTrue(spec.isMultiFileBundle)
        assertTrue(spec.isOnnxModel)
        assertEquals("v4", spec.versionTag)
        assertEquals(13, spec.bundleFiles.size)
        assertEquals(507_046_768L, spec.expectedTotalBytes)
        assertEquals("GigaAM v4 · Русский + English", spec.localizedTitle(StringsEn))
        assertEquals("GigaAM v4 · Русский + English", spec.localizedTitle(StringsRu))

        val livePresets = TinyAsrCatalog.PRESETS.filter { it.modelId == TinyAsrCatalog.GIGAAM_V4 }
        val filePresets = TinyAsrCatalog.FILE_PRESETS.filter { it.modelId == TinyAsrCatalog.GIGAAM_V4 }
        assertEquals(1, livePresets.size)
        assertEquals(1, filePresets.size)
        assertEquals("GigaAM v4 · Русский + English", livePresets.single().label(StringsRu))
        assertEquals("GigaAM v4 · Русский + English", filePresets.single().label(StringsEn))

        val expectedPrefix = "https://huggingface.co/ai-babai/gigaam-v4-onnx/resolve/${TinyAsrCatalog.GIGAAM_V4_REVISION}/"
        for (fileSpec in spec.bundleFiles) {
            assertTrue(fileSpec.url.startsWith(expectedPrefix))
            assertEquals(64, fileSpec.sha256.length)
            assertTrue(fileSpec.bytes > 0L)
        }
        val relPaths = spec.bundleFiles.map { it.relativePath }.toSet()
        assertTrue("rnnt-streaming-640ms/v4_rnnt_640ms_stream_step.int8.onnx" in relPaths)
        assertTrue("rnnt/v4_rnnt_encoder.int8.onnx" in relPaths)
        assertTrue("rnnt/v4_rnnt_advance.onnx" in relPaths)
        assertTrue("LICENSE" in relPaths)
        assertTrue("LICENSE-APACHE-2.0" in relPaths)
        assertTrue("NOTICE" in relPaths)
    }

    @Test
    fun bundleDownloadPauseResumeShaVerificationCancelAndLeaseProtection(): Unit = runBlocking {
        val filesData = mapOf(
            "rnnt-streaming-640ms/stream.onnx" to ByteArray(256 * 1024) { ((it * 31) and 0xFF).toByte() },
            "rnnt/encoder.onnx" to ByteArray(192 * 1024) { ((it * 17 + 7) and 0xFF).toByte() },
            "NOTICE" to "GigaAM NOTICE\n".toByteArray(Charsets.UTF_8)
        )
        val specs = filesData.map { (path, bytes) ->
            BundleFileSpec(
                relativePath = path,
                url = "https://example.invalid/$path",
                bytes = bytes.size.toLong(),
                sha256 = sha256Hex(bytes)
            )
        }

        val corruptNotice = AtomicBoolean(false)
        val firstChunkServed = CountDownLatch(1)
        val allowFinishStream = CountDownLatch(1)
        val running = AtomicBoolean(true)

        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val acceptThread = Thread {
            while (running.get()) {
                val sock = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
                pool.execute {
                    sock.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val requestLine = reader.readLine() ?: return@execute
                        val relPath = requestLine.split(' ').getOrNull(1)?.removePrefix("/") ?: ""
                        var rangeHeader: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(':').trim()
                            }
                        }

                        val raw = filesData[relPath]
                        val out = s.getOutputStream()
                        if (raw == null) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            return@execute
                        }

                        val data = if (corruptNotice.get() && relPath == "NOTICE") {
                            "CORRUPT NOTICE".toByteArray(Charsets.UTF_8).copyOf(raw.size)
                        } else {
                            raw
                        }

                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val start = rangeHeader.removePrefix("bytes=").substringBefore('-').toInt()
                            val tailLen = data.size - start
                            val headers = "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Range: bytes $start-${data.size - 1}/${data.size}\r\n" +
                                "Content-Length: $tailLen\r\nConnection: close\r\n\r\n"
                            out.write(headers.toByteArray())
                            out.write(data, start, tailLen)
                            out.flush()
                            return@execute
                        }

                        val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                        out.write(headers.toByteArray())
                        if (relPath == "rnnt-streaming-640ms/stream.onnx" && allowFinishStream.count > 0) {
                            val firstHalf = 64 * 1024
                            out.write(data, 0, firstHalf)
                            out.flush()
                            firstChunkServed.countDown()
                            allowFinishStream.await(3, TimeUnit.SECONDS)
                            runCatching { out.write(data, firstHalf, data.size - firstHalf) }
                        } else {
                            out.write(data)
                        }
                        runCatching { out.flush() }
                    }
                }
            }
        }.apply { start() }

        try {
            val modelsDir = tempFolder.newFolder("models")
            val downloadsDir = tempFolder.newFolder("downloads")
            val resolver: (BundleFileSpec) -> String = { spec ->
                "http://127.0.0.1:${serverSocket.localPort}/${spec.relativePath}"
            }
            val manager = ModelManager(modelsDir, downloadsDir).apply { bundleUrlResolver = resolver }

            // 1. Pause mid-transfer -> incomplete state & restored on new ModelManager instance
            val pausedJob = async(Dispatchers.IO) { manager.downloadBundleFiles(TinyAsrCatalog.GIGAAM_V4, specs) }
            assertTrue(firstChunkServed.await(5, TimeUnit.SECONDS))
            val partFile = File(downloadsDir, "${TinyAsrCatalog.GIGAAM_V4}/rnnt-streaming-640ms/stream.onnx.part")
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline && (!partFile.isFile || partFile.length() == 0L)) {
                delay(20L)
            }
            assertTrue("Expected partial file bytes written before pause", partFile.isFile && partFile.length() > 0L)
            manager.pauseDownload(TinyAsrCatalog.GIGAAM_V4)
            allowFinishStream.countDown()
            val pausedResult = pausedJob.await()
            assertTrue(pausedResult.isFailure)
            assertFalse(manager.isModelInstalled(TinyAsrCatalog.GIGAAM_V4))

            val restartedManager = ModelManager(modelsDir, downloadsDir).apply { bundleUrlResolver = resolver }
            val pausedStatus = restartedManager.getAllModelStatuses().first { it.spec.id == TinyAsrCatalog.GIGAAM_V4 }
            assertFalse(pausedStatus.isInstalled)
            assertTrue(pausedStatus.isIncomplete)
            assertEquals(ModelDownloadPhase.PAUSED, pausedStatus.downloadProgress?.phase)

            // 2. Corrupted SHA-256 fails verification and keeps bundle incomplete/uninstalled
            corruptNotice.set(true)
            val corruptResult = restartedManager.downloadBundleFiles(TinyAsrCatalog.GIGAAM_V4, specs)
            assertTrue(corruptResult.isFailure)
            assertFalse(restartedManager.isModelInstalled(TinyAsrCatalog.GIGAAM_V4))
            val corruptStatus = restartedManager.getAllModelStatuses().first { it.spec.id == TinyAsrCatalog.GIGAAM_V4 }
            assertTrue(corruptStatus.isIncomplete)

            // 3. Cancel cleans staged files
            restartedManager.cancelDownload(TinyAsrCatalog.GIGAAM_V4)
            assertFalse(File(downloadsDir, TinyAsrCatalog.GIGAAM_V4).exists())

            // 4. Clean complete download succeeds, installs files + .verified marker
            corruptNotice.set(false)
            val okResult = restartedManager.downloadBundleFiles(TinyAsrCatalog.GIGAAM_V4, specs)
            assertTrue("Expected success, got ${okResult.exceptionOrNull()}", okResult.isSuccess)
            val bundleDir = restartedManager.getBundleDir(TinyAsrCatalog.GIGAAM_V4)
            assertTrue(File(bundleDir, ".verified").isFile)
            for ((path, expected) in filesData) {
                assertTrue(File(bundleDir, path).readBytes().contentEquals(expected))
            }

            // 5. Active ONNX lease blocks deletion until released
            val v3Spec = TinyAsrCatalog.findModel(TinyAsrCatalog.GIGAAM)
            val v3File = restartedManager.getModelFile(TinyAsrCatalog.GIGAAM)
            java.io.RandomAccessFile(v3File, "rw").use { it.setLength(v3Spec.onnxDownload!!.bytes) }
            val lease = restartedManager.acquireOnnxLease(TinyAsrCatalog.GIGAAM)
            val blockedDelete = restartedManager.deleteModel(v3Spec.id)
            assertTrue(blockedDelete.isFailure)
            restartedManager.releaseOnnxLease(lease)
            val allowedDelete = restartedManager.deleteModel(v3Spec.id)
            assertTrue(allowedDelete.isSuccess)
            assertFalse(v3File.exists())
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
            acceptThread.join(1000L)
        }
    }

    @Test
    fun offlineJsonParserLanguageMergeAndCoverageWordTimings() {
        val raw = """
            {"language":"ru,en","words":[
              {"text":"Открываем","start":0.160,"end":0.640},
              {"text":"dashboard","start":0.680,"end":1.120},
              {"text":"Vanta.","start":1.160,"end":1.600}
            ]}
        """.trimIndent()
        val parsed = GigaAmV4JsonParser.parseOfflineResult(raw)
        assertNotNull(parsed)
        assertEquals("ru,en", parsed!!.language)
        assertEquals(3, parsed.words.size)

        val text = AsrTextFormatter.joinWords(parsed.words)
        assertEquals("Открываем dashboard Vanta.", text)

        val para = ParagraphEntity(
            id = 1L,
            recordingId = 1L,
            orderIndex = 0,
            startSec = parsed.words.first().start,
            endSec = parsed.words.last().end,
            text = text,
            wordsJson = AsrJsonParser.encodeWords(parsed.words)
        )
        assertEquals(3, TranscriptCoverage.splittableWords(para).size)

        assertEquals("ru,en", mergeDetectedLanguages("ru", "en"))
        assertEquals("en", mergeDetectedLanguages(null, "en"))
        assertEquals("ru,en", mergeDetectedLanguages("ru,en", "ru"))
        assertNull(mergeDetectedLanguages(null, null))
        assertEquals("gigaam-v4", GroqAsrClient.resolveLocalReviewPreset("gigaam-v4", false))
        assertEquals("err", GigaAmV4JsonParser.decodeError("err\u0000\u0000".toByteArray(), "fallback"))
        assertEquals("fallback", GigaAmV4JsonParser.decodeError(ByteArray(16), "fallback"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
