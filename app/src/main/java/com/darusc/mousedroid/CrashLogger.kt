package com.darusc.mousedroid

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val PREFS_NAME = "crash_logs"
    private const val LAST_CRASH_KEY = "last_crash"
    private const val CRASH_COUNT_KEY = "crash_count"

    fun logCrash(context: Context, exception: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val stackTrace = exception.stackTraceToString()
            val message = exception.message ?: "Unknown error"
            
            val crashInfo = """
                Crash at: $timestamp
                Exception: ${exception::class.simpleName}
                Message: $message
                Stack Trace:
                $stackTrace
            """.trimIndent()
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(LAST_CRASH_KEY, crashInfo)
                putLong("last_crash_time", System.currentTimeMillis())
                val count = prefs.getInt(CRASH_COUNT_KEY, 0)
                putInt(CRASH_COUNT_KEY, count + 1)
            }.apply()
            
            Log.e(TAG, "Crash logged: $crashInfo")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }

    fun getLastCrash(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(LAST_CRASH_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve crash log", e)
            null
        }
    }

    fun clearCrashLog(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(LAST_CRASH_KEY).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash log", e)
        }
    }

    fun getCrashCount(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(CRASH_COUNT_KEY, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get crash count", e)
            0
        }
    }
}
