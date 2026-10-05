package com.netodaily.app.vision

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central visual-input pipeline for NETO Live.
 *
 * Camera and screen capture never talk directly to Gemini.
 * They publish their newest frame here.
 *
 * The router:
 * - keeps only the newest frame
 * - never builds a frame queue
 * - limits delivery to 4 frames/second
 * - drops identical frames
 * - avoids blocking camera/screen capture threads
 */
class NetoLiveFrameRouter(
    private val onFrame: (ByteArray) -> Unit
) {

    companion object {
        private const val FRAME_INTERVAL_MS = 250L
        private const val HASH_SAMPLE_SIZE = 4096
    }

    private val running = AtomicBoolean(false)

    private val latestFrame =
        AtomicReference<ByteArray?>(null)

    @Volatile
    private var lastSentAt = 0L

    @Volatile
    private var lastHash: String? = null

    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        worker = Thread(
            {
                loop()
            },
            "NETO-VisualRouter"
        ).also {
            it.start()
        }
    }

    fun submit(frame: ByteArray) {
        if (!running.get()) return
        if (frame.isEmpty()) return

        /*
         * Copy the reference only once.
         *
         * Producers are expected to give us an immutable
         * byte array. We replace the previous frame instead
         * of queuing it.
         */
        latestFrame.set(frame)
    }

    private fun loop() {
        while (running.get()) {

            try {
                val now = System.currentTimeMillis()

                val remaining =
                    FRAME_INTERVAL_MS -
                        (now - lastSentAt)

                if (remaining > 0) {
                    Thread.sleep(
                        remaining.coerceAtMost(200L)
                    )
                    continue
                }

                val frame =
                    latestFrame.getAndSet(null)
                        ?: run {
                            Thread.sleep(50L)
                            continue
                        }

                val hash = fingerprint(frame)

                /*
                 * If the screen/camera hasn't changed,
                 * don't repeatedly transmit the same image.
                 */
                if (hash == lastHash) {
                    continue
                }

                lastHash = hash
                lastSentAt = System.currentTimeMillis()

                onFrame(frame)

            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                // Never allow visual processing to kill NETO.
            }
        }
    }

    private fun fingerprint(
        data: ByteArray
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")

        if (data.size <= HASH_SAMPLE_SIZE) {
            return digest
                .digest(data)
                .joinToString("") {
                    "%02x".format(it)
                }
        }

        /*
         * Hash only distributed samples rather than
         * the complete JPEG. This keeps hashing cheap.
         */
        val sample =
            ByteArray(HASH_SAMPLE_SIZE)

        val step =
            data.size.toDouble() /
                HASH_SAMPLE_SIZE.toDouble()

        for (i in sample.indices) {
            sample[i] =
                data[
                    (i * step)
                        .toInt()
                        .coerceAtMost(data.lastIndex)
                ]
        }

        return digest
            .digest(sample)
            .joinToString("") {
                "%02x".format(it)
            }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        latestFrame.set(null)

        worker?.interrupt()
        worker = null

        lastHash = null
        lastSentAt = 0L
    }
}
