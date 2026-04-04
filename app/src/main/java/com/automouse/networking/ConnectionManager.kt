package com.automouse.networking

import android.content.Context
import android.content.Intent
import android.util.Log
import com.automouse.mkinput.InputEvent
import com.automouse.networking.bluetooth.BluetoothConnection
import com.automouse.services.BluetoothConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

class ConnectionManager private constructor() : Connection.Listener {

    companion object {
        private const val TAG = "ConnectionManager"
        private const val PREFS_NAME = "automouse_device"
        private const val KEY_DEVICE_MAC = "last_device_mac"
        private const val KEY_DEVICE_NAME = "last_device_name"

        @Volatile
        private var instance: ConnectionManager? = null

        fun getInstance(): ConnectionManager {
            synchronized(this) {
                return instance ?: ConnectionManager().also { instance = it }
            }
        }

        fun getInstance(connectionStateCallback: ConnectionStateCallback): ConnectionManager {
            synchronized(this) {
                if (instance == null) {
                    instance = ConnectionManager()
                }
                instance!!.setConnectionStateCallback(connectionStateCallback)
                return instance!!
            }
        }
    }

    private var connectionStateCallback: ConnectionStateCallback? = null

    private var btConn: BluetoothConnection? = null
    
    /**
     * Flag to prevent concurrent HID registration attempts
     * CRITICAL: Only one registration can proceed at a time
     */
    private var isRegistering = false

    /**
     * Active connection is always Bluetooth
     */
    private val connection: Connection?
        get() = btConn

    private var connected = false

    /**
     * Whether the current disconnect was initiated by the user (vs unexpected)
     */
    private var isUserDisconnect = false

    /**
     * Last connected device MAC address for auto-reconnect
     */
    var lastConnectedMacAddress: String? = null
        private set

    /**
     * Last connected device name for display
     */
    var lastConnectedDeviceName: String? = null
        private set

    /**
     * Application context reference for reconnection
     */
    private var appContext: Context? = null

    /**
     * Flag to track if old connection cleanup is in progress
     * CRITICAL: New connection waits for this to become false
     */
    private var isCleanupInProgress = false

    /**
     * Flag to prevent recursive disconnect calls
     * CRITICAL: Prevents infinite loop between service and manager
     */
    private var isDisconnecting = false

    /**
     * Connection event logs for debugging
     */
    private val connectionLogs = mutableListOf<String>()
    private val maxLogs = 100

    /**
     * Add a log entry with timestamp
     */
    private fun addLog(message: String) {
        val timestamp = System.currentTimeMillis()
        val logEntry = "[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(timestamp)}] $message"
        connectionLogs.add(logEntry)
        if (connectionLogs.size > maxLogs) {
            connectionLogs.removeAt(0)
        }
        Log.d(TAG, message)
    }

    /**
     * Get all connection logs (for UI display)
     */
    fun getConnectionLogs(): List<String> {
        return connectionLogs.toList()
    }

    /**
     * Clear connection logs
     */
    fun clearConnectionLogs() {
        connectionLogs.clear()
        addLog("Logs cleared")
    }

    interface ConnectionStateCallback {
        fun onConnectionInitiated(mode: Connection.Mode) {}
        fun onConnectionSuccessful(connectionMode: Connection.Mode, hostName: String) {}
        fun onConnectionFailed(connectionMode: Connection.Mode) {}
        fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {}
        fun onReconnecting(connectionMode: Connection.Mode) {}
        fun onReconnectFailed(connectionMode: Connection.Mode) {}
    }

    private fun setConnectionStateCallback(connectionStateCallback: ConnectionStateCallback) {
        this.connectionStateCallback = connectionStateCallback
    }

    override fun onConnected(connectionMode: Connection.Mode, hostName: String) {
        connected = true
        lastConnectedDeviceName = hostName
        addLog("✓ CONNECTED to $hostName - Protocol properly followed")
        
        // Save the device for auto-reconnect
        saveRememberedDevice(lastConnectedMacAddress, hostName)
        
        connectionStateCallback?.onConnectionSuccessful(connectionMode, hostName)

        // If we were reconnecting, notify the service
        appContext?.let { ctx ->
            try {
                val service = Intent(ctx, BluetoothConnectionService::class.java).apply {
                    action = BluetoothConnectionService.ACTION_START
                    putExtra(BluetoothConnectionService.EXTRA_DEVICE_NAME, hostName)
                    putExtra(BluetoothConnectionService.EXTRA_MAC_ADDRESS, lastConnectedMacAddress)
                }
                ctx.startService(service)
                addLog("Connection service started")
            } catch (e: Exception) {
                addLog("Failed to notify connection service: ${e.message}")
            }
        }
    }

