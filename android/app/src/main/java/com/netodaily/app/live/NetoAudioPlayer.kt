package com.netodaily.app.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class NetoAudioPlayer {

    companion object {
        private const val SAMPLE_RATE = 24_000
    }

    private var track: AudioTrack? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return

        val minimumBuffer =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (minimumBuffer <= 0) return

        val audioTrack =
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_MONO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        maxOf(
                            minimumBuffer,
                            SAMPLE_RATE / 2
                        )
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()
            }.getOrNull()
                ?: return

        track = audioTrack
        started = true

        runCatching {
            audioTrack.play()
        }.onFailure {
            started = false
            track = null
            audioTrack.release()
        }
    }

    @Synchronized
    fun play(pcm: ByteArray) {
        if (!started || pcm.isEmpty()) return

        val audioTrack =
            track ?: return

        runCatching {
            audioTrack.write(
                pcm,
                0,
                pcm.size,
                AudioTrack.WRITE_BLOCKING
            )
        }
    }

    fun clear() {
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    fun stop() {
        started = false

        val audioTrack = track
        track = null

        if (audioTrack != null) {
            runCatching {
                audioTrack.pause()
            }

            runCatching {
                audioTrack.flush()
            }

            runCatching {
                audioTrack.stop()
            }

            runCatching {
                audioTrack.release()
            }
        }
    }
}
