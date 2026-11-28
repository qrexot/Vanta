package com.stenograf.app

import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.timedCtcWords
import com.stenograf.app.audio.AudioStft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioModelIntegrationTest {
    @Test
    fun gigaAmIsAvailableForFilesAndExcludedFromStreamingPresets() {
        val preset = TinyAsrCatalog.FILE_PRESETS.single { it.modelId == TinyAsrCatalog.GIGAAM }
        val model = TinyAsrCatalog.findModel(preset.modelId)
        assertEquals("ru", preset.langsCsv)
        assertFalse(model.bundledInApk)
        assertTrue(requireNotNull(model.onnxDownload).bytes > 0)
        assertFalse(TinyAsrCatalog.PRESETS.any { it.modelId == model.id })
    }

    @Test
    fun ctcJoinsSubwordsAndPunctuationWithOriginalTiming() {
        val words = timedCtcWords(
            arrayOf(" ", "До", "брый", ",", " день", "!"),
            floatArrayOf(0f, 0.04f, 0.20f, 0.28f, 0.48f, 0.64f),
            duration = 0.65f
        )
        assertEquals(listOf("Добрый,", "день!"), words.map { it.text })
        assertEquals(0.04f, words[0].start, 0.0001f)
        assertEquals(0.48f, words[1].start, 0.0001f)
        assertEquals(0.65f, words[1].end, 0.0001f)
    }

    @Test
    fun stftRoundTripPreservesSampleCountAndAlignmentAtBothRates() {
        val input = FloatArray(6_031) { i -> (0.4 * sin(i * 0.08) + 0.2 * sin(i * 1.3)).toFloat() }
        for (size in listOf(512, 960)) {
            val stft = AudioStft(size)
            val spectrum = stft.analyze(input) {}
            val output = stft.synthesize(spectrum, input.size) {}
            assertEquals(input.size, output.size)
            assertTrue(output.indices.all { abs(output[it] - input[it]) < 1e-5 })
        }
    }
}
