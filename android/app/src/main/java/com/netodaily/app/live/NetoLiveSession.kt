package com.netodaily.app.live

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class NetoLiveSession(
    private val scope: CoroutineScope,
    private val onStateChanged: (State) -> Unit,
    private val onCaption: (speaker: String, text: String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onError: (String) -> Unit
) {

    enum class State {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    companion object {
        private const val MODEL = "models/gemini-3.8-live"

        private const val INPUT_MIME =
            "audio/pcm;rate=16000"

        private const val OUTPUT_MIME_PREFIX =
            "audio/pcm"

        private const val WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/" +
                "ws/google.ai.generativelanguage.v1beta." +
                "GenerativeService.BidiGenerateContentConstrained"
    }

    private val tokenClient =
        NetoLiveTokenClient()

    private val httpClient =
        HttpClient(Android) {
            install(WebSockets)
        }

    private val socketMutex =
        Mutex()

    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null
    private var tokenJob: Job? = null
    private var recorder: NetoAudioRecorder? = null

    private val player =
        NetoAudioPlayer()

    @Volatile
    private var connected = false

    @Volatile
    private var microphoneActive = false

    @Volatile
    private var closing = false

    fun start() {
        if (
            connected ||
            tokenJob?.isActive == true
        ) {
            return
        }

        closing = false

        onStateChanged(State.THINKING)

        tokenJob =
            scope.launch(Dispatchers.IO) {
                val tokenResponse: NetoLiveTokenResponse =
                    tokenClient.requestToken()

                val token: String =
                    tokenResponse.token
                        ?.takeIf { it.isNotBlank() }
                        ?: run {
                            onError(
                                tokenResponse.error
                                    ?: "NETO Live could not start."
                            )
                            onStateChanged(State.IDLE)
                            return@launch
                        }

                connect(token)
            }
    }

    private suspend fun connect(
        token: String
    ) {
        try {
            val url =
                "$WS_ENDPOINT?access_token=$token"

            httpClient.webSocket(
                urlString = url
            ) {
                session = this
                connected = true
                closing = false

                sendSetup()

                player.start()

                receiveJob =
                    scope.launch(Dispatchers.IO) {
                        receiveLoop(this@webSocket)
                    }

                startMicrophone()

                if (microphoneActive) {
                    onStateChanged(
                        State.LISTENING
                    )
                }

                receiveJob?.join()
            }
        } catch (t: Throwable) {
            connected = false

            if (!closing) {
                onError(
                    t.message
                        ?: "NETO Live connection failed."
                )
            }
        } finally {
            connected = false
            microphoneActive = false

            recorder?.stop()
            recorder = null

            player.stop()

            session = null

            onAudioLevel(0f)

            if (!closing) {
                onStateChanged(State.IDLE)
            }
        }
    }

    private suspend fun sendSetup() {
        val setup =
            JSONObject()
                .put(
                    "setup",
                    JSONObject()
                        .put(
                            "model",
                            MODEL
                        )
                        .put(
                            "responseModalities",
                            org.json.JSONArray()
                                .put("AUDIO")
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
                                    org.json.JSONArray()
                                        .put(
                                            JSONObject()
                                                .put(
                                                    "text",
                                                    """
                                                    You are NETO, a warm, concise,
                                                    voice-first personal AI assistant.

                                                    Speak naturally and conversationally.
                                                    Help the user ask, do, remember,
                                                    find, and act.

                                                    When speaking aloud, avoid unnecessary
                                                    formatting and keep responses natural.

                                                    The user may provide camera or screen
                                                    images. Treat visual input as part of
                                                    the current conversation and describe
                                                    what is relevant when asked.
                                                    """.trimIndent()
                                                )
                                        )
                                )
                        )
                )

        socketMutex.withLock {
            session?.send(
                Frame.Text(
                    setup.toString()
                )
            )
        }
    }

    private fun startMicrophone() {
        if (microphoneActive) {
            return
        }

        microphoneActive = true

        recorder =
            NetoAudioRecorder(
                scope = scope,

                onPcm = { pcm ->
                    sendAudio(pcm)
                },

                onLevel = { level ->
                    onAudioLevel(level)
                },

                onError = { message ->
                    microphoneActive = false

                    if (!closing) {
                        onError(message)
                    }
                }
            )

        recorder?.start()
    }

    private fun sendAudio(
        pcm: ByteArray
    ) {
        if (
            !connected ||
            closing ||
            pcm.isEmpty()
        ) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val encoded =
                Base64.encodeToString(
                    pcm,
                    Base64.NO_WRAP
                )

            val audio =
                JSONObject()
                    .put(
                        "data",
                        encoded
                    )
                    .put(
                        "mimeType",
                        INPUT_MIME
                    )

            val message =
                JSONObject()
                    .put(
                        "realtimeInput",
                        JSONObject()
                            .put(
                                "audio",
                                audio
                            )
                    )

            runCatching {
                socketMutex.withLock {
                    session?.send(
                        Frame.Text(
                            message.toString()
                        )
                    )
                }
            }.onFailure {
                if (!closing) {
                    onError(
                        it.message
                            ?: "Audio transmission failed."
                    )
                }
            }
        }
    }

    private suspend fun receiveLoop(
        socket: WebSocketSession
    ) {
        try {
            for (frame in socket.incoming) {
                if (!scope.isActive) {
                    break
                }

                when (frame) {
                    is Frame.Text -> {
                        handleServerMessage(
                            frame.readText()
                        )
                    }

                    is Frame.Close -> {
                        break
                    }

                    else -> {
                        Unit
                    }
                }
            }
        } catch (t: Throwable) {
            if (!closing) {
                onError(
                    t.message
                        ?: "NETO Live disconnected."
                )
            }
        }
    }

    private fun handleServerMessage(
        raw: String
    ) {
        runCatching {
            val json =
                JSONObject(raw)

            if (
                json.has("setupComplete")
            ) {
                return@runCatching
            }

            if (
                json.has("goAway")
            ) {
                return@runCatching
            }

            val serverContent =
                json.optJSONObject(
                    "serverContent"
                )

            if (serverContent == null) {
                return@runCatching
            }

            /*
             * Gemini can interrupt the model while it
             * is speaking when the user starts talking.
             */
            if (
                serverContent.optBoolean(
                    "interrupted",
                    false
                )
            ) {
                player.clear()

                if (microphoneActive) {
                    onStateChanged(
                        State.LISTENING
                    )
                }
            }

            /*
             * User transcription.
             */
            val inputTranscript =
                serverContent
                    .optJSONObject(
                        "inputTranscription"
                    )
                    ?.optString(
                        "text",
                        ""
                    )
                    .orEmpty()

            if (inputTranscript.isNotBlank()) {
                onCaption(
                    "You",
                    inputTranscript
                )
            }

            /*
             * Model transcription.
             */
            val outputTranscript =
                serverContent
                    .optJSONObject(
                        "outputTranscription"
                    )
                    ?.optString(
                        "text",
                        ""
                    )
                    .orEmpty()

            if (outputTranscript.isNotBlank()) {
                onCaption(
                    "NETO",
                    outputTranscript
                )
            }

            /*
             * Gemini's generated audio.
             */
            val modelTurn =
                serverContent.optJSONObject(
                    "modelTurn"
                )

            if (modelTurn != null) {
                val parts =
                    modelTurn.optJSONArray(
                        "parts"
                    )

                if (parts != null) {
                    var playedAudio = false

                    for (
                        index in
                        0 until parts.length()
                    ) {
                        val part =
                            parts.optJSONObject(
                                index
                            )
                                ?: continue

                        val inlineData =
                            part.optJSONObject(
                                "inlineData"
                            )
                                ?: part.optJSONObject(
                                    "inline_data"
                                )
                                ?: continue

                        val data =
                            inlineData.optString(
                                "data",
                                ""
                            )

                        if (data.isBlank()) {
                            continue
                        }

                        val mime =
                            inlineData.optString(
                                "mimeType",
                                ""
                            )

                        if (
                            mime.startsWith(
                                OUTPUT_MIME_PREFIX
                            )
                        ) {
                            val pcm =
                                Base64.decode(
                                    data,
                                    Base64.DEFAULT
                                )

                            if (pcm.isNotEmpty()) {
                                if (!playedAudio) {
                                    onStateChanged(
                                        State.SPEAKING
                                    )

                                    playedAudio = true
                                }

                                player.play(pcm)
                            }
                        }
                    }
                }
            }

            /*
             * End of model turn.
             */
            if (
                serverContent.optBoolean(
                    "turnComplete",
                    false
                )
            ) {
                if (microphoneActive) {
                    onStateChanged(
                        State.LISTENING
                    )
                } else {
                    onStateChanged(
                        State.IDLE
                    )
                }
            }
        }.onFailure {
            if (!closing) {
                onError(
                    "NETO received an invalid Live response."
                )
            }
        }
    }

    fun stop() {
        if (
            !connected &&
            tokenJob?.isActive != true
        ) {
            return
        }

        closing = true
        microphoneActive = false

        recorder?.stop()
        recorder = null

        scope.launch(Dispatchers.IO) {
            runCatching {
                socketMutex.withLock {
                    session?.send(
                        Frame.Text(
                            JSONObject()
                                .put(
                                    "realtimeInput",
                                    JSONObject()
                                        .put(
                                            "audioStreamEnd",
                                            true
                                        )
                                )
                                .toString()
                        )
                    )
                }
            }

            runCatching {
                session?.close()
            }

            session = null
            connected = false

            player.stop()
            onAudioLevel(0f)
            onStateChanged(State.IDLE)
        }
    }

    fun sendVideoFrame(
        jpeg: ByteArray
    ) {
        if (
            !connected ||
            closing ||
            jpeg.isEmpty()
        ) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val encoded =
                Base64.encodeToString(
                    jpeg,
                    Base64.NO_WRAP
                )

            val video =
                JSONObject()
                    .put(
                        "data",
                        encoded
                    )
                    .put(
                        "mimeType",
                        "image/jpeg"
                    )

            val message =
                JSONObject()
                    .put(
                        "realtimeInput",
                        JSONObject()
                            .put(
                                "video",
                                video
                            )
                    )

            runCatching {
                socketMutex.withLock {
                    session?.send(
                        Frame.Text(
                            message.toString()
                        )
                    )
                }
            }.onFailure {
                if (!closing) {
                    onError(
                        it.message
                            ?: "Visual input failed."
                    )
                }
            }
        }
    }

    fun sendText(
        text: String
    ) {
        if (
            !connected ||
            closing ||
            text.isBlank()
        ) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val message =
                JSONObject()
                    .put(
                        "realtimeInput",
                        JSONObject()
                            .put(
                                "text",
                                text.trim()
                            )
                    )

            runCatching {
                socketMutex.withLock {
                    session?.send(
                        Frame.Text(
                            message.toString()
                        )
                    )
                }
            }.onFailure {
                if (!closing) {
                    onError(
                        it.message
                            ?: "Message transmission failed."
                    )
                }
            }
        }
    }

    fun release() {
        closing = true
        microphoneActive = false

        tokenJob?.cancel()
        receiveJob?.cancel()

        recorder?.stop()
        recorder = null

        player.stop()

        scope.launch(Dispatchers.IO) {
            runCatching {
                socketMutex.withLock {
                    session?.close()
                }
            }
        }

        session = null
        connected = false

        onAudioLevel(0f)

        httpClient.close()
    }
}
