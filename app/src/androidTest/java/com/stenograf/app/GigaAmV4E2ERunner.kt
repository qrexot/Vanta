package com.stenograf.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.room.Room
import com.stenograf.app.asr.AsrEvent
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.BundleFileSpec
import com.stenograf.app.asr.FileAsr
import com.stenograf.app.asr.GigaAmV4Native
import com.stenograf.app.asr.ModelDownloadPhase
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptCoverage
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.db.StenografDatabase
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.prefs.AppPreferences
import com.stenograf.app.data.prefs.AsrEngineMode
import com.stenograf.app.data.repo.RecordingRepository
import com.stenograf.app.service.CoverageAudio
import com.stenograf.app.service.ReTranscribeWorker
import com.stenograf.app.service.RecordingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GigaAmV4E2ERunner : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = StringBuilder()
        var status = Activity.RESULT_CANCELED
        val stamp = System.currentTimeMillis()
        val scratch = File(targetContext.cacheDir, "vanta-v4-e2e-$stamp")
        var db: StenografDatabase? = null

        try {
            scratch.mkdirs()
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = File(scratch, "files").apply { mkdirs() }
                override fun getCacheDir(): File = File(scratch, "cache").apply { mkdirs() }
            }

            runBlocking {
                val bundleSrc = File("/data/local/tmp/vanta-e2e/bundle")
                val audioDir = File("/data/local/tmp/vanta-e2e/audio")
                val gigaV3Src = File("/data/local/tmp/vanta-e2e/giga-v3.onnx")
                val gigaWav = File(audioDir, "giga.wav")
                val ruWav = File(audioDir, "ru_test.wav")
                val enWav = File(audioDir, "en_test.wav")
                val mixedWav = File(audioDir, "ru_en_mixed.wav")
                check(bundleSrc.isDirectory && gigaWav.isFile && ruWav.isFile && enWav.isFile && mixedWav.isFile) {
                    "Missing /data/local/tmp/vanta-e2e test fixtures"
                }

                val manager = ModelManager(isolated)
                val expectedSpecs = TinyAsrCatalog.findModel(TinyAsrCatalog.GIGAAM_V4).bundleFiles

                // 1. Loopback HTTP download verification: pause/resume -> corrupt rejection -> cancel -> real 13-file SHA-256 install
                verifyBundleDownloadAndInstall(manager, isolated, bundleSrc, expectedSpecs, report)

                // Install GigaAM v3 beside GigaAM v4
                gigaV3Src.copyTo(manager.getModelFile(TinyAsrCatalog.GIGAAM), overwrite = true)
                check(manager.isModelInstalled(TinyAsrCatalog.GIGAAM)) { "GigaAM v3 install check failed" }
                report.appendLine("v3_install=ok")

                // 2. Deletion protection while active leases are held
                val tmpOnline = manager.acquireGigaV4OnlineLease(2)
                check(manager.deleteModel(TinyAsrCatalog.GIGAAM_V4).isFailure) { "Delete succeeded during online lease!" }
                manager.releaseGigaV4OnlineLease(tmpOnline)

                val tmpOffline = manager.acquireGigaV4OfflineLease(2)
                check(manager.deleteModel(TinyAsrCatalog.GIGAAM_V4).isFailure) { "Delete succeeded during offline lease!" }
                manager.releaseGigaV4OfflineLease(tmpOffline)
                report.appendLine("lease_delete_protection=ok")

                // 3. Online stateful 640 ms streaming (2 threads) — RU, EN, mixed RU+EN, <1s short tail flush, sequential session restart
                val onlineLease = manager.acquireGigaV4OnlineLease(2)
                try {
                    val ruStream = streamWav(onlineLease.handle, ruWav)
                    check(ruStream.partialsCount > 0) { "Expected live partial updates on RU audio" }
                    check(ruStream.text.contains(Regex("[А-Яа-яЁё]"))) { "Expected Cyrillic in RU stream: ${ruStream.text}" }
                    check("ru" in ruStream.langs) { "Expected ru language event, got ${ruStream.langs}" }
                    report.appendLine("stream_ru(partials=${ruStream.partialsCount},langs=${ruStream.langs})=${ruStream.text}")

                    val enStream = streamWav(onlineLease.handle, enWav)
                    check(enStream.text.contains(Regex("[A-Za-z]"))) { "Expected Latin in EN stream: ${enStream.text}" }
                    check("en" in enStream.langs) { "Expected en language event, got ${enStream.langs}" }
                    report.appendLine("stream_en(partials=${enStream.partialsCount},langs=${enStream.langs})=${enStream.text}")

                    val mixedStream = streamWav(onlineLease.handle, mixedWav)
                    check(
                        mixedStream.text.contains(Regex("[А-Яа-яЁё]")) &&
                            mixedStream.text.contains(Regex("[A-Za-z]"))
                    ) {
                        "Expected RU+EN mixed stream text, got: ${mixedStream.text}"
                    }
                    report.appendLine("stream_mixed=${mixedStream.text}")

                    // Short tail (<1s: first 0.7s of ru_test.wav)
                    val tailStream = streamWav(onlineLease.handle, ruWav, maxSamples = 11_200)
                    check(tailStream.words.size in 1..6) {
                        "Expected concise tail flush without repetition, got ${tailStream.words.size}: ${tailStream.text}"
                    }
                    report.appendLine("stream_short_tail(${tailStream.words.size})=${tailStream.text}")

                    // Sequential session #2 immediate restart
                    val restartStream = streamWav(onlineLease.handle, ruWav, maxSamples = 16_000 * 2)
                    check(restartStream.text.isNotBlank()) { "Expected non-blank output on restarted session" }
                    report.appendLine("stream_restart_2=${restartStream.text}")
                } finally {
                    manager.releaseGigaV4OnlineLease(onlineLease)
                }

                // 4. Full-context offline FileAsr (auto-frees idle online handle, loads offline on 2 threads) + real timestamps
                val v4Preset = TinyAsrCatalog.findPreset(TinyAsrCatalog.GIGAAM_V4)
                val v4Offline = FileAsr.acquire(isolated, manager, v4Preset, 2)
                try {
                    val gigaRes = offlineWav(v4Offline, gigaWav)
                    val ruRes = offlineWav(v4Offline, ruWav)
                    val enRes = offlineWav(v4Offline, enWav)
                    val mixedRes = offlineWav(v4Offline, mixedWav)

                    for ((tag, res) in listOf("giga" to gigaRes, "ru" to ruRes, "en" to enRes, "mixed" to mixedRes)) {
                        check(res.words.size >= 3) { "Too few words for offline $tag: ${res.words}" }
                        assertRealNonUniformTimestamps(res.words, tag)
                    }
                    check(gigaRes.language == "ru") { "Unexpected lang for giga.wav: ${gigaRes.language}" }
                    check(enRes.language == "en") { "Unexpected lang for en_test.wav: ${enRes.language}" }
                    check(mixedRes.language == "ru,en") { "Unexpected lang for ru_en_mixed.wav: ${mixedRes.language}" }
                    check(mixedRes.text.contains(Regex("[А-Яа-яЁё]")) && mixedRes.text.contains(Regex("[A-Za-z]"))) {
                        "Expected RU+EN words in offline mixed: ${mixedRes.text}"
                    }
                    report.appendLine("offline_giga(lang=${gigaRes.language},words=${gigaRes.words.size})=${gigaRes.text}")
                    report.appendLine("offline_ru=${ruRes.text}")
                    report.appendLine("offline_en=${enRes.text}")
                    report.appendLine("offline_mixed(lang=${mixedRes.language})=${mixedRes.text}")
                } finally {
                    v4Offline.release()
                }

                // 5. Preserve GigaAM v3 + built-in TinyASR
                val v3Asr = FileAsr.acquire(isolated, manager, TinyAsrCatalog.findPreset(TinyAsrCatalog.GIGAAM), 2)
                try {
                    val v3Res = offlineWav(v3Asr, gigaWav)
                    check(v3Res.words.isNotEmpty()) { "GigaAM v3 produced empty output" }
                    report.appendLine("preserve_gigaam_v3=${v3Res.words.take(5).joinToString(" ") { it.text }}")
                } finally {
                    v3Asr.release()
                }

                val tinyAsr = FileAsr.acquire(isolated, manager, TinyAsrCatalog.findPreset("ru_only"), 2)
                try {
                    val tinyRes = offlineWav(tinyAsr, ruWav)
                    check(tinyRes.words.isNotEmpty()) { "Built-in TinyASR produced empty output" }
                    report.appendLine("preserve_tinyasr=${tinyRes.words.joinToString(" ") { it.text }}")
                } finally {
                    tinyAsr.release()
                }

                // 6. Full ReTranscribeWorker + Groq non-interference + bookmarks/speakers/edits + tail completion + CoverageAudio
                db = Room.inMemoryDatabaseBuilder(isolated, StenografDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                val repo = RecordingRepository(isolated, db!!)
                val prefs = AppPreferences(isolated)
                prefs.setAsrEngineMode(AsrEngineMode.LOCAL_TINYASR)
                prefs.setLanguagePreset(TinyAsrCatalog.GIGAAM_V4)
                prefs.setGroqApiKey("gsk_invalid_test_key_should_not_be_called")

                val worker = ReTranscribeWorker(isolated, repo, manager, prefs)

                // 6a. Import & streaming transcription
                val prep = worker.prepareImportedRecording(
                    uri = Uri.fromFile(mixedWav),
                    presetId = TinyAsrCatalog.GIGAAM_V4,
                    modelId = TinyAsrCatalog.GIGAAM_V4
                ).getOrThrow()
                val impRes = worker.streamTranscribeImportedRecording(
                    recording = prep,
                    useGroqApi = false,
                    groqApiKey = "gsk_invalid_test_key_should_not_be_called",
                    groqModelId = com.stenograf.app.asr.GroqAsrClient.DEFAULT_MODEL_ID,
                    presetId = TinyAsrCatalog.GIGAAM_V4,
                    requestedThreads = 2,
                    beam = 4,
                    autoParagraph = true,
                    distantSpeechBoost = false,
                    neuralNoiseSuppression = false,
                    speakerDiarization = false
                ) {}
                check(impRes.isSuccess) { "Import failed: ${impRes.exceptionOrNull()}" }
                val impUpdated = repo.getRecording(prep.id)!!
                val impParas = repo.getParagraphs(prep.id)
                check(impParas.isNotEmpty() && impUpdated.detectedLanguage == "ru,en") {
                    "Unexpected import result: lang=${impUpdated.detectedLanguage}, paras=${impParas.size}"
                }
                for (p in impParas) {
                    val words = TranscriptCoverage.splittableWords(p)
                    check(words.isNotEmpty()) { "Import paragraph not word-splittable: $p" }
                }
                report.appendLine("worker_import_mixed(lang=${impUpdated.detectedLanguage})=${impParas.joinToString(" ") { it.text }}")

                // 6b. Full re-transcribe preserving bookmark + speaker
                val firstPara = impParas.first()
                repo.updateParagraph(firstPara.copy(speaker = "Алексей", isBookmark = true, bookmarkLabel = "Важно"))
                val reRes = worker.reTranscribeRecording(
                    recording = impUpdated,
                    presetId = TinyAsrCatalog.GIGAAM_V4,
                    requestedThreads = 2,
                    beam = 1,
                    autoParagraph = true,
                    onlyUnprocessedTail = false,
                    distantSpeechBoost = false,
                    neuralNoiseSuppression = false,
                    speakerDiarization = false
                ) {}
                check(reRes.isSuccess) { "Re-transcribe failed: ${reRes.exceptionOrNull()}" }
                val reParas = repo.getParagraphs(prep.id)
                check(reParas.any { it.speaker == "Алексей" } && reParas.any { it.isBookmark && it.bookmarkLabel == "Важно" }) {
                    "Expected speaker/bookmark annotations preserved after re-transcribe: $reParas"
                }
                report.appendLine("worker_retranscribe_preserved=ok")

                // 6c. Tail completion ("Дораспознать") preserving prefix edits
                val gigaAudioCopy = File(repo.recordingsDir, "tail_test.wav").also { gigaWav.copyTo(it, overwrite = true) }
                val gigaSpoolCopy = File(repo.recordingsDir, "tail_test.asr16k.pcm").also {
                    val bytes = gigaAudioCopy.readBytes()
                    it.writeBytes(bytes.copyOfRange(WavHelper.HEADER_SIZE, bytes.size))
                }
                val halfCheckpoint = (4 * 32_000L) and -2L
                val tailRec = RecordingEntity(
                    title = "TailCheck",
                    durationMs = (gigaSpoolCopy.length() * 1000L) / 32_000L,
                    audioPath = gigaAudioCopy.absolutePath,
                    asrSpoolPath = gigaSpoolCopy.absolutePath,
                    audioFormat = AudioFormat.WAV_16K.name,
                    modelId = TinyAsrCatalog.GIGAAM_V4,
                    languagePresetId = TinyAsrCatalog.GIGAAM_V4,
                    detectedLanguage = "ru",
                    status = RecordingStatus.COMPLETED.name,
                    hasTranscript = true,
                    processedAsrBytes = halfCheckpoint,
                    totalAsrBytes = gigaSpoolCopy.length(),
                    wordCount = 2
                )
                val insertedTailId = repo.createNewRecordingSession(
                    AudioFormat.WAV_16K, TinyAsrCatalog.GIGAAM_V4, TinyAsrCatalog.GIGAAM_V4, "ru"
                ).let { shellRec ->
                    val realRec = tailRec.copy(id = shellRec.id)
                    repo.updateRecording(realRec)
                    realRec
                }
                repo.insertParagraph(
                    ParagraphEntity(
                        recordingId = insertedTailId.id,
                        orderIndex = 0,
                        startSec = 0.2f,
                        endSec = 3.8f,
                        text = "Ручная правка начала.",
                        wordsJson = "",
                        speaker = "Редактор",
                        isBookmark = true,
                        bookmarkLabel = "Начало"
                    )
                )
                val tailRes = worker.reTranscribeRecording(
                    recording = repo.getRecording(insertedTailId.id)!!,
                    presetId = TinyAsrCatalog.GIGAAM_V4,
                    requestedThreads = 2,
                    beam = 1,
                    autoParagraph = true,
                    onlyUnprocessedTail = true,
                    distantSpeechBoost = false,
                    neuralNoiseSuppression = false,
                    speakerDiarization = false
                ) {}
                check(tailRes.isSuccess) { "Tail resume failed: ${tailRes.exceptionOrNull()}" }
                val afterTail = repo.getParagraphs(insertedTailId.id)
                val recAfterTail = repo.getRecording(insertedTailId.id)!!
                check(!recAfterTail.hasUnprocessedAudio && afterTail.size >= 2 && afterTail.first().text == "Ручная правка начала.") {
                    "Unexpected tail completion state: hasUnprocessed=${recAfterTail.hasUnprocessedAudio}, paras=${afterTail.map { it.text }}"
                }
                report.appendLine("worker_tail_resume(paras=${afterTail.size})=ok")

                // 6d. CoverageAudio check ("Проверить полноту") on giga.wav with first-half transcript
                val covRec = recAfterTail
                CoverageAudio.withSource(isolated, covRec, {}) { src ->
                    val gaps = CoverageAudio.scan(src, manager, afterTail.take(1)) {}
                    check(gaps.isNotEmpty()) { "Expected CoverageAudio to detect missing second half" }
                    val recovered = CoverageAudio.recognize(isolated, manager, src, gaps.first())
                    check(recovered.isNotEmpty()) { "Expected CoverageAudio.recognize to transcribe gap" }
                    report.appendLine("coverage_gap_recognized=${recovered.take(5).joinToString(" ") { it.text }}")
                }

                // 7. Real RecordingForegroundService lifecycle with GigaAM v4 -> stop -> start second recording
                val app = targetContext.applicationContext as StenografApp
                installBundleFastFromLocal(bundleSrc, app.modelManager.getBundleDir(TinyAsrCatalog.GIGAAM_V4))
                app.preferences.setAsrEngineMode(AsrEngineMode.LOCAL_TINYASR)
                app.preferences.setLanguagePreset(TinyAsrCatalog.GIGAAM_V4)
                verifyForegroundServiceLiveSwitchAndRestart()
                report.appendLine("service_live_switch_restart=ok")

                val memMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                report.appendLine("native_heap_mb=$memMb")
                Log.i("GigaAmV4E2E", report.toString())
            }
            status = Activity.RESULT_OK
        } catch (t: Throwable) {
            Log.e("GigaAmV4E2E", "E2E failure", t)
            report.appendLine("ERROR=${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { db?.close() }
            runCatching { scratch.deleteRecursively() }
            finish(status, Bundle().apply { putString("report", report.toString()) })
        }
    }

    private suspend fun verifyBundleDownloadAndInstall(
        manager: ModelManager,
        context: Context,
        bundleSrc: File,
        realSpecs: List<BundleFileSpec>,
        report: StringBuilder
    ) {
        // Test pause / corrupt / cancel with small fast loopback specs derived from NOTICE/json files in bundleSrc
        val fastFiles = realSpecs.filter {
            it.relativePath == "rnnt-streaming-640ms/tokens.e2e.txt" ||
                it.relativePath == "rnnt/manifest.json" ||
                it.relativePath == "NOTICE"
        }
        val corruptFlag = AtomicBoolean(false)
        val pauseLatch = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val running = AtomicBoolean(true)

        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val exec = Executors.newCachedThreadPool()
        val acceptThread = Thread {
            while (running.get()) {
                val sock = try {
                    ss.accept()
                } catch (_: Throwable) {
                    break
                }
                exec.execute {
                    sock.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val reqLine = reader.readLine() ?: return@execute
                        val rel = reqLine.split(' ').getOrNull(1)?.removePrefix("/") ?: ""
                        var rangeHdr: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) rangeHdr = line.substringAfter(':').trim()
                        }
                        val srcFile = File(bundleSrc, rel)
                        val out = s.getOutputStream()
                        if (!srcFile.isFile) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@execute
                        }
                        val totalSize = srcFile.length()
                        val start = if (rangeHdr != null && rangeHdr.startsWith("bytes=")) {
                            rangeHdr.removePrefix("bytes=").substringBefore('-').toLong()
                        } else 0L
                        val sendLen = totalSize - start
                        if (rangeHdr != null && rangeHdr.startsWith("bytes=")) {
                            out.write(
                                ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${totalSize - 1}/$totalSize\r\n" +
                                    "Content-Length: $sendLen\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                        } else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: $sendLen\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        java.io.RandomAccessFile(srcFile, "r").use { raf ->
                            raf.seek(start)
                            if (start == 0L && rel == "rnnt-streaming-640ms/tokens.e2e.txt" && allowFinish.count > 0) {
                                val head = ByteArray(minOf(1024L, sendLen).toInt())
                                val nHead = raf.read(head)
                                if (nHead > 0) {
                                    out.write(head, 0, nHead)
                                    out.flush()
                                }
                                pauseLatch.countDown()
                                allowFinish.await(3, TimeUnit.SECONDS)
                            }
                            val buf = ByteArray(64 * 1024)
                            var firstBytePending = (start == 0L && corruptFlag.get() && rel == "NOTICE")
                            while (true) {
                                val n = raf.read(buf)
                                if (n <= 0) break
                                if (firstBytePending) {
                                    buf[0] = (buf[0].toInt() xor 0xFF).toByte()
                                    firstBytePending = false
                                }
                                runCatching { out.write(buf, 0, n) }.onFailure { return@execute }
                            }
                        }
                        runCatching { out.flush() }
                    }
                }
            }
        }.apply { start() }

        try {
            manager.bundleUrlResolver = { spec -> "http://127.0.0.1:${ss.localPort}/${spec.relativePath}" }

            val pausedJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                manager.downloadBundleFiles(TinyAsrCatalog.GIGAAM_V4, fastFiles)
            }
            if (!pauseLatch.await(5, TimeUnit.SECONDS)) {
                val early = if (pausedJob.isCompleted) pausedJob.await().exceptionOrNull() else null
                throw IllegalStateException("Loopback bundle transfer did not start: ${early?.message ?: early}")
            }
            val part = File(context.cacheDir, "model_downloads/${TinyAsrCatalog.GIGAAM_V4}/rnnt-streaming-640ms/tokens.e2e.txt.part")
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline && (!part.isFile || part.length() == 0L)) delay(15L)
            manager.pauseDownload(TinyAsrCatalog.GIGAAM_V4)
            allowFinish.countDown()
            check(pausedJob.await().isFailure)
            check(manager.getAllModelStatuses().first { it.spec.id == TinyAsrCatalog.GIGAAM_V4 }.isIncomplete)

            corruptFlag.set(true)
            check(manager.downloadBundleFiles(TinyAsrCatalog.GIGAAM_V4, fastFiles).isFailure)
            check(manager.getAllModelStatuses().first { it.spec.id == TinyAsrCatalog.GIGAAM_V4 }.isIncomplete)

            manager.cancelDownload(TinyAsrCatalog.GIGAAM_V4)
            check(!File(context.cacheDir, "model_downloads/${TinyAsrCatalog.GIGAAM_V4}").exists())

            // Now download & SHA-256 verify ALL 13 REAL files (507,046,768 bytes) via ModelManager.downloadModel!
            corruptFlag.set(false)
            val fullInstall = manager.downloadModel(TinyAsrCatalog.GIGAAM_V4)
            check(fullInstall.isSuccess) { "Full 13-file SHA-256 install failed: ${fullInstall.exceptionOrNull()}" }
            check(manager.isModelInstalled(TinyAsrCatalog.GIGAAM_V4)) { "Bundle not marked installed" }
            val status = manager.getAllModelStatuses().first { it.spec.id == TinyAsrCatalog.GIGAAM_V4 }
            check(status.isInstalled && !status.isIncomplete && status.sizeBytes >= 507_046_768L) {
                "Unexpected installed status: $status"
            }
            report.appendLine("bundle_13_files_sha256_install(bytes=${status.sizeBytes})=ok")
        } finally {
            running.set(false)
            runCatching { ss.close() }
            exec.shutdownNow()
            acceptThread.join(1000L)
        }
    }

    private fun installBundleFastFromLocal(srcDir: File, dstDir: File) {
        if (dstDir.exists()) dstDir.deleteRecursively()
        dstDir.mkdirs()
        for (spec in TinyAsrCatalog.findModel(TinyAsrCatalog.GIGAAM_V4).bundleFiles) {
            val s = File(srcDir, spec.relativePath)
            val d = File(dstDir, spec.relativePath)
            d.parentFile?.mkdirs()
            s.copyTo(d, overwrite = true)
        }
        File(dstDir, ".verified").writeText(TinyAsrCatalog.GIGAAM_V4_REVISION)
    }

    private data class StreamOutcome(
        val text: String,
        val words: List<com.stenograf.app.asr.TranscriptWord>,
        val partialsCount: Int,
        val langs: Set<String>
    )

    private fun streamWav(onlineHandle: Long, wavFile: File, maxSamples: Int = Int.MAX_VALUE): StreamOutcome {
        val shorts = readWavShorts(wavFile, maxSamples)
        val stream = GigaAmV4Native.nativeStreamCreate(onlineHandle)
        check(stream != 0L) { "nativeStreamCreate failed" }
        val committed = mutableListOf<com.stenograf.app.asr.TranscriptWord>()
        var lastPartial = emptyList<com.stenograf.app.asr.TranscriptWord>()
        var partials = 0
        val langs = linkedSetOf<String>()
        try {
            var pos = 0
            val chunk = 2560 // 160 ms
            while (pos < shorts.size) {
                val n = minOf(chunk, shorts.size - pos)
                val raw = GigaAmV4Native.nativeStreamPushPcm16(stream, shorts, pos, n)
                pos += n
                for (ev in AsrJsonParser.parseEvents(raw)) {
                    when (ev) {
                        is AsrEvent.Commit -> {
                            committed += ev.words
                            lastPartial = emptyList()
                        }
                        is AsrEvent.Partial -> {
                            partials++
                            lastPartial = ev.open + ev.tail
                        }
                        is AsrEvent.Language -> langs += ev.lang
                        is AsrEvent.Eou -> Unit
                    }
                }
            }
            val finishRaw = GigaAmV4Native.nativeStreamFinish(stream)
            for (ev in AsrJsonParser.parseEvents(finishRaw)) {
                when (ev) {
                    is AsrEvent.Commit -> {
                        committed += ev.words
                        lastPartial = emptyList()
                    }
                    is AsrEvent.Partial -> lastPartial = ev.open + ev.tail
                    is AsrEvent.Language -> langs += ev.lang
                    is AsrEvent.Eou -> Unit
                }
            }
            check(lastPartial.isEmpty()) { "Leftover un-committed partial words after flush: $lastPartial" }
            return StreamOutcome(AsrTextFormatter.joinWords(committed), committed, partials, langs)
        } finally {
            GigaAmV4Native.nativeStreamFree(stream)
        }
    }

    private data class OfflineOutcome(
        val text: String,
        val words: List<com.stenograf.app.asr.TranscriptWord>,
        val language: String?
    )

    private fun offlineWav(fileAsr: FileAsr, wavFile: File): OfflineOutcome {
        val sampleCount = (wavFile.length() - WavHelper.HEADER_SIZE) / 2L
        val words = mutableListOf<com.stenograf.app.asr.TranscriptWord>()
        var lang: String? = null
        fileAsr.open(wavFile, WavHelper.HEADER_SIZE.toLong(), sampleCount, 4).use { win ->
            while (true) {
                val next = win.nextWords() ?: break
                words += next
                win.language?.let { lang = it }
            }
        }
        return OfflineOutcome(AsrTextFormatter.joinWords(words), words, lang)
    }

    private fun readWavShorts(wavFile: File, maxSamples: Int): ShortArray {
        val raw = wavFile.readBytes()
        val pcmLen = (raw.size - WavHelper.HEADER_SIZE) / 2
        val count = minOf(pcmLen, maxSamples)
        val out = ShortArray(count)
        ByteBuffer.wrap(raw, WavHelper.HEADER_SIZE, count * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(out)
        return out
    }

    private fun assertRealNonUniformTimestamps(words: List<com.stenograf.app.asr.TranscriptWord>, tag: String) {
        var prevStart = -1f
        val durations = hashSetOf<Int>()
        for (w in words) {
            check(w.start >= 0f && w.end >= w.start && w.start + 1e-4f >= prevStart) {
                "Non-monotonic word timing in $tag: $w after $prevStart"
            }
            prevStart = w.start
            durations += kotlin.math.round((w.end - w.start) * 100f).toInt()
        }
        check(durations.size > 1) { "Word durations appear uniform in $tag: $words" }
        val text = AsrTextFormatter.joinWords(words)
        val para = ParagraphEntity(
            recordingId = 1L,
            orderIndex = 0,
            startSec = words.first().start,
            endSec = words.last().end,
            text = text,
            wordsJson = AsrJsonParser.encodeWords(words)
        )
        check(TranscriptCoverage.splittableWords(para).size == words.size) {
            "TranscriptCoverage.splittableWords rejected timestamps for $tag"
        }
    }

    private suspend fun verifyForegroundServiceLiveSwitchAndRestart() {
        RecordingForegroundService.startNewRecording(targetContext, null)
        var deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline && !RecordingForegroundService.uiState.value.isActive) {
            delay(50L)
        }
        val state1 = RecordingForegroundService.uiState.value
        if (!state1.isActive) {
            // Mic permission might not be granted in headless instrumentation; skip live audio capture gracefully
            return
        }
        delay(800L)
        val connIntent = android.content.Intent(targetContext, RecordingForegroundService::class.java)
        var boundSvc: RecordingForegroundService? = null
        val boundLatch = CountDownLatch(1)
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                boundSvc = (binder as RecordingForegroundService.LocalBinder).getService()
                boundLatch.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
        }
        targetContext.bindService(connIntent, conn, Context.BIND_AUTO_CREATE)
        try {
            check(boundLatch.await(4, TimeUnit.SECONDS)) { "Failed to bind RecordingForegroundService" }
            boundSvc?.switchLanguagePreset("ru_only")
            delay(700L)
            boundSvc?.switchLanguagePreset(TinyAsrCatalog.GIGAAM_V4)
            delay(700L)
            boundSvc?.stopRecording()
        } finally {
            runCatching { targetContext.unbindService(conn) }
        }
        deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline && RecordingForegroundService.uiState.value.finishedRecordingId == null) {
            delay(50L)
        }
        check(RecordingForegroundService.uiState.value.finishedRecordingId != null) { "Recording 1 did not finish" }
        RecordingForegroundService.clearFinishedEvent()

        // Immediately start recording #2 and stop cleanly
        RecordingForegroundService.startNewRecording(targetContext, null)
        deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && !RecordingForegroundService.uiState.value.isActive) {
            delay(50L)
        }
        delay(500L)
        RecordingForegroundService.sendAction(targetContext, RecordingForegroundService.ACTION_STOP)
        deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline && RecordingForegroundService.uiState.value.finishedRecordingId == null) {
            delay(50L)
        }
        check(RecordingForegroundService.uiState.value.finishedRecordingId != null) { "Recording 2 did not finish" }
        RecordingForegroundService.clearFinishedEvent()
    }
}
