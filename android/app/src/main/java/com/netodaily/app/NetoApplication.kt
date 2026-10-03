package com.netodaily.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class NetoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            Supabase.initialize(this)
        } catch (_: Throwable) {}

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val traceWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(traceWriter))

                val report = buildString {
                    appendLine("NETO Daily Crash Report")
                    appendLine("========================")
                    appendLine()
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine()
                    appendLine("Stack trace:")
                    appendLine(traceWriter.toString())
                }

                File(filesDir, "neto-crash.txt").writeText(report)
            } catch (_: Throwable) {
                // Never allow the crash reporter itself to crash.
            }

            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}
