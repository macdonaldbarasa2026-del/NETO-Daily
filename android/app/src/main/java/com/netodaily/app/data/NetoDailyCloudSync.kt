package com.netodaily.app.data

import com.netodaily.app.BuildConfig
import com.netodaily.app.Supabase
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement
import java.net.HttpURLConnection
import java.net.URL

class NetoDailyCloudSync {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class TaskRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val title: String,
        @SerialName("time_slot") val timeSlot: String,
        val category: String,
        val priority: String,
        @SerialName("is_completed") val isCompleted: Boolean,
        @SerialName("date_str") val dateStr: String,
        @SerialName("created_at") val createdAt: Long
    )

    @Serializable
    private data class HabitRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String,
        val icon: String,
        val category: String,
        val streak: Int,
        @SerialName("completed_today") val completedToday: Boolean,
        @SerialName("last_completed_date") val lastCompletedDate: String,
        @SerialName("created_at") val createdAt: Long
    )

    @Serializable
    private data class NoteRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val title: String,
        val content: String,
        val tag: String,
        val timestamp: Long
    )

    suspend fun restoreIntoLocal(store: NetoLocalStore) {
        val userId = currentUserId() ?: return

        runCatching {
            val tasks = get<TaskRow>("daily_tasks")
            val habits = get<HabitRow>("daily_habits")
            val notes = get<NoteRow>("daily_notes")

            if (tasks.isNotEmpty()) {
                store.saveTasks(
                    tasks.map {
                        DailyTask(
                            id = it.id,
                            title = it.title,
                            timeSlot = it.timeSlot,
                            category = it.category,
                            priority = it.priority,
                            isCompleted = it.isCompleted,
                            dateStr = it.dateStr,
                            createdAt = it.createdAt
                        )
                    }
                )
            }

            if (habits.isNotEmpty()) {
                store.saveHabits(
                    habits.map {
                        DailyHabit(
                            id = it.id,
                            name = it.name,
                            icon = it.icon,
                            category = it.category,
                            streak = it.streak,
                            completedToday = it.completedToday,
                            lastCompletedDate = it.lastCompletedDate
                        )
                    }
                )
            }

            if (notes.isNotEmpty()) {
                store.saveNotes(
                    notes.map {
                        DailyNote(
                            id = it.id,
                            title = it.title,
                            content = it.content,
                            tag = it.tag,
                            timestamp = it.timestamp
                        )
                    }
                )
            }
        }
    }

    suspend fun syncAll(store: NetoLocalStore) {
        val userId = currentUserId() ?: return

        runCatching {
            upsert(
                "daily_tasks",
                store.getTasks().map {
                    TaskRow(
                        id = it.id,
                        userId = userId,
                        title = it.title,
                        timeSlot = it.timeSlot,
                        category = it.category,
                        priority = it.priority,
                        isCompleted = it.isCompleted,
                        dateStr = it.dateStr,
                        createdAt = it.createdAt
                    )
                }
            )

            upsert(
                "daily_habits",
                store.getHabits().map {
                    HabitRow(
                        id = it.id,
                        userId = userId,
                        name = it.name,
                        icon = it.icon,
                        category = it.category,
                        streak = it.streak,
                        completedToday = it.completedToday,
                        lastCompletedDate = it.lastCompletedDate,
                        createdAt = System.currentTimeMillis()
                    )
                }
            )

            upsert(
                "daily_notes",
                store.getNotes().map {
                    NoteRow(
                        id = it.id,
                        userId = userId,
                        title = it.title,
                        content = it.content,
                        tag = it.tag,
                        timestamp = it.timestamp
                    )
                }
            )
        }
    }

    private inline fun <reified T> get(table: String): List<T> {
        val connection = createConnection(
            "$table?select=*",
            "GET"
        )

        return try {
            val code = connection.responseCode

            if (code !in 200..299) {
                readBody(connection, code)
                emptyList()
            } else {
                json.decodeFromString(readBody(connection, code))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun upsert(
        table: String,
        rows: List<Any>
    ) {
        if (rows.isEmpty()) return

        val connection = createConnection(
            "$table?on_conflict=id",
            "POST"
        )

        try {
            connection.setRequestProperty(
                "Prefer",
                "resolution=merge-duplicates,return=minimal"
            )

            connection.doOutput = true

            val elements = rows.map {
                when (it) {
                    is TaskRow -> json.encodeToJsonElement(it)
                    is HabitRow -> json.encodeToJsonElement(it)
                    is NoteRow -> json.encodeToJsonElement(it)
                    else -> error("Unsupported row type")
                }
            }

            val body = JsonArray(elements).toString()

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode

            if (code !in 200..299) {
                readBody(connection, code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun currentUserId(): String? {
        return Supabase.client.auth.currentUserOrNull()?.id
    }

    private fun createConnection(
        table: String,
        method: String
    ): HttpURLConnection {

        val session =
            Supabase.client.auth.currentSessionOrNull()
                ?: error("No active Supabase session")

        val url =
            "${BuildConfig.SUPABASE_URL}/rest/v1/$table"

        return (URL(url).openConnection() as HttpURLConnection).apply {

            requestMethod = method

            connectTimeout = 10_000
            readTimeout = 20_000

            setRequestProperty(
                "apikey",
                BuildConfig.SUPABASE_PUBLISHABLE_KEY
            )

            setRequestProperty(
                "Authorization",
                "Bearer ${session.accessToken}"
            )

            setRequestProperty(
                "Content-Type",
                "application/json"
            )

            setRequestProperty(
                "Accept",
                "application/json"
            )
        }
    }

    private fun readBody(
        connection: HttpURLConnection,
        code: Int
    ): String {

        val stream =
            if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        return stream
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
    }
}
