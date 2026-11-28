package com.stenograf.app.audio

import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.prefs.AppSettings
import com.stenograf.app.data.prefs.RecognitionAudioFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioTreatmentTest {
    @Test
    fun selectsSameShortRangeForOriginalAndFilteredPreview() {
        assertEquals(12_000L until 20_000L, treatmentSampleRange(25_000L, 12_000L, 8_000L))
        assertEquals(23_000L until 25_000L, treatmentSampleRange(25_000L, 23_000L, 8_000L))
        assertEquals(17_000L until 25_000L, treatmentSampleRange(25_000L, 25_000L, 8_000L))
        assertEquals(0L until 25_000L, treatmentSampleRange(25_000L, 12_000L, null))
        assertTrue(treatmentSampleRange(0L, 0L, 8_000L).isEmpty())
    }

    @Test
    fun everyDownloadableAudioFilterHasAMatchingCatalogEntry() {
        val specById = TinyAsrCatalog.DOWNLOAD_MODELS.associateBy { it.id }
        for (filter in AudioTreatment.entries.filterNot { it.isBuiltIn }) {
            val spec = specById[filter.modelId]
            assertNotNull(spec)
            assertTrue(spec!!.isOnnxModel)
        }
        assertTrue(AudioTreatment.QUICK_CLEANUP.isBuiltIn)
    }

    @Test
    fun recognitionFiltersReadLegacyFlagsWithoutMigratingSettings() {
        assertEquals(RecognitionAudioFilter.STANDARD, AppSettings().recognitionAudioFilter)
        assertEquals(RecognitionAudioFilter.FAST, AppSettings(fastEnhancer = true).recognitionAudioFilter)
        assertEquals(RecognitionAudioFilter.OFF, AppSettings(neuralNoiseSuppression = false, fastEnhancer = true).recognitionAudioFilter)
    }
}
