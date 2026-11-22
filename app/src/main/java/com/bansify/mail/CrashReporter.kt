package com.bansify.mail

import android.content.Context
import java.io.File

/**
 * Minimal on-device crash capture.
 *
 * A process that dies before the first frame leaves the user with nothing to report, so the
 * uncaught-exception handler writes the stack trace to internal storage and the next launch shows
 * it on screen. No network, no third-party SDK, and the file is deleted once the user dismisses it.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText().takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, error: Throwable) {
        val text = buildString {
            appendLine(error.toString())
            appendLine(error.stackTraceToString().lineSequence().take(40).joinToString("\n"))
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }
}
