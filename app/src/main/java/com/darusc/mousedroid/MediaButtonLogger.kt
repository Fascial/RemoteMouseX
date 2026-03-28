package com.darusc.mousedroid

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaButtonLogger {
    private const val TAG = "MediaButtonLogger"
    private const val PREFS_NAME = "media_button_logs"
    private const val MEDIA_LOG_KEY = "media_log"
    private const val MAX_LOG_ENTRIES = 50  // Keep last 50 button presses

    fun logMediaButtonPress(context: Context, buttonName: String, action: String, dataBytes: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logEntry = "[$timestamp] Button: $buttonName | Action: $action | Data: $dataBytes"
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingLog = prefs.getString(MEDIA_LOG_KEY, "") ?: ""
            
            // Keep only last 50 entries
            val logEntries = existingLog
                .split("\n")
                .filter { it.isNotBlank() }
                .takeLast(MAX_LOG_ENTRIES - 1)
            
            val updatedLog = (logEntries + logEntry).joinToString("\n")
            
            prefs.edit().apply {
                putString(MEDIA_LOG_KEY, updatedLog)
                putLong("last_media_log_time", System.currentTimeMillis())
            }.apply()
            
            Log.d(TAG, "Media button logged: $logEntry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log media button press", e)
        }
    }

    fun getMediaLog(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val log = prefs.getString(MEDIA_LOG_KEY, null)
            if (log.isNullOrBlank()) {
                return null
            }
            // Show most recent entries first
            log.split("\n")
                .filter { it.isNotBlank() }
                .reversed()
                .joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve media button log", e)
            null
        }
    }

    fun clearMediaLog(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(MEDIA_LOG_KEY).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear media button log", e)
        }
    }

    fun getMediaLogCount(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val log = prefs.getString(MEDIA_LOG_KEY, "") ?: ""
            log.split("\n").filter { it.isNotBlank() }.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get media log count", e)
            0
        }
    }
}
