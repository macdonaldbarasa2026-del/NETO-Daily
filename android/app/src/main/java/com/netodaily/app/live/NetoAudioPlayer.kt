package com.netodaily.app.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, non-blocking PCM16 24 kHz audio player for NETO Gemini Live.
 *
 * Uses a dedicated playback thread and queue so WebSocket receive frames are
 * never blocked. Supports instant interruption / barge-in flushing.
 */
class NetoAudioPlayer(
    private val onPlaybackStateChanged: ((Boolean) -> Unit)? = null
) {

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    @Volatile
    private var isPlayingAudio = false

    fun start() {
        if (running.get()) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        if (minBufferSize <= 0) return

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE / 2)

        val audioTrack = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        runCatching {
            audioTrack.play()
        }.onFailure {
            audioTrack.release()
            return
        }

        track = audioTrack
        running.set(true)

        playbackThread = Thread({
            playbackLoop()
        }, "NETO-AudioPlayer").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun playbackLoop() {
        while (running.get()) {
            try {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null && chunk.isNotEmpty()) {
                    if (!isPlayingAudio) {
                        isPlayingAudio = true
                        onPlaybackStateChanged?.invoke(true)
                    }

                    val currentTrack = track
                    if (currentTrack != null && running.get()) {
                        currentTrack.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    }
                } else {
                    if (isPlayingAudio && queue.isEmpty()) {
                        isPlayingAudio = false
                        onPlaybackStateChanged?.invoke(false)
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                // Keep player running through minor transient playback glitches
            }
        }
    }

    /**
     * Enqueue a PCM16 chunk for playback without blocking the WebSocket receiver.
     */
    fun play(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        queue.offer(pcm)
    }

    /**
     * Immediately cut off audio playback (used for interruption / barge-in).
     */
    fun clear() {
        queue.clear()
        isPlayingAudio = false
        runCatching {
            track?.let { t ->
                t.pause()
                t.flush()
                t.play()
            }
        }
        onPlaybackStateChanged?.invoke(false)
    }

    fun isPlaying(): Boolean {
        return isPlayingAudio || queue.isNotEmpty()
    }

    fun stop() {
        running.set(false)
        isPlayingAudio = false
        queue.clear()

        playbackThread?.interrupt()
        playbackThread = null

        val currentTrack = track
        track = null

        if (currentTrack != null) {
            runCatching { currentTrack.pause() }
            runCatching { currentTrack.flush() }
            runCatching { currentTrack.stop() }
            runCatching { currentTrack.release() }
        }

        onPlaybackStateChanged?.invoke(false)
    }
}
