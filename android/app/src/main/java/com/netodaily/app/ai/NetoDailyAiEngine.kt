package com.netodaily.app.ai

import com.netodaily.app.BuildConfig
import com.netodaily.app.Supabase
import com.netodaily.app.data.DailyHabit
import com.netodaily.app.data.DailyNote
import com.netodaily.app.data.DailyTask
import com.netodaily.app.data.NetoLocalStore
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NetoDailyAiEngine(private val store: NetoLocalStore) {

    suspend fun processUserMessage(rawText: String): String {
        val query = rawText.trim()
        if (query.isBlank()) return "I'm here! What would you like to plan or work through today?"

        val lower = query.lowercase(Locale.ROOT)

        // 1. Check for quick local action commands
        handleLocalCommands(lower, query)?.let { directResponse ->
            return directResponse
        }

        // 2. Try Supabase cloud Gemini endpoint if available
        val cloudReply = tryCloudReply(query)
        if (cloudReply != null && cloudReply.isNotBlank()) {
            return cloudReply
        }

        // 3. Fallback to resilient on-device smart assistant
        return generateOfflineSmartReply(query, lower)
    }

    private fun handleLocalCommands(lower: String, query: String): String? {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        // Add task command
        if (lower.startsWith("add task") || lower.startsWith("new task") || lower.startsWith("schedule ") || lower.startsWith("remind me to ")) {
            val title = query
                .replaceFirst(Regex("^(add task:?|new task:?|schedule:?|remind me to)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (title.isNotEmpty()) {
                val newTask = DailyTask(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    timeSlot = extractTime(title) ?: "Anytime",
                    category = categorizeTask(title),
                    priority = if (lower.contains("urgent") || lower.contains("important")) "High" else "Normal",
                    isCompleted = false,
                    dateStr = todayStr
                )
                store.addTask(newTask)
                return "Got it! Added \"${newTask.title}\" to your daily schedule for today. 🎯\nCategory: ${newTask.category} · Time: ${newTask.timeSlot}"
            }
        }

        // Add habit command
        if (lower.startsWith("add habit") || lower.startsWith("new habit") || lower.startsWith("track habit")) {
            val name = query
                .replaceFirst(Regex("^(add habit:?|new habit:?|track habit:?)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (name.isNotEmpty()) {
                val icon = when {
                    lower.contains("water") || lower.contains("drink") -> "💧"
                    lower.contains("read") || lower.contains("book") -> "📚"
                    lower.contains("run") || lower.contains("walk") || lower.contains("gym") || lower.contains("workout") -> "🏃"
                    lower.contains("meditat") || lower.contains("mind") || lower.contains("breath") -> "🧘"
                    lower.contains("sleep") || lower.contains("bed") -> "🌙"
                    else -> "⚡"
                }
                val newHabit = DailyHabit(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    icon = icon,
                    category = "Routine",
                    streak = 1,
                    completedToday = false
                )
                store.addHabit(newHabit)
                return "New habit tracked: $icon \"${newHabit.name}\"! Consistency starts today. 🔥"
            }
        }

        // Daily briefing / Schedule request
        if (lower.contains("briefing") || lower.contains("schedule") || lower.contains("what's my plan") || lower.contains("plan today") || lower.contains("my agenda")) {
            val tasks = store.getTasks()
            val habits = store.getHabits()
            val completedTasks = tasks.count { it.isCompleted }
            val completedHabits = habits.count { it.completedToday }

            val sb = StringBuilder()
            val timeGreeting = getTimeGreeting()
            val userName = store.getUserName().ifBlank { "there" }
            sb.append("$timeGreeting, $userName! Here is your NETO Daily Briefing ☀️\n\n")

            sb.append("📋 Today's Tasks ($completedTasks/${tasks.size} done):\n")
            if (tasks.isEmpty()) {
                sb.append("• No tasks scheduled yet today. What's your priority?\n")
            } else {
                tasks.take(5).forEach { t ->
                    val status = if (t.isCompleted) "✓ [Done]" else "○ [ ]"
                    sb.append("$status ${t.title} (${t.timeSlot})\n")
                }
                if (tasks.size > 5) {
                    sb.append("...and ${tasks.size - 5} more in your schedule.\n")
                }
            }

            sb.append("\n🔥 Daily Habits ($completedHabits/${habits.size} active):\n")
            habits.take(4).forEach { h ->
                val check = if (h.completedToday) "✅" else "⚪"
                sb.append("$check ${h.icon} ${h.name} (${h.streak} day streak)\n")
            }

            sb.append("\n💡 Daily Focus: Choose 1 primary goal and conquer it early today!")
            return sb.toString()
        }

        // Add note / reflection
        if (lower.startsWith("note:") || lower.startsWith("save note") || lower.startsWith("reflection:")) {
            val body = query
                .replaceFirst(Regex("^(note:|save note:?|reflection:?)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (body.isNotEmpty()) {
                val newNote = DailyNote(
                    id = UUID.randomUUID().toString(),
                    title = "Reflection · " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date()),
                    content = body,
                    tag = "Reflection"
                )
                store.addNote(newNote)
                return "Saved to your Daily Reflections! 📝\n\"$body\""
            }
        }

        return null
    }

    private suspend fun tryCloudReply(text: String): String? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val session = Supabase.client.auth.currentSessionOrNull()
                val url = "${BuildConfig.SUPABASE_URL}/functions/v1/neto-chat"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (session != null) {
                    connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                }
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)

                val body = buildJsonObject {
                    put("message", text)
                }.toString()

                connection.outputStream.use { it.write(body.toByteArray()) }

                val code = connection.responseCode
                if (code in 200..299) {
                    val raw = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    extractAnswerFromResponse(raw)
                } else {
                    connection.disconnect()
                    null
                }
            }.getOrNull()
        }
    }

    private fun extractAnswerFromResponse(raw: String): String {
        return runCatching {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(raw)
            val obj = element as? kotlinx.serialization.json.JsonObject
            obj?.get("reply")?.toString()?.trim('"')?.replace("\\n", "\n")
                ?: obj?.get("message")?.toString()?.trim('"')?.replace("\\n", "\n")
                ?: raw
        }.getOrDefault(raw)
    }

    private fun generateOfflineSmartReply(query: String, lower: String): String {
        val userName = store.getUserName().ifBlank { "friend" }

        return when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hello $userName! 👋 How can I help power your day? We can plan your schedule, check off habits, or work through any ideas."

            lower.contains("who are you") || lower.contains("what can you do") ->
                "I am NETO, your 2026 daily assistant and routine co-pilot. I help you plan your daily schedule, stay consistent with habits, take quick reflection notes, and guide your daily productivity through voice, vision, and chat."

            lower.contains("motivat") || lower.contains("inspire") || lower.contains("quote") ->
                "\"Action is the foundational key to all success.\" — Pablo Picasso\n\nTake 5 minutes right now to tackle the most important item on your schedule. Momentum builds once you begin!"

            lower.contains("focus") || lower.contains("pomodoro") || lower.contains("deep work") ->
                "Let's get into a Deep Work state! 🧠\n1. Pick 1 task from your agenda.\n2. Set a 25-minute timer.\n3. Put away distractions.\n4. You've got this — starting now!"

            lower.contains("habit") ->
                "Small habits compounded daily lead to extraordinary results. You can view your current habit streaks in the Habits tab, or tell me 'Add habit <name>' anytime!"

            lower.contains("plan") || lower.contains("organize") ->
                "To organize your day effectively:\n• Define 1-3 Non-Negotiable priorities.\n• Time-block your morning for high-leverage work.\n• Protect time for movement and rest.\nWhat is the #1 thing you want to accomplish today?"

            else ->
                "Got it! I've noted that for your day. You can ask me to schedule tasks ('add task <name>'), track new habits ('add habit <name>'), or ask for advice and planning anytime. Let's make today count! ⚡"
        }
    }

    private fun extractTime(text: String): String? {
        val regex = Regex("\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|AM|PM))\\b")
        return regex.find(text)?.value
    }

    private fun categorizeTask(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("work") || lower.contains("code") || lower.contains("meeting") || lower.contains("call") || lower.contains("email") -> "Work"
            lower.contains("health") || lower.contains("gym") || lower.contains("run") || lower.contains("water") || lower.contains("doctor") -> "Wellness"
            lower.contains("study") || lower.contains("read") || lower.contains("learn") -> "Focus"
            else -> "Personal"
        }
    }

    private fun getTimeGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
}
