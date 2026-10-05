package com.netodaily.app.data

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.netodaily.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NetoDailyWorkspace(
    private val context: Context,
    private val store: NetoLocalStore,
    private val onStudio: () -> Unit,
    private val onSync: () -> Unit,
    private val onDeleteTask: (String) -> Unit,
    private val onDeleteHabit: (String) -> Unit,
    private val onDeleteNote: (String) -> Unit
) {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var selectedTab = 0

    private val accent = Color.rgb(46, 106, 69)
    private val bg = Color.rgb(246, 251, 244)
    private val text = Color.rgb(28, 35, 30)
    private val secondary = Color.rgb(92, 105, 95)

    private val today: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    fun build(): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "NETO Daily"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(text)
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )

        val studio = ImageButton(context).apply {
            setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_tab_studio)
            )
            background = ContextCompat.getDrawable(
                context,
                R.drawable.bg_gemini_pill
            )
            setColorFilter(accent)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "Open Studio"
            setOnClickListener { onStudio() }
        }

        header.addView(
            studio,
            LinearLayout.LayoutParams(dp(46), dp(46))
        )

        root.addView(header)

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(context).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(createBottomNav())

        showTab(0)

        return root
    }

    fun rootView(): View = root

    fun refresh() {
        if (::content.isInitialized) showTab(selectedTab)
    }

    private fun showTab(tab: Int) {
        selectedTab = tab
        content.removeAllViews()

        when (tab) {
            0 -> showToday()
            1 -> showHabits()
            2 -> showNotes()
            3 -> showStudioInfo()
        }
    }

    private fun showToday() {
        addSectionHeader(
            "Today's Tasks",
            "Plan your day",
            R.drawable.ic_tab_today
        )

        val tasks = store.getTasks()
            .filter { it.dateStr.isBlank() || it.dateStr == today }

        if (tasks.isEmpty()) {
            addEmpty("No tasks for today.")
        } else {
            tasks.forEach { task ->
                addTaskCard(task)
            }
        }

        addPrimaryButton("Add Task", R.drawable.ic_add) {
            showAddTaskDialog()
        }
    }

    private fun showHabits() {
        addSectionHeader(
            "Habits",
            "Build consistent routines",
            R.drawable.ic_tab_habits
        )

        val habits = store.getHabits()

        if (habits.isEmpty()) {
            addEmpty("No habits yet.")
        } else {
            habits.forEach { habit ->
                addHabitCard(habit)
            }
        }

        addPrimaryButton("Add Habit", R.drawable.ic_add) {
            showAddHabitDialog()
        }
    }

    private fun showNotes() {
        addSectionHeader(
            "Notes",
            "Keep your daily thoughts",
            R.drawable.ic_tab_notes
        )

        val notes = store.getNotes()

        if (notes.isEmpty()) {
            addEmpty("No notes yet.")
        } else {
            notes.sortedByDescending { it.timestamp }.forEach { note ->
                addNoteCard(note)
            }
        }

        addPrimaryButton("Add Note", R.drawable.ic_add) {
            showAddNoteDialog()
        }
    }

    private fun showStudioInfo() {
        addSectionHeader(
            "Studio",
            "Your live NETO AI workspace",
            R.drawable.ic_tab_studio
        )

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = ContextCompat.getDrawable(
                context,
                R.drawable.bg_gemini_pill
            )
        }

        val title = TextView(context).apply {
            text = "Gemini Live Studio"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(text)
        }

        val description = TextView(context).apply {
            text = "Use NETO's live voice, vision, phone actions, search, and AI controls."
            textSize = 14f
            setTextColor(secondary)
            setPadding(0, dp(8), 0, dp(16))
        }

        card.addView(title)
        card.addView(description)

        addButtonTo(card, "Open Live Studio", R.drawable.ic_tab_studio) {
            onStudio()
        }

        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun addTaskCard(task: DailyTask) {
        val card = createCard()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val check = CheckBox(context).apply {
            isChecked = task.isCompleted
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnClickListener {
                store.toggleTask(task.id)
                onSync()
                refresh()
            }
        }

        row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(context).apply {
            text = task.title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(text)
        }

        if (task.isCompleted) {
            title.alpha = 0.55f
        }

        val details = TextView(context).apply {
            text = "${task.timeSlot}  •  ${task.category}  •  ${task.priority}"
            textSize = 12f
            setTextColor(secondary)
        }

        textCol.addView(title)
        textCol.addView(details)

        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val delete = iconButton(R.drawable.ic_delete, "Delete task") {
            store.deleteTask(task.id)
            onDeleteTask(task.id)
            onSync()
            refresh()
        }

        row.addView(delete)

        card.addView(row)
        content.addView(card, cardParams())
    }

    private fun addHabitCard(habit: DailyHabit) {
        val card = createCard()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(context).apply {
            setImageResource(habitIcon(habit.icon))
            setColorFilter(accent)
            contentDescription = habit.name
        }

        row.addView(
            icon,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                rightMargin = dp(12)
            }
        )

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val name = TextView(context).apply {
            text = habit.name
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(text)
        }

        val streak = TextView(context).apply {
            text = "Streak: ${habit.streak} days  •  ${habit.category}"
            textSize = 12f
            setTextColor(secondary)
        }

        textCol.addView(name)
        textCol.addView(streak)

        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val done = CheckBox(context).apply {
            isChecked = habit.completedToday
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnClickListener {
                store.toggleHabit(habit.id, today)
                onSync()
                refresh()
            }
        }

        row.addView(done, LinearLayout.LayoutParams(dp(48), dp(48)))

        val delete = iconButton(R.drawable.ic_delete, "Delete habit") {
            store.deleteHabit(habit.id)
            onDeleteHabit(habit.id)
            onSync()
            refresh()
        }

        row.addView(delete)

        card.addView(row)
        content.addView(card, cardParams())
    }

    private fun addNoteCard(note: DailyNote) {
        val card = createCard()

        val title = TextView(context).apply {
            text = note.title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(text)
        }

        val body = TextView(context).apply {
            text = note.content
            textSize = 14f
            setTextColor(secondary)
            setPadding(0, dp(8), 0, dp(8))
        }

        val tag = TextView(context).apply {
            text = note.tag
            textSize = 12f
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
        }

        val delete = iconButton(R.drawable.ic_delete, "Delete note") {
            store.deleteNote(note.id)
            onDeleteNote(note.id)
            onSync()
            refresh()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(delete)

        card.addView(header)
        card.addView(body)
        card.addView(tag)

        content.addView(card, cardParams())
    }

    private fun showAddTaskDialog() {
        val input = EditText(context).apply {
            hint = "Task title"
        }

        dialog("Add Task", input) {
            val title = input.text.toString().trim()
            if (title.isNotEmpty()) {
                store.addTask(
                    DailyTask(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        dateStr = today
                    )
                )
                onSync()
                refresh()
            }
        }
    }

    private fun showAddHabitDialog() {
        val input = EditText(context).apply {
            hint = "Habit name"
        }

        dialog("Add Habit", input) {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                store.addHabit(
                    DailyHabit(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        icon = NetoDailyHabitIcons.DEFAULT
                    )
                )
                onSync()
                refresh()
            }
        }
    }

    private fun showAddNoteDialog() {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }

        val title = EditText(context).apply {
            hint = "Title"
        }

        val body = EditText(context).apply {
            hint = "Write your note..."
            minLines = 4
            gravity = Gravity.TOP
        }

        layout.addView(title)
        layout.addView(body)

        AlertDialog.Builder(context)
            .setTitle("Add Note")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val noteTitle = title.text.toString().trim()
                val noteBody = body.text.toString().trim()

                if (noteTitle.isNotEmpty() || noteBody.isNotEmpty()) {
                    store.addNote(
                        DailyNote(
                            id = UUID.randomUUID().toString(),
                            title = noteTitle.ifEmpty { "Daily Note" },
                            content = noteBody
                        )
                    )
                    onSync()
                    refresh()
                }
            }
            .show()
    }

    private fun dialog(
        title: String,
        input: EditText,
        save: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> save() }
            .show()
    }

    private fun addSectionHeader(
        titleText: String,
        subtitleText: String,
        iconRes: Int
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(accent)
        }

        row.addView(
            icon,
            LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                rightMargin = dp(12)
            }
        )

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        col.addView(TextView(context).apply {
            text = titleText
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(text)
        })

        col.addView(TextView(context).apply {
            text = subtitleText
            textSize = 13f
            setTextColor(secondary)
        })

        row.addView(col)
        content.addView(row)
    }

    private fun addEmpty(message: String) {
        content.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(28), dp(16), dp(28))
        })
    }

    private fun addPrimaryButton(
        label: String,
        iconRes: Int,
        action: () -> Unit
    ) {
        val button = Button(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setOnClickListener { action() }
            setBackgroundColor(accent)
        }

        content.addView(button, cardParams())
    }

    private fun addButtonTo(
        parent: LinearLayout,
        label: String,
        iconRes: Int,
        action: () -> Unit
    ) {
        val button = Button(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            setBackgroundColor(accent)
            setOnClickListener { action() }
        }

        parent.addView(button)
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
            background = ContextCompat.getDrawable(
                context,
                R.drawable.bg_gemini_pill
            )
        }
    }

    private fun iconButton(
        iconRes: Int,
        description: String,
        action: () -> Unit
    ): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            setColorFilter(secondary)
            background = null
            contentDescription = description
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun createBottomNav(): LinearLayout {
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }

        addNavItem(nav, "Today", R.drawable.ic_tab_today, 0)
        addNavItem(nav, "Habits", R.drawable.ic_tab_habits, 1)
        addNavItem(nav, "Notes", R.drawable.ic_tab_notes, 2)
        addNavItem(nav, "Studio", R.drawable.ic_tab_studio, 3)

        return nav
    }

    private fun addNavItem(
        nav: LinearLayout,
        label: String,
        iconRes: Int,
        tab: Int
    ) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                if (tab == 3) {
                    onStudio()
                } else {
                    showTab(tab)
                }
            }
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(if (selectedTab == tab) accent else secondary)
        }

        val labelView = TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selectedTab == tab) accent else secondary)
            gravity = Gravity.CENTER
        }

        item.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
        item.addView(labelView)

        nav.addView(
            item,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )
    }

    private fun habitIcon(value: String): Int {
        return when (NetoDailyHabitIcons.normalize(value)) {
            NetoDailyHabitIcons.WATER -> R.drawable.ic_water
            NetoDailyHabitIcons.FITNESS -> R.drawable.ic_fitness
            NetoDailyHabitIcons.BOOK -> R.drawable.ic_book
            NetoDailyHabitIcons.MINDFULNESS -> R.drawable.ic_mindfulness
            else -> R.drawable.ic_habit
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(10)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
