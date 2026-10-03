package com.netodaily.app.live

import kotlinx.serialization.Serializable

@Serializable
data class NetoLiveTokenResponse(
    val ok: Boolean = false,
    val token: String? = null,
    val model: String? = null,
    val error: String? = null
)
