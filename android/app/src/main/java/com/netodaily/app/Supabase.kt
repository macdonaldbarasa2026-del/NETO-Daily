package com.netodaily.app

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.functions.Functions

object Supabase {

    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://vjzelobnfjvfchoiqbqr.supabase.co" },
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.also {
            require(it.isNotBlank()) { "SUPABASE_PUBLISHABLE_KEY is missing from the Android build." }
        }
    ) {
        install(Auth)
        install(Functions)
    }
}
