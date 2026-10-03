package com.netodaily.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.netodaily.app.auth.AuthActivity
import io.github.jan.supabase.auth.auth
import java.io.File

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "neto-crash.txt")

        if (crashFile.exists()) {
            startActivity(
                Intent(this, CrashActivity::class.java)
            )
            finish()
            return
        }

        val next = if (
            Supabase.client.auth.currentUserOrNull() != null
        ) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, AuthActivity::class.java)
        }

        startActivity(next)
        finish()
    }
}
