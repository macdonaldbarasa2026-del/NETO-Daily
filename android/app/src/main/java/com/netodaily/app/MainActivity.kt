package com.netodaily.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.netodaily.app.ai.NetoDailyAiEngine
import com.netodaily.app.auth.AuthActivity
import com.netodaily.app.data.DailyHabit
import com.netodaily.app.data.DailyNote
import com.netodaily.app.data.DailyTask
import com.netodaily.app.data.NetoConversation
import com.netodaily.app.data.NetoLocalStore
import com.netodaily.app.data.NetoMessage
import com.netodaily.app.live.NetoLiveSession
import com.netodaily.app.ui.NetoOrbView
import com.netodaily.app.vision.NetoVisionController
import com.netodaily.app.vision.NetoVisualPermissionController
import com.netodaily.app.vision.NetoVisualState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var store: NetoLocalStore
    private lateinit var aiEngine: NetoDailyAiEngine

    // Navigation & Views
    private lateinit var rootContainer: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var bottomNav: LinearLayout

    private lateinit var todayView: ScrollView
    private lateinit var studioView: LinearLayout
    private lateinit var habitsView: ScrollView
    private lateinit var notesView: ScrollView

    // Today tab components
    private lateinit var tasksContainer: LinearLayout
    private lateinit var habitsRowContainer: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var greetingTitle: TextView

    // Studio tab components
    private lateinit var orb: NetoOrbView
    private lateinit var statusView: TextView
    private lateinit var captionView: TextView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var studioScrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var visualPreviewCard: View
    private lateinit var visualPreview: ImageView
    private lateinit var visualPreviewLabel: TextView

    // Habits tab components
    private lateinit var habitsListContainer: LinearLayout

    // Notes tab components
    private lateinit var notesListContainer: LinearLayout

    // State
    private var currentTab = 0 // 0: Today, 1: Studio, 2: Habits, 3: Notes
    private var conversation = NetoConversation(id = UUID.randomUUID().toString())
    private var busy = false

    private lateinit var liveSession: NetoLiveSession
    private lateinit var visionController: NetoVisionController
    private lateinit var visualPermissions: NetoVisualPermissionController
    private var visualState: NetoVisualState = NetoVisualState.None

    // Colors
    private val colorBg = Color.rgb(246, 251, 244)
    private val colorPrimary = Color.rgb(8, 127, 104)
    private val colorPrimaryDark = Color.rgb(15, 90, 77)
    private val colorTextDark = Color.rgb(22, 38, 30)
    private val colorTextMuted = Color.rgb(105, 120, 112)
    private val colorCardBg = Color.WHITE
    private val colorBorder = Color.rgb(228, 236, 231)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        store = NetoLocalStore(this)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        store.initDefaultDataIfEmpty(todayStr)

        val isGuest = store.isGuestMode() || store.getBoolean("has_entered", false)
        val isLoggedIn = runCatching { Supabase.client.auth.currentUserOrNull() != null }.getOrDefault(false)

        if (!isLoggedIn && !isGuest) {
            openAuth()
            return
        }

        aiEngine = NetoDailyAiEngine(store)
        conversation = store.loadCurrentConversation() ?: NetoConversation(id = UUID.randomUUID().toString())

        buildModernUi()
        setupLiveAndVision()
        restoreConversation()
        refreshAllData()
    }

    private fun buildModernUi() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        // Top App Bar
        rootContainer.addView(createTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        // Content Frame (swaps between 4 tabs)
        contentFrame = FrameLayout(this).apply {
            id = View.generateViewId()
        }

        todayView = createTodayTab()
        studioView = createStudioTab()
        habitsView = createHabitsTab()
        notesView = createNotesTab()

        contentFrame.addView(todayView)
        contentFrame.addView(studioView)
        contentFrame.addView(habitsView)
        contentFrame.addView(notesView)

        rootContainer.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom Navigation Bar
        bottomNav = createBottomNav()
        rootContainer.addView(bottomNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        setContentView(rootContainer)
        switchTab(0)
    }

    // --- Top Bar ---

    private fun createTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundColor(colorBg)
        }

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "NETO Daily"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
        }

        val pill = TextView(this).apply {
            val isCloud = runCatching { Supabase.client.auth.currentUserOrNull() != null }.getOrDefault(false)
            text = if (isCloud) "● Cloud Synced" else "● Local Assistant"
            textSize = 11f
            setTextColor(colorTextMuted)
        }

        brandCol.addView(title)
        brandCol.addView(pill)

        bar.addView(brandCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Quick Add Button
        val addBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_add))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_chip)
            setColorFilter(colorPrimary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showQuickAddDialog() }
        }
        bar.addView(addBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) })

        // Settings Button
        val settingsBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_settings))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_chip)
            setColorFilter(colorPrimary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showSettingsDialog() }
        }
        bar.addView(settingsBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        return bar
    }

    // --- Tab 0: Today Dashboard ---

    private fun createTodayTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(16), 0, dp(16), dp(16))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Date & Motivational Greeting
        greetingTitle = TextView(this).apply {
            val name = store.getUserName().ifBlank { "User" }
            text = "Good morning, $name ☀️"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
            setPadding(0, dp(8), 0, dp(2))
        }
        body.addView(greetingTitle)

        val dateSubtitle = TextView(this).apply {
            val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            text = dateFmt
            textSize = 14f
            setTextColor(colorTextMuted)
            setPadding(0, 0, 0, dp(14))
        }
        body.addView(dateSubtitle)

        // Progress Hero Card
        val heroCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_accent)
            setContentPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val heroTitle = TextView(this).apply {
            text = "Daily Productivity & Routines"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        heroContent.addView(heroTitle)

        progressText = TextView(this).apply {
            text = "Loading today's schedule..."
            textSize = 13f
            setTextColor(Color.rgb(220, 245, 235))
            setPadding(0, dp(4), 0, dp(10))
        }
        heroContent.addView(progressText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 50
            progressDrawable.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        heroContent.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        val briefingBtn = MaterialButton(this).apply {
            text = "⚡ NETO Daily Briefing"
            textSize = 13f
            setTextColor(colorPrimaryDark)
            setBackgroundColor(Color.WHITE)
            cornerRadius = dp(12)
            isAllCaps = false
            setOnClickListener {
                switchTab(1)
                requestNeto("Give me my daily briefing for today")
            }
        }
        heroContent.addView(briefingBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { topMargin = dp(12) })

        heroCard.addView(heroContent)
        body.addView(heroCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        // Quick Action Chips Row
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(14))
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val actionAdd = createPillChip("+ Add Task") { showAddTaskDialog() }
        val actionHabit = createPillChip("+ Add Habit") { showAddHabitDialog() }
        val actionNote = createPillChip("📝 Note") { showAddNoteDialog() }
        val actionAsk = createPillChip("✨ Ask NETO") { switchTab(1) }

        chipRow.addView(actionAdd)
        chipRow.addView(actionHabit)
        chipRow.addView(actionNote)
        chipRow.addView(actionAsk)
        chipScroll.addView(chipRow)
        body.addView(chipScroll)

        // Today's Agenda Section
        val agendaHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        val agendaTitle = TextView(this).apply {
            text = "Today's Agenda"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
        }
        agendaHeaderRow.addView(agendaTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val addTextBtn = TextView(this).apply {
            text = "+ Task"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            setOnClickListener { showAddTaskDialog() }
        }
        agendaHeaderRow.addView(addTextBtn)
        body.addView(agendaHeaderRow)

        tasksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(tasksContainer)

        // Habits Quick Row Section
        val habitsHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(10))
        }
        val habitsTitle = TextView(this).apply {
            text = "Daily Habits & Streaks"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
        }
        habitsHeaderRow.addView(habitsTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val viewAllHabits = TextView(this).apply {
            text = "View all →"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            setOnClickListener { switchTab(2) }
        }
        habitsHeaderRow.addView(viewAllHabits)
        body.addView(habitsHeaderRow)

        val habitScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        habitsRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        habitScroll.addView(habitsRowContainer)
        body.addView(habitScroll)

        // Daily Reflection Card
        val quoteCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = colorBorder
            setCardBackgroundColor(Color.WHITE)
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val quoteContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val quoteTitle = TextView(this).apply {
            text = "💡 Daily Motivation"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
        }
        val quoteBody = TextView(this).apply {
            text = "\"Excellence is not an act, but a habit. What you practice each day defines who you become.\""
            textSize = 14f
            setTextColor(colorTextDark)
            setPadding(0, dp(6), 0, dp(8))
        }
        val quotePrompt = TextView(this).apply {
            text = "Reflect with NETO →"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            setOnClickListener {
                switchTab(1)
                requestNeto("Let's do a 2-minute daily reflection")
            }
        }
        quoteContent.addView(quoteTitle)
        quoteContent.addView(quoteBody)
        quoteContent.addView(quotePrompt)
        quoteCard.addView(quoteContent)

        body.addView(quoteCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            bottomMargin = dp(24)
        })

        scroll.addView(body)
        return scroll
    }

    // --- Tab 1: AI Studio (Orb, Voice, Vision & Chat) ---

    private fun createStudioTab(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
        }

        studioScrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // Orb
        orb = NetoOrbView(this).apply {
            setState(NetoOrbView.State.IDLE)
            setOnClickListener { toggleVoice() }
        }
        scrollContent.addView(orb, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        // Status & Hint
        statusView = TextView(this).apply {
            text = "Ready · Tap orb to speak"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            gravity = Gravity.CENTER
        }
        scrollContent.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))

        // Camera / Visual Preview Container
        val visualLayout = layoutInflater.inflate(R.layout.view_visual_preview, scrollContent, false)
        visualPreviewCard = visualLayout.findViewById(R.id.visualPreviewCard)
        visualPreview = visualLayout.findViewById(R.id.visualPreview)
        visualPreviewLabel = visualLayout.findViewById(R.id.visualPreviewLabel)
        val closeVisual = visualLayout.findViewById<ImageButton>(R.id.visualPreviewClose)
        closeVisual.setOnClickListener { stopVisualInput() }
        scrollContent.addView(visualPreviewCard)

        // Live Captions Box
        captionView = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(colorTextDark)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_rounded)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        scrollContent.addView(captionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        // Quick Suggestion Prompts
        val promptScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(8))
        }
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        promptRow.addView(createPillChip("📋 What's my schedule?") { requestNeto("What is my schedule today?") })
        promptRow.addView(createPillChip("🔥 Habit check-in") { requestNeto("Review my daily habits and streaks") })
        promptRow.addView(createPillChip("💡 Focus tips") { requestNeto("Give me a tip to enter deep focus") })
        promptRow.addView(createPillChip("✨ Plan afternoon") { requestNeto("Help me organize my afternoon priorities") })
        promptScroll.addView(promptRow)
        scrollContent.addView(promptScroll)

        // Messages Container
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        scrollContent.addView(messagesContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        studioScrollView.addView(scrollContent)
        root.addView(studioScrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Modern Composer
        root.addView(createComposer(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        return root
    }

    private fun createComposer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }

        // Camera toggle button
        val camBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_camera))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_chip)
            setColorFilter(colorPrimary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { toggleCameraVision() }
        }
        row.addView(camBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })

        // Input field
        input = EditText(this).apply {
            hint = "Ask NETO or type 'add task...' "
            textSize = 14f
            setSingleLine(true)
            setTextColor(colorTextDark)
            setHintTextColor(colorTextMuted)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input_rounded)
            setPadding(dp(16), 0, dp(16), 0)
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })

        // Mic toggle button
        val micBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mic))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_chip)
            setColorFilter(colorPrimary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { toggleVoice() }
        }
        row.addView(micBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })

        // Send button
        val sendBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_send))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_button)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { sendTypedMessage() }
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        input.setOnEditorActionListener { _, _, _ ->
            sendTypedMessage()
            true
        }

        return row
    }

    // --- Tab 2: Habits Hub ---

    private fun createHabitsTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(14))
        }

        val title = TextView(this).apply {
            text = "Habits & Consistency"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
        }
        headerRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val addBtn = MaterialButton(this).apply {
            text = "+ New Habit"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(colorPrimary)
            cornerRadius = dp(14)
            isAllCaps = false
            setOnClickListener { showAddHabitDialog() }
        }
        headerRow.addView(addBtn)
        body.addView(headerRow)

        habitsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(habitsListContainer)

        scroll.addView(body)
        return scroll
    }

    // --- Tab 3: Notes & Reflections ---

    private fun createNotesTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(14))
        }

        val title = TextView(this).apply {
            text = "Reflections & Smart Notes"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
        }
        headerRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val addBtn = MaterialButton(this).apply {
            text = "+ New Note"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(colorPrimary)
            cornerRadius = dp(14)
            isAllCaps = false
            setOnClickListener { showAddNoteDialog() }
        }
        headerRow.addView(addBtn)
        body.addView(headerRow)

        notesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(notesListContainer)

        scroll.addView(body)
        return scroll
    }

    // --- Bottom Navigation Bar ---

    private fun createBottomNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_bottom_bar)
        }

        bar.addView(createNavItem(0, "Today", R.drawable.ic_tab_today), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(createNavItem(1, "AI Studio", R.drawable.ic_tab_studio), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(createNavItem(2, "Habits", R.drawable.ic_tab_habits), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(createNavItem(3, "Notes", R.drawable.ic_tab_notes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        return bar
    }

    private fun createNavItem(index: Int, label: String, iconRes: Int): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            tag = index
            setOnClickListener { switchTab(index) }
        }

        val icon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, iconRes))
            id = View.generateViewId()
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
            id = View.generateViewId()
        }

        col.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        col.addView(text)

        return col
    }

    private fun switchTab(tabIndex: Int) {
        currentTab = tabIndex
        todayView.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        studioView.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        habitsView.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        notesView.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

        // Update bottom nav highlighting
        for (i in 0 until bottomNav.childCount) {
            val child = bottomNav.getChildAt(i) as? LinearLayout ?: continue
            val icon = child.getChildAt(0) as? ImageView
            val text = child.getChildAt(1) as? TextView
            val isSelected = (i == tabIndex)

            val tint = if (isSelected) colorPrimary else colorTextMuted
            icon?.setColorFilter(tint)
            text?.setTextColor(tint)
        }

        if (tabIndex == 0) refreshTodayData()
        if (tabIndex == 2) refreshHabitsList()
        if (tabIndex == 3) refreshNotesList()
    }

    // --- Data Rendering ---

    private fun refreshAllData() {
        refreshTodayData()
        refreshHabitsList()
        refreshNotesList()
    }

    private fun refreshTodayData() {
        val tasks = store.getTasks()
        val habits = store.getHabits()

        // Update greeting
        val name = store.getUserName().ifBlank { "User" }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeWord = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        greetingTitle.text = "$timeWord, $name ☀️"

        // Update progress hero
        val completedCount = tasks.count { it.isCompleted }
        val percent = if (tasks.isNotEmpty()) (completedCount * 100) / tasks.size else 0
        progressText.text = "$completedCount of ${tasks.size} tasks done today · $percent% complete"
        progressBar.progress = percent

        // Render tasks
        tasksContainer.removeAllViews()
        if (tasks.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No tasks yet today. Tap '+ Task' to plan your first win!"
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(dp(12), dp(16), dp(12), dp(16))
            }
            tasksContainer.addView(empty)
        } else {
            tasks.forEach { task ->
                tasksContainer.addView(createTaskCard(task))
            }
        }

        // Render quick habits row
        habitsRowContainer.removeAllViews()
        habits.forEach { habit ->
            habitsRowContainer.addView(createQuickHabitCard(habit))
        }
    }

    private fun createTaskCard(task: DailyTask): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = colorBorder
            setCardBackgroundColor(colorCardBg)
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val check = CheckBox(this).apply {
            isChecked = task.isCompleted
            setOnCheckedChangeListener { _, _ ->
                store.toggleTask(task.id)
                refreshTodayData()
            }
        }
        row.addView(check)

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }

        val title = TextView(this).apply {
            text = task.title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (task.isCompleted) colorTextMuted else colorTextDark)
            if (task.isCompleted) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
        infoCol.addView(title)

        val meta = TextView(this).apply {
            text = "⏰ ${task.timeSlot}  ·  ${task.category}"
            textSize = 12f
            setTextColor(colorTextMuted)
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(meta)

        row.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val delBtn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete))
            background = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                store.deleteTask(task.id)
                refreshTodayData()
            }
        }
        row.addView(delBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        card.addView(row)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        card.layoutParams = lp
        return card
    }

    private fun createQuickHabitCard(habit: DailyHabit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = if (habit.completedToday) colorPrimary else colorBorder
            setCardBackgroundColor(if (habit.completedToday) Color.rgb(235, 248, 241) else Color.WHITE)
            setContentPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                store.toggleHabit(habit.id, todayStr)
                refreshTodayData()
                refreshHabitsList()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val icon = TextView(this).apply {
            text = habit.icon
            textSize = 24f
            gravity = Gravity.CENTER
        }
        col.addView(icon)

        val name = TextView(this).apply {
            text = habit.name
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        col.addView(name)

        val streak = TextView(this).apply {
            text = "🔥 ${habit.streak}d"
            textSize = 11f
            setTextColor(if (habit.completedToday) colorPrimary else colorTextMuted)
            gravity = Gravity.CENTER
        }
        col.addView(streak)

        card.addView(col)
        val lp = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = dp(10)
        card.layoutParams = lp
        return card
    }

    private fun refreshHabitsList() {
        habitsListContainer.removeAllViews()
        val habits = store.getHabits()

        if (habits.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No habits tracked yet. Tap '+ New Habit' to build your streak!"
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(dp(12), dp(16), dp(12), dp(16))
            }
            habitsListContainer.addView(empty)
            return
        }

        habits.forEach { habit ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = colorBorder
                setCardBackgroundColor(Color.WHITE)
                setContentPadding(dp(16), dp(14), dp(16), dp(14))
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val iconText = TextView(this).apply {
                text = habit.icon
                textSize = 28f
            }
            row.addView(iconText)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }
            val title = TextView(this).apply {
                text = habit.name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextDark)
            }
            val sub = TextView(this).apply {
                text = "Streak: 🔥 ${habit.streak} days · ${habit.category}"
                textSize = 13f
                setTextColor(colorTextMuted)
                setPadding(0, dp(2), 0, 0)
            }
            info.addView(title)
            info.addView(sub)
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val doneBtn = MaterialButton(this).apply {
                text = if (habit.completedToday) "✓ Done" else "Check in"
                textSize = 12f
                setTextColor(if (habit.completedToday) Color.WHITE else colorPrimary)
                setBackgroundColor(if (habit.completedToday) colorPrimary else Color.rgb(235, 248, 241))
                cornerRadius = dp(12)
                isAllCaps = false
                setOnClickListener {
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    store.toggleHabit(habit.id, todayStr)
                    refreshAllData()
                }
            }
            row.addView(doneBtn)

            card.addView(row)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            card.layoutParams = lp
            habitsListContainer.addView(card)
        }
    }

    private fun refreshNotesList() {
        notesListContainer.removeAllViews()
        val notes = store.getNotes()

        if (notes.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No notes or reflections yet. Tap '+ New Note' to capture your thoughts!"
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(dp(12), dp(16), dp(12), dp(16))
            }
            notesListContainer.addView(empty)
            return
        }

        notes.forEach { note ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = colorBorder
                setCardBackgroundColor(Color.WHITE)
                setContentPadding(dp(16), dp(14), dp(16), dp(14))
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = note.title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextDark)
            }
            topRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val delBtn = ImageButton(this).apply {
                setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete))
                background = null
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    store.deleteNote(note.id)
                    refreshNotesList()
                }
            }
            topRow.addView(delBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
            col.addView(topRow)

            val body = TextView(this).apply {
                text = note.content
                textSize = 14f
                setTextColor(colorTextDark)
                setPadding(0, dp(6), 0, dp(6))
            }
            col.addView(body)

            val tag = TextView(this).apply {
                val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(note.timestamp))
                text = "🏷️ ${note.tag} · $timeStr"
                textSize = 12f
                setTextColor(colorTextMuted)
            }
            col.addView(tag)

            card.addView(col)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            card.layoutParams = lp
            notesListContainer.addView(card)
        }
    }

    // --- Studio Chat & Voice Actions ---

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
            title = text.take(40).ifBlank { "New chat" },
            updatedAt = System.currentTimeMillis(),
            messages = conversation.messages + userMessage
        )

        addMessageBubble(userMessage)
        store.saveCurrentConversation(conversation)
        scrollStudioToBottom()

        requestNeto(text)
    }

    private fun requestNeto(text: String) {
        busy = true
        orb.setState(NetoOrbView.State.THINKING)
        statusView.text = "NETO is thinking..."
        captionView.visibility = View.VISIBLE
        captionView.text = "NETO is preparing reply…"

        scope.launch {
            try {
                val reply = aiEngine.processUserMessage(text)

                val netoMsg = NetoMessage(
                    id = UUID.randomUUID().toString(),
                    role = NetoMessage.Role.NETO,
                    text = reply
                )

                conversation = conversation.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = conversation.messages + netoMsg
                )

                addMessageBubble(netoMsg)
                captionView.text = reply
                captionView.visibility = View.VISIBLE
                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"

                store.saveCurrentConversation(conversation)
                scrollStudioToBottom()
                refreshAllData()

            } catch (e: Throwable) {
                val err = e.message ?: "Something went wrong."
                captionView.text = err
                captionView.visibility = View.VISIBLE
                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"
            } finally {
                busy = false
            }
        }
    }

    private fun restoreConversation() {
        messagesContainer.removeAllViews()
        conversation.messages.forEach {
            addMessageBubble(it)
        }
        if (conversation.messages.isNotEmpty()) {
            scrollStudioToBottom()
            val last = conversation.messages.last()
            if (last.role == NetoMessage.Role.NETO) {
                captionView.text = last.text
                captionView.visibility = View.VISIBLE
            }
        }
    }

    private fun addMessageBubble(message: NetoMessage) {
        val isUser = message.role == NetoMessage.Role.USER
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }

        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = if (isUser) 0 else dp(1)
            strokeColor = colorBorder
            setCardBackgroundColor(if (isUser) colorPrimary else Color.WHITE)
            setContentPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val text = TextView(this).apply {
            this.text = message.text
            textSize = 14f
            setTextColor(if (isUser) Color.WHITE else colorTextDark)
        }
        card.addView(text)
        wrapper.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        messagesContainer.addView(wrapper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun scrollStudioToBottom() {
        studioScrollView.post {
            studioScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    // --- Live Voice & Vision ---

    private fun setupLiveAndVision() {
        liveSession = NetoLiveSession(
            scope = scope,
            onStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        NetoLiveSession.State.IDLE -> {
                            orb.setState(NetoOrbView.State.IDLE)
                            statusView.text = "Ready · Tap orb to speak"
                        }
                        NetoLiveSession.State.LISTENING -> {
                            orb.setState(NetoOrbView.State.LISTENING)
                            statusView.text = "Listening... Speak naturally"
                        }
                        NetoLiveSession.State.THINKING -> {
                            orb.setState(NetoOrbView.State.THINKING)
                            statusView.text = "NETO is thinking..."
                        }
                        NetoLiveSession.State.SPEAKING -> {
                            orb.setState(NetoOrbView.State.SPEAKING)
                            statusView.text = "Speaking · Tap orb to stop"
                        }
                    }
                }
            },
            onCaption = { speaker, text ->
                runOnUiThread {
                    if (text.isNotBlank()) {
                        captionView.text = if (speaker.isBlank()) text else "$speaker: $text"
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
                    captionView.text = message.ifBlank { "Live voice session ended." }
                    captionView.visibility = View.VISIBLE
                    if (::orb.isInitialized) orb.setState(NetoOrbView.State.IDLE)
                    statusView.text = "Ready"
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

        visualPermissions = NetoVisualPermissionController(
            activity = this,
            onCameraGranted = { visionController.startFrontCamera() },
            onScreenShareResult = { code, data -> visionController.startScreenShare(code, data) }
        )

        visionController.start()
    }

    private fun toggleVoice() {
        if (!::liveSession.isInitialized) return
        when (orb.currentState()) {
            NetoOrbView.State.IDLE -> {
                orb.setState(NetoOrbView.State.THINKING)
                statusView.text = "Connecting..."
                liveSession.start()
            }
            else -> {
                liveSession.stop()
                orb.setState(NetoOrbView.State.IDLE)
                statusView.text = "Ready"
            }
        }
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
        if (::visionController.isInitialized) visionController.stop()
        visualState = NetoVisualState.None
        if (::visualPreviewCard.isInitialized) visualPreviewCard.visibility = View.GONE
        if (::visualPreview.isInitialized) visualPreview.setImageDrawable(null)
    }

    // --- Dialogs (Add Task, Add Habit, Add Note, Settings) ---

    private fun showQuickAddDialog() {
        val options = arrayOf("Add Daily Task", "Add Habit Tracker", "Add Smart Note / Reflection")
        AlertDialog.Builder(this)
            .setTitle("Create New Item")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddTaskDialog()
                    1 -> showAddHabitDialog()
                    2 -> showAddNoteDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTaskDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val titleInput = EditText(this).apply {
            hint = "Task title (e.g. Focus work block)"
        }
        val timeInput = EditText(this).apply {
            hint = "Time slot (e.g. 10:00 AM or Anytime)"
        }
        val catInput = EditText(this).apply {
            hint = "Category (Work, Wellness, Focus, Personal)"
        }

        layout.addView(titleInput)
        layout.addView(timeInput)
        layout.addView(catInput)

        AlertDialog.Builder(this)
            .setTitle("Add Daily Task")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val newTask = DailyTask(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        timeSlot = timeInput.text.toString().trim().ifBlank { "Anytime" },
                        category = catInput.text.toString().trim().ifBlank { "General" },
                        priority = "Normal",
                        isCompleted = false,
                        dateStr = todayStr
                    )
                    store.addTask(newTask)
                    refreshTodayData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddHabitDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val nameInput = EditText(this).apply {
            hint = "Habit name (e.g. 10k Steps, Drink 2L)"
        }
        val iconInput = EditText(this).apply {
            hint = "Emoji Icon (💧, 🏃, 📚, 🧘, ⚡)"
        }

        layout.addView(nameInput)
        layout.addView(iconInput)

        AlertDialog.Builder(this)
            .setTitle("Track New Habit")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    val icon = iconInput.text.toString().trim().ifBlank { "✨" }
                    val newHabit = DailyHabit(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        icon = icon,
                        category = "Daily",
                        streak = 1,
                        completedToday = false
                    )
                    store.addHabit(newHabit)
                    refreshAllData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddNoteDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val titleInput = EditText(this).apply {
            hint = "Note Title"
        }
        val contentInput = EditText(this).apply {
            hint = "Write your reflection or note here..."
            minLines = 3
        }

        layout.addView(titleInput)
        layout.addView(contentInput)

        AlertDialog.Builder(this)
            .setTitle("Add Reflection / Note")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val content = contentInput.text.toString().trim()
                if (content.isNotEmpty()) {
                    val title = titleInput.text.toString().trim().ifBlank { "Daily Note" }
                    val newNote = DailyNote(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        content = content,
                        tag = "Reflection"
                    )
                    store.addNote(newNote)
                    refreshNotesList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        val nameLabel = TextView(this).apply {
            text = "Your Name"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextDark)
        }
        val nameInput = EditText(this).apply {
            setText(store.getUserName())
        }

        val syncStatus = TextView(this).apply {
            val isCloud = runCatching { Supabase.client.auth.currentUserOrNull() != null }.getOrDefault(false)
            text = if (isCloud) "Cloud Account: Active ✅" else "Mode: Offline / Guest Mode"
            textSize = 13f
            setTextColor(colorTextMuted)
            setPadding(0, dp(10), 0, dp(14))
        }

        val creatorInfo = TextView(this).apply {
            text = "NETO Daily · Created by Macdonald Barasa\nBuilt for everyday life, routines & productivity."
            textSize = 12f
            setTextColor(colorTextMuted)
        }

        layout.addView(nameLabel)
        layout.addView(nameInput)
        layout.addView(syncStatus)
        layout.addView(creatorInfo)

        AlertDialog.Builder(this)
            .setTitle("NETO Settings & Profile")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    store.setUserName(newName)
                    refreshTodayData()
                }
            }
            .setNeutralButton("Sign Out / Switch") { _, _ ->
                scope.launch {
                    runCatching { Supabase.client.auth.signOut() }
                    store.saveBoolean("has_entered", false)
                    openAuth()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAuth() {
        startActivity(
            Intent(this, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        finish()
    }

    // --- Helpers ---

    private fun createPillChip(label: String, onClick: () -> Unit): View {
        val chip = TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorPrimary)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_chip)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = dp(8)
        chip.layoutParams = lp
        return chip
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        if (::liveSession.isInitialized) liveSession.stop()
        if (::visionController.isInitialized) visionController.stop()
        super.onDestroy()
    }
}
