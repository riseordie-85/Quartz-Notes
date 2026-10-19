package com.quartz.notes.data

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** App-wide voice note player. Only one recording plays at a time. */
class AudioPlayer(private val media: MediaRepository, private val scope: CoroutineScope) {

    data class State(
        val path: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Plays [path], or pauses/resumes it if it's the current recording. */
    fun toggle(path: String) {
        val current = player
        if (current != null && _state.value.path == path) {
            if (current.isPlaying) {
                current.pause()
                _state.value = _state.value.copy(playing = false)
                ticker?.cancel()
            } else {
                current.start()
                _state.value = _state.value.copy(playing = true)
                startTicker()
            }
            return
        }
        start(path, 0)
    }

    fun seek(path: String, positionMs: Long) {
        val current = player
        if (current != null && _state.value.path == path) {
            current.seekTo(positionMs.toInt())
            _state.value = _state.value.copy(positionMs = positionMs)
        } else {
            start(path, positionMs, autoplay = false)
        }
    }

    private fun start(path: String, positionMs: Long, autoplay: Boolean = true) {
        release()
        val file = media.file(path)
        if (!file.exists()) return
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        }.onFailure {
            mp.release()
            return
        }
        if (positionMs > 0) mp.seekTo(positionMs.toInt())
        mp.setOnCompletionListener {
            _state.value = _state.value.copy(playing = false, positionMs = 0)
            ticker?.cancel()
            runCatching { it.seekTo(0) }
        }
        player = mp
        _state.value = State(path = path, playing = autoplay, positionMs = positionMs, durationMs = mp.duration.toLong())
        if (autoplay) {
            mp.start()
            startTicker()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player ?: break
                _state.value = _state.value.copy(positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(0))
                delay(100)
            }
        }
    }

    /** Stops playback if [path] (or anything, when null) is playing. */
    fun stop(path: String? = null) {
        if (path == null || _state.value.path == path) {
            release()
            _state.value = State()
        }
    }

    private fun release() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}
