package com.metronomekiller.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches crashes so they can be reported.
 *
 * LEARN: when Kotlin code throws an exception nobody catches, the thread's
 * "uncaught exception handler" runs just before Android kills the app. We
 * install our own that first writes the error (the "stack trace": which
 * function called which, down to the line that failed) to a file, then hands
 * over to Android's normal handler. On the next launch the app shows it, with
 * a Copy button, so it can be pasted into a bug report.
 *
 * Nothing is sent anywhere: the file stays on the phone until you close the message.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText(
                    "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · " +
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n$stamp · thread ${thread.name}\n\n" +
                        trace.take(12_000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report from the last crash, or null. */
    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) { file(context).delete() }
}
