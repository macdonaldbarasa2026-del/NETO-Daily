package com.netodaily.app.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.util.UUID

class NetoLocalStore(context: Context) {

    private val prefs = context.getSharedPreferences(
        "neto_daily_store",
        Context.MODE_PRIVATE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- Daily Tasks ---

    fun getTasks(): List<DailyTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DailyTask>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveTasks(tasks: List<DailyTask>) {
        prefs.edit()
            .putString(KEY_TASKS, json.encodeToString(tasks))
            .apply()
    }

    fun addTask(task: DailyTask) {
        val current = getTasks()
        saveTasks(listOf(task) + current)
    }

    fun toggleTask(id: String): DailyTask? {
        val tasks = getTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = tasks[index]
            val updated = item.copy(isCompleted = !item.isCompleted)
            tasks[index] = updated
            saveTasks(tasks)
            return updated
        }
        return null
    }

    fun deleteTask(id: String) {
        saveTasks(getTasks().filterNot { it.id == id })
    }

    // --- Habits ---

    fun getHabits(): List<DailyHabit> {
        val raw = prefs.getString(KEY_HABITS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DailyHabit>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveHabits(habits: List<DailyHabit>) {
        prefs.edit()
            .putString(KEY_HABITS, json.encodeToString(habits))
            .apply()
    }

    fun addHabit(habit: DailyHabit) {
        val current = getHabits()
        saveHabits(current + habit)
    }

    fun toggleHabit(id: String, todayStr: String): DailyHabit? {
        val habits = getHabits().toMutableList()
        val index = habits.indexOfFirst { it.id == id }
        if (index != -1) {
            val h = habits[index]
            val isNowCompleted = !h.completedToday
            val newStreak = if (isNowCompleted) h.streak + 1 else (h.streak - 1).coerceAtLeast(0)
            val updated = h.copy(
                completedToday = isNowCompleted,
                streak = newStreak,
                lastCompletedDate = if (isNowCompleted) todayStr else h.lastCompletedDate
            )
            habits[index] = updated
            saveHabits(habits)
            return updated
        }
        return null
    }

    fun deleteHabit(id: String) {
        saveHabits(getHabits().filterNot { it.id == id })
    }

    // --- Daily Notes ---

    fun getNotes(): List<DailyNote> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DailyNote>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveNotes(notes: List<DailyNote>) {
        prefs.edit()
            .putString(KEY_NOTES, json.encodeToString(notes))
            .apply()
    }

    fun addNote(note: DailyNote) {
        val current = getNotes()
        saveNotes(listOf(note) + current)
    }

    fun deleteNote(id: String) {
        saveNotes(getNotes().filterNot { it.id == id })
    }

    // --- Seed Initial Data if First Launch ---

    fun initDefaultDataIfEmpty(todayDateStr: String) {
        if (getTasks().isEmpty()) {
            val initialTasks = listOf(
                DailyTask(
                    id = UUID.randomUUID().toString(),
                    title = "Morning Focus & Hydration",
                    timeSlot = "08:00 AM",
                    category = "Wellness",
                    priority = "High",
                    isCompleted = true,
                    dateStr = todayDateStr
                ),
                DailyTask(
                    id = UUID.randomUUID().toString(),
                    title = "Review Day Priorities with NETO",
                    timeSlot = "09:30 AM",
                    category = "Focus",
                    priority = "High",
                    isCompleted = false,
                    dateStr = todayDateStr
                ),
                DailyTask(
                    id = UUID.randomUUID().toString(),
                    title = "Deep Work Block & Core Project",
                    timeSlot = "11:00 AM",
                    category = "Work",
                    priority = "High",
                    isCompleted = false,
                    dateStr = todayDateStr
                ),
                DailyTask(
                    id = UUID.randomUUID().toString(),
                    title = "Afternoon Activity & Walk",
                    timeSlot = "03:30 PM",
                    category = "Wellness",
                    priority = "Normal",
                    isCompleted = false,
                    dateStr = todayDateStr
                ),
                DailyTask(
                    id = UUID.randomUUID().toString(),
                    title = "Evening Reflection & Plan Tomorrow",
                    timeSlot = "06:00 PM",
                    category = "Personal",
                    priority = "Normal",
                    isCompleted = false,
                    dateStr = todayDateStr
                )
            )
            saveTasks(initialTasks)
        }

        if (getHabits().isEmpty()) {
            val initialHabits = listOf(
                DailyHabit(
                    id = UUID.randomUUID().toString(),
                    name = "Drink 2L Water",
                    icon = "water",
                    category = "Health",
                    streak = 4,
                    completedToday = true
                ),
                DailyHabit(
                    id = UUID.randomUUID().toString(),
                    name = "30m Movement / Workout",
                    icon = "fitness",
                    category = "Health",
                    streak = 6,
                    completedToday = false
                ),
                DailyHabit(
                    id = UUID.randomUUID().toString(),
                    name = "Read 15 Pages",
                    icon = "book",
                    category = "Mind",
                    streak = 3,
                    completedToday = false
                ),
                DailyHabit(
                    id = UUID.randomUUID().toString(),
                    name = "5m Mindfulness & Gratitude",
                    icon = "mindfulness",
                    category = "Mind",
                    streak = 5,
                    completedToday = false
                )
            )
            saveHabits(initialHabits)
        }

        if (getNotes().isEmpty()) {
            val initialNote = DailyNote(
                id = UUID.randomUUID().toString(),
                title = "Welcome to NETO Daily",
                content = "NETO Daily is your 2026 everyday companion. Track your daily agenda, build lasting habits, and talk to your live AI assistant via voice, chat, and vision.",
                tag = "Welcome"
            )
            saveNotes(listOf(initialNote))
        }
    }

    // --- Conversation & Messages ---

    fun loadCurrentConversation(): NetoConversation? {
        val raw = prefs.getString(KEY_CURRENT_CONVERSATION, null)
            ?: return null

        return runCatching {
            json.decodeFromString<NetoConversation>(raw)
        }.getOrNull()
    }

    fun saveCurrentConversation(conversation: NetoConversation) {
        prefs.edit()
            .putString(
                KEY_CURRENT_CONVERSATION,
                json.encodeToString(conversation)
            )
            .apply()
    }

    fun loadHistory(): List<NetoConversation> {
        val raw = prefs.getString(KEY_HISTORY, null)
            ?: return emptyList()

        return runCatching {
            json.decodeFromString<List<NetoConversation>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveHistory(conversations: List<NetoConversation>) {
        prefs.edit()
            .putString(
                KEY_HISTORY,
                json.encodeToString(conversations)
            )
            .apply()
    }

    fun archiveConversation(conversation: NetoConversation) {
        if (conversation.messages.isEmpty()) return

        val existing = loadHistory()
            .filterNot { it.id == conversation.id }

        saveHistory(
            listOf(conversation) + existing
        )
    }

    fun deleteConversation(id: String) {
        saveHistory(
            loadHistory().filterNot { it.id == id }
        )
    }

    fun clearHistory() {
        prefs.edit()
            .remove(KEY_HISTORY)
            .apply()
    }

    fun clearCurrentConversation() {
        prefs.edit()
            .remove(KEY_CURRENT_CONVERSATION)
            .apply()
    }

    // --- Settings & User Profile ---

    fun getUserName(): String {
        return prefs.getString(KEY_USER_NAME, "User") ?: "User"
    }

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun isGuestMode(): Boolean {
        return prefs.getBoolean(KEY_GUEST_MODE, true)
    }

    fun setGuestMode(guest: Boolean) {
        prefs.edit().putBoolean(KEY_GUEST_MODE, guest).apply()
    }

    fun saveSetting(key: String, value: String) {
        prefs.edit()
            .putString(key, value)
            .apply()
    }

    fun getSetting(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit()
            .putBoolean(key, value)
            .apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun saveFloat(key: String, value: Float) {
        prefs.edit()
            .putFloat(key, value)
            .apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return prefs.getFloat(key, defaultValue)
    }

    companion object {
        private const val KEY_CURRENT_CONVERSATION = "current_conversation"
        private const val KEY_HISTORY = "conversation_history"
        private const val KEY_TASKS = "daily_tasks"
        private const val KEY_HABITS = "daily_habits"
        private const val KEY_NOTES = "daily_notes"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_GUEST_MODE = "guest_mode"
    }
}
