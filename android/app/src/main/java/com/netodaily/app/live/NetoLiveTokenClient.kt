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
            val session =
                Supabase.client.auth.currentSessionOrNull()
                    ?: return NetoLiveTokenResponse(
                        error = "Please sign in to use NETO voice."
                    )

            val url =
                "${BuildConfig.SUPABASE_URL}/functions/v1/neto-live-token"

            connection =
                URL(url).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doInput = true
            connection.doOutput = false

            connection.setRequestProperty(
                "Authorization",
                "Bearer ${session.accessToken}"
            )

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
                                500 -> "NETO Live server configuration failed."
                                502 -> "Gemini Live could not be started."
                                else -> "NETO Live request failed (HTTP $code)."
                            }
                )
            }

            val response =
                json.decodeFromString<NetoLiveTokenResponse>(body)

            if (!response.ok || response.token.isNullOrBlank()) {
                NetoLiveTokenResponse(
                    error =
                        response.error
                            ?.takeIf { it.isNotBlank() }
                            ?: "NETO Live returned an invalid token."
                )
            } else {
                response
            }

        } catch (t: Throwable) {
            NetoLiveTokenResponse(
                error =
                    t.message?.takeIf { it.isNotBlank() }
                        ?: "NETO could not connect to Live voice."
            )
        } finally {
            connection?.disconnect()
        }
    }
}
