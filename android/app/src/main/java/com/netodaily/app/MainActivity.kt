package com.netodaily.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.netodaily.app.auth.AuthActivity
import com.netodaily.app.data.NetoConversation
import com.netodaily.app.data.NetoLocalStore
import com.netodaily.app.data.NetoMessage
import com.netodaily.app.ui.NetoOrbView
import com.netodaily.app.live.NetoLiveSession
import com.netodaily.app.vision.NetoVisionController
import com.netodaily.app.vision.NetoVisualPermissionController
import com.netodaily.app.vision.NetoVisualState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var store: NetoLocalStore
    private lateinit var orb: NetoOrbView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var captionView: TextView
    private lateinit var statusView: TextView
    private lateinit var input: EditText

    private var conversation: NetoConversation =
        NetoConversation(
            id = UUID.randomUUID().toString()
        )

    private var currentPanel: View? = null
    private var busy = false

    private lateinit var liveSession: NetoLiveSession
    private lateinit var visionController: NetoVisionController
    private lateinit var visualPermissions: NetoVisualPermissionController

    private lateinit var visualPreviewCard: View
    private lateinit var visualPreview: ImageView
    private lateinit var visualPreviewLabel: TextView

    private var visualState: NetoVisualState = NetoVisualState.None

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        if (Supabase.client.auth.currentUserOrNull() == null) {
            openAuth()
            return
        }

        store = NetoLocalStore(this)
        conversation =
            store.loadCurrentConversation()
                ?: NetoConversation(
                    id = UUID.randomUUID().toString()
                )

        buildHome()
        setupLiveAndVision()
        restoreConversation()
    }

    private fun setupLiveAndVision() {
        liveSession = NetoLiveSession(
            scope = scope,
            onStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        NetoLiveSession.State.IDLE -> {
                            orb.setState(NetoOrbView.State.IDLE)
                            statusView.text = "Ready"
                        }

                        NetoLiveSession.State.LISTENING -> {
                            orb.setState(NetoOrbView.State.LISTENING)
                            statusView.text =
                                "Listening — speak naturally · tap orb to end"
                        }

                        NetoLiveSession.State.THINKING -> {
                            orb.setState(NetoOrbView.State.THINKING)
                            statusView.text =
                                "Neto is preparing a reply"
                        }

                        NetoLiveSession.State.SPEAKING -> {
                            orb.setState(NetoOrbView.State.SPEAKING)
                            statusView.text =
                                "Speaking — tap orb to end"
                        }
                    }
                }
            },

            onCaption = { speaker, text ->
                runOnUiThread {
                    if (text.isNotBlank()) {
                        captionView.text =
                            if (speaker.isBlank()) {
                                text
                            } else {
                                "$speaker: $text"
                            }

                        captionView.visibility = View.VISIBLE
                    }
                }
            },

            onAudioLevel = { level ->
                runOnUiThread {
                    if (::orb.isInitialized) {
                        orb.setAudioLevel(level)
                    }
                }
            },

            onError = { message ->
                runOnUiThread {
                    captionView.text =
                        message.ifBlank {
                            "Live voice is unavailable."
                        }

                    captionView.visibility = View.VISIBLE

                    if (::orb.isInitialized) {
                        orb.setState(NetoOrbView.State.IDLE)
                    }

                    statusView.text = "Ready"
                }
            }
        )

        visionController = NetoVisionController(
            context = this,
            liveSession = liveSession,

            onPreviewFrame = { frame ->
                runOnUiThread {
                    val bitmap =
                        BitmapFactory.decodeByteArray(
                            frame,
                            0,
                            frame.size
                        )

                    if (bitmap != null) {
                        visualPreview.setImageBitmap(bitmap)
                        visualPreviewCard.visibility = View.VISIBLE
                    }
                }
            },

            onStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        NetoVisionController.State.FRONT_CAMERA -> {
                            visualState = NetoVisualState.FrontCamera
                            visualPreviewLabel.text = "Front camera"
                            visualPreviewCard.visibility = View.VISIBLE
                        }

                        NetoVisionController.State.BACK_CAMERA -> {
                            visualState = NetoVisualState.BackCamera
                            visualPreviewLabel.text = "Back camera"
                            visualPreviewCard.visibility = View.VISIBLE
                        }

                        NetoVisionController.State.SCREEN -> {
                            visualState = NetoVisualState.Screen
                            visualPreviewLabel.text = "Screen sharing"
                            visualPreviewCard.visibility = View.VISIBLE
                        }

                        NetoVisionController.State.OFF -> {
                            visualState = NetoVisualState.None
                            visualPreviewCard.visibility = View.GONE
                            visualPreview.setImageDrawable(null)
                        }
                    }
                }
            }
        )

        visualPermissions =
            NetoVisualPermissionController(
                activity = this,

                onCameraGranted = {
                    visionController.startFrontCamera()
                },

                onScreenShareResult = { resultCode, data ->
                    visionController.startScreenShare(
                        resultCode,
                        data
                    )
                }
            )

        visionController.start()
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 251, 244))
        }

        root.addView(
            topBar(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
            )
        )

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        body.addView(
            TextView(this).apply {
                text = "What can we work through?"
                textSize = 25f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(31, 55, 39))
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        body.addView(
            Space(this),
            LinearLayout.LayoutParams(
                1,
                dp(8)
            )
        )

        orb = NetoOrbView(this).apply {
            setState(NetoOrbView.State.IDLE)

            setOnClickListener {
                toggleVoice()
            }
        }

        body.addView(
            orb,
            LinearLayout.LayoutParams(
                dp(290),
                dp(290)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(46, 106, 69))
            gravity = Gravity.CENTER
        }

        body.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )

        val hint = TextView(this).apply {
            text = "Tap the orb to speak"
            textSize = 13f
            setTextColor(Color.rgb(91, 111, 96))
            gravity = Gravity.CENTER
        }

        body.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
            )
        )

        val visualLayout = layoutInflater.inflate(
            com.netodaily.app.R.layout.view_visual_preview,
            body,
            false
        )

        visualPreviewCard = visualLayout.findViewById(
            com.netodaily.app.R.id.visualPreviewCard
        )

        visualPreview = visualLayout.findViewById(
            com.netodaily.app.R.id.visualPreview
        )

        visualPreviewLabel = visualLayout.findViewById(
            com.netodaily.app.R.id.visualPreviewLabel
        )

        val closeVisual = visualLayout.findViewById<ImageButton>(
            com.netodaily.app.R.id.visualPreviewClose
        )

        closeVisual.setOnClickListener {
            stopVisualInput()
        }

        body.addView(visualPreviewCard)

        scrollView = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

        scrollView.addView(
            messagesContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        body.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        captionView = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.rgb(55, 74, 61))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }

        body.addView(
            captionView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        body.addView(
            composer(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)
            )
        )

        root.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }

        val menu = button("☰")
        menu.setOnClickListener {
            showMenu()
        }

        bar.addView(
            menu,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        bar.addView(
            TextView(this).apply {
                text = "NETO"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(46, 106, 69))
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        val more = button("⋮")
        more.setOnClickListener {
            showQuickActions()
        }

        bar.addView(
            more,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        return bar
    }

    private fun composer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.WHITE)
        }

        val attach = button("+")
        attach.setOnClickListener {
            showInbox()
        }

        row.addView(
            attach,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        input = EditText(this).apply {
            hint = "Type a message…"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.rgb(30, 45, 34))
            setHintTextColor(Color.rgb(125, 140, 129))
            setPadding(dp(10), 0, dp(10), 0)
            background = null
        }

        row.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        )

        val mic = button("●")
        mic.setOnClickListener {
            toggleVoice()
        }

        row.addView(
            mic,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        val send = button("↑")
        send.setOnClickListener {
            sendTypedMessage()
        }

        row.addView(
            send,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        input.setOnEditorActionListener { _, _, _ ->
            sendTypedMessage()
            true
        }

        return row
    }

    private fun restoreConversation() {
        messagesContainer.removeAllViews()

        conversation.messages.forEach {
            addMessageBubble(it)
        }

        if (conversation.messages.isNotEmpty()) {
            scrollToBottom()
            val last = conversation.messages.last()
            if (last.role == NetoMessage.Role.NETO) {
                captionView.text = last.text
                captionView.visibility = View.VISIBLE
            }
        }
    }

    private fun addMessageBubble(message: NetoMessage) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity =
                if (message.role == NetoMessage.Role.USER)
                    Gravity.END
                else
                    Gravity.START

            setPadding(dp(4), dp(3), dp(4), dp(3))
        }

        val bubble = TextView(this).apply {
            text = message.text
            textSize = 15f
            setTextColor(
                if (message.role == NetoMessage.Role.USER)
                    Color.WHITE
                else
                    Color.rgb(36, 53, 42)
            )
            setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
            )
            setBackgroundColor(
                if (message.role == NetoMessage.Role.USER)
                    Color.rgb(46, 106, 69)
                else
                    Color.WHITE
            )
        }

        wrapper.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        messagesContainer.addView(
            wrapper,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun sendTypedMessage() {
        if (busy) return

        val text = input.text?.toString()?.trim().orEmpty()

        if (text.isEmpty()) return

        input.setText("")

        val userMessage = NetoMessage(
            id = UUID.randomUUID().toString(),
            role = NetoMessage.Role.USER,
            text = text
        )

        conversation = conversation.copy(
            title = makeConversationTitle(
                conversation,
                text
            ),
            updatedAt = System.currentTimeMillis(),
            messages = conversation.messages + userMessage
        )

        addMessageBubble(userMessage)
        persistConversation()
        scrollToBottom()

        requestNeto(text)
    }

    private fun requestNeto(text: String) {
        busy = true

        orb.setState(NetoOrbView.State.THINKING)
        statusView.text = "Neto is preparing a reply"

        captionView.visibility = View.VISIBLE
        captionView.text = "Neto is preparing a reply…"

        scope.launch {
            try {
                val session =
                    Supabase.client.auth.currentSessionOrNull()

                if (session == null) {
                    openAuth()
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    val url =
                        "${BuildConfig.SUPABASE_URL}/functions/v1/neto-chat"

                    val connection =
                        java.net.URL(url).openConnection()
                            as java.net.HttpURLConnection

                    connection.requestMethod = "POST"
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 60_000
                    connection.doOutput = true

                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    connection.setRequestProperty(
                        "Authorization",
                        "Bearer ${session.accessToken}"
                    )

                    connection.setRequestProperty(
                        "apikey",
                        BuildConfig.SUPABASE_PUBLISHABLE_KEY
                    )

                    val body = buildJsonObject {
                        put("message", text)
                    }.toString()

                    connection.outputStream.use {
                        it.write(body.toByteArray())
                    }

                    val code = connection.responseCode

                    val stream =
                        if (code in 200..299)
                            connection.inputStream
                        else
                            connection.errorStream

                    val result =
                        stream?.bufferedReader()?.use {
                            it.readText()
                        }.orEmpty()

                    connection.disconnect()

                    if (code !in 200..299) {
                        throw IllegalStateException(
                            "NETO request failed ($code): $result"
                        )
                    }

                    result
                }

                val answer =
                    extractMessage(response)

                val netoMessage = NetoMessage(
                    id = UUID.randomUUID().toString(),
                    role = NetoMessage.Role.NETO,
                    text = answer
                )

                conversation = conversation.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages =
                        conversation.messages + netoMessage
                )

                addMessageBubble(netoMessage)

                captionView.text = answer
                captionView.visibility = View.VISIBLE

                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"

                persistConversation()
                scrollToBottom()

            } catch (error: Throwable) {
                val message =
                    error.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "Something went wrong."

                captionView.text = message
                captionView.visibility = View.VISIBLE

                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"
            } finally {
                busy = false
            }
        }
    }

    private fun extractMessage(raw: String): String {
        return runCatching {
            val json =
                kotlinx.serialization.json.Json.parseToJsonElement(raw)

            val objectValue =
                json as? kotlinx.serialization.json.JsonObject

            objectValue?.get("message")
                ?.toString()
                ?.trim('"')
                ?: objectValue?.get("text")
                    ?.toString()
                    ?.trim('"')
                ?: raw
        }.getOrDefault(raw)
            .ifBlank {
                "I received an empty response."
            }
    }

    private fun makeConversationTitle(
        current: NetoConversation,
        text: String
    ): String {
        if (
            current.messages.isNotEmpty() &&
            current.title != "New chat"
        ) {
            return current.title
        }

        return text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(42)
            .ifBlank { "New chat" }
    }

    private fun persistConversation() {
        store.saveCurrentConversation(conversation)
    }

    private fun newChat() {
        if (conversation.messages.isNotEmpty()) {
            store.archiveConversation(conversation)
        }

        conversation = NetoConversation(
            id = UUID.randomUUID().toString()
        )

        store.saveCurrentConversation(conversation)

        messagesContainer.removeAllViews()
        captionView.text = ""
        captionView.visibility = View.GONE
        input.setText("")

        orb.setState(NetoOrbView.State.IDLE)
        statusView.text = "Ready"

        closePanel()
    }

    private fun showHistory() {
        closePanel()

        val panel = panel("History")

        val history = store.loadHistory()

        if (history.isEmpty()) {
            panel.addView(
                TextView(this).apply {
                    text = "Your completed conversations will appear here."
                    textSize = 15f
                    setTextColor(Color.DKGRAY)
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                }
            )
        } else {
            history.forEach { item ->
                val row = menuRow(
                    item.title,
                    "${item.messages.size} messages"
                )

                row.setOnClickListener {
                    loadConversation(item)
                }

                panel.addView(row)
            }
        }

        showPanel(panel)
    }

    private fun loadConversation(item: NetoConversation) {
        if (conversation.messages.isNotEmpty()) {
            store.archiveConversation(conversation)
        }

        conversation = item
        store.saveCurrentConversation(conversation)

        restoreConversation()
        closePanel()
    }

    private fun showMenu() {
        val panel = panel("NETO")

        panel.addView(
            menuRow("New chat", "Start a fresh conversation").apply {
                setOnClickListener {
                    newChat()
                }
            }
        )

        panel.addView(
            menuRow("History", "Previous conversations").apply {
                setOnClickListener {
                    showHistory()
                }
            }
        )

        panel.addView(
            menuRow("NETO Inbox", "Photos, files, PDFs and links").apply {
                setOnClickListener {
                    showInbox()
                }
            }
        )

        panel.addView(
            menuRow("Settings", "Voice, captions and memories").apply {
                setOnClickListener {
                    showSettings()
                }
            }
        )

        panel.addView(
            menuRow("About creator", "About NETO").apply {
                setOnClickListener {
                    showAbout()
                }
            }
        )

        showPanel(panel)
    }

    private fun showQuickActions() {
        val panel = panel("Quick actions")

        panel.addView(
            menuRow("New chat", "Start over").apply {
                setOnClickListener {
                    newChat()
                }
            }
        )

        panel.addView(
            menuRow("Inbox", "Bring something to NETO").apply {
                setOnClickListener {
                    showInbox()
                }
            }
        )

        panel.addView(
            menuRow("Settings", "Customize NETO").apply {
                setOnClickListener {
                    showSettings()
                }
            }
        )

        showPanel(panel)
    }

    private fun showInbox() {
        val panel = panel("NETO Inbox")

        panel.addView(
            menuRow("Photo", "Choose a photo").apply {
                setOnClickListener {
                    chooseFile("image/*")
                }
            }
        )

        panel.addView(
            menuRow("Screenshot", "Choose an image").apply {
                setOnClickListener {
                    chooseFile("image/*")
                }
            }
        )

        panel.addView(
            menuRow("PDF", "Choose a PDF document").apply {
                setOnClickListener {
                    chooseFile("application/pdf")
                }
            }
        )

        panel.addView(
            menuRow("Text / document", "Choose a file").apply {
                setOnClickListener {
                    chooseFile("*/*")
                }
            }
        )

        panel.addView(
            menuRow("Link", "Paste a web link").apply {
                setOnClickListener {
                    showLinkDialog()
                }
            }
        )

        showPanel(panel)
    }

    private fun chooseFile(type: String) {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
            }

        startActivityForResult(
            intent,
            REQUEST_FILE
        )

        closePanel()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode == REQUEST_FILE &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return

            captionView.visibility = View.VISIBLE
            captionView.text =
                "Added to NETO Inbox: ${uri.lastPathSegment ?: "file"}"
        }
    }

    private fun showLinkDialog() {
        val edit = EditText(this).apply {
            hint = "https://example.com"
            setSingleLine(true)
        }

        AlertDialogBuilder()
            .setTitle("Add a link")
            .setView(edit)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val link = edit.text.toString().trim()

                if (link.isNotEmpty()) {
                    captionView.visibility = View.VISIBLE
                    captionView.text =
                        "Added to NETO Inbox: $link"
                }
            }
            .show()
    }

    private fun showSettings() {
        val panel = panel("Settings")

        panel.addView(
            menuRow(
                "Voice Sky",
                store.getSetting(
                    "voice_sky",
                    "Voice Sky"
                )
            )
        )

        panel.addView(
            menuRow(
                "Speaking speed",
                "${store.getFloat(
                    "speaking_speed",
                    1f
                )}×"
            )
        )

        panel.addView(
            menuRow(
                "Captions",
                if (
                    store.getBoolean(
                        "captions",
                        true
                    )
                ) "On" else "Off"
            ).apply {
                setOnClickListener {
                    val current =
                        store.getBoolean(
                            "captions",
                            true
                        )

                    store.saveBoolean(
                        "captions",
                        !current
                    )

                    showSettings()
                }
            }
        )

        panel.addView(
            menuRow(
                "Caption style",
                store.getSetting(
                    "caption_style",
                    "Clean"
                )
            )
        )

        panel.addView(
            menuRow(
                "Memories",
                "Saved conversation context"
            )
        )

        panel.addView(
            menuRow(
                "Saved items",
                "Things NETO saved for you"
            )
        )

        panel.addView(
            menuRow(
                "Sign out",
                "Sign out of this device"
            ).apply {
                setOnClickListener {
                    signOut()
                }
            }
        )

        showPanel(panel)
    }

    private fun showAbout() {
        val panel = panel("About NETO")

        panel.addView(
            TextView(this).apply {
                text =
                    """
                    NETO

                    Your voice-first AI assistant.

                    Created by Macdonald Barasa

                    NETO is designed to help you ask, do, remember, find and act.
                    """.trimIndent()

                textSize = 16f
                setTextColor(Color.rgb(45, 61, 49))
                setPadding(
                    dp(8),
                    dp(18),
                    dp(8),
                    dp(18)
                )
            }
        )

        showPanel(panel)
    }

    private fun stopVisualInput() {
        if (::visionController.isInitialized) {
            visionController.stop()
        }

        visualState = NetoVisualState.None

        if (::visualPreviewCard.isInitialized) {
            visualPreviewCard.visibility = View.GONE
        }

        if (::visualPreview.isInitialized) {
            visualPreview.setImageDrawable(null)
        }

        if (::visualPreviewLabel.isInitialized) {
            visualPreviewLabel.text = ""
        }
    }

    private fun toggleVoice() {
        if (!::liveSession.isInitialized) {
            captionView.text = "Live voice is still starting."
            captionView.visibility = View.VISIBLE
            return
        }

        when (orb.currentState()) {
            NetoOrbView.State.IDLE -> {
                captionView.visibility = View.GONE
                orb.setState(NetoOrbView.State.THINKING)
                statusView.text = "Neto is preparing a reply"
                liveSession.start()
            }

            NetoOrbView.State.LISTENING -> {
                liveSession.stop()
                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"
            }

            NetoOrbView.State.THINKING,
            NetoOrbView.State.SPEAKING -> {
                liveSession.stop()
                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"
            }
        }
    }

    private fun signOut() {
        scope.launch {
            runCatching {
                Supabase.client.auth.signOut()
            }

            openAuth()
        }
    }

    private fun openAuth() {
        startActivity(
            Intent(this, AuthActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
        )

        finish()
    }

    private fun panel(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(46, 106, 69))
                    setPadding(
                        0,
                        0,
                        0,
                        dp(14)
                    )
                }
            )
        }
    }

    private fun menuRow(
        title: String,
        subtitle: String
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(35, 50, 39))
                }
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(Color.rgb(100, 115, 103))
                    setPadding(
                        0,
                        dp(3),
                        0,
                        0
                    )
                }
            )
        }
    }

    private fun showPanel(view: View) {
        closePanel()

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            setPadding(
                dp(12),
                dp(76),
                dp(12),
                dp(12)
            )
        }

        val card = view

        overlay.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        card.elevation = dp(12).toFloat()

        addContentView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        currentPanel = overlay

        overlay.setOnClickListener {
            closePanel()
        }

        card.setOnClickListener {
            // Keep clicks inside the panel from closing it.
        }
    }

    private fun closePanel() {
        currentPanel?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        currentPanel = null
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun button(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(46, 106, 69))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            isClickable = true
            isFocusable = true
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (currentPanel != null) {
            closePanel()
            return
        }

        super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun AlertDialogBuilder(): android.app.AlertDialog.Builder =
        android.app.AlertDialog.Builder(this)

    companion object {
        private const val REQUEST_FILE = 4101
    }
}
