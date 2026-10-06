package com.netodaily.app.live

import com.netodaily.app.BuildConfig
import com.netodaily.app.Supabase
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class NetoLiveTokenClient {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun requestToken(): NetoLiveTokenResponse {
        var connection: HttpURLConnection? = null

        return try {
            val session = Supabase.client.auth.currentSessionOrNull()

            val url =
                "${BuildConfig.SUPABASE_URL}/functions/v1/neto-live-token"

            connection =
                URL(url).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doInput = true
            connection.doOutput = false

            if (session != null) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer ${session.accessToken}"
                )
            }

            connection.setRequestProperty(
                "apikey",
                BuildConfig.SUPABASE_PUBLISHABLE_KEY
            )

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val code = connection.responseCode

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body =
                stream?.bufferedReader()?.use { reader ->
                    reader.readText()
                }.orEmpty()

            if (code !in 200..299) {
                val serverError =
                    runCatching {
                        json.decodeFromString<NetoLiveTokenResponse>(body)
                    }.getOrNull()

                return NetoLiveTokenResponse(
                    error =
                        serverError?.error?.takeIf { it.isNotBlank() }
                            ?: when (code) {
                                401 -> "NETO authentication expired. Please sign in again."
                                403 -> "NETO Live access was denied."
                                404 -> "NETO Live endpoint was not found."
                                429 -> "Voice service is busy. Please wait a moment and try again."
                                500 -> "NETO Live server configuration failed."
                                502 -> "Gemini Live could not be started."
                                else -> "NETO couldn't connect to the voice service. Please try again."
                            }
                )
            }

            val response =
                runCatching {
                    json.decodeFromString<NetoLiveTokenResponse>(body)
                }.getOrNull()

            if (response == null || !response.ok || response.token.isNullOrBlank()) {
                NetoLiveTokenResponse(
                    error =
                        response?.error
                            ?.takeIf { it.isNotBlank() }
                            ?: "NETO couldn't connect to the voice service. Please try again."
                )
            } else {
                response
            }

        } catch (t: Throwable) {
            val friendlyMsg = when {
                t is java.net.UnknownHostException -> "No internet connection. Please check your network."
                t is java.net.SocketTimeoutException -> "Voice connection timed out. Please try again."
                else -> t.message?.takeIf { it.isNotBlank() }
                    ?: "NETO couldn't connect to the voice service. Please try again."
            }
            NetoLiveTokenResponse(error = friendlyMsg)
        } finally {
            connection?.disconnect()
        }
    }
}