    override fun onConnectionFailed(connectionMode: Connection.Mode) {
        isRegistering = false  // Reset flag on failure
        addLog("✗ Connection failed - unable to register HID profile")
        connectionStateCallback?.onConnectionFailed(connectionMode)
    }

    override fun onBytesReceived(buffer: ByteArray, bytes: Int) {}

    override fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {
        connected = false
        addLog("Disconnected from $hostName")

        if (isUserDisconnect) {
            // User-initiated disconnect: tear down completely
            addLog("User-initiated disconnect from $hostName - graceful shutdown")
            isUserDisconnect = false
            cleanupConnection()
            connectionStateCallback?.onDisconnected(connectionMode, hostName)
        } else {
            // Unexpected disconnect: start auto-reconnect via the service
            addLog("Unexpected disconnect from $hostName - starting auto-reconnect")
            connectionStateCallback?.onReconnecting(connectionMode)

            appContext?.let { ctx ->
                try {
                    val intent = Intent(ctx, BluetoothConnectionService::class.java).apply {
                        action = BluetoothConnectionService.ACTION_RECONNECT
                        putExtra(BluetoothConnectionService.EXTRA_MAC_ADDRESS, lastConnectedMacAddress)
                    }
                    ctx.startService(intent)
                } catch (e: Exception) {
                    addLog("Failed to start reconnect service: ${e.message}")
                    // Fallback: treat as full disconnect
                    cleanupConnection()
                    connectionStateCallback?.onDisconnected(connectionMode, hostName)
                }
            } ?: run {
                // No context available, can't reconnect
                cleanupConnection()
                connectionStateCallback?.onDisconnected(connectionMode, hostName)
            }
        }
    }

    /**
     * Register the bluetooth HID profile
     * CRITICAL: Always creates a fresh BluetoothConnection to avoid stale state
     * This is non-blocking but ensures instance is created
     * PROTECTED: Prevents concurrent registration attempts
     * 
     * Note: Should NOT be called if btConn is already successfully connected
     * For reconnection, call initiateConnection() which handles cleanup first
     */
    fun registerBluetoothHID(context: Context) {
        // CRITICAL: Block registration while cleanup is in progress
        // This prevents racing with old connection's async cleanup
        if (isCleanupInProgress) {
            addLog("Cleanup in progress, cannot register HID yet")
            return
        }

        // Prevent concurrent registration attempts
        if (isRegistering) {
            addLog("Registration already in progress, skipping")
            return
        }
        
        appContext = context.applicationContext
        
        // Check if connection exists and is still usable
        // But allow re-registration if btConn is null (fresh start)
        if (btConn != null && !btConn!!.isClosed() && btConn!!.appRegisteredState.value) {
            addLog("BluetoothConnection already registered and active")
            return
        }

        addLog("Registering BluetoothHID...")
        isRegistering = true
        
        // Force create new instance and let it initialize asynchronously
        btConn = null
        try {
            btConn = BluetoothConnection(context, this@ConnectionManager)
            addLog("BluetoothHID instance created, waiting for profile proxy...")
        } catch (e: Exception) {
            addLog("Failed to create BluetoothConnection: ${e.message}")
            btConn = null
            isRegistering = false
        }
    }
    
