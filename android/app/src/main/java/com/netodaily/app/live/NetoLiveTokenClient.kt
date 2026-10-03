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
        return try {
            val session = Supabase.client.auth.currentSessionOrNull()
                ?: return NetoLiveTokenResponse(
                    error = "Please sign in to use NETO voice."
                )

            val connection =
                URL(
                    "${BuildConfig.SUPABASE_URL}/functions/v1/neto-live-token"
                ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doInput = true

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

            val code = connection.responseCode

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body =
                stream?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()

            if (code !in 200..299) {
                return runCatching {
                    json.decodeFromString<NetoLiveTokenResponse>(body)
                }.getOrElse {
                    NetoLiveTokenResponse(
                        error = "Could not start NETO Live."
                    )
                }
            }

            json.decodeFromString(body)
        } catch (_: Throwable) {
            NetoLiveTokenResponse(
                error = "NETO could not connect to Live voice."
            )
        }
    }
}
