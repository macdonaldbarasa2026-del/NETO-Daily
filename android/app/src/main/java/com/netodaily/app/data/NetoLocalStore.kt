package com.netodaily.app.data

import android.content.Context
import kotlinx.serialization.json.Json

class NetoLocalStore(context: Context) {

    private val prefs = context.getSharedPreferences(
        "neto_daily_store",
        Context.MODE_PRIVATE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
        private const val KEY_CURRENT_CONVERSATION =
            "current_conversation"

        private const val KEY_HISTORY =
            "conversation_history"
    }
}
