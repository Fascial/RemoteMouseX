package com.automouse.networking

import android.content.Context
import android.content.Intent
import android.util.Log
import com.automouse.mkinput.InputEvent
import com.automouse.networking.bluetooth.BluetoothConnection
import com.automouse.services.BluetoothConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectionManager private constructor() : Connection.Listener {

    companion object {
        private const val TAG = "ConnectionManager"
        private const val PREFS_NAME = "mousebuster_connection"
        private const val KEY_LAST_MAC = "last_connected_mac"
        private const val KEY_LAST_NAME = "last_connected_name"

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
        connectionStateCallback?.onConnectionSuccessful(connectionMode, hostName)

        // Persist last connected device for auto-connect on next app launch
        saveLastConnectedDevice()

        // If we were reconnecting, notify the service
        appContext?.let { ctx ->
            try {
                val service = Intent(ctx, BluetoothConnectionService::class.java).apply {
                    action = BluetoothConnectionService.ACTION_START
                    putExtra(BluetoothConnectionService.EXTRA_DEVICE_NAME, hostName)
                    putExtra(BluetoothConnectionService.EXTRA_MAC_ADDRESS, lastConnectedMacAddress)
                }
                ctx.startService(service)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify connection service", e)
            }
        }
    }

    override fun onConnectionFailed(connectionMode: Connection.Mode) {
        connectionStateCallback?.onConnectionFailed(connectionMode)
    }

    override fun onBytesReceived(buffer: ByteArray, bytes: Int) {}

    override fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {
        connected = false

        if (isUserDisconnect) {
            // User-initiated disconnect: tear down completely
            Log.d(TAG, "User-initiated disconnect from $hostName")
            isUserDisconnect = false
            cleanupConnection()
            connectionStateCallback?.onDisconnected(connectionMode, hostName)
        } else {
            // Unexpected disconnect: start auto-reconnect via the service
            Log.d(TAG, "Unexpected disconnect from $hostName, starting auto-reconnect")
            connectionStateCallback?.onReconnecting(connectionMode)

            appContext?.let { ctx ->
                try {
                    val intent = Intent(ctx, BluetoothConnectionService::class.java).apply {
                        action = BluetoothConnectionService.ACTION_RECONNECT
                        putExtra(BluetoothConnectionService.EXTRA_MAC_ADDRESS, lastConnectedMacAddress)
                    }
                    ctx.startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start reconnect service", e)
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
     */
    fun registerBluetoothHID(context: Context) {
        appContext = context.applicationContext
        if (btConn != null) return

        CoroutineScope(Dispatchers.IO).launch {
            btConn = BluetoothConnection(context, this@ConnectionManager)
        }
    }

    /**
     * Connect in bluetooth mode
     */
    fun connectBluetooth(macAddress: String) {
        lastConnectedMacAddress = macAddress
        isUserDisconnect = false
        connectionStateCallback?.onConnectionInitiated(Connection.Mode.BLUETOOTH)
        btConn?.connect(macAddress)
    }

    /**
     * User-initiated disconnect. Stops the foreground service and tears down the connection.
     */
    fun disconnectByUser() {
        isUserDisconnect = true
        Log.d(TAG, "User disconnect requested")

        // Clear saved device so we don't auto-connect next launch
        clearLastConnectedDevice()

        // Stop the connection service
        appContext?.let { ctx ->
            try {
                val intent = Intent(ctx, BluetoothConnectionService::class.java).apply {
                    action = BluetoothConnectionService.ACTION_STOP
                }
                ctx.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop connection service", e)
            }
        }

        disconnect()
    }

    /**
     * Internal disconnect — closes the BT connection but doesn't stop the service
     */
    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            connected = false
            btConn?.close()
            btConn = null
        }
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
            // Clean up old connection if it exists
            try {
                btConn?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing old connection", e)
            }
            btConn = null

            // Re-register HID profile and connect
            btConn = BluetoothConnection(context.applicationContext, this@ConnectionManager)
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

    // --- Persistence helpers for auto-connect ---

    private fun saveLastConnectedDevice() {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_MAC, lastConnectedMacAddress)
                .putString(KEY_LAST_NAME, lastConnectedDeviceName)
                .apply()
            Log.d(TAG, "Saved last device: $lastConnectedDeviceName ($lastConnectedMacAddress)")
        }
    }

    private fun clearLastConnectedDevice() {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_LAST_MAC)
                .remove(KEY_LAST_NAME)
                .apply()
            Log.d(TAG, "Cleared saved last device")
        }
    }

    /**
     * Returns the last connected device (name, mac) or null if none saved.
     */
    fun getLastConnectedDevice(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_LAST_MAC, null) ?: return null
        val name = prefs.getString(KEY_LAST_NAME, null) ?: "Unknown"
        return Pair(name, mac)
    }
}