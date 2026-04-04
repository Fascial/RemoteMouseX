package com.automouse.networking

import com.automouse.mkinput.InputEvent

abstract class Connection {

    enum class Mode {
        BLUETOOTH
    }

    // Maximum size of a packet for socket based connections
    open val maxPacketSize: Int = 0

    class ConnectionFailedException(host: String) : Exception("Connection to $host failed!")

    interface Listener {
        fun onConnected(connectionMode: Mode, hostName: String)
        fun onConnectionFailed(connectionMode: Mode)
        fun onBytesReceived(buffer: ByteArray, bytes: Int)
        fun onDisconnected(connectionMode: Mode, hostName: String)
    }

    abstract fun send(event: InputEvent)
    abstract fun close()
    abstract fun isClosed(): Boolean
}