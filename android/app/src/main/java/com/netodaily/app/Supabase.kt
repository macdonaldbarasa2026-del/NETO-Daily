package com.netodaily.app

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.functions.Functions

object Supabase {

    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://vjzelobnfjvfchoiqbqr.supabase.co" },
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.ifBlank { "anon" }
    ) {
        install(Auth)
        install(Functions)
    }
}
