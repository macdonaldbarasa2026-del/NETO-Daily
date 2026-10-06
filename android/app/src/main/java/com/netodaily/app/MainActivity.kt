package com.netodaily.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.netodaily.app.agent.NetoPhoneAgent
import com.netodaily.app.ai.NetoDailyAiEngine
import com.netodaily.app.auth.AuthActivity
import com.netodaily.app.data.NetoConversation
import com.netodaily.app.data.NetoLocalStore
import com.netodaily.app.data.NetoDailyCloudSync
import com.netodaily.app.data.NetoDailyWorkspace
import com.netodaily.app.data.NetoMessage
import com.netodaily.app.live.NetoLiveSession
import com.netodaily.app.ui.NetoOrbView
import com.netodaily.app.vision.NetoVisionController
import com.netodaily.app.vision.NetoVisualPermissionController
import com.netodaily.app.vision.NetoVisualState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var store: NetoLocalStore
    private lateinit var cloudSync: NetoDailyCloudSync
    private lateinit var dailyWorkspace: NetoDailyWorkspace
    private lateinit var aiEngine: NetoDailyAiEngine
    private lateinit var phoneAgent: NetoPhoneAgent
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // UI Root and Inset Containers
    private lateinit var rootLayout: FrameLayout
    private lateinit var mainContentContainer: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomControls: LinearLayout

    // Center Stage Components (Gemini Live)
    private lateinit var orb: NetoOrbView
    private lateinit var statusPill: TextView
    private lateinit var liveCaptionText: TextView
    private lateinit var agentActionToast: TextView
    private lateinit var quickChipsRow: LinearLayout
    private lateinit var quickChipsScroll: HorizontalScrollView

    // Camera Preview Floating Card
    private lateinit var visualPreviewCard: View
    private lateinit var visualPreview: ImageView
    private lateinit var visualPreviewLabel: TextView

    // Full-screen native camera overlay
    private lateinit var cameraOverlay: FrameLayout
    private lateinit var cameraOverlayImage: ImageView

    // Bottom Controls
    private lateinit var micPillButton: LinearLayout
    private lateinit var micIcon: ImageView
    private lateinit var micLabel: TextView
    private lateinit var cameraBtn: ImageButton
    private lateinit var keyboardBtn: ImageButton
    private lateinit var stopBtn: ImageButton

    // Quick Composer Bar (Hidden by default, toggled with keyboardBtn)
    private lateinit var composerBar: LinearLayout
    private lateinit var inputEdit: EditText

    // Live Voice & Vision Controllers
    private lateinit var liveSession: NetoLiveSession
    private lateinit var visionController: NetoVisionController
    private lateinit var visualPermissions: NetoVisualPermissionController
    private var visualState: NetoVisualState = NetoVisualState.None

    private var conversation = NetoConversation(id = UUID.randomUUID().toString())
    private var busy = false

    private var currentUserCaption: String = ""
    private var currentNetoCaption: String = ""
    private var currentConfirmationDialog: AlertDialog? = null

    // Prevent repeated Gemini Live caption events from executing the same
    // phone action more than once.
    private var lastAgentCaption: String = ""
    private var lastAgentCaptionAt: Long = 0L

    // Colors
    private val colorBg = Color.rgb(9, 17, 15)
    private val colorSurface = Color.rgb(18, 31, 27)
    private val colorAccent = Color.rgb(0, 229, 163)
    private val colorTextPrimary = Color.WHITE
    private val colorTextSecondary = Color.rgb(150, 175, 166)

    // Permission launcher for Agentic Phone
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Continue smoothly regardless of permission outcome
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        store = NetoLocalStore(this)
        cloudSync = NetoDailyCloudSync()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        store.initDefaultDataIfEmpty(todayStr)

        phoneAgent = NetoPhoneAgent(this, store)
        aiEngine = NetoDailyAiEngine(this, store)
        conversation = store.loadCurrentConversation() ?: NetoConversation(id = UUID.randomUUID().toString())

        tts = TextToSpeech(this, this)

        requestRequiredPhonePermissions()
        buildGeminiLiveUi()
        setupLiveAndVision()
        attachDailyWorkspace()

        scope.launch {
            withContext(Dispatchers.IO) {
                cloudSync.restoreIntoLocal(store)
            }
            dailyWorkspace.refresh()
        }
    }

    private fun attachDailyWorkspace() {
        dailyWorkspace = NetoDailyWorkspace(
            context = this,
            store = store,
            onStudio = {
                dailyWorkspace.rootView().visibility = View.GONE
                mainContentContainer.visibility = View.VISIBLE
            },
            onSync = {
                scope.launch(Dispatchers.IO) {
                    cloudSync.syncAll(store)
                }
            },
            onDeleteTask = { id ->
                scope.launch(Dispatchers.IO) {
                    cloudSync.deleteTask(id)
                }
            },
            onDeleteHabit = { id ->
                scope.launch(Dispatchers.IO) {
                    cloudSync.deleteHabit(id)
                }
            },
            onDeleteNote = { id ->
                scope.launch(Dispatchers.IO) {
                    cloudSync.deleteNote(id)
                }
            }
        )

        val dailyView = dailyWorkspace.build()

        rootLayout.addView(
            dailyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Live is the primary screen after authentication.
        // Daily remains available through the workspace navigation.
        mainContentContainer.visibility = View.VISIBLE
        dailyView.visibility = View.GONE
    }

    private fun requestRequiredPhonePermissions() {
        // All permissions NETO Daily needs to act as a full phone agent
        val allRequired = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            allRequired.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = allRequired.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            val criticalMissing = missing.any {
                it in listOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_CONTACTS
                )
            }
            if (criticalMissing) {
                AlertDialog.Builder(this)
                    .setTitle("NETO needs permissions to be your agent")
                    .setMessage(
                        "To call contacts, send messages, control your phone by voice, and use live audio — NETO needs:\n\n" +
                        "Microphone - live voice input\n" +
                        "Phone — make calls by voice\n" +
                        "SMS — send messages by voice\n" +
                        "Contacts - look up names\n" +
                        "Camera - visual AI\n" +
                        "Notifications - agent alerts\n\n" +
                        "Tap Allow to enable all features."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        requestPermissionLauncher.launch(missing.toTypedArray())
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            } else {
                requestPermissionLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun buildGeminiLiveUi() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(colorBg)
        }

        mainContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Apply WindowInsets safely so nothing overflows or gets cut off!
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            mainContentContainer.setPadding(
                dp(16),
                systemBars.top + dp(6),
                dp(16),
                systemBars.bottom + dp(10)
            )
            insets
        }

        // 1. Top Header Bar
        topBar = createTopHeader()
        mainContentContainer.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2. Agent Action Toast (Pops up when a phone automation occurs)
        agentActionToast = TextView(this).apply {
            text = ""
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_action)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            gravity = Gravity.CENTER
        }
        mainContentContainer.addView(agentActionToast, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })

        // 3. Camera / Visual Floating Preview Card
        val visualLayout = layoutInflater.inflate(R.layout.view_visual_preview, mainContentContainer, false)
        visualPreviewCard = visualLayout.findViewById(R.id.visualPreviewCard)
        visualPreview = visualLayout.findViewById(R.id.visualPreview)
        visualPreviewLabel = visualLayout.findViewById(R.id.visualPreviewLabel)
        val closeVisual = visualLayout.findViewById<ImageButton>(R.id.visualPreviewClose)
        closeVisual.setOnClickListener { stopVisualInput() }
        mainContentContainer.addView(visualPreviewCard)

        // 4. Center Stage: Gemini Orb & Ambient Wave
        val centerContainer = FrameLayout(this).apply {
            id = View.generateViewId()
        }

        orb = NetoOrbView(this).apply {
            setState(NetoOrbView.State.IDLE)
            setOnClickListener { toggleVoice() }
        }
        val orbParams = FrameLayout.LayoutParams(dp(250), dp(250)).apply {
            gravity = Gravity.CENTER
        }
        centerContainer.addView(orb, orbParams)

        mainContentContainer.addView(centerContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 5. Live Streaming Caption & Response Area
        liveCaptionText = TextView(this).apply {
            text = "Tap the mic or speak to start your personal Gemini agent."
            textSize = 16f
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            maxLines = 4
        }
        mainContentContainer.addView(liveCaptionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 6. Quick Action Suggestion Chips
        quickChipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, dp(10))
        }
        quickChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        addQuickChip("Call", R.drawable.ic_call) { sendAgentPrompt("Call someone") }
        addQuickChip("Text", R.drawable.ic_message) { sendAgentPrompt("Send message") }
        addQuickChip("Open YouTube", R.drawable.ic_youtube) { sendAgentPrompt("Open YouTube") }
        addQuickChip("Alarm 7 AM", R.drawable.ic_alarm) { sendAgentPrompt("Set alarm for 7:00 AM") }
        addQuickChip("Flashlight", R.drawable.ic_flashlight) { sendAgentPrompt("Turn on flashlight") }
        addQuickChip("Search", R.drawable.ic_search) { sendAgentPrompt("Search Google for latest tech") }
        addQuickChip("Today's Plan", R.drawable.ic_tasks) { sendAgentPrompt("What is my schedule today?") }
        quickChipsScroll.addView(quickChipsRow)
        mainContentContainer.addView(quickChipsScroll)

        // 7. Floating Bottom Controls Bar
        bottomControls = createBottomControls()
        mainContentContainer.addView(bottomControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 8. Collapsible Composer Bar
        composerBar = createComposerBar()
        mainContentContainer.addView(composerBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        rootLayout.addView(
            mainContentContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        buildCameraOverlay()

        setContentView(rootLayout)
    }

    private fun buildCameraOverlay() {
        cameraOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            elevation = dp(24).toFloat()
        }

        cameraOverlayImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            contentDescription = "NETO live camera"
        }

        cameraOverlay.addView(
            cameraOverlayImage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "NETO CAMERA"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        topBar.addView(
            title,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val switchButton = ImageButton(this).apply {
            setImageDrawable(
                ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.ic_camera
                )
            )
            background = ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.bg_circle_button
            )
            setColorFilter(Color.WHITE)
            contentDescription = "Switch camera"

            setOnClickListener {
                if (::visionController.isInitialized) {
                    visionController.switchCamera()
                }
            }
        }

        topBar.addView(
            switchButton,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                rightMargin = dp(8)
            }
        )

        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.bg_circle_button
            )
            contentDescription = "Close camera"

            setOnClickListener {
                stopVisualInput()
            }
        }

        topBar.addView(
            closeButton,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        cameraOverlay.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(80),
                Gravity.TOP
            )
        )

        rootLayout.addView(
            cameraOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun createTopHeader(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val spark = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_sparkle))
        }
        bar.addView(spark, LinearLayout.LayoutParams(dp(26), dp(26)))

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }

        val brandTitle = TextView(this).apply {
            text = "NETO Daily"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
        }
        brandCol.addView(brandTitle)

        statusPill = TextView(this).apply {
            text = "● Gemini Live Ready"
            textSize = 12f
            setTextColor(colorAccent)
            setOnClickListener {
                if (::dailyWorkspace.isInitialized) {
                    mainContentContainer.visibility = View.GONE
                    dailyWorkspace.rootView().visibility = View.VISIBLE
                    dailyWorkspace.refresh()
                }
            }
        }
        brandCol.addView(statusPill)

        bar.addView(brandCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Profile / Settings Button
        val settingsBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_settings))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            setColorFilter(colorAccent)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showProfileSettings() }
        }
        bar.addView(settingsBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        return bar
    }

    private fun createBottomControls(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        // Camera Toggle Button
        cameraBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_camera))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { toggleCameraVision() }
        }
        row.addView(cameraBtn, LinearLayout.LayoutParams(dp(50), dp(50)))

        // Large Glowing Mic Pill (Center Stage)
        micPillButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { toggleVoice() }
        }

        micIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mic))
            setColorFilter(colorAccent)
        }
        micPillButton.addView(micIcon, LinearLayout.LayoutParams(dp(22), dp(22)))

        micLabel = TextView(this).apply {
            text = "Live Agent"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            setPadding(dp(10), 0, 0, 0)
        }
        micPillButton.addView(micLabel)

        row.addView(micPillButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            leftMargin = dp(10)
            rightMargin = dp(10)
        })

        // Keyboard Toggle Button
        keyboardBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_keyboard))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { toggleComposer() }
        }
        row.addView(keyboardBtn, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
            rightMargin = dp(8)
        })

        // Stop / Reset Session Button
        stopBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_stop))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            setColorFilter(Color.rgb(255, 82, 82))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { stopLiveSession() }
        }
        row.addView(stopBtn, LinearLayout.LayoutParams(dp(50), dp(50)))

        return row
    }

    private fun createComposerBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        inputEdit = EditText(this).apply {
            hint = "Ask NETO or command (e.g. Call John)..."
            textSize = 14f
            setTextColor(colorTextPrimary)
            setHintTextColor(colorTextSecondary)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setSingleLine(true)
        }
        bar.addView(inputEdit, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            rightMargin = dp(8)
        })

        val sendBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_send))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_button)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { sendTypedInput() }
        }
        bar.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        inputEdit.setOnEditorActionListener { _, _, _ ->
            sendTypedInput()
            true
        }

        return bar
    }

    private fun toggleComposer() {
        if (composerBar.visibility == View.VISIBLE) {
            composerBar.visibility = View.GONE
        } else {
            composerBar.visibility = View.VISIBLE
            inputEdit.requestFocus()
        }
    }

    private fun addQuickChip(
        label: String,
        iconRes: Int,
        onClick: () -> Unit
    ) {
        val chip = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            background = ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.bg_gemini_pill
            )

            setCompoundDrawablesWithIntrinsicBounds(
                iconRes,
                0,
                0,
                0
            )
            compoundDrawablePadding = dp(6)

            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(8)
        chip.layoutParams = lp
        quickChipsRow.addView(chip)
    }

    // --- Live Voice & Vision Session ---

    private fun setupLiveAndVision() {
        liveSession = NetoLiveSession(
            scope = scope,
            onStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        NetoLiveSession.State.IDLE -> {
                            orb.setState(NetoOrbView.State.IDLE)
                            statusPill.text = "● Ready"
                            micLabel.text = "Live Agent"
                            micIcon.setColorFilter(colorAccent)
                        }
                        NetoLiveSession.State.CONNECTING -> {
                            orb.setState(NetoOrbView.State.CONNECTING)
                            statusPill.text = "Connecting to Gemini Live..."
                            micLabel.text = "Connecting..."
                            micIcon.setColorFilter(Color.rgb(251, 191, 36))
                        }
                        NetoLiveSession.State.LISTENING -> {
                            orb.setState(NetoOrbView.State.LISTENING)
                            statusPill.text = "Listening... Speak naturally"
                            micLabel.text = "Listening..."
                            micIcon.setColorFilter(Color.WHITE)
                        }
                        NetoLiveSession.State.THINKING -> {
                            orb.setState(NetoOrbView.State.THINKING)
                            statusPill.text = "NETO is thinking..."
                            micLabel.text = "Thinking..."
                            micIcon.setColorFilter(Color.rgb(96, 165, 250))
                        }
                        NetoLiveSession.State.SPEAKING -> {
                            orb.setState(NetoOrbView.State.SPEAKING)
                            statusPill.text = "Speaking..."
                            micLabel.text = "Speaking..."
                            micIcon.setColorFilter(Color.rgb(56, 189, 248))
                        }
                        NetoLiveSession.State.ERROR -> {
                            orb.setState(NetoOrbView.State.ERROR)
                            statusPill.text = "● Error"
                            micLabel.text = "Try Again"
                            micIcon.setColorFilter(Color.rgb(239, 68, 68))
                        }
                    }
                }
            },
            onCaption = { speaker, text ->
                runOnUiThread {
                    if (text.isNotBlank()) {
                        val isUser = speaker.equals("You", ignoreCase = true) || speaker.isBlank()
                        if (isUser) {
                            currentUserCaption = text.trim()
                        } else {
                            currentNetoCaption = text.trim()
                        }

                        val dialogue = buildString {
                            if (currentUserCaption.isNotBlank()) {
                                append("You: ").append(currentUserCaption)
                            }
                            if (currentNetoCaption.isNotBlank()) {
                                if (isNotEmpty()) append("\n")
                                append("NETO: ").append(currentNetoCaption)
                            }
                        }

                        if (dialogue.isNotBlank()) {
                            liveCaptionText.text = dialogue
                        }

                        // Persist turn in conversation history
                        val role = if (isUser) NetoMessage.Role.USER else NetoMessage.Role.NETO
                        val msg = NetoMessage(
                            id = UUID.randomUUID().toString(),
                            role = role,
                            text = text.trim()
                        )
                        conversation = conversation.copy(
                            messages = conversation.messages + msg,
                            updatedAt = System.currentTimeMillis()
                        )
                        store.saveCurrentConversation(conversation)

                        // Check if user's caption triggers a phone agent action or voice confirmation
                        if (isUser) {
                            val now = System.currentTimeMillis()
                            val normalized = text.trim()

                            if (
                                normalized.isNotEmpty() &&
                                (
                                    !normalized.equals(lastAgentCaption, ignoreCase = true) ||
                                    now - lastAgentCaptionAt > 5000L
                                )
                            ) {
                                lastAgentCaption = normalized
                                lastAgentCaptionAt = now
                                checkAndExecuteAgentAction(normalized)
                            }
                        }
                    }
                }
            },
            onAudioLevel = { level ->
                runOnUiThread {
                    orb.setAudioLevel(level)
                }
            },
            onError = { message ->
                runOnUiThread {
                    val msg = message.ifBlank { "Live voice session ended." }
                    liveCaptionText.text = msg
                    orb.setState(NetoOrbView.State.ERROR)
                    statusPill.text = "● Error"
                    micLabel.text = "Live Agent"
                    micIcon.setColorFilter(Color.rgb(239, 68, 68))
                }
            }
        )

        visionController = NetoVisionController(
            context = this,
            liveSession = liveSession,
            onPreviewFrame = { frame ->
                runOnUiThread {
                    val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    if (bitmap != null) {
                        visualPreview.setImageBitmap(bitmap)
                        visualPreviewCard.visibility = View.VISIBLE

                        if (::cameraOverlay.isInitialized &&
                            visualState != NetoVisualState.Screen) {
                            cameraOverlayImage.setImageBitmap(bitmap)
                        }
                    }
                }
            },
            onStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        NetoVisionController.State.FRONT_CAMERA -> {
                            visualState = NetoVisualState.FrontCamera
                            visualPreviewLabel.text = "Front camera"
                            visualPreviewCard.visibility = View.GONE

                            if (::cameraOverlay.isInitialized) {
                                cameraOverlay.visibility = View.VISIBLE
                            }
                        }
                        NetoVisionController.State.BACK_CAMERA -> {
                            visualState = NetoVisualState.BackCamera
                            visualPreviewLabel.text = "Back camera"
                            visualPreviewCard.visibility = View.GONE

                            if (::cameraOverlay.isInitialized) {
                                cameraOverlay.visibility = View.VISIBLE
                            }
                        }
                        NetoVisionController.State.SCREEN -> {
                            visualState = NetoVisualState.Screen
                            visualPreviewLabel.text = "Screen sharing"
                            visualPreviewCard.visibility = View.VISIBLE

                            if (::cameraOverlay.isInitialized) {
                                cameraOverlay.visibility = View.GONE
                            }
                        }
                        NetoVisionController.State.OFF -> {
                            visualState = NetoVisualState.None
                            visualPreviewCard.visibility = View.GONE
                            visualPreview.setImageDrawable(null)

                            if (::cameraOverlay.isInitialized) {
                                cameraOverlay.visibility = View.GONE
                                cameraOverlayImage.setImageDrawable(null)
                            }
                        }
                    }
                }
            }
        )

        visualPermissions = NetoVisualPermissionController(
            activity = this,
            onCameraGranted = { visionController.startFrontCamera() },
            onScreenShareResult = { code, data -> visionController.startScreenShare(code, data) }
        )

        visionController.start()
    }

    private fun toggleVoice() {
        when (orb.currentState()) {
            NetoOrbView.State.IDLE, NetoOrbView.State.ERROR -> {
                orb.setState(NetoOrbView.State.CONNECTING)
                statusPill.text = "Connecting..."
                micLabel.text = "Connecting..."
                micIcon.setColorFilter(Color.rgb(251, 191, 36))
                liveSession.start()
            }
            else -> {
                liveSession.stop()
                orb.setState(NetoOrbView.State.IDLE)
                statusPill.text = "● Ready"
                micLabel.text = "Live Agent"
                micIcon.setColorFilter(colorAccent)
            }
        }
    }

    private fun stopLiveSession() {
        liveSession.stop()
        stopVisualInput()
        tts?.stop()
        orb.setState(NetoOrbView.State.IDLE)
        statusPill.text = "● Ready"
        micLabel.text = "Live Agent"
        micIcon.setColorFilter(colorAccent)
        currentUserCaption = ""
        currentNetoCaption = ""
        liveCaptionText.text = "Session ended. Tap mic to talk."
    }

    private fun toggleCameraVision() {
        when (visualState) {
            NetoVisualState.None -> visualPermissions.requestCamera()
            NetoVisualState.FrontCamera -> visionController.startBackCamera()
            NetoVisualState.BackCamera -> stopVisualInput()
            else -> stopVisualInput()
        }
    }

    private fun stopVisualInput() {
        if (::visionController.isInitialized) {
            visionController.stop()
        }

        visualState = NetoVisualState.None
        visualPreviewCard.visibility = View.GONE
        visualPreview.setImageDrawable(null)

        if (::cameraOverlay.isInitialized) {
            cameraOverlay.visibility = View.GONE
            cameraOverlayImage.setImageDrawable(null)
        }
    }

    // --- Agent Action Execution & Text Input ---

    private fun sendTypedInput() {
        val query = inputEdit.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        inputEdit.setText("")
        composerBar.visibility = View.GONE
        sendAgentPrompt(query)
    }

    private fun sendAgentPrompt(query: String) {
        currentUserCaption = query
        currentNetoCaption = ""
        liveCaptionText.text = "You: $query"
        orb.setState(NetoOrbView.State.THINKING)
        statusPill.text = "NETO is acting..."

        // 1. Check phone agent automation first
        val result = phoneAgent.handleAgenticAction(query)
        if (result.handled) {
            presentAgentResult(result)
            return
        }

        // 2. Query Gemini cloud or local AI engine
        scope.launch {
            try {
                val response = aiEngine.processUserMessage(query)
                currentNetoCaption = response
                val dialogue = "You: $query\nNETO: $response"
                liveCaptionText.text = dialogue
                speakOutLoud(response)

                val msgUser = NetoMessage(id = UUID.randomUUID().toString(), role = NetoMessage.Role.USER, text = query)
                val msgAi = NetoMessage(id = UUID.randomUUID().toString(), role = NetoMessage.Role.NETO, text = response)
                conversation = conversation.copy(
                    messages = conversation.messages + listOf(msgUser, msgAi),
                    updatedAt = System.currentTimeMillis()
                )
                store.saveCurrentConversation(conversation)

                orb.setState(NetoOrbView.State.IDLE)
                statusPill.text = "● Ready"
            } catch (e: Exception) {
                val err = e.message ?: "Something went wrong."
                currentNetoCaption = err
                liveCaptionText.text = "You: $query\nNETO: $err"
                orb.setState(NetoOrbView.State.IDLE)
                statusPill.text = "● Ready"
            }
        }
    }

    private fun checkAndExecuteAgentAction(text: String) {
        val normalizedCaption = text.trim().lowercase()
        val now = System.currentTimeMillis()

        if (
            normalizedCaption.isNotBlank() &&
            normalizedCaption == lastAgentCaption &&
            now - lastAgentCaptionAt < 5000L
        ) {
            return
        }

        lastAgentCaption = normalizedCaption
        lastAgentCaptionAt = now

        val result = phoneAgent.handleAgenticAction(text)
        if (result.handled) {
            presentAgentResult(result)
        }
    }

    private fun presentAgentResult(result: NetoPhoneAgent.AgentResult) {
        if (!result.handled) return

        currentConfirmationDialog?.dismiss()
        currentConfirmationDialog = null

        currentNetoCaption = result.feedback
        val dialogue = buildString {
            if (currentUserCaption.isNotBlank()) append("You: ").append(currentUserCaption).append("\n")
            append("NETO: ").append(result.feedback)
        }
        liveCaptionText.text = dialogue

        if (!result.requiresConfirmation) {
            showActionToast(result.feedback)
            speakOutLoud(result.feedback)

            orb.setState(NetoOrbView.State.IDLE)
            statusPill.text =
                if (result.actionType == "ERROR") {
                    "● Action Failed"
                } else {
                    "● Action Completed"
                }
            return
        }

        speakOutLoud(result.feedback)

        val title =
            when (result.actionType) {
                "CALL" -> "Confirm call"
                "SMS" -> "Confirm message"
                else -> "Confirm action"
            }

        currentConfirmationDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(result.feedback)
            .setNegativeButton("Cancel") { _, _ ->
                currentConfirmationDialog = null
                val cancelled = phoneAgent.cancelPendingAction()
                showActionToast(cancelled.feedback)
                currentNetoCaption = cancelled.feedback
                liveCaptionText.text = buildString {
                    if (currentUserCaption.isNotBlank()) append("You: ").append(currentUserCaption).append("\n")
                    append("NETO: ").append(cancelled.feedback)
                }
                speakOutLoud(cancelled.feedback)
                orb.setState(NetoOrbView.State.IDLE)
                statusPill.text = "● Cancelled"
            }
            .setPositiveButton("Confirm") { _, _ ->
                currentConfirmationDialog = null
                val confirmed = phoneAgent.confirmPendingAction()
                showActionToast(confirmed.feedback)
                currentNetoCaption = confirmed.feedback
                liveCaptionText.text = buildString {
                    if (currentUserCaption.isNotBlank()) append("You: ").append(currentUserCaption).append("\n")
                    append("NETO: ").append(confirmed.feedback)
                }
                speakOutLoud(confirmed.feedback)
                orb.setState(NetoOrbView.State.IDLE)
                statusPill.text =
                    if (confirmed.actionType == "ERROR") {
                        "● Action Failed"
                    } else {
                        "● Action Completed"
                    }
            }
            .setOnCancelListener {
                currentConfirmationDialog = null
                phoneAgent.cancelPendingAction()
            }
            .show()
    }

    private fun showActionToast(msg: String) {
        agentActionToast.text = msg
        agentActionToast.visibility = View.VISIBLE
        agentActionToast.postDelayed({
            agentActionToast.visibility = View.GONE
        }, 4000)
    }

    private fun speakOutLoud(text: String) {
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NETO_UTTERANCE")
        }
    }

    // --- TTS Listener ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ttsReady = true
        }
    }

    // --- Profile & Settings Modal ---

    private fun showProfileSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorSurface)
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }

        val title = TextView(this).apply {
            text = "NETO Cloud Agent Profile"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
        }
        layout.addView(title)

        val nameLabel = TextView(this).apply {
            text = "User Name:"
            textSize = 13f
            setTextColor(colorTextSecondary)
            setPadding(0, dp(12), 0, dp(4))
        }
        layout.addView(nameLabel)

        val nameInput = EditText(this).apply {
            setText(store.getUserName())
            setTextColor(colorTextPrimary)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_gemini_pill)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        layout.addView(nameInput)

        val agentPill = TextView(this).apply {
            text = "Agent Capabilities Active:\n✓ Phone Dialer\n✓ SMS & Messaging\n✓ App Launcher (YouTube, WhatsApp, Camera)\n✓ Alarm & Timer Engine\n✓ Flashlight Control\n✓ Web Search Bridge\n✓ Gemini Live Audio WebSocket"
            textSize = 13f
            setTextColor(colorAccent)
            setPadding(0, dp(14), 0, dp(14))
        }
        layout.addView(agentPill)

        val creatorLabel = TextView(this).apply {
            text = "Created by Macdonald Barasa\nPowered by Gemini API & Supabase"
            textSize = 12f
            setTextColor(colorTextSecondary)
        }
        layout.addView(creatorLabel)

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    store.setUserName(newName)
                }
            }
            .setNeutralButton("Account / Sign Out") { _, _ ->
                scope.launch {
                    runCatching { Supabase.client.auth.signOut() }
                    store.saveBoolean("has_entered", false)
                    startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        if (visualState != NetoVisualState.None) {
            stopVisualInput()
        }
    }

    override fun onStop() {
        super.onStop()
        if (visualState != NetoVisualState.None) {
            stopVisualInput()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        currentConfirmationDialog?.dismiss()
        currentConfirmationDialog = null
        liveSession.release()
        visionController.dispose()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
