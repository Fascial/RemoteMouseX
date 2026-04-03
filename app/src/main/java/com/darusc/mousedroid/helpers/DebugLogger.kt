package com.darusc.mousedroid.helpers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun log(message: String) {
        _logs.update { currentLogs ->
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val newLog = "[$timestamp] $message"
            // Keep last 150 logs to prevent memory bloat while capturing everything
            (currentLogs + newLog).takeLast(150)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
