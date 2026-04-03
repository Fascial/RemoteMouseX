package com.automouse.networking

import android.content.Context
import com.automouse.mkinput.InputEvent
import com.automouse.networking.bluetooth.BluetoothConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnectionManager private constructor() : Connection.Listener {

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
}