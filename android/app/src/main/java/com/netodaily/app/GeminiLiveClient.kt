package com.netodaily.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val onStateChanged: (NetoVoiceOrbView.State) -> Unit,
    private val onCaption: (speaker: String, text: String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
        private const val INPUT_BUFFER = 4096
        private const val MODEL = "models/gemini-3.8-live"
    }

    private val client = HttpClient(Android) {
        install(WebSockets)
    }

    private var socket: WebSocketSession? = null
    private var microphone: AudioRecord? = null
    private var speaker: AudioTrack? = null

    private var microphoneJob: Job? = null
    private var receiveJob: Job? = null

    @Volatile
    private var running = false

    suspend fun start() {
        if (running) return

        try {
            onStateChanged(NetoVoiceOrbView.State.THINKING)

            val token = requestToken()

            connect(token)

            if (!running) return

            startSpeaker()
            startMicrophone()

            onStateChanged(NetoVoiceOrbView.State.LISTENING)

        } catch (e: Exception) {
            running = false
            stopAudio()

            onStateChanged(NetoVoiceOrbView.State.IDLE)

            onError(
                e.message ?: "NETO Live could not start."
            )
        }
    }

    suspend fun stop() {
        running = false

        microphoneJob?.cancel()
        receiveJob?.cancel()

        microphoneJob = null
        receiveJob = null

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null

        stopAudio()

        onAudioLevel(0f)
        onStateChanged(NetoVoiceOrbView.State.IDLE)
    }

    private suspend fun requestToken(): String =
        withContext(Dispatchers.IO) {

            val session =
                Supabase.client.auth.currentSessionOrNull()
                    ?: throw IllegalStateException(
                        "Please sign in to use NETO Live."
                    )

            val connection =
                (URL(
                    "${BuildConfig.SUPABASE_URL}/functions/v1/neto-live-token"
                ).openConnection() as HttpURLConnection)

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true

            connection.setRequestProperty(
                "Authorization",
                "Bearer ${session.accessToken}"
            )

            connection.setRequestProperty(
                "apikey",
                BuildConfig.SUPABASE_PUBLISHABLE_KEY
            )

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.outputStream.use {
                it.write("{}".toByteArray())
            }

            val responseCode = connection.responseCode

            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body =
                stream.bufferedReader().use {
                    it.readText()
                }

            if (responseCode !in 200..299) {
                val message =
                    try {
                        JSONObject(body)
                            .optString(
                                "error",
                                "Could not start NETO Live."
                            )
                    } catch (_: Exception) {
                        "Could not start NETO Live."
                    }

                throw IllegalStateException(message)
            }

            val json = JSONObject(body)

            json.optString("token")
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "NETO Live did not return an access token."
                )
        }

    private suspend fun connect(token: String) {

        client.webSocket(
            request = {
                url(
                    "wss://generativelanguage.googleapis.com/" +
                        "ws/google.ai.generativelanguage.v1beta." +
                        "GenerativeService." +
                        "BidiGenerateContentConstrained" +
                        "?access_token=$token"
                )
            }
        ) {

            socket = this
            running = true

            sendSetup()

            receiveJob = scope.launch {
                receiveMessages(this@webSocket)
            }

            receiveJob?.join()
        }
    }

    private suspend fun WebSocketSession.sendSetup() {

        val setup = JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", MODEL)
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put(
                                "responseModalities",
                                JSONArray().put("AUDIO")
                            )
                    )
                    .put(
                        "inputAudioTranscription",
                        JSONObject()
                    )
                    .put(
                        "outputAudioTranscription",
                        JSONObject()
                    )
                    .put(
                        "systemInstruction",
                        JSONObject()
                            .put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        """
                                        You are NETO, a voice-first AI assistant.

                                        Speak naturally and concisely.
                                        Help the user ask, do, remember,
                                        find and act.

                                        Do not describe yourself as a
                                        developer tool. Respond as NETO.
                                        """.trimIndent()
                                    )
                                )
                            )
                    )
            )

        send(Frame.Text(setup.toString()))
    }

    private suspend fun receiveMessages(
        session: WebSocketSession
    ) {

        try {

            for (frame in session.incoming) {

                if (!running) break

                if (frame is Frame.Text) {
                    handleMessage(frame.readText())
                }
            }

        } catch (e: Exception) {

            if (running) {
                onError(
                    e.message ?: "NETO Live connection ended."
                )
            }
        }
    }

    private fun handleMessage(raw: String) {

        try {

            val json = JSONObject(raw)

            val serverContent =
                json.optJSONObject("serverContent")
                    ?: return

            if (
                serverContent.optBoolean(
                    "interrupted",
                    false
                )
            ) {
                clearSpeaker()

                onStateChanged(
                    NetoVoiceOrbView.State.LISTENING
                )
            }

            serverContent
                .optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    onCaption("You", it)
                }

            serverContent
                .optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    onCaption("NETO", it)
                }

            val modelTurn =
                serverContent.optJSONObject("modelTurn")
                    ?: return

            val parts =
                modelTurn.optJSONArray("parts")
                    ?: return

            for (i in 0 until parts.length()) {

                val part =
                    parts.optJSONObject(i)
                        ?: continue

                val inline =
                    part.optJSONObject("inlineData")
                        ?: continue

                val mime =
                    inline.optString("mimeType")

                val base64 =
                    inline.optString("data")

                if (
                    mime.startsWith("audio/") &&
                    base64.isNotBlank()
                ) {

                    val audio =
                        android.util.Base64.decode(
                            base64,
                            android.util.Base64.DEFAULT
                        )

                    onStateChanged(
                        NetoVoiceOrbView.State.SPEAKING
                    )

                    playAudio(audio)
                }
            }

            if (
                serverContent.optBoolean(
                    "turnComplete",
                    false
                )
            ) {
                onStateChanged(
                    NetoVoiceOrbView.State.LISTENING
                )
            }

        } catch (_: Exception) {
            // Ignore non-content protocol frames.
        }
    }

    private fun startMicrophone() {

        val minimum =
            AudioRecord.getMinBufferSize(
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        val size =
            max(
                minimum,
                INPUT_BUFFER
            )

        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                size
            )

        if (
            recorder.state !=
            AudioRecord.STATE_INITIALIZED
        ) {
            recorder.release()

            throw IllegalStateException(
                "NETO could not access the microphone."
            )
        }

        microphone = recorder

        recorder.startRecording()

        microphoneJob =
            scope.launch(Dispatchers.IO) {

                val buffer =
                    ByteArray(INPUT_BUFFER)

                while (
                    isActive &&
                    running
                ) {

                    val count =
                        recorder.read(
                            buffer,
                            0,
                            buffer.size
                        )

                    if (count <= 0) continue

                    val level =
                        audioLevel(
                            buffer,
                            count
                        )

                    withContext(Dispatchers.Main) {
                        onAudioLevel(level)
                    }

                    sendAudio(
                        buffer.copyOf(count)
                    )
                }
            }
    }

    private suspend fun sendAudio(
        bytes: ByteArray
    ) {

        val session =
            socket ?: return

        val encoded =
            android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.NO_WRAP
            )

        val message =
            JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject()
                        .put(
                            "mediaChunks",
                            JSONArray().put(
                                JSONObject()
                                    .put(
                                        "mimeType",
                                        "audio/pcm;rate=16000"
                                    )
                                    .put(
                                        "data",
                                        encoded
                                    )
                            )
                        )
                )

        try {
            session.send(
                Frame.Text(message.toString())
            )
        } catch (_: Exception) {
        }
    }

    private fun startSpeaker() {

        val minimum =
            AudioTrack.getMinBufferSize(
                OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        speaker =
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
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .setSampleRate(
                            OUTPUT_RATE
                        )
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setBufferSizeInBytes(
                    max(minimum, 8192)
                )
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .build()

        speaker?.play()
    }

    private fun playAudio(
        bytes: ByteArray
    ) {

        try {

            speaker?.write(
                bytes,
                0,
                bytes.size,
                AudioTrack.WRITE_BLOCKING
            )

        } catch (_: Exception) {
        }
    }

    private fun clearSpeaker() {

        try {
            speaker?.pause()
            speaker?.flush()
            speaker?.play()
        } catch (_: Exception) {
        }
    }

    private fun audioLevel(
        buffer: ByteArray,
        count: Int
    ): Float {

        var sum = 0.0
        var samples = 0

        var i = 0

        while (i + 1 < count) {

            val low =
                buffer[i].toInt() and 0xff

            val high =
                buffer[i + 1].toInt()

            val sample =
                ((high shl 8) or low)
                    .toShort()
                    .toInt()

            sum += abs(sample)
            samples++

            i += 2
        }

        if (samples == 0) {
            return 0f
        }

        return (
            (sum / samples) / 32768.0
            ).toFloat()
            .coerceIn(0f, 1f)
    }


    fun sendVideoFrame(
        jpeg: ByteArray
    ) {

        if (!running) return

        scope.launch {

            val session =
                socket ?: return@launch

            try {

                val encoded =
                    android.util.Base64.encodeToString(
                        jpeg,
                        android.util.Base64.NO_WRAP
                    )

                val message =
                    JSONObject()
                        .put(
                            "realtimeInput",
                            JSONObject()
                                .put(
                                    "video",
                                    JSONObject()
                                        .put(
                                            "data",
                                            encoded
                                        )
                                        .put(
                                            "mimeType",
                                            "image/jpeg"
                                        )
                                )
                        )

                session.send(
                    Frame.Text(
                        message.toString()
                    )
                )

            } catch (_: Exception) {
            }
        }
    }

    private fun stopAudio() {

        try {
            microphone?.stop()
        } catch (_: Exception) {
        }

        try {
            microphone?.release()
        } catch (_: Exception) {
        }

        microphone = null

        try {
            speaker?.stop()
        } catch (_: Exception) {
        }

        try {
            speaker?.release()
        } catch (_: Exception) {
        }

        speaker = null
    }

    fun release() {

        scope.launch {

            stop()

            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }
}
