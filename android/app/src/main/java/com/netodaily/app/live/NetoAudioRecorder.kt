package com.netodaily.app.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NetoAudioRecorder(
    private val scope: CoroutineScope,
    private val onPcm: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 640
    }

    private var recorder: AudioRecord? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        val minimumBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

        if (minimumBuffer <= 0) {
            onError("NETO could not initialize the microphone.")
            return
        }

        val bufferSize =
            maxOf(
                minimumBuffer,
                CHUNK_SIZE * 4
            )

        val audioRecord =
            runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }.getOrElse {
                onError(
                    it.message
                        ?: "NETO could not open the microphone."
                )
                return
            }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("NETO could not initialize the microphone.")
            return
        }

        recorder = audioRecord
        running = true

        job =
            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK_SIZE)

                try {
                    audioRecord.startRecording()

                    while (
                        isActive &&
                        running
                    ) {
                        val count =
                            audioRecord.read(
                                buffer,
                                0,
                                buffer.size
                            )

                        if (count <= 0) continue

                        val pcm =
                            if (count == buffer.size) {
                                buffer.copyOf()
                            } else {
                                buffer.copyOf(count)
                            }

                        onPcm(pcm)
                        onLevel(calculateLevel(pcm))
                    }
                } catch (t: Throwable) {
                    if (running) {
                        onError(
                            t.message
                                ?: "Microphone capture failed."
                        )
                    }
                } finally {
                    runCatching {
                        audioRecord.stop()
                    }

                    runCatching {
                        audioRecord.release()
                    }

                    if (recorder === audioRecord) {
                        recorder = null
                    }

                    running = false
                    onLevel(0f)
                }
            }
    }

    private fun calculateLevel(
        pcm: ByteArray
    ): Float {
        if (pcm.size < 2) return 0f

        var sum = 0.0

        var index = 0

        while (index + 1 < pcm.size) {
            val low =
                pcm[index].toInt() and 0xFF

            val high =
                pcm[index + 1].toInt()

            val sample =
                (
                    low or
                        (high shl 8)
                ).toShort().toInt()

            sum +=
                sample.toDouble() *
                    sample.toDouble()

            index += 2
        }

        val sampleCount =
            pcm.size / 2

        if (sampleCount == 0) return 0f

        val rms =
            kotlin.math.sqrt(
                sum / sampleCount
            )

        return (
            rms / Short.MAX_VALUE
        ).toFloat()
            .coerceIn(0f, 1f)
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null

        recorder?.let {
            runCatching {
                it.stop()
            }

            runCatching {
                it.release()
            }
        }

        recorder = null
        onLevel(0f)
    }
}
