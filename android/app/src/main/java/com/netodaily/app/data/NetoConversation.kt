package com.netodaily.app.data

import kotlinx.serialization.Serializable

@Serializable
data class NetoMessage(
    val id: String,
    val role: Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    @Serializable
    enum class Role {
        USER,
        NETO
    }
}

@Serializable
data class NetoConversation(
    val id: String,
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<NetoMessage> = emptyList()
)
