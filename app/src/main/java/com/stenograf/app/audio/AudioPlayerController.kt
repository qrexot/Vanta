package com.stenograf.app.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlayerState(
    val loadedPath: String? = null,
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val loudnessBoost: Boolean = false,
    val error: String? = null
)

class AudioPlayerController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var tickerJob: Job? = null
    private var generation = 0
    private var previewEndMs: Long? = null
    private var previewSeekMs: Long? = null
    @Volatile private var lastManualSeekAtMs = 0L
    @Volatile private var lastManualSeekTargetMs = 0L

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    suspend fun load(audioPath: String) {
        if (_state.value.loadedPath == audioPath && player != null && _state.value.isReady) {
            return
        }
        release()
        val loadGeneration = generation
        val strings = AppLocaleManager.currentStrings()
        val mp = MediaPlayer()
        try {
            val hasAudio = withContext(Dispatchers.IO) {
                val file = File(audioPath)
                if (!file.isFile || file.length() == 0L) false
                else {
                    mp.setDataSource(audioPath)
                    true
                }
            }
            if (loadGeneration != generation) {
                disposePlayer(mp)
                return
            }
            if (!hasAudio) {
                disposePlayer(mp)
                _state.value = _state.value.copy(loadedPath = audioPath, error = strings.audioFileNotFound)
                return
            }
            mp.setOnPreparedListener { readyMp ->
                if (player !== readyMp) return@setOnPreparedListener
                val dur = readyMp.duration.toLong().coerceAtLeast(0L)
                // Never call setPlaybackParams() here: on Android, setting PlaybackParams
                // on a prepared MediaPlayer silently starts playback even when paused.
                initEnhancer(readyMp)
                _state.value = _state.value.copy(
                    isReady = true,
                    isPlaying = false,
                    durationMs = dur,
                    error = null
                )
            }
            mp.setOnSeekCompleteListener { source ->
                val target = previewSeekMs ?: return@setOnSeekCompleteListener
                if (source !== player || kotlin.math.abs(source.currentPosition.toLong() - target) > 250L) {
                    return@setOnSeekCompleteListener
                }
                previewSeekMs = null
                startPlayback(source)
            }
            mp.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                previewEndMs = null
                previewSeekMs = null
                tickerJob?.cancel()
                _state.value = _state.value.copy(
                    isPlaying = false,
                    positionMs = _state.value.durationMs
                )
            }
            mp.setOnErrorListener { source, what, extra ->
                if (player !== source) return@setOnErrorListener true
                val failed = _state.value
                release()
                _state.value = failed.copy(
                    isPlaying = false,
                    isReady = false,
                    error = "${AppLocaleManager.currentStrings().playbackError} ($what/$extra)"
                )
                true
            }
            player = mp
            _state.value = PlayerState(
                loadedPath = audioPath,
                speed = _state.value.speed,
                loudnessBoost = _state.value.loudnessBoost
            )
            mp.prepareAsync()
        } catch (e: CancellationException) {
            disposePlayer(mp)
            throw e
        } catch (e: Exception) {
            if (player === mp) player = null
            disposePlayer(mp)
            if (loadGeneration == generation) {
                _state.value = _state.value.copy(loadedPath = audioPath, error = e.message ?: strings.playerOpenError)
            }
        }
    }

    fun togglePlayPause() {
        val mp = player ?: return
        if (!_state.value.isReady) return
        previewEndMs = null
        previewSeekMs = null
        if (_state.value.isPlaying || mp.isPlaying) {
            runCatching { if (mp.isPlaying) mp.pause() }
            tickerJob?.cancel()
            _state.value = _state.value.copy(isPlaying = false, positionMs = mp.currentPosition.toLong())
        } else {
            if (_state.value.durationMs > 0 && mp.currentPosition >= _state.value.durationMs - 150) {
                mp.seekTo(0)
            }
            startPlayback(mp)
        }
    }

    fun seekTo(ms: Long, autoPlay: Boolean = false) {
        previewEndMs = null
        previewSeekMs = null
        seek(ms, autoPlay)
    }

    fun previewRange(startMs: Long, endMs: Long) {
        val mp = player ?: return
        if (!_state.value.isReady || startMs >= _state.value.durationMs || endMs <= startMs) return
        stopPreview()
        if (mp.isPlaying) togglePlayPause()
        previewEndMs = endMs.coerceAtMost(_state.value.durationMs)
        previewSeekMs = startMs
        seek(startMs, autoPlay = false)
    }

    fun stopPreview() {
        if (previewEndMs == null) return
        previewEndMs = null
        previewSeekMs = null
        val mp = player ?: return
        if (_state.value.isReady && mp.isPlaying) togglePlayPause()
    }

    private fun seek(ms: Long, autoPlay: Boolean) {
        val mp = player ?: return
        if (!_state.value.isReady) return
        val clamped = ms.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        lastManualSeekTargetMs = clamped
        lastManualSeekAtMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            mp.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
        }.onFailure {
            runCatching { mp.seekTo(clamped.toInt()) }
        }
        _state.value = _state.value.copy(positionMs = clamped)
        if (autoPlay && !_state.value.isPlaying) startPlayback(mp)
    }

    fun seekBy(deltaMs: Long) {
        val state = _state.value
        if (!state.isReady) return
        seekTo(state.positionMs + deltaMs, autoPlay = false)
    }

    fun setSpeed(speed: Float) {
        if (!speed.isFinite()) return
        val clamped = speed.coerceIn(0.5f, 2.5f)
        _state.value = _state.value.copy(speed = clamped)
        val mp = player ?: return
        // Apply immediately ONLY when already playing so paused audio never starts by itself.
        if (_state.value.isReady && (_state.value.isPlaying || mp.isPlaying)) {
            applySpeed(mp, clamped)
        }
    }

    fun toggleLoudnessBoost() {
        val next = !_state.value.loudnessBoost
        _state.value = _state.value.copy(loudnessBoost = next)
        runCatching {
            loudnessEnhancer?.apply {
                setTargetGain(if (next) 1200 else 0)
                enabled = next
            }
        }
    }

    private fun initEnhancer(mp: MediaPlayer) {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = runCatching {
            LoudnessEnhancer(mp.audioSessionId).apply {
                val boost = _state.value.loudnessBoost
                setTargetGain(if (boost) 1200 else 0)
                enabled = boost
            }
        }.getOrNull()
    }

    private fun startPlayback(mp: MediaPlayer) {
        val targetSpeed = _state.value.speed
        try {
            val currentSpeed = runCatching { mp.playbackParams.speed }.getOrDefault(1.0f)
            if (currentSpeed != targetSpeed) applySpeed(mp, targetSpeed)
            if (!mp.isPlaying) mp.start()
            _state.value = _state.value.copy(isPlaying = true, error = null)
            startTicker()
        } catch (failure: IllegalStateException) {
            val failed = _state.value
            release()
            _state.value = failed.copy(
                isReady = false,
                isPlaying = false,
                error = AppLocaleManager.currentStrings().playbackError
            )
        }
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        runCatching {
            val params = PlaybackParams().setSpeed(speed)
            mp.playbackParams = params
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val mp = player
                if (mp != null && mp.isPlaying) {
                    val pos = mp.currentPosition.toLong()
                    val end = previewEndMs
                    if (end != null && pos >= end) {
                        stopPreview()
                        return@launch
                    }
                    val sinceManualSeek = android.os.SystemClock.elapsedRealtime() - lastManualSeekAtMs
                    // Prevent a brief 1-2 frame backward highlight flicker while the async codec seek settles.
                    if (sinceManualSeek >= 180L || kotlin.math.abs(pos - lastManualSeekTargetMs) <= 450L) {
                        _state.value = _state.value.copy(positionMs = pos)
                    }
                }
                delay(55L)
            }
        }
    }

    private fun disposePlayer(player: MediaPlayer) {
        scope.launch(Dispatchers.IO) { runCatching { player.release() } }
    }

    fun release() {
        generation++
        previewEndMs = null
        previewSeekMs = null
        tickerJob?.cancel()
        tickerJob = null
        val enhancer = loudnessEnhancer
        loudnessEnhancer = null
        val oldPlayer = player
        player = null
        if (oldPlayer != null || enhancer != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { enhancer?.release() }
                runCatching { oldPlayer?.release() }
            }
        }
        _state.value = PlayerState(
            speed = _state.value.speed,
            loudnessBoost = _state.value.loudnessBoost
        )
    }
}
