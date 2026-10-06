package com.netodaily.app.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Microphone capture for NETO Gemini Live.
 * Captures 16 kHz mono 16-bit PCM (640 bytes = 20ms chunks) with hardware echo cancellation.
 */
class NetoAudioRecorder(
    private val scope: CoroutineScope,
    private val onPcm: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onVoiceDetected: (() -> Unit)? = null,
    private val onError: (String) -> Unit
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 640
        private const val VOICE_THRESHOLD = 0.07f
    }

    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    private var consecutiveVoiceFrames = 0

    fun start() {
        if (running) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (minBufferSize <= 0) {
            onError("NETO could not initialize the microphone.")
            return
        }

        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE * 4)

        // Prefer VOICE_COMMUNICATION for echo-canceled speakerphone duplex conversation; fallback to VOICE_RECOGNITION
        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }.getOrElse {
                onError(it.message ?: "NETO could not open the microphone.")
                return
            }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("NETO could not initialize the microphone.")
            return
        }

        // Enable hardware echo cancellation and noise suppression if supported on this device
        val sessionId = audioRecord.audioSessionId
        if (sessionId != 0) {
            if (AcousticEchoCanceler.isAvailable()) {
                runCatching {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                runCatching {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                    }
                }
            }
        }

        recorder = audioRecord
        running = true
        consecutiveVoiceFrames = 0

        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_SIZE)

            try {
                audioRecord.startRecording()

                while (isActive && running) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count <= 0) continue

                    val pcm = if (count == buffer.size) {
                        buffer.copyOf()
                    } else {
                        buffer.copyOf(count)
                    }

                    val level = calculateLevel(pcm)
                    onLevel(level)

                    if (level >= VOICE_THRESHOLD) {
                        consecutiveVoiceFrames++
                        if (consecutiveVoiceFrames >= 2) {
                            onVoiceDetected?.invoke()
                        }
                    } else {
                        consecutiveVoiceFrames = 0
                    }

                    onPcm(pcm)
                }
            } catch (t: Throwable) {
                if (running) {
                    onError(t.message ?: "Microphone capture failed.")
                }
            } finally {
                cleanupAudioRecord(audioRecord)
                running = false
                onLevel(0f)
            }
        }
    }

    private fun calculateLevel(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f

        var sum = 0.0
        var index = 0

        while (index + 1 < pcm.size) {
            val low = pcm[index].toInt() and 0xFF
            val high = pcm[index + 1].toInt()
            val sample = (low or (high shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            index += 2
        }

        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0f

        val rms = kotlin.math.sqrt(sum / sampleCount)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun cleanupAudioRecord(record: AudioRecord) {
        runCatching { record.stop() }
        runCatching { record.release() }

        runCatching {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        }
        echoCanceler = null

        runCatching {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
        }
        noiseSuppressor = null

        if (recorder === record) {
            recorder = null
        }
    }

    fun stop() {
        running = false
        consecutiveVoiceFrames = 0
        job?.cancel()
        job = null

        recorder?.let { cleanupAudioRecord(it) }
        recorder = null
        onLevel(0f)
    }
}
