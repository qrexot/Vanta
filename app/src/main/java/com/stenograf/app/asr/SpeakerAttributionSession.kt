package com.stenograf.app.asr

import android.content.res.AssetManager
import android.util.Log
import java.io.Closeable
import kotlinx.coroutines.CancellationException

internal class SpeakerAttributionSession(
    private val assets: AssetManager,
    private val label: String,
    private val autoVoicePrefix: String = label,
    private val autoLearnSpeakers: Boolean = false,
    private val storedProfiles: List<StoredVoiceprint> = emptyList(),
    private val onPersistUpdates: (List<LearnedVoiceprintUpdate>) -> Unit = {},
    private val onUnavailable: () -> Unit = {}
) : Closeable {
    private var diarizer: SpeakerDiarizer? = null
    private var failed = false

    fun seed(name: String, pcm: ByteArray) = withDiarizer(Unit) { it.seed(name, pcm) }

    fun analyze(pcm: ByteArray, offsetBytes: Long, cancelled: () -> Boolean): SpeakerTimeline =
        withDiarizer(SpeakerTimeline.Empty) { it.analyze(pcm, offsetBytes, cancelled) }

    fun flushLearned() {
        if (!autoLearnSpeakers) return
        val updates = diarizer?.drainLearnedUpdates().orEmpty()
        if (updates.isNotEmpty()) {
            onPersistUpdates(updates)
        }
    }

    private fun <T> withDiarizer(fallback: T, action: (SpeakerDiarizer) -> T): T {
        if (failed) return fallback
        try {
            val model = diarizer ?: SpeakerDiarizer(
                assets = assets,
                label = label,
                autoVoicePrefix = autoVoicePrefix,
                autoLearnSpeakers = autoLearnSpeakers
            ).also {
                if (storedProfiles.isNotEmpty()) {
                    it.seedStoredProfiles(storedProfiles)
                }
                diarizer = it
            }
            return action(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            reportFailure(e)
        } catch (e: LinkageError) {
            reportFailure(e)
        }
        return fallback
    }

    private fun reportFailure(error: Throwable) {
        failed = true
        Log.w("Stenograf", "Speaker identification failed", error)
        onUnavailable()
    }

    override fun close() {
        runCatching { flushLearned() }
        diarizer?.close()
    }
}
