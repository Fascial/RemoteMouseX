package com.darusc.mousedroid.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.darusc.mousedroid.MainActivity
import com.darusc.mousedroid.R
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.ConnectionManager
import kotlin.random.Random

class AutoMouseService : Service() {

    private val TAG = "AutoMouseService"
    private val NOTIFICATION_ID = 42
    private val CHANNEL_ID = "auto_mouse_channel"
    
    private val connectionManager = ConnectionManager.getInstance()
    
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var moveRunnable: Runnable? = null
    private var clickRunnable: Runnable? = null
    private var timer: CountDownTimer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Configuration
    private var moveIntervalMs = 50L         // Move every 50ms for smooth motion
    private var clickIntervalMs = 2000L      // Click every 2 seconds
    private var maxMoveDistance = 80         // Max pixels per movement
    private var durationSeconds = 0L         // 0 = unlimited
    
    // Smooth motion with velocity
    private var velocityX = 0f               // Current velocity in X direction
    private var velocityY = 0f               // Current velocity in Y direction
    private var targetVelocityX = 0f         // Target velocity for smooth transitions
    private var targetVelocityY = 0f         // Target velocity for smooth transitions
    private var moveCounter = 0              // Counter to periodically change direction
    private val directionChangeInterval = 8  // Change direction every ~400ms (8 * 50ms)
    
