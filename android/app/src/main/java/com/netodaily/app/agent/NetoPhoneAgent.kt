package com.netodaily.app.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import com.netodaily.app.data.DailyHabit
import com.netodaily.app.data.DailyTask
import com.netodaily.app.data.NetoLocalStore
import java.text.SimpleDateFormat
import java.util.*

class NetoPhoneAgent(
    private val context: Context,
    private val store: NetoLocalStore
) {

    data class AgentResult(
        val handled: Boolean,
        val feedback: String,
        val actionType: String? = null
    )

    fun handleAgenticAction(query: String): AgentResult {
        val text = query.trim()
        val lower = text.lowercase(Locale.ROOT)

        // 1. Phone Call
        if (lower.startsWith("call ") || lower.startsWith("dial ") || lower.startsWith("phone ")) {
            val target = text.replaceFirst(Regex("^(call|dial|phone)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return executeCall(target)
        }

        // 2. Send SMS / Text message
        if (lower.startsWith("text ") || lower.startsWith("send sms ") || lower.startsWith("send message to ")) {
            return executeSms(text)
        }

        // 3. Open Apps
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start app ")) {
            val appName = text.replaceFirst(Regex("^(open|launch|start app)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return executeOpenApp(appName)
        }

        // 4. Set Alarm or Timer
        if (lower.contains("alarm") && (lower.contains("set") || lower.contains("wake"))) {
            return executeSetAlarm(text)
        }
        if (lower.contains("timer") && lower.contains("set")) {
            return executeSetTimer(text)
        }

        // 5. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            if (lower.contains("on") || lower.contains("enable")) {
                return executeFlashlight(true)
            } else if (lower.contains("off") || lower.contains("disable")) {
                return executeFlashlight(false)
            }
        }

        // 6. Play Music / YouTube
        if (lower.startsWith("play ") || lower.startsWith("search youtube for ")) {
            val musicQuery = text.replaceFirst(Regex("^(play|search youtube for)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return executePlayMusic(musicQuery)
        }

        // 7. Web Search
        if (lower.startsWith("search ") || lower.startsWith("google ")) {
            val searchTarget = text.replaceFirst(Regex("^(search for|search|google)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return executeWebSearch(searchTarget)
        }

        // 8. Daily Agenda & Routine Commands
        if (lower.startsWith("add task ") || lower.startsWith("schedule ")) {
            val taskTitle = text.replaceFirst(Regex("^(add task:?|schedule:?)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return executeAddTask(taskTitle)
        }
        if (lower.startsWith("add habit ") || lower.startsWith("track habit ")) {
            val habitName = text.replaceFirst(Regex("^(add habit:?|track habit:?)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return executeAddHabit(habitName)
        }
        if (lower.contains("what is my schedule") || lower.contains("what's my schedule") || lower.contains("my plan today") || lower.contains("today's agenda")) {
            return executeReadSchedule()
        }

        return AgentResult(handled = false, feedback = "")
    }

    private fun executeCall(target: String): AgentResult {
        return try {
            val number = if (target.matches(Regex("^[+0-9\\s()-]+$"))) {
                target
            } else {
                findContactNumber(target) ?: target
            }

            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            AgentResult(true, "Calling $target ($number)...", "CALL")
        } catch (e: Exception) {
            AgentResult(true, "Could not place call: ${e.message}", "ERROR")
        }
    }

    private fun findContactNumber(name: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIndex >= 0) it.getString(numIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun executeSms(text: String): AgentResult {
        return try {
            val cleaned = text.replaceFirst(Regex("^(text|send sms to|send message to)\\s+", RegexOption.IGNORE_CASE), "")
            val parts = cleaned.split(Regex(":\\s*|\\s+that\\s+|\\s+saying\\s+"), limit = 2)
            val recipient = parts[0].trim()
            val messageBody = if (parts.size > 1) parts[1].trim() else ""

            val number = if (recipient.matches(Regex("^[+0-9\\s()-]+$"))) recipient else findContactNumber(recipient) ?: recipient

            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                if (messageBody.isNotEmpty()) putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            AgentResult(true, "Opening message to $recipient...", "SMS")
        } catch (e: Exception) {
            AgentResult(true, "Could not open message: ${e.message}", "ERROR")
        }
    }

    private fun executeOpenApp(appName: String): AgentResult {
        val pm = context.packageManager
        val cleanName = appName.lowercase(Locale.ROOT).trim()

        // Known common mappings
        val knownPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "chrome" to "com.android.chrome",
            "camera" to null,
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator"
        )

        try {
            if (cleanName == "camera") {
                val camIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(camIntent)
                return AgentResult(true, "Opening Camera...", "APP")
            }

            val pkg = knownPackages[cleanName]
            if (pkg != null) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return AgentResult(true, "Opening ${appName.replaceFirstChar { it.uppercase() }}...", "APP")
                }
            }

            // General search across installed apps
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
                if (label.contains(cleanName) || cleanName.contains(label)) {
                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return AgentResult(true, "Launching ${pm.getApplicationLabel(app)}...", "APP")
                    }
                }
            }

            return AgentResult(true, "I searched for \"$appName\" but could not find the application installed.", "APP_NOT_FOUND")
        } catch (e: Exception) {
            return AgentResult(true, "Could not open $appName: ${e.message}", "ERROR")
        }
    }

    private fun executeSetAlarm(text: String): AgentResult {
        return try {
            val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
            val match = timeRegex.find(text)
            if (match != null) {
                var hour = match.groupValues[1].toInt()
                val minStr = match.groupValues[2]
                val minute = if (minStr.isNotEmpty()) minStr.toInt() else 0
                val ampm = match.groupValues[3].lowercase(Locale.ROOT)

                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0

                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "NETO Daily Alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val displayHour = if (hour % 12 == 0) 12 else hour % 12
                val period = if (hour >= 12) "PM" else "AM"
                val minFormatted = String.format(Locale.ROOT, "%02d", minute)
                AgentResult(true, "Alarm set for $displayHour:$minFormatted $period!", "ALARM")
            } else {
                AgentResult(true, "Please specify a time for the alarm (e.g. 'Set alarm for 7:30 AM').", "ALARM")
            }
        } catch (e: Exception) {
            AgentResult(true, "Could not set alarm: ${e.message}", "ERROR")
        }
    }

    private fun executeSetTimer(text: String): AgentResult {
        return try {
            val minRegex = Regex("(\\d+)\\s*(?:min|minute)", RegexOption.IGNORE_CASE)
            val secRegex = Regex("(\\d+)\\s*(?:sec|second)", RegexOption.IGNORE_CASE)

            var seconds = 0
            minRegex.find(text)?.let { seconds += it.groupValues[1].toInt() * 60 }
            secRegex.find(text)?.let { seconds += it.groupValues[1].toInt() }

            if (seconds == 0) {
                // Check standalone number
                val numRegex = Regex("(\\d+)")
                numRegex.find(text)?.let { seconds = it.groupValues[1].toInt() * 60 }
            }

            if (seconds > 0) {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "NETO Timer")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AgentResult(true, "⏳ Timer set for ${seconds / 60}m ${seconds % 60}s!", "TIMER")
            } else {
                AgentResult(true, "Please specify timer duration (e.g. 'Set timer for 5 minutes').", "TIMER")
            }
        } catch (e: Exception) {
            AgentResult(true, "Could not set timer: ${e.message}", "ERROR")
        }
    }

    private fun executeFlashlight(enable: Boolean): AgentResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
            if (cameraManager != null && cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                val status = if (enable) "turned on" else "turned off"
                AgentResult(true, "Flashlight has been $status.", "FLASHLIGHT")
            } else {
                AgentResult(true, "Flashlight hardware is unavailable.", "ERROR")
            }
        } catch (e: Exception) {
            AgentResult(true, "Flashlight error: ${e.message}", "ERROR")
        }
    }

    private fun executePlayMusic(query: String): AgentResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AgentResult(true, "🎵 Playing \"$query\"...", "MUSIC")
        } catch (e: Exception) {
            AgentResult(true, "Could not play music: ${e.message}", "ERROR")
        }
    }

    private fun executeWebSearch(query: String): AgentResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AgentResult(true, "Searching for \"$query\"...", "SEARCH")
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            AgentResult(true, "Searching Google for \"$query\"...", "SEARCH")
        }
    }

    private fun executeAddTask(title: String): AgentResult {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val newTask = DailyTask(
            id = UUID.randomUUID().toString(),
            title = title,
            timeSlot = "Scheduled",
            category = "Daily Action",
            priority = "Normal",
            isCompleted = false,
            dateStr = todayStr
        )
        store.addTask(newTask)
        return AgentResult(true, "Task added: \"$title\" to your daily schedule!", "TASK")
    }

    private fun executeAddHabit(name: String): AgentResult {
        val newHabit = DailyHabit(
            id = UUID.randomUUID().toString(),
            name = name,
            icon = "default",
            category = "Daily",
            streak = 1,
            completedToday = false
        )
        store.addHabit(newHabit)
        return AgentResult(true, "Habit added: \"$name\"! Consistency starts now.", "HABIT")
    }

    private fun executeReadSchedule(): AgentResult {
        val tasks = store.getTasks()
        if (tasks.isEmpty()) {
            return AgentResult(true, "You have no tasks scheduled for today yet. What's your #1 priority?", "SCHEDULE")
        }
        val count = tasks.count { it.isCompleted }
        val sb = StringBuilder("You have ${tasks.size} tasks today ($count completed):\n")
        tasks.take(4).forEach {
            val mark = if (it.isCompleted) "✓" else "○"
            sb.append("$mark ${it.title} (${it.timeSlot})\n")
        }
        return AgentResult(true, sb.toString().trim(), "SCHEDULE")
    }
}
