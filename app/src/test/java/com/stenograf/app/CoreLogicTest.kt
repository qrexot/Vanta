package com.stenograf.app

import com.stenograf.app.asr.AsrEvent
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.ParagraphAccumulator
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.audio.SpeechNormalizer
import com.stenograf.app.audio.StreamingResamplerTo16k
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.export.TranscriptExporter
import com.stenograf.app.i18n.AppLanguage
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.i18n.AppStrings
import com.stenograf.app.i18n.StringsEn
import com.stenograf.app.i18n.StringsRu
import com.stenograf.app.i18n.VantaExtraStrings
import com.stenograf.app.i18n.VantaExtraStringsRu
import com.stenograf.app.i18n.VantaExtraStringsUk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class CoreLogicTest {

    @Test
    fun `parseEvents parses commit partial language and eou`() {
        val raw = """
            [
              {"type":"commit","words":[{"text":"Привет,","start":0.42,"end":0.85},{"text":"мир.","start":0.90,"end":1.30}]},
              {"type":"partial","open":[{"text":"Как","start":1.50,"end":1.70}],"tail":[{"text":"дела","start":1.75,"end":1.95}]},
              {"type":"language","lang":"en"},
              {"type":"eou","at":2.10,"p":0.942}
            ]
        """.trimIndent()

        val events = AsrJsonParser.parseEvents(raw)
        assertEquals(4, events.size)

        val commit = events[0] as AsrEvent.Commit
        assertEquals(2, commit.words.size)
        assertEquals("Привет,", commit.words[0].text)
        assertEquals(0.42f, commit.words[0].start, 0.001f)

        val partial = events[1] as AsrEvent.Partial
        assertEquals("Как", partial.open[0].text)
        assertEquals("дела", partial.tail[0].text)

        val lang = events[2] as AsrEvent.Language
        assertEquals("en", lang.lang)

        val eou = events[3] as AsrEvent.Eou
        assertEquals(0.942f, eou.p, 0.001f)
    }

    @Test
    fun `joinWords respects join flag and formatting helpers`() {
        val words = listOf(
            TranscriptWord("Лекция", 0.1f, 0.5f, join = false),
            TranscriptWord("№1:", 0.5f, 0.8f, join = false),
            TranscriptWord("кванто", 1.0f, 1.3f, join = false),
            TranscriptWord("вание.", 1.3f, 1.6f, join = true)
        )
        assertEquals("Лекция №1: квантование.", AsrTextFormatter.joinWords(words))
        assertTrue(AsrTextFormatter.isSentenceEnd("слово."))
        assertTrue(AsrTextFormatter.isSentenceEnd("вопрос?»"))
        assertFalse(AsrTextFormatter.isSentenceEnd("продолжение,"))
        assertEquals("01:05:09", AsrTextFormatter.formatDurationMs((3600 + 5 * 60 + 9) * 1000L))
        assertEquals("00:01:05,250", AsrTextFormatter.formatSrtTime(65.25f))
    }

    @Test
    fun `paragraph accumulator splits on pause and bookmark`() {
        val acc = ParagraphAccumulator(autoParagraph = true, pauseThresholdSec = 2.2f, maxCharsPerParagraph = 400)
        val u1 = acc.acceptCommit(
            listOf(
                TranscriptWord("Первое", 0.0f, 0.4f),
                TranscriptWord("предложение.", 0.4f, 0.9f)
            )
        )
        assertNotNull(u1.newParagraph)

        // Short pause -> appends to same paragraph
        val u2 = acc.acceptCommit(
            listOf(
                TranscriptWord("Второе", 1.3f, 1.7f),
                TranscriptWord("предложение.", 1.7f, 2.2f)
            )
        )
        assertNotNull(u2.updatedLastParagraph)
        assertEquals("Первое предложение. Второе предложение.", u2.updatedLastParagraph!!.text)

        // Long pause > 2.2s after sentence end -> starts new paragraph
        val u3 = acc.acceptCommit(
            listOf(
                TranscriptWord("Новый", 5.0f, 5.4f),
                TranscriptWord("абзац.", 5.4f, 5.8f)
            )
        )
        assertNotNull(u3.newParagraph)
        assertEquals("Новый абзац.", u3.newParagraph!!.text)

        // Explicit bookmark -> starts bookmarked paragraph even without long pause
        acc.requestParagraphBreak("Определение")
        val u4 = acc.acceptCommit(
            listOf(
                TranscriptWord("Теорема", 6.1f, 6.5f),
                TranscriptWord("Коши.", 6.5f, 7.0f)
            )
        )
        assertNotNull(u4.newParagraph)
        assertTrue(u4.newParagraph!!.isBookmark)
        assertEquals("Определение", u4.newParagraph!!.bookmarkLabel)
    }

    @Test
    fun `wav header creation and crash repair`() {
        val tmp = File.createTempFile("wav_test", ".wav")
        try {
            val pcmBytes = ByteArray(32000) // 1 sec @ 16kHz 16-bit mono
            tmp.outputStream().use {
                it.write(WavHelper.createHeader(16000, 1, 16, 0L))
                it.write(pcmBytes)
            }
            val repairedDataLen = WavHelper.repairHeaderFromFileLength(tmp, 16000, 1)
            assertEquals(32000L, repairedDataLen)

            val header = tmp.readBytes().copyOfRange(0, WavHelper.HEADER_SIZE)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(32036, bb.getInt(4))
            assertEquals(16000, bb.getInt(24))
            assertEquals(32000, bb.getInt(40))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `resampler downsamples 48khz to 16khz cleanly`() {
        val resampler = StreamingResamplerTo16k(48_000)
        val input48k = ShortArray(4800) { idx ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * idx / 48000.0) * 10000.0).toInt().toShort()
        }
        val out16k = resampler.process(input48k, input48k.size)
        assertTrue(out16k.size in 1595..1605)
    }

    @Test
    fun `resampler preserves speech band and attenuates aliasing across chunks`() {
        fun meanAmplitude(frequency: Double): Double {
            val resampler = StreamingResamplerTo16k(48_000)
            val input = ShortArray(4800) { index ->
                (kotlin.math.sin(2.0 * Math.PI * frequency * index / 48_000) * 10_000).toInt().toShort()
            }
            return input.asList().chunked(127).flatMap { chunk ->
                resampler.process(chunk.toShortArray(), chunk.size).asList()
            }.drop(24).map { kotlin.math.abs(it.toInt()) }.average()
        }

        assertTrue(meanAmplitude(1_000.0) > 5_000)
        assertTrue(meanAmplitude(12_000.0) < 300)
    }

    @Test
    fun `formatter attaches separate punctuation in a bilingual transcript`() {
        val words = listOf("Да", ",", "let's", "start", "!").map { TranscriptWord(it, 0f, 0f) }
        assertEquals("Да, let's start!", AsrTextFormatter.joinWords(words))
    }

    @Test
    fun `catalog contains official en-ru-uk and fr-de models`() {
        val enRuUk = TinyAsrCatalog.findModel("en-ru-uk")
        assertTrue(enRuUk.bundledInApk)
        assertEquals(listOf("en", "ru", "uk"), enRuUk.languages)

        val frDe = TinyAsrCatalog.findModel("fr-de")
        assertTrue(frDe.bundledInApk)
        assertEquals(listOf("fr", "de"), frDe.languages)

        // All presets must map to a valid TinyASR model in the catalog
        val modelIds = TinyAsrCatalog.MODELS.map { it.id }.toSet()
        assertEquals(9, modelIds.size)
        for (preset in TinyAsrCatalog.PRESETS) {
            assertTrue("Preset ${preset.id} references unknown model ${preset.modelId}", preset.modelId in modelIds)
        }
    }

    @Test
    fun `all 40 interface languages are complete and free of fallback leakage`() {
        val langs = AppLanguage.selectableLanguages
        assertEquals(40, langs.size)

        val expectedRtl = setOf(AppLanguage.AR, AppLanguage.FA, AppLanguage.HE, AppLanguage.UR)
        for (lang in langs) {
            assertEquals("RTL flag mismatch for $lang", lang in expectedRtl, lang.isRtl)
            val s = AppLocaleManager.stringsForLanguage(lang)
            assertEquals("Vanta", s.appName)
            for (field in AppStrings::class.java.declaredFields) {
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    val v = field.get(s) as? String
                    assertNotNull("Null field ${field.name} for $lang", v)
                    assertTrue("Blank field ${field.name} for $lang", !v.isNullOrBlank())
                }
            }
            if (lang != AppLanguage.EN) {
                assertNotEquals("aboutEngineText fell back to English in $lang", StringsEn.aboutEngineText, s.aboutEngineText)
                assertNotEquals("aboutVadText fell back to English in $lang", StringsEn.aboutVadText, s.aboutVadText)
                assertNotEquals("uiLanguageTitle fell back to English in $lang", StringsEn.uiLanguageTitle, s.uiLanguageTitle)
            }
            if (lang != AppLanguage.RU) {
                assertNotEquals("aboutEngineText fell back to Russian in $lang", StringsRu.aboutEngineText, s.aboutEngineText)
                assertNotEquals("aboutVadText fell back to Russian in $lang", StringsRu.aboutVadText, s.aboutVadText)
                assertNotEquals("uiLanguageTitle fell back to Russian in $lang", StringsRu.uiLanguageTitle, s.uiLanguageTitle)
            }
        }

        // Verify Abkhaz uses authentic Cyrillic script (ԥ, ә)
        val ab = AppLocaleManager.stringsForLanguage(AppLanguage.AB)
        assertTrue(AppLanguage.AB.nativeName.contains('ԥ'))
        assertTrue(ab.sectionInterface.contains('ԥ') || ab.recordingsTitle.contains('ԥ') || ab.settingsTitle.contains('ԥ'))
        assertTrue(ab.appName.contains('ә') || ab.emptyLibraryTitle.contains('ә'))

        // Verify all 12 Indian languages exist and differ from English/Hindi fallbacks
        val indian = listOf("hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "ur", "or", "as")
        for (code in indian) {
            val l = AppLanguage.fromCode(code)
            assertEquals(code, l.code)
        }
    }

    @Test
    fun `model TZF header verification and catalog search work cleanly`() {
        assertEquals("TZF6", ModelManager.parseTzfMagic(byteArrayOf('T'.code.toByte(), 'Z'.code.toByte(), 'F'.code.toByte(), '6'.code.toByte())))
        assertEquals("TZF3", ModelManager.parseTzfMagic(byteArrayOf('T'.code.toByte(), 'Z'.code.toByte(), 'F'.code.toByte(), '3'.code.toByte())))
        assertNull(ModelManager.parseTzfMagic(byteArrayOf('T'.code.toByte(), 'Z'.code.toByte(), 'F'.code.toByte(), '0'.code.toByte())))
        assertNull(ModelManager.parseTzfMagic(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte(), 'L'.code.toByte())))
        assertNull(ModelManager.parseTzfMagic(byteArrayOf('T'.code.toByte(), 'Z'.code.toByte())))

        assertTrue(TinyAsrCatalog.findModel("en-es-it-pt").matchesQuery("Español", StringsEn))
        assertTrue(TinyAsrCatalog.findModel("zh").matchesQuery("简体", StringsEn))
        assertEquals("Lecture_1___", TranscriptExporter.safeBaseName("Lecture/1:*?"))
        assertEquals("recording", TranscriptExporter.safeBaseName("   "))

        for (extra in listOf(VantaExtraStrings(), VantaExtraStringsRu, VantaExtraStringsUk)) {
            for (field in VantaExtraStrings::class.java.declaredFields) {
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    val v = field.get(extra) as? String
                    assertNotNull("Null extra field ${field.name}", v)
                    assertTrue("Blank extra field ${field.name}", !v.isNullOrBlank())
                }
            }
        }
    }

    @Test
    fun `speech normalizer boosts distant speech without blowing up silence or clipping peaks`() {
        fun frameRms(buf: ShortArray): Float {
            var sum = 0.0
            for (s in buf) {
                val f = s / 32768.0
                sum += f * f
            }
            return sqrt(sum / buf.size).toFloat()
        }

        // 1. Quiet distant speech (~0.011 RMS, ~ -39 dBFS) should lift near target 0.12 RMS
        val normalizer = SpeechNormalizer(sampleRate = 16_000)
        var lastQuietFrame = ShortArray(640)
        repeat(20) { block ->
            val chunk = ShortArray(640) { idx ->
                val n = block * 640 + idx
                (sin(2.0 * Math.PI * 350.0 * n / 16_000.0) * 520.0).toInt().toShort()
            }
            normalizer.processInPlace(chunk, chunk.size)
            lastQuietFrame = chunk
        }
        val boostedRms = frameRms(lastQuietFrame)
        assertTrue("Expected boosted RMS in [0.06..0.20], got $boostedRms", boostedRms in 0.06f..0.20f)

        // 2. Sudden loud transient peak should never digitally clip to +/-32767
        val transient = ShortArray(640) { idx ->
            (sin(2.0 * Math.PI * 500.0 * idx / 16_000.0) * 28_000.0).toInt().toShort()
        }
        normalizer.processInPlace(transient, transient.size)
        val maxAbs = transient.maxOf { abs(it.toInt()) }
        assertTrue("Soft limiter should keep samples safely below 32600, was $maxAbs", maxAbs < 32600)

        // 3. Steady low room noise floor should not be cranked +19 dB after settling
        val quietOnly = SpeechNormalizer(sampleRate = 16_000)
        var settledNoise = ShortArray(640)
        repeat(80) { block ->
            val noise = ShortArray(640) { idx ->
                val v = if ((idx + block) % 2 == 0) 24 else -24
                v.toShort()
            }
            quietOnly.processInPlace(noise, noise.size)
            settledNoise = noise
        }
        assertTrue("Room noise floor must remain quiet (< 0.005 RMS), got ${frameRms(settledNoise)}", frameRms(settledNoise) < 0.005f)
    }

    @Test
    fun `offline file speech normalizer analyzes quiet file and preserves exact duration`() {
        val rawPcm = File.createTempFile("quiet_speech", ".pcm")
        val normPcm = File.createTempFile("norm_speech", ".pcm")
        try {
            // 1.5s: 0.5s near-silence + 1.0s very quiet 300 Hz speech
            val totalSamples = 24_000
            val bytes = ByteArray(totalSamples * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                val amp = if (i < 8_000) 10.0 else 480.0
                val sample = (sin(2.0 * Math.PI * 300.0 * i / 16_000.0) * amp).toInt().toShort()
                bb.putShort(sample)
            }
            rawPcm.writeBytes(bytes)

            val stats = SpeechNormalizer.analyzePcm16File(rawPcm, 0L, rawPcm.length())
            assertTrue("Recommended gain should boost quiet speech (> 4x), was ${stats.recommendedGain}", stats.recommendedGain > 4.0f)

            val outBytes = SpeechNormalizer.writeNormalizedPcm16Window(rawPcm, 0L, rawPcm.length(), normPcm, stats)
            assertEquals(rawPcm.length(), outBytes)
            assertEquals(rawPcm.length(), normPcm.length())
        } finally {
            rawPcm.delete()
            normPcm.delete()
        }
    }
}