    // Pattern state
    private var patternType = PATTERN_SMOOTH_LINEAR
    private var circleAngle = 0.0
    private var patternStep = 0
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")
        return when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "ACTION_START received")
                extractConfig(intent!!)
                startAutoMouse()
                START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                stopAutoMouse()
                START_NOT_STICKY
            }
            else -> {
                Log.d(TAG, "Unknown action: ${intent?.action}")
                START_NOT_STICKY
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Mouse Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Auto Mouse is running in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(timeRemaining: String = ""): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val title = if (timeRemaining.isNotEmpty()) "Auto Mouse - $timeRemaining" else "Auto Mouse Running"
        
        // Use system icon as fallback to prevent crashes
        val iconResId = try {
            R.drawable.ic_touchpad
        } catch (e: Exception) {
            android.R.drawable.ic_menu_view // System fallback icon
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Tap to return to app")
            .setSmallIcon(android.R.drawable.ic_menu_view) // Use system icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoMouse::ServiceWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
            // Continue without wake lock - non-fatal error
        }
    }

    private fun extractConfig(intent: Intent) {
        moveIntervalMs = intent.getLongExtra(EXTRA_MOVE_INTERVAL, 50L)
        clickIntervalMs = intent.getLongExtra(EXTRA_CLICK_INTERVAL, 2000L)
        maxMoveDistance = intent.getIntExtra(EXTRA_MAX_DISTANCE, 80)
        durationSeconds = intent.getLongExtra(EXTRA_DURATION_SECONDS, 0L)
        patternType = intent.getIntExtra(EXTRA_PATTERN_TYPE, PATTERN_SMOOTH_LINEAR)
    }

    private fun startAutoMouse() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Auto mouse started. Move: ${moveIntervalMs}ms, Click: ${clickIntervalMs}ms, Duration: ${durationSeconds}s")
        Toast.makeText(this, "AutoMouse Started!", Toast.LENGTH_SHORT).show()
        
        try {
            // Check if connection is valid
            val isConnected = connectionManager.isConnected()
            Log.d(TAG, "Connection status: connected=$isConnected")
            if (!isConnected) {
                Toast.makeText(this, "ERROR: Not connected to device!", Toast.LENGTH_LONG).show()
                Log.e(TAG, "ConnectionManager reports: not connected")
                isRunning = false
                return
            }
            
            // Set flag in SharedPreferences to indicate AutoMouse is running
            val prefs = getSharedPreferences("automouse_state", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", true).apply()
            
            // Start as foreground service with error handling for Android 12+
            try {
                val notification = createNotification().build()
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
                com.darusc.mousedroid.CrashLogger.logCrash(this, e)
                isRunning = false
                prefs.edit().putBoolean("is_running", false).apply()
                return
            }
            
            startMouseMovement()
            startAutoClick()
            
            if (durationSeconds > 0) {
                startTimer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in startAutoMouse: ${e.message}", e)
            com.darusc.mousedroid.CrashLogger.logCrash(this, e)
            isRunning = false
            val prefs = getSharedPreferences("automouse_state", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", false).apply()
        }
    }

    private fun stopAutoMouse() {
        if (!isRunning) return
        isRunning = false
        Log.d(TAG, "Auto mouse stopped")
        
        try {
            // Clear flag in SharedPreferences
            val prefs = getSharedPreferences("automouse_state", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", false).apply()
            
            // Reset velocity
            velocityX = 0f
            velocityY = 0f
            targetVelocityX = 0f
            targetVelocityY = 0f
            moveCounter = 0
            
            moveRunnable?.let { handler.removeCallbacks(it) }
            clickRunnable?.let { handler.removeCallbacks(it) }
            timer?.cancel()
            
            // Send button release to clear any held state
            try {
                connectionManager.send(InputEvent.MouseDragState(InputEvent.MouseButton.NONE, false), true)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending button release: ${e.message}")
            }
            
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopAutoMouse: ${e.message}", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startTimer() {
        timer = object : CountDownTimer(durationSeconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val minutes = seconds / 60
                val secs = seconds % 60
                val timeStr = String.format("%02d:%02d", minutes, secs)
                
                val notification = createNotification(timeStr).build()
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }

            override fun onFinish() {
                Log.d(TAG, "Timer finished, stopping auto mouse")
                stopAutoMouse()
            }
        }.start()
    }

    private fun startMouseMovement() {
        moveRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                try {
                    var dx = 0
                    var dy = 0

                    when (patternType) {
                        PATTERN_SMOOTH_LINEAR -> {
                            if (moveCounter % directionChangeInterval == 0) {
                                targetVelocityX = Random.nextInt(-maxMoveDistance, maxMoveDistance + 1).toFloat()
                                targetVelocityY = Random.nextInt(-maxMoveDistance, maxMoveDistance + 1).toFloat()
                            }
                            moveCounter++
                            val easing = 0.2f
                            velocityX += (targetVelocityX - velocityX) * easing
                            velocityY += (targetVelocityY - velocityY) * easing
                            dx = velocityX.toInt()
                            dy = velocityY.toInt()
                        }
                        PATTERN_CIRCULAR_TRACE -> {
                            val radius = maxOf(10.0, maxMoveDistance / 2.0)
                            val rad = Math.toRadians(circleAngle)
                            val nextRad = Math.toRadians(circleAngle + 20.0) // 20 degrees per tick
                            
                            val currentX = radius * Math.cos(rad)
                            val currentY = radius * Math.sin(rad)
                            val nextX = radius * Math.cos(nextRad)
                            val nextY = radius * Math.sin(nextRad)
                            
                            dx = (nextX - currentX).toInt()
                            dy = (nextY - currentY).toInt()
                            circleAngle = (circleAngle + 20.0) % 360
                        }
                        PATTERN_ERRATIC_JUMPS -> {
                            dx = Random.nextInt(-maxMoveDistance, maxMoveDistance + 1)
                            dy = Random.nextInt(-maxMoveDistance, maxMoveDistance + 1)
                        }
                        PATTERN_PATTERN_REPEAT -> {
                            val stepSize = maxOf(2, maxMoveDistance / 10)
                            when (patternStep % 40) {
                                in 0..9 -> { dx = stepSize; dy = 0 } // Right
                                in 10..19 -> { dx = 0; dy = stepSize } // Down
                                in 20..29 -> { dx = -stepSize; dy = 0 } // Left
                                in 30..39 -> { dx = 0; dy = -stepSize } // Up
                            }
                            patternStep++
                        }
                    }
                    
                    // Send mouse move only if values changed
                    if (dx != 0 || dy != 0) {
                        try {
                            val isConnected = connectionManager.isConnected()
                            Log.d(TAG, "Mouse move: connected=$isConnected, vel=($velocityX, $velocityY), delta=($dx, $dy)")
                            
                            if (!isConnected) {
                                Log.e(TAG, "CRITICAL: Connection lost during mouse movement!")
                                return
                            }
                            
                            connectionManager.send(InputEvent.MouseMove(dx, dy, InputEvent.MouseButton.NONE), true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending mouse move: ${e.message}", e)
                        }
                    }
                    
                    // Reschedule
                    if (isRunning) {
                        handler.postDelayed(this, moveIntervalMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mouse movement loop: ${e.message}", e)
                    if (isRunning) {
                        handler.postDelayed(this, moveIntervalMs)
                    }
                }
            }
        }
        Log.d(TAG, "Starting mouse movement with interval: $moveIntervalMs ms, max distance: $maxMoveDistance, smooth velocity enabled")
        if (handler.looper != null) {
            handler.post(moveRunnable!!)
            Log.d(TAG, "Mouse movement runnable POSTED to handler")
        } else {
            Log.e(TAG, "CRITICAL: Handler looper is null!")
        }
    }

    private fun startAutoClick() {
        clickRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                try {
                    // Random click: left (70%), right (20%), middle (10%)
                    val random = Random.nextInt(100)
                    val button = when {
                        random < 70 -> InputEvent.MouseButton.LEFT
                        random < 90 -> InputEvent.MouseButton.RIGHT
                        else -> InputEvent.MouseButton.MIDDLE
                    }
                    
                    try {
                        val isConnected = connectionManager.isConnected()
                        Log.d(TAG, "Click attempt: connected=$isConnected, button=$button")
                        
                        if (!isConnected) {
                            Log.e(TAG, "CRITICAL: Connection lost during click!")
                            return
                        }
                        
                        connectionManager.send(InputEvent.MouseClick(button), true)
                        Log.d(TAG, "Click SENT: $button")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending click: ${e.message}", e)
                    }
                    
                    // Reschedule
                    if (isRunning) {
                        handler.postDelayed(this, clickIntervalMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in click loop: ${e.message}", e)
                    if (isRunning) {
                        handler.postDelayed(this, clickIntervalMs)
                    }
                }
            }
        }
        Log.d(TAG, "Starting auto click with interval: $clickIntervalMs ms")
        if (handler.looper != null) {
            handler.post(clickRunnable!!)
            Log.d(TAG, "Click runnable POSTED to handler")
        } else {
            Log.e(TAG, "CRITICAL: Handler looper is null for clicks!")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            stopAutoMouse()
        }
        wakeLock?.release()
    }

    companion object {
        const val ACTION_START = "com.darusc.mousedroid.AUTO_MOUSE_START"
        const val ACTION_STOP = "com.darusc.mousedroid.AUTO_MOUSE_STOP"
        
        const val EXTRA_MOVE_INTERVAL = "move_interval_ms"
        const val EXTRA_CLICK_INTERVAL = "click_interval_ms"
        const val EXTRA_MAX_DISTANCE = "max_distance"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_PATTERN_TYPE = "pattern_type"
        
        const val PATTERN_SMOOTH_LINEAR = 0
        const val PATTERN_CIRCULAR_TRACE = 1
        const val PATTERN_ERRATIC_JUMPS = 2
        const val PATTERN_PATTERN_REPEAT = 3
    }
}
