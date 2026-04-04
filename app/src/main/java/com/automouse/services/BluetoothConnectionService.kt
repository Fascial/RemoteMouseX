package com.automouse.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.automouse.MainActivity
import com.automouse.R
import com.automouse.networking.ConnectionManager
import kotlinx.coroutines.*

/**
 * Foreground service that keeps the Bluetooth HID connection alive
 * even when the app is in the background, phone is sleeping, etc.
 *
 * The connection is only terminated when:
 * 1. The user explicitly disconnects via the UI
 * 2. The app is force-killed / removed from recents
 * 3. Auto-reconnect exhausts all retry attempts
 */
class BluetoothConnectionService : Service() {

    companion object {
        private const val TAG = "BtConnService"
        private const val NOTIFICATION_ID = 100
        private const val CHANNEL_ID = "bt_connection_channel"

        const val ACTION_START = "com.automouse.BT_CONN_START"
        const val ACTION_STOP = "com.automouse.BT_CONN_STOP"
        const val ACTION_RECONNECT = "com.automouse.BT_CONN_RECONNECT"

        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_MAC_ADDRESS = "mac_address"

        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var deviceName: String = "Unknown"
    private var macAddress: String? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Unknown"
                macAddress = intent.getStringExtra(EXTRA_MAC_ADDRESS)
                startPersistentConnection()
                START_STICKY
            }
            ACTION_STOP -> {
                stopPersistentConnection()
                START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                macAddress = intent.getStringExtra(EXTRA_MAC_ADDRESS) ?: macAddress
                startAutoReconnect()
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth HID connection alive"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): NotificationCompat.Builder {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect action
        val disconnectIntent = Intent(this, BluetoothConnectionService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingDisconnect = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MouseBuster")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                pendingDisconnect
            )
    }

    private fun startPersistentConnection() {
        Log.d(TAG, "Starting persistent connection for $deviceName")
        acquireWakeLock()
        reconnectAttempt = 0 // Reset reconnect counter on fresh connection

        try {
            val notification = buildNotification("Connected to $deviceName").build()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    private fun stopPersistentConnection() {
        Log.d(TAG, "Stopping persistent connection")
        reconnectJob?.cancel()
        reconnectJob = null

        // Trigger user disconnect through ConnectionManager
        val connectionManager = ConnectionManager.getInstance()
        connectionManager.disconnectByUser()

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Start auto-reconnect with exponential backoff.
     * Called when the Bluetooth connection drops unexpectedly.
     */
    fun startAutoReconnect() {
        if (macAddress == null) {
            Log.w(TAG, "No MAC address stored, cannot reconnect")
            stopPersistentConnection()
            return
        }

        // Cancel any existing reconnect job
        reconnectJob?.cancel()

        updateNotification("Reconnecting to $deviceName...")

        reconnectJob = serviceScope.launch {
            while (reconnectAttempt < MAX_RECONNECT_ATTEMPTS && isActive) {
                reconnectAttempt++
                val delayMs = calculateReconnectDelay(reconnectAttempt)

                Log.d(TAG, "Reconnect attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")
                updateNotification("Reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)...")

                delay(delayMs)

                if (!isActive) break

                val connectionManager = ConnectionManager.getInstance()
                connectionManager.reconnect(applicationContext, macAddress!!)

                // Wait a bit to see if the connection succeeds
                delay(5000)

                if (connectionManager.isConnected()) {
                    Log.d(TAG, "Reconnection successful!")
                    reconnectAttempt = 0
                    updateNotification("Connected to $deviceName")
                    return@launch
                }
            }

            // All retries exhausted
            if (isActive) {
                Log.w(TAG, "All reconnect attempts exhausted")
                val connectionManager = ConnectionManager.getInstance()
                connectionManager.onReconnectFailed()

                withContext(Dispatchers.Main) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * Called by ConnectionManager when reconnection succeeds
     * (connection state callback reports connected)
     */
    fun onReconnectSucceeded(hostName: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        deviceName = hostName
        updateNotification("Connected to $deviceName")
    }

    private fun calculateReconnectDelay(attempt: Int): Long {
        val delay = INITIAL_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
        return delay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text).build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    manager.notify(NOTIFICATION_ID, notification)
                }
            } else {
                manager.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MouseBuster::BtConnectionWakeLock"
            )
            wakeLock?.acquire() // No timeout — held until explicitly released
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
    }
}
