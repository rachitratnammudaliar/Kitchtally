package com.rachitratnammudaliar.kitchtally

import android.app.Application
import android.util.Log
import java.io.File
import java.util.Date

/** Keeps the latest crash on the phone (never sent anywhere) so "Report a problem" can include it. */
class KitchtallyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(filesDir, ERROR_FILE).writeText("${Date()}\n${Log.getStackTraceString(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val ERROR_FILE = "last-error.txt"
    }
}