    /**
     * Wait for HID profile to be fully initialized and ready
     * Must be called before connectBluetooth()
     * CRITICAL: Ensures profile proxy callback has fired
     */
    suspend fun ensureHidReady(timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        var lastLogTime = startTime
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (btConn == null) {
                Log.e(TAG, "btConn is null, HID initialization failed")
                isRegistering = false
                return false
            }
            
            if (btConn!!.appRegisteredState.value) {
                Log.d(TAG, "HID is ready and registered (appRegisteredState=true)")
                isRegistering = false  // Mark registration as complete
                return true
            }
            
            if (btConn!!.hidState.value is com.automouse.networking.bluetooth.HidState.Registered) {
                Log.d(TAG, "HID state is Registered (hidState transition)")
                isRegistering = false  // Mark registration as complete
                return true
            }
            
            // Detect error states early
            if (btConn!!.hidState.value is com.automouse.networking.bluetooth.HidState.Error) {
                Log.e(TAG, "HID registration error: ${(btConn!!.hidState.value as com.automouse.networking.bluetooth.HidState.Error).message}")
                isRegistering = false
                return false
            }
            
            // Log progress every 1 second for debugging
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 1000) {
                Log.d(TAG, "HID not ready state=${btConn!!.hidState.value}, registered=${btConn!!.appRegisteredState.value}, elapsed=${now - startTime}ms")
                lastLogTime = now
            }
            
