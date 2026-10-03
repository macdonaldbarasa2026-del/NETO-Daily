package com.netodaily.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.netodaily.app.auth.AuthActivity
import io.github.jan.supabase.auth.auth

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val destination =
            if (Supabase.client.auth.currentUserOrNull() != null) {
                MainActivity::class.java
            } else {
                AuthActivity::class.java
            }

        startActivity(Intent(this, destination))
        finish()
    }
}
