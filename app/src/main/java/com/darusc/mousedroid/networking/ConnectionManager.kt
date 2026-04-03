package com.darusc.mousedroid.networking

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.bluetooth.BluetoothConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectionManager private constructor() : Connection.Listener {

    private val TAG = "Mousedroid"

    companion object {
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

    interface ConnectionStateCallback {
        fun onConnectionInitiated(mode: Connection.Mode) {}
        fun onConnectionSuccessful(connectionMode: Connection.Mode, hostName: String) {}
        fun onConnectionFailed(connectionMode: Connection.Mode) {}
        fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {}
    }

    private fun setConnectionStateCallback(connectionStateCallback: ConnectionStateCallback) {
        this.connectionStateCallback = connectionStateCallback
    }

    override fun onConnected(connectionMode: Connection.Mode, hostName: String) {
        connected = true
        connectionStateCallback?.onConnectionSuccessful(connectionMode, hostName)
    }

    override fun onConnectionFailed(connectionMode: Connection.Mode) {
        connectionStateCallback?.onConnectionFailed(connectionMode)
    }

    override fun onBytesReceived(buffer: ByteArray, bytes: Int) {}

    override fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {
        disconnect()
        connectionStateCallback?.onDisconnected(connectionMode, hostName)
    }

    /**
     * Register the bluetooth HID profile
     */
    fun registerBluetoothHID(context: Context) {
        if (btConn != null) return

        CoroutineScope(Dispatchers.IO).launch {
            btConn = BluetoothConnection(context, this@ConnectionManager)
        }
    }

    /**
     * Connect in bluetooth mode
     */
    fun connectBluetooth(macAddress: String) {
        connectionStateCallback?.onConnectionInitiated(Connection.Mode.BLUETOOTH)
        btConn?.connect(macAddress)
    }

    /**
     * Close active connection
     */
    fun disconnect() {
        CoroutineScope(Dispatchers.IO).launch {
            connected = false
            btConn?.close()
            btConn = null
        }
    }

    fun send(event: InputEvent, withCoroutine: Boolean = true) {
        val isConnected = connection != null && connected
        Log.d(TAG, "send() called: withCoroutine=$withCoroutine, connected=$isConnected, btConn=$btConn")
        
        if (!isConnected) {
            Log.w(TAG, "send() - Not connected! event=$event, connection=$connection, connected=$connected")
            return
        }
        
        when (withCoroutine) {
            false -> {
                Log.d(TAG, "Sending synchronously: $event")
                connection?.send(event)
            }
            true -> {
                Log.d(TAG, "Sending asynchronously: $event")
                CoroutineScope(Dispatchers.IO).launch { 
                    Log.d(TAG, "Async send executing: $event")
                    connection?.send(event) 
                }
            }
        }
    }

    fun isConnected(): Boolean {
        return connected && connection != null
    }
}