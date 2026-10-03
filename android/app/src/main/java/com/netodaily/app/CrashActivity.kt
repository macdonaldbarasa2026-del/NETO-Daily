package com.netodaily.app

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = File(filesDir, "neto-crash.txt")
            .takeIf { it.exists() }
            ?.readText()
            ?: "No crash report was found."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
            setBackgroundColor(Color.rgb(238, 243, 241))
        }

        val title = TextView(this).apply {
            text = "NETO Daily crash report"
            textSize = 22f
            setTextColor(Color.rgb(18, 33, 30))
            gravity = Gravity.CENTER
        }

        val body = TextView(this).apply {
            text = report
            textSize = 13f
            setTextColor(Color.rgb(18, 33, 30))
            setPadding(0, 24, 0, 24)
        }

        val scroll = ScrollView(this).apply {
            addView(body)
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
    }
}