            delay(50)  // Better polling interval
        }
        
        Log.e(TAG, "HID registration timeout after ${timeoutMs}ms - state=${btConn?.hidState?.value}, registered=${btConn?.appRegisteredState?.value}")
        isRegistering = false  // Reset flag even on timeout
        return false
    }

    /**
     * Force re-register the bluetooth HID profile
     * Closes the existing connection and creates a new one
     * CRITICAL: Waits for old connection to fully close before creating new one
     */
    private suspend fun forceReregisterBluetoothHID(context: Context) {
        Log.d(TAG, "Force re-registering BluetoothHID...")
        
        // Close the old connection if it exists and WAIT for it to complete
        val oldConn = btConn
        if (oldConn != null) {
            try {
                oldConn.close()
                
                // Wait for close to actually complete (state-driven, not blind delay)
                val closed = withTimeoutOrNull(3000) {
                    while (!oldConn.isClosed()) {
                        kotlinx.coroutines.yield()
                    }
                    true
                }
                
                if (closed == true) {
                    Log.d(TAG, "Old connection fully closed")
                } else {
                    Log.w(TAG, "Old connection close timeout, continuing anyway")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing old connection", e)
            }
        }
        
        btConn = null
        
        // Create a new connection
        try {
            btConn = BluetoothConnection(context, this@ConnectionManager)
            Log.d(TAG, "BluetoothHID re-registration created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new BluetoothConnection", e)
        }
    }

    /**
     * Flag to track if we're in an auto-connect attempt
     */
    private var isAutoConnecting = false

    /**
     * Check if currently auto-connecting
     */
    fun isAutoConnecting(): Boolean {
        return isAutoConnecting
    }

    /**
     * Set auto-connect flag
     */
    fun setAutoConnecting(value: Boolean) {
        isAutoConnecting = value
    }

    /**
     * Cancel auto-connect attempt - stops reconnection and clears device memory
     */
    fun cancelAutoConnect() {
        isAutoConnecting = false
        Log.d(TAG, "Auto-connect cancelled by user")
        disconnectAndForget()
    }

    /**
     * Connect in bluetooth mode
     * CRITICAL: Must be called after HID is initialized
     */
    fun connectBluetooth(macAddress: String) {
        lastConnectedMacAddress = macAddress
        isUserDisconnect = false
        
        if (btConn == null) {
            Log.e(TAG, "BluetoothConnection not initialized, cannot connect")
            connectionStateCallback?.onConnectionFailed(Connection.Mode.BLUETOOTH)
            return
        }
        
        connectionStateCallback?.onConnectionInitiated(Connection.Mode.BLUETOOTH)
        btConn!!.connect(macAddress)
    }
    
    /**
     * UNIFIED CONNECTION WORKFLOW
     * Used by both manual and auto-reconnect to ensure consistent behavior
     * Handles: close old → register HID → wait for ready → connect
     * CRITICAL: Forces fresh HID registration for each connection attempt
     */
    fun initiateConnection(context: Context, macAddress: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                addLog("Initiating connection to $macAddress")
                
                // Step 0: FORCE close old connection if it exists
                if (btConn != null) {
                    addLog("Closing previous connection...")
                    try {
                        btConn!!.close()  // Launches async cleanup
                        addLog("Previous connection close initiated")
                    } catch (e: Exception) {
                        addLog("Error closing previous connection: ${e.message}")
                    }
                }
                
                btConn = null  // Clear reference immediately
                addLog("Old btConn reference nulled")
                
                // CRITICAL: Wait for any ongoing cleanup to complete
                // This prevents race condition where new getProfileProxy() happens
                // before old closeProfileProxy() finishes
                var waitCount = 0
                while (isCleanupInProgress && waitCount < 100) {  // Max 10 seconds (100 * 100ms)
                    addLog("Waiting for cleanup to complete... (${waitCount})")
                    delay(100)
                    waitCount++
                }
                
                if (isCleanupInProgress) {
                    addLog("WARNING: Cleanup still in progress after 10 seconds, proceeding anyway")
                } else {
                    addLog("Cleanup completed, safe to create new connection")
                }
                
                delay(500)  // Additional buffer for system Bluetooth stack
                
                addLog("Creating fresh BluetoothConnection instance")
                
                // Step 1: Register HID (fresh instance)
                registerBluetoothHID(context)
                
                addLog("Waiting for HID to be ready...")
                
                // Step 2: Wait for HID to actually be ready
                val hidReady = ensureHidReady(6000)  // Increased timeout
                if (!hidReady) {
                    addLog("HID failed to initialize after 6 seconds - connection failed")
                    connectionStateCallback?.onConnectionFailed(Connection.Mode.BLUETOOTH)
                    return@launch
                }
                
                addLog("HID ready, connecting to $macAddress")
                
                // Step 3: Connect (must be on IO dispatcher)
                connectBluetooth(macAddress)
            } catch (e: Exception) {
                addLog("Error in connection workflow: ${e.message}")
                connectionStateCallback?.onConnectionFailed(Connection.Mode.BLUETOOTH)
            }
        }
    }

    /**
     * User-initiated disconnect. Stops the foreground service and tears down the connection.
     * NOTE: Does NOT clear remembered device - user can reconnect with the same device
     * CRITICAL: Synchronously resets connection state flags so that an immediate
     * reconnect attempt doesn't see stale state from the previous connection.
     */
    fun disconnectByUser() {
        // CRITICAL: Guard against recursive calls from service
        // If already disconnecting, don't call startService(ACTION_STOP) again
        if (isDisconnecting) {
            addLog("Disconnect already in progress, skipping recursive call")
            return
        }

        isDisconnecting = true
        isUserDisconnect = true
        addLog("User disconnect requested")

        // CRITICAL: Synchronously reset flags BEFORE launching the async cleanup.
        // This prevents a race where the user immediately reconnects and
        // registerBluetoothHID() sees isRegistering=true from the old connection.
        connected = false
        isRegistering = false

        // Mark cleanup as in progress
        isCleanupInProgress = true
        addLog("Cleanup marked as in progress")

        // Stop the connection service
        appContext?.let { ctx ->
            try {
                val intent = Intent(ctx, BluetoothConnectionService::class.java).apply {
                    action = BluetoothConnectionService.ACTION_STOP
                }
                ctx.startService(intent)
                addLog("Connection service stop requested")
            } catch (e: Exception) {
                addLog("Failed to stop connection service: ${e.message}")
            }
        }

        // Capture the current btConn reference and clear it synchronously.
        // This ensures any subsequent registerBluetoothHID() call creates a fresh instance
        // instead of seeing the stale (closing/closed) connection object.
        val oldConn = btConn
        btConn = null
        addLog("btConn reference nulled, launching async cleanup")

        // Launch async cleanup for the old connection
        CoroutineScope(Dispatchers.IO).launch {
            disconnectOldConnection(oldConn)
        }
    }

    /**
     * Forget the remembered device and disconnect.
     * Used when user wants to disconnect AND forget the device.
     */
    fun disconnectAndForget() {
        Log.d(TAG, "User disconnect and forget requested")
        clearRememberedDevice()
        disconnectByUser()
    }

    /**
     * Async cleanup of an old connection instance
     * Called after btConn is nulled to ensure resources are freed
     * Sets isCleanupInProgress to false when complete
     */
    private suspend fun disconnectOldConnection(oldConn: BluetoothConnection?) {
        if (oldConn == null) {
            addLog("No old connection to clean up")
            isCleanupInProgress = false
            isDisconnecting = false
            return
        }
        
        try {
            addLog("Starting async cleanup with 2000ms delay")
            delay(2000)  // Allow async close() operations to complete
            addLog("Calling oldConn.close() to terminate async cleanup")
            oldConn.close()
            addLog("Old connection cleanup completed - marking cleanup as done")
        } catch (e: Exception) {
            addLog("Error during old connection cleanup: ${e.message}")
        } finally {
            // CRITICAL: Always mark cleanup as complete, even on error
            isCleanupInProgress = false
            isDisconnecting = false
            addLog("Cleanup in progress flag reset - safe for new connection")
        }
    }

    /**
     * Internal disconnect — closes the BT connection but doesn't stop the service
     * The close() function launches async, so this just clears references
     */
    suspend fun disconnect() {
        connected = false
        isRegistering = false
        
        if (btConn != null) {
            Log.d(TAG, "Closing connection")
            try {
                btConn!!.close()  // Launches async cleanup
            } catch (e: Exception) {
                Log.e(TAG, "Error closing connection", e)
            }
        }
        
        btConn = null
        delay(300)  // Small delay for system to process
        
        Log.d(TAG, "Disconnect completed")
    }

    /**
     * Reconnect to the last connected device.
     * Re-registers the HID profile and attempts connection.
     */
    fun reconnect(context: Context, macAddress: String) {
        Log.d(TAG, "Attempting reconnect to $macAddress")
        appContext = context.applicationContext
        lastConnectedMacAddress = macAddress
        isUserDisconnect = false

        CoroutineScope(Dispatchers.IO).launch {
            // Force re-register HID profile
            forceReregisterBluetoothHID(context.applicationContext)
            
            // Wait a bit for the new connection to be ready
            delay(1000)
            
            // Attempt connection
            btConn?.connect(macAddress)
        }
    }

    /**
     * Called when all reconnect attempts have been exhausted
     */
    fun onReconnectFailed() {
        Log.w(TAG, "All reconnect attempts failed")
        connected = false
        cleanupConnection()
        connectionStateCallback?.onReconnectFailed(Connection.Mode.BLUETOOTH)
    }

    private fun cleanupConnection() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                btConn?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
            btConn = null
        }
    }

    fun send(event: InputEvent, withCoroutine: Boolean = true) {
        val isConnected = connection != null && connected
        
        if (!isConnected) {
            return
        }
        
        when (withCoroutine) {
            false -> {
                connection?.send(event)
            }
            true -> {
                CoroutineScope(Dispatchers.IO).launch { 
                    connection?.send(event) 
                }
            }
        }
    }

    fun isConnected(): Boolean {
        return connected && connection != null
    }

    /**
     * Save the connected device to SharedPreferences for auto-reconnect
     */
    private fun saveRememberedDevice(macAddress: String?, deviceName: String?) {
        if (macAddress == null) return
        
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putString(KEY_DEVICE_MAC, macAddress)
                    putString(KEY_DEVICE_NAME, deviceName ?: "Unknown")
                    apply()
                }
                Log.d(TAG, "Saved device: $deviceName ($macAddress)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save device to preferences", e)
            }
        }
    }

    /**
     * Load the remembered device from SharedPreferences
     */
    fun loadRememberedDevice(): Pair<String?, String?>? {
        return appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val macAddress = prefs.getString(KEY_DEVICE_MAC, null)
                val deviceName = prefs.getString(KEY_DEVICE_NAME, null)
                if (macAddress != null) {
                    Log.d(TAG, "Loaded remembered device: $deviceName ($macAddress)")
                    return@let Pair(macAddress, deviceName)
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load device from preferences", e)
                null
            }
        }
    }

    /**
     * Clear the remembered device from SharedPreferences
     */
    fun clearRememberedDevice() {
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    remove(KEY_DEVICE_MAC)
                    remove(KEY_DEVICE_NAME)
                    apply()
                }
                Log.d(TAG, "Cleared remembered device")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear device from preferences", e)
            }
        }
    }

    /**
     * Check if a device is remembered
     */
    fun hasRememberedDevice(): Boolean {
        return appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.contains(KEY_DEVICE_MAC)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check if device is remembered", e)
                false
            }
        } ?: false
    }
}