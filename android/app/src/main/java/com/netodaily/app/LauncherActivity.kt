package com.netodaily.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.netodaily.app.auth.AuthActivity
import com.netodaily.app.data.NetoLocalStore
import io.github.jan.supabase.auth.auth

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = NetoLocalStore(this)
        val hasEnteredBefore = store.getBoolean("has_entered", false)
        val isLoggedIn = runCatching { Supabase.client.auth.currentUserOrNull() != null }.getOrDefault(false)

        val destination = if (hasEnteredBefore || isLoggedIn) {
            MainActivity::class.java
        } else {
            AuthActivity::class.java
        }

        startActivity(Intent(this, destination))
        finish()
    }
}
