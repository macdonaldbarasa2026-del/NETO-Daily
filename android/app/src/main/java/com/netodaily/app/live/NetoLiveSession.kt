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

/**
 * Manages the real-time Gemini Live WebSocket session for NETO Daily.
 *
 * Supported features:
 * - PCM16 16 kHz mono microphone capture
 * - PCM16 24 kHz mono assistant audio playback through phone speaker
 * - Real-time two-way dialogue with clean interruption / barge-in
 * - User and assistant live transcriptions
 * - Camera and screen-share visual frames
 * - Session resumption and automatic reconnection
 */
class NetoLiveSession(
    private val scope: CoroutineScope,
    private val onStateChanged: (State) -> Unit,
    private val onCaption: (speaker: String, text: String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onError: (String) -> Unit
) {

    enum class State {
        IDLE,
        CONNECTING,
        LISTENING,
        THINKING,
        SPEAKING,
        ERROR
    }

    companion object {
        private const val MODEL = "models/gemini-3.8-live"

        private const val INPUT_MIME = "audio/pcm;rate=16000"
        private const val OUTPUT_MIME_PREFIX = "audio/pcm"

        private const val WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/" +
                "ws/google.ai.generativelanguage.v1beta." +
                "GenerativeService.BidiGenerateContentConstrained"

        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1_000L
    }

    private val tokenClient = NetoLiveTokenClient()

    private val httpClient = HttpClient(Android) {
        install(WebSockets)
    }

    private val socketMutex = Mutex()

    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null
    private var tokenJob: Job? = null
    private var recorder: NetoAudioRecorder? = null

    private val player = NetoAudioPlayer(
        onPlaybackStateChanged = { isPlaying ->
            if (!isPlaying && microphoneActive && !closing && currentState == State.SPEAKING) {
                currentState = State.LISTENING
                onStateChanged(State.LISTENING)
            }
        }
    )

    @Volatile
    private var currentState: State = State.IDLE

    @Volatile
    private var connected = false

    @Volatile
    private var microphoneActive = false

    @Volatile
    private var closing = false

    @Volatile
    private var reconnecting = false

    private var sessionResumptionHandle: String? = null
    private var reconnectAttempt = 0

    private val outboundChannel = kotlinx.coroutines.channels.Channel<String>(
        capacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private var outboundJob: Job? = null

    fun currentState(): State = currentState

    fun start() {
        if (connected || tokenJob?.isActive == true) {
            return
        }

        closing = false
        reconnecting = false
        reconnectAttempt = 0
        sessionResumptionHandle = null

        currentState = State.CONNECTING
        onStateChanged(State.CONNECTING)

        tokenJob = scope.launch(Dispatchers.IO) {
            val tokenResponse: NetoLiveTokenResponse = tokenClient.requestToken()

            val token: String = tokenResponse.token
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    currentState = State.ERROR
                    onStateChanged(State.ERROR)
                    onError(
                        tokenResponse.error
                            ?: "NETO couldn't connect to the voice service. Please try again."
                    )
                    kotlinx.coroutines.delay(2000L)
                    currentState = State.IDLE
                    onStateChanged(State.IDLE)
                    return@launch
                }

            connect(token)
        }
    }

    private suspend fun connect(token: String) {
        try {
            val url = buildString {
                append(WS_ENDPOINT)
                append("?access_token=")
                append(token)
            }

            httpClient.webSocket(urlString = url) {
                session = this
                connected = true
                closing = false

                outboundJob = scope.launch(Dispatchers.IO) {
                    try {
                        for (message in outboundChannel) {
                            if (closing) break

                            runCatching {
                                socketMutex.withLock {
                                    session?.send(Frame.Text(message))
                                }
                            }.onFailure {
                                if (!closing) {
                                    scheduleReconnect()
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // Outbound channel shutdown is expected.
                    }
                }

                sendSetup()
                player.start()

                receiveJob = scope.launch(Dispatchers.IO) {
                    receiveLoop(this@webSocket)
                }

                startMicrophone()

                if (microphoneActive) {
                    currentState = State.LISTENING
                    onStateChanged(State.LISTENING)
                }

                receiveJob?.join()
            }
        } catch (t: Throwable) {
            connected = false

            if (!closing) {
                scheduleReconnect()
            }
        } finally {
            connected = false
            microphoneActive = false

            recorder?.stop()
            recorder = null

            player.stop()

            outboundJob?.cancel()
            outboundJob = null

            session = null
            onAudioLevel(0f)

            if (!closing && !reconnecting) {
                currentState = State.IDLE
                onStateChanged(State.IDLE)
            }
        }
    }

    private suspend fun sendSetup() {
        val setup = JSONObject().put(
            "setup",
            JSONObject()
                .put("model", MODEL)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", "Aoede")
                                )
                            )
                        )
                )
                .put(
                    "responseModalities",
                    org.json.JSONArray().put("AUDIO")
                )
                .put(
                    "sessionResumption",
                    JSONObject().apply {
                        sessionResumptionHandle
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("handle", it) }
                    }
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "text",
                                """
                                You are NETO, a warm, concise, voice-first personal AI assistant.
                                Speak naturally and conversationally like a helpful friend.
                                Help the user ask questions, do tasks, remember notes, find information, and control their phone.
                                Keep spoken responses brief and natural without markdown.
                                Created by Macdonald Barasa.
                                """.trimIndent()
                            )
                        )
                    )
                )
        )

        socketMutex.withLock {
            session?.send(Frame.Text(setup.toString()))
        }
    }

    private fun startMicrophone() {
        if (microphoneActive) return
        microphoneActive = true

        recorder = NetoAudioRecorder(
            scope = scope,
            onPcm = { pcm ->
                sendAudio(pcm)
            },
            onLevel = { level ->
                onAudioLevel(level)
            },
            onVoiceDetected = {
                handleBargeIn()
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

    /**
     * User started speaking while assistant is speaking -> instantly clear audio.
     */
    private fun handleBargeIn() {
        if (currentState == State.SPEAKING) {
            player.clear()
            currentState = State.LISTENING
            onStateChanged(State.LISTENING)
        }
    }

    private fun sendAudio(pcm: ByteArray) {
        if (!connected || closing || pcm.isEmpty()) {
            return
        }

        val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)

        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", encoded)
                        .put("mimeType", INPUT_MIME)
                )
            )
            .toString()

        outboundChannel.trySend(message)
    }

    private suspend fun receiveLoop(socket: WebSocketSession) {
        try {
            for (frame in socket.incoming) {
                if (!scope.isActive) break

                when (frame) {
                    is Frame.Text -> {
                        handleServerMessage(frame.readText())
                    }
                    is Frame.Close -> {
                        break
                    }
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            if (!closing) {
                scheduleReconnect()
            }
        }
    }

    private fun handleServerMessage(raw: String) {
        runCatching {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                return@runCatching
            }

            val goAway = json.optJSONObject("goAway")
            if (goAway != null) {
                scheduleReconnect()
                return@runCatching
            }

            val resumptionUpdate = json.optJSONObject("sessionResumptionUpdate")
            if (resumptionUpdate != null) {
                val resumable = resumptionUpdate.optBoolean("resumable", false)
                val newHandle = resumptionUpdate.optString("newHandle", "").takeIf { it.isNotBlank() }
                if (resumable && newHandle != null) {
                    sessionResumptionHandle = newHandle
                }
                return@runCatching
            }

            val serverContent = json.optJSONObject("serverContent") ?: return@runCatching

            // Interruption detected by Gemini
            if (serverContent.optBoolean("interrupted", false)) {
                player.clear()
                if (microphoneActive) {
                    currentState = State.LISTENING
                    onStateChanged(State.LISTENING)
                }
            }

            // User transcription
            val inputTranscript = serverContent
                .optJSONObject("inputTranscription")
                ?.optString("text", "")
                .orEmpty()

            if (inputTranscript.isNotBlank()) {
                onCaption("You", inputTranscript)
            }

            // Assistant transcription
            val outputTranscript = serverContent
                .optJSONObject("outputTranscription")
                ?.optString("text", "")
                .orEmpty()

            if (outputTranscript.isNotBlank()) {
                onCaption("NETO", outputTranscript)
            }

            // Gemini generated audio chunks
            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        val inlineData = part.optJSONObject("inlineData")
                            ?: part.optJSONObject("inline_data")
                            ?: continue

                        val data = inlineData.optString("data", "")
                        if (data.isBlank()) continue

                        val mime = inlineData.optString("mimeType", "")
                        if (mime.startsWith(OUTPUT_MIME_PREFIX)) {
                            val pcm = Base64.decode(data, Base64.DEFAULT)
                            if (pcm.isNotEmpty()) {
                                if (currentState != State.SPEAKING) {
                                    currentState = State.SPEAKING
                                    onStateChanged(State.SPEAKING)
                                }
                                player.play(pcm)
                            }
                        }
                    }
                }
            }

            // Turn complete
            if (serverContent.optBoolean("turnComplete", false)) {
                if (!player.isPlaying()) {
                    if (microphoneActive) {
                        currentState = State.LISTENING
                        onStateChanged(State.LISTENING)
                    } else {
                        currentState = State.IDLE
                        onStateChanged(State.IDLE)
                    }
                }
            }
        }.onFailure {
            if (!closing) {
                onError("NETO received an invalid Live response.")
            }
        }
    }

    private fun scheduleReconnect() {
        if (closing || reconnecting || !scope.isActive) return

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            currentState = State.ERROR
            onStateChanged(State.ERROR)
            onError("NETO Live connection ended. Please try again.")
            scope.launch {
                kotlinx.coroutines.delay(2000L)
                currentState = State.IDLE
                onStateChanged(State.IDLE)
            }
            return
        }

        reconnecting = true
        reconnectAttempt += 1
        val attempt = reconnectAttempt

        currentState = State.CONNECTING
        onStateChanged(State.CONNECTING)

        scope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(RECONNECT_DELAY_MS * attempt)
                if (closing || !scope.isActive) return@launch

                val tokenResponse = tokenClient.requestToken()
                val token = tokenResponse.token?.takeIf { it.isNotBlank() }

                if (token == null) {
                    reconnecting = false
                    if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                        currentState = State.ERROR
                        onStateChanged(State.ERROR)
                        onError(tokenResponse.error ?: "NETO Live could not reconnect.")
                        kotlinx.coroutines.delay(2000L)
                        currentState = State.IDLE
                        onStateChanged(State.IDLE)
                    } else {
                        scheduleReconnect()
                    }
                    return@launch
                }

                reconnecting = false
                connect(token)
            } catch (_: Throwable) {
                reconnecting = false
                if (!closing) {
                    scheduleReconnect()
                }
            }
        }
    }

    fun stop() {
        if (!connected && tokenJob?.isActive != true) return

        closing = true
        reconnecting = false
        reconnectAttempt = 0
        sessionResumptionHandle = null
        microphoneActive = false

        recorder?.stop()
        recorder = null
        player.clear()
        player.stop()

        scope.launch(Dispatchers.IO) {
            runCatching {
                socketMutex.withLock {
                    session?.send(
                        Frame.Text(
                            JSONObject().put(
                                "realtimeInput",
                                JSONObject().put("audioStreamEnd", true)
                            ).toString()
                        )
                    )
                }
            }

            runCatching { session?.close() }

            session = null
            connected = false

            outboundJob?.cancel()
            outboundJob = null

            onAudioLevel(0f)
            currentState = State.IDLE
            onStateChanged(State.IDLE)
        }
    }

    fun sendVideoFrame(jpeg: ByteArray) {
        if (!connected || closing || jpeg.isEmpty()) return

        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "video",
                    JSONObject()
                        .put("data", encoded)
                        .put("mimeType", "image/jpeg")
                )
            )
            .toString()

        outboundChannel.trySend(message)
    }

    fun sendText(text: String) {
        if (!connected || closing || text.isBlank()) return

        val message = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put("text", text.trim())
            )
            .toString()

        outboundChannel.trySend(message)
    }

    fun release() {
        closing = true
        microphoneActive = false

        tokenJob?.cancel()
        receiveJob?.cancel()
        outboundJob?.cancel()
        outboundJob = null

        recorder?.stop()
        recorder = null

        player.clear()
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
        currentState = State.IDLE

        httpClient.close()
    }
}
