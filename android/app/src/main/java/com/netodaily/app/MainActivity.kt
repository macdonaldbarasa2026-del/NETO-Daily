package com.netodaily.app

import android.graphics.Color
import android.os.Bundle
import com.netodaily.app.media.NetoScreenFrameBus
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

import com.netodaily.app.media.NetoCameraController
import com.netodaily.app.ui.NetoVoiceOrbView

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var orb: NetoVoiceOrbView
    private lateinit var caption: TextView
    private lateinit var captionSpeaker: TextView
    private lateinit var composer: EditText
    private lateinit var statusText: TextView

    private var menuOpen = false
    private var settingsOpen = false
    private var historyOpen = false
    private var aboutOpen = false
    private var listening = false
    private var pendingCameraFront = false

    private val liveScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val liveClient by lazy {
        GeminiLiveClient(
            scope = liveScope,

            onStateChanged = { state ->
                runOnUiThread {
                    orb.setState(state)

                    statusText.text = when (state) {
                        NetoVoiceOrbView.State.IDLE -> "Ready"
                        NetoVoiceOrbView.State.LISTENING -> "Listening"
                        NetoVoiceOrbView.State.THINKING -> "Thinking"
                        NetoVoiceOrbView.State.SPEAKING -> "Speaking"
                    }
                }
            },

            onCaption = { speaker, message ->
                runOnUiThread {
                    captionSpeaker.text = speaker
                    caption.text = message
                }
            },

            onAudioLevel = { level ->
                runOnUiThread {
                    orb.setAudioLevel(level)
                }
            },

            onError = { message ->
                runOnUiThread {
                    listening = false
                    orb.setState(NetoVoiceOrbView.State.IDLE)
                    orb.setAudioLevel(0f)
                    statusText.text = "Ready"
                    captionSpeaker.text = "NETO"
                    caption.text = message
                }
            }
        )
    }

    private val bg = Color.rgb(238, 243, 241)
    private val surface = Color.rgb(251, 252, 251)
    private val textColor = Color.rgb(18, 33, 30)
    private val muted = Color.rgb(100, 115, 111)
    private val accent = Color.rgb(8, 127, 104)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowInsetsControllerCompatHelper.lightBars(window)

        try {
            buildUi()
        } catch (t: Throwable) {
            showStartupError(t)
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        aboutOpen -> closePanel()
                        settingsOpen -> closePanel()
                        historyOpen -> closePanel()
                        menuOpen -> closeMenu()
                        else -> finish()
                    }
                }
            }
        )
    }

    private fun showStartupError(error: Throwable) {
        val trace = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(trace))

        val view = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(
                TextView(this@MainActivity).apply {
                    text = "NETO could not open the main screen.\n\n$trace"
                    textSize = 13f
                    setTextColor(textColor)
                    setPadding(dp(24), dp(32), dp(24), dp(32))
                }
            )
        }

        setContentView(view)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(bg)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        buildHome()

        setContentView(root)
    }

    private fun buildHome() {
        content.removeAllViews()

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val menu = iconButton(R.drawable.ic_menu)
        menu.setOnClickListener { openMenu() }

        val brand = TextView(this).apply {
            text = "NETO"
            textSize = 18f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val settings = iconButton(R.drawable.ic_more)
        settings.setOnClickListener { openSettings() }

        top.addView(
            menu,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        top.addView(
            brand,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        top.addView(
            settings,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        content.addView(
            top,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val title = TextView(this).apply {
            text = "What can we work through?"
            textSize = 28f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        content.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(80)
            ).apply {
                topMargin = dp(18)
            }
        )

        val stage = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        orb = NetoVoiceOrbView(this).apply {
            setState(NetoVoiceOrbView.State.IDLE)
            setOnClickListener {
                toggleVoice()
            }
        }

        stage.addView(
            orb,
            FrameLayout.LayoutParams(dp(290), dp(290), Gravity.CENTER)
        )

        content.addView(
            stage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(330)
            )
        )

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        content.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )

        val voiceHint = TextView(this).apply {
            text = "Tap the orb to speak"
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }

        content.addView(
            voiceHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(32)
            )
        )

        val captionCard = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(surface)
            strokeColor = Color.argb(28, 18, 33, 30)
            strokeWidth = dp(1)
            visibility = View.GONE
        }

        val captionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
        }

        captionSpeaker = TextView(this).apply {
            text = "You"
            textSize = 11f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        caption = TextView(this).apply {
            textSize = 16f
            setTextColor(textColor)
        }

        captionBox.addView(captionSpeaker)
        captionBox.addView(caption)

        captionCard.addView(captionBox)

        content.addView(
            captionCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(90)
            ).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
            }
        )

        val spacer = View(this)

        content.addView(
            spacer,
            LinearLayout.LayoutParams(
                1,
                0,
                1f
            )
        )

        val composerCard = MaterialCardView(this).apply {
            radius = dp(27).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(surface)
            strokeColor = Color.argb(28, 18, 33, 30)
            strokeWidth = dp(1)
        }

        val composerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val attach = iconButton(R.drawable.ic_add)
        attach.setOnClickListener {
            composer.hint = "Attachment support coming from NETO Inbox"
        }

        composer = EditText(this).apply {
            setTextColor(textColor)
            setHintTextColor(muted)
            textSize = 15f
            hint = "Type a message..."
            background = null
            setSingleLine(false)
            maxLines = 4
            setPadding(dp(12), 0, dp(8), 0)
        }

        val mic = iconButton(R.drawable.ic_mic)
        mic.setOnClickListener {
            toggleVoice()
        }

        val send = iconButton(R.drawable.ic_send)
        send.setOnClickListener {
            submitText()
        }

        composerRow.addView(
            attach,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        composerRow.addView(
            composer,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        composerRow.addView(
            mic,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        composerRow.addView(
            send,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                leftMargin = dp(4)
            }
        )

        composerCard.addView(composerRow)

        content.addView(
            composerCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(12)
            }
        )
    }

    private fun toggleVoice() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                7001
            )
            return
        }

        if (listening) {

            listening = false

            liveScope.launch {
                liveClient.stop()
            }

            return
        }

        listening = true

        captionSpeaker.text = "You"
        caption.text = "Listening..."

        liveScope.launch {
            liveClient.start()
        }
    }

    private fun submitText() {
        val message = composer.text.toString().trim()
        if (message.isEmpty()) return

        composer.setText("")
        statusText.text = "Thinking"
        orb.setState(NetoVoiceOrbView.State.THINKING)

        captionSpeaker.text = "You"
        caption.text = message

        scope.launch(Dispatchers.IO) {
            val result = callNetoChat(message)

            launch(Dispatchers.Main) {
                if (result.first) {
                    orb.setState(NetoVoiceOrbView.State.IDLE)
                    statusText.text = "Ready"
                    captionSpeaker.text = "NETO"
                    caption.text = result.second
                } else {
                    orb.setState(NetoVoiceOrbView.State.IDLE)
                    statusText.text = "Ready"
                    captionSpeaker.text = "NETO"
                    caption.text = result.second
                }
            }
        }
    }

    private fun callNetoChat(message: String): Pair<Boolean, String> {
        return try {
            val session = Supabase.client.auth.currentSessionOrNull()
                ?: return false to "Please sign in to continue."

            val url = URL(
                "${BuildConfig.SUPABASE_URL}/functions/v1/neto-chat"
            )

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
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

            val body = JSONObject()
                .put("message", message)

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val stream =
                if (connection.responseCode in 200..299)
                    connection.inputStream
                else
                    connection.errorStream

            val response = stream.bufferedReader().use { it.readText() }

            if (connection.responseCode !in 200..299) {
                val error = try {
                    JSONObject(response).optString(
                        "error",
                        "NETO could not complete the request."
                    )
                } catch (_: Exception) {
                    "NETO could not complete the request."
                }

                return false to error
            }

            val json = JSONObject(response)

            val answer = json.optString(
                "message",
                "NETO received your message."
            )

            true to answer
        } catch (e: Exception) {
            false to "NETO could not connect right now. Please try again."
        }
    }

    private fun openMenu() {
        if (menuOpen) return

        menuOpen = true

        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            setOnClickListener { closeMenu() }
        }

        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            elevation = dp(12).toFloat()
            setPadding(
                dp(24),
                dp(32),
                dp(18),
                dp(24)
            )
        }

        val heading = TextView(this).apply {
            text = "NETO"
            textSize = 20f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "Voice-first AI assistant"
            textSize = 13f
            setTextColor(muted)
        }

        panel.addView(heading)
        panel.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            )
        )

        addMenuItem(panel, "New chat") {
            closeMenu()
            buildHome()
        }

        addMenuItem(panel, "History") {
            closeMenu()
            openHistory()
        }

        addMenuItem(panel, "Settings") {
            closeMenu()
            openSettings()
        }

        addMenuItem(panel, "About creator") {
            closeMenu()
            openAbout()
        }

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                dp(310),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        )
    }

    private fun addMenuItem(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val item = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { action() }
            background = roundedBackground(
                Color.argb(15, 8, 127, 104),
                dp(16).toFloat()
            )
        }

        parent.addView(
            item,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                topMargin = dp(6)
            }
        )
    }

    private fun closeMenu() {
        if (!menuOpen) return

        menuOpen = false

        while (root.childCount > 1) {
            root.removeViewAt(root.childCount - 1)
        }
    }

    private fun openSettings() {
        settingsOpen = true
        showPanel(
            "Settings",
            listOf(
                "Voice" to "Sky",
                "Speaking speed" to "Normal",
                "Captions" to "On",
                "Memories" to "Managed by NETO"
            )
        )
    }

    private fun openHistory() {
        historyOpen = true
        showPanel(
            "History",
            listOf(
                "Conversations" to "Your saved NETO conversations appear here.",
                "Search" to "Search across your previous conversations."
            )
        )
    }

    private fun openAbout() {
        aboutOpen = true
        showPanel(
            "About NETO",
            listOf(
                "Creator" to "Macdonald Barasa",
                "Product" to "NETO AI assistant",
                "Purpose" to "Ask, do, remember, find and act."
            )
        )
    }

    private fun showPanel(
        title: String,
        rows: List<Pair<String, String>>
    ) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(80, 0, 0, 0))
            setOnClickListener { closePanel() }
        }

        val card = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = dp(8).toFloat()
            setCardBackgroundColor(surface)
            setOnClickListener { }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = iconButton(R.drawable.ic_back)
        back.setOnClickListener { closePanel() }

        val heading = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            back,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        header.addView(
            heading,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(10)
            }
        )

        box.addView(header)

        rows.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(14), dp(4), dp(14))
            }

            val labelView = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(muted)
            }

            val valueView = TextView(this).apply {
                text = value
                textSize = 16f
                setTextColor(textColor)
            }

            row.addView(labelView)
            row.addView(valueView)

            box.addView(row)
        }

        card.addView(box)

        overlay.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(12)
            }
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun closePanel() {
        settingsOpen = false
        historyOpen = false
        aboutOpen = false

        if (root.childCount > 1) {
            root.removeViewAt(root.childCount - 1)
        }
    }

    private fun iconButton(icon: Int): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(textColor)
            background = roundedBackground(
                surface,
                dp(50).toFloat()
            )
            elevation = dp(1).toFloat()
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = null
        }
    }

    private fun circleButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            background = roundedBackground(
                surface,
                dp(50).toFloat()
            )
            elevation = dp(1).toFloat()
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Float
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 7001) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                toggleVoice()
            } else {
                statusText.text = "Ready"
                captionSpeaker.text = "NETO"
                caption.text =
                    "Microphone access is needed for voice conversations."
            }
            return
        }

        if (
            requestCode == 7003 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera(
                if (pendingCameraFront)
                    NetoCameraController.Lens.FRONT
                else
                    NetoCameraController.Lens.BACK
            )
        }
    }

    private val cameraController by lazy {
        NetoCameraController(
            this,
            onFrame = { jpeg ->
                liveClient.sendVideoFrame(jpeg)
            },
            onError = { message ->
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        message,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun requestCamera(
        front: Boolean
    ) {

        pendingCameraFront = front

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                7003
            )

            return
        }

        startCamera(
            if (front)
                NetoCameraController.Lens.FRONT
            else
                NetoCameraController.Lens.BACK
        )
    }

    private fun startCamera(
        lens: NetoCameraController.Lens
    ) {
        cameraController.start(lens)
    }

    private fun requestScreenShare() {

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            7002
        )
    }


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

        if (requestCode == 7002) {

            if (
                resultCode != RESULT_OK ||
                data == null
            ) {

                android.widget.Toast.makeText(
                    this,
                    "Screen sharing was cancelled.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                return
            }

            val serviceIntent =
                Intent(
                    this,
                    com.netodaily.app.media
                        .NetoMediaCaptureService::class.java
                ).apply {

                    action =
                        com.netodaily.app.media
                            .NetoMediaCaptureService
                            .ACTION_START

                    putExtra(
                        com.netodaily.app.media
                            .NetoMediaCaptureService
                            .EXTRA_RESULT_CODE,
                        resultCode
                    )

                    putExtra(
                        com.netodaily.app.media
                            .NetoMediaCaptureService
                            .EXTRA_RESULT_DATA,
                        data
                    )
                }

            androidx.core.content.ContextCompat
                .startForegroundService(
                    this,
                    serviceIntent
                )

            NetoScreenFrameBus.setListener { jpeg ->
                liveClient.sendVideoFrame(jpeg)
            }

            android.widget.Toast.makeText(
                this,
                "NETO is viewing your screen",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {

        NetoScreenFrameBus.setListener(null)

        cameraController.stop()

        liveClient.release()

        liveScope.cancel()

        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

private object WindowInsetsControllerCompatHelper {

    fun lightBars(window: Window) {

        if (android.os.Build.VERSION.SDK_INT >= 30) {

            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )

        } else {

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}
