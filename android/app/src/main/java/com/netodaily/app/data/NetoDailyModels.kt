package com.netodaily.app.data

import kotlinx.serialization.Serializable

@Serializable
data class DailyTask(
    val id: String,
    val title: String,
    val timeSlot: String = "Anytime",
    val category: String = "General",
    val priority: String = "Normal",
    val isCompleted: Boolean = false,
    val dateStr: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class DailyHabit(
    val id: String,
    val name: String,
    val icon: String = "default",
    val category: String = "Routine",
    val streak: Int = 1,
    val completedToday: Boolean = false,
    val lastCompletedDate: String = ""
)

@Serializable
data class DailyNote(
    val id: String,
    val title: String,
    val content: String,
    val tag: String = "Daily Log",
    val timestamp: Long = System.currentTimeMillis()
)
