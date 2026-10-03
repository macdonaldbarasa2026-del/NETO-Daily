package com.netodaily.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.netodaily.app.auth.AuthActivity
import io.github.jan.supabase.auth.auth
import java.io.File

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "neto-crash.txt")

        if (crashFile.exists()) {
            val report = try {
                crashFile.readText()
            } catch (e: Exception) {
                "Could not read crash report:\n\n${e.stackTraceToString()}"
            }

            val scroll = ScrollView(this)

            val text = TextView(this).apply {
                text = report
                textSize = 14f
                setTextColor(Color.rgb(18, 33, 30))
                setBackgroundColor(Color.rgb(238, 243, 241))
                setPadding(32, 48, 32, 48)
                gravity = Gravity.START
            }

            scroll.addView(text)
            setContentView(scroll)
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
