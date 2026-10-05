package com.netodaily.app.ai

import android.content.Context
import com.netodaily.app.BuildConfig
import com.netodaily.app.Supabase
import com.netodaily.app.agent.NetoPhoneAgent
import com.netodaily.app.data.NetoLocalStore
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

class NetoDailyAiEngine(
    private val context: Context,
    private val store: NetoLocalStore
) {

    private val phoneAgent = NetoPhoneAgent(context, store)

    suspend fun processUserMessage(rawText: String): String {
        val query = rawText.trim()
        if (query.isBlank()) return "I'm listening. Ask me anything or tell me to call, open apps, set alarms, or plan your day."

        // 1. Check if user wants a phone agent action (Call, SMS, App, Alarm, Torch, Search, Tasks)
        val agentResult = phoneAgent.handleAgenticAction(query)
        if (agentResult.handled) {
            return agentResult.feedback
        }

        // 2. Try Supabase cloud Gemini endpoint if online
        val cloudReply = tryCloudReply(query)
        if (cloudReply != null && cloudReply.isNotBlank()) {
            return cloudReply
        }

        // 3. Fallback to intelligent on-device smart assistant
        return generateOfflineSmartReply(query)
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

    private fun generateOfflineSmartReply(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        val userName = store.getUserName().ifBlank { "friend" }

        return when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hello $userName! 👋 I am your Gemini-powered agent. You can ask me questions, or tell me to call contacts, open apps, set alarms, control flashlight, or organize your day."

            lower.contains("who are you") || lower.contains("what can you do") ->
                "I am NETO, your personal cloud agent powered by Gemini. I can automate phone actions like calling contacts, launching apps (YouTube, WhatsApp, Camera), setting timers, searching the web, and guiding your daily habits and schedule."

            lower.contains("motivat") || lower.contains("inspire") ->
                "\"Your only limit is you.\" Take action on your #1 goal today and build unshakeable momentum!"

            lower.contains("focus") || lower.contains("deep work") ->
                "Let's initiate Deep Focus mode! 🧠 Pick your primary task, silence distractions, and let's get it done."

            else ->
                "I've got you, $userName. I can help answer that, launch an app, make a call, set an alarm, or search the web. What would you like to do next?"
        }
    }
}
