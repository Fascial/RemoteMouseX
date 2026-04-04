package com.automouse.viewmodels

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.lifecycle.viewModelScope
import com.automouse.getDeviceDetails
import com.automouse.networking.Connection
import com.automouse.networking.ConnectionManager
import com.automouse.networking.bluetooth.BluetoothAdapterWrapper
import com.automouse.services.BluetoothConnectionService
import kotlinx.coroutines.launch

class ConnectionViewModel :
    BaseViewModel<ConnectionViewModel.State, ConnectionViewModel.Event>(State.Idle),
    ConnectionManager.ConnectionStateCallback {

    sealed class State : BaseViewModel.State() {
        object Idle : State()
        data class Connecting(val message: String) : State()
        data class Connected(val connectionMode: Connection.Mode, val hostName: String) : State()
        data class Reconnecting(val connectionMode: Connection.Mode) : State()
        data class AutoConnecting(val deviceName: String) : State()
    }

    sealed class Event : BaseViewModel.Event() {
        data class Navigate(@IdRes val id: Int) : Event()
        object NavigateToInput : Event()
        object NavigateToMain : Event()
        data class NavigateToDeviceList(val mode: Connection.Mode, val isPairing: Boolean = false) : Event()

        object EnableBluetooth : Event()

        data class ConnectionFailed(val connectionMode: Connection.Mode) : Event()
        data class ConnectionDisconnected(val connectionMode: Connection.Mode, val hostName: String) : Event()
        data class ReconnectFailed(val connectionMode: Connection.Mode) : Event()
    }

    private val connectionManager = ConnectionManager.getInstance(this)

    override fun onConnectionInitiated(mode: Connection.Mode) {
        if (state.value is State.Idle) {
            // Check if this is an auto-connect attempt
            if (connectionManager.isAutoConnecting()) {
                setState(State.AutoConnecting(connectionManager.lastConnectedDeviceName ?: "device"))
            } else {
                setState(State.Connecting("Registering HID Profile..."))
            }
        }
    }

    override fun onConnectionSuccessful(connectionMode: Connection.Mode, hostName: String) {
        setState(State.Connected(connectionMode, hostName))

        // Only navigate to input if we're not already there (i.e., not a reconnect)
        if (state.value !is State.Reconnecting) {
            sendEvent(Event.NavigateToInput)
        }
    }

    override fun onConnectionFailed(connectionMode: Connection.Mode) {
        setState(State.Idle)
        sendEvent(Event.ConnectionFailed(connectionMode))
    }

    override fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {
        // This is only called for user-initiated disconnects now
        setState(State.Idle)
        sendEvent(Event.ConnectionDisconnected(connectionMode, hostName))
        sendEvent(Event.NavigateToMain)
    }

    override fun onReconnecting(connectionMode: Connection.Mode) {
        // Connection dropped unexpectedly, auto-reconnect started
        setState(State.Reconnecting(connectionMode))
    }

    override fun onReconnectFailed(connectionMode: Connection.Mode) {
        // All retry attempts exhausted
        setState(State.Idle)
        sendEvent(Event.ReconnectFailed(connectionMode))
        sendEvent(Event.NavigateToMain)
    }

    /**
     * Call this from MainActivity or ConnectionFragment as early as possible
     * to register the HID Profile early and prevent race conditions.
     */
    fun initBluetoothEarlyRegistration(context: Context) {
        try {
            if (BluetoothAdapterWrapper.getInstance()?.isEnabled == true) {
                connectionManager.registerBluetoothHID(context)
            }
        } catch (e: SecurityException) {
            // Permission not granted yet, will be retrieved on user action
        }
    }

    /**
     * Start bluetooth mode. Either starts a bluetooth enable intent
     * or redirects to the bluetooth device list
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startBluetoothMode(context: Context, afterEnableIntent: Boolean = false) {
        if (afterEnableIntent || BluetoothAdapterWrapper.getInstance()?.isEnabled == true) {
            connectionManager.registerBluetoothHID(context)
            sendEvent(Event.NavigateToDeviceList(Connection.Mode.BLUETOOTH))
        } else {
            // Notify the fragment to start the bluetooth enable intent
            sendEvent(Event.EnableBluetooth)
        }
    }

    /**
     * Start pairing mode. Starts a bluetooth enable intent if needed,
     * registers the HID SDP profile globally, but stays on the Main UI
     * to await Host discovery.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startPairingMode(context: Context, afterEnableIntent: Boolean = false) {
        if (afterEnableIntent || BluetoothAdapterWrapper.getInstance()?.isEnabled == true) {
            connectionManager.registerBluetoothHID(context)
            sendEvent(Event.NavigateToDeviceList(Connection.Mode.BLUETOOTH, true))
        } else {
            sendEvent(Event.EnableBluetooth) // Notify to request Bluetooth turn-on
        }
    }

    /**
     * Start the foreground connection service after a successful connection.
     * Should be called from a fragment/activity that has a Context.
     */
    fun startConnectionService(context: Context, hostName: String) {
        try {
            val intent = Intent(context, BluetoothConnectionService::class.java).apply {
                action = BluetoothConnectionService.ACTION_START
                putExtra(BluetoothConnectionService.EXTRA_DEVICE_NAME, hostName)
                putExtra(BluetoothConnectionService.EXTRA_MAC_ADDRESS, connectionManager.lastConnectedMacAddress)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Non-fatal: connection still works, just no background persistence
        }
    }

    /**
     * Should be called only when the user requests a manual disconnect
     */
    fun disconnect() {
        viewModelScope.launch {
            connectionManager.disconnectByUser()
            setState(State.Idle)
            sendEvent(Event.NavigateToMain)
        }
    }

    /**
     * Called to notify that auto-connect is starting
     */
    fun notifyAutoConnectStart(deviceName: String) {
        setState(State.AutoConnecting(deviceName))
    }

    /**
     * Cancel auto-connect attempt
     */
    fun cancelAutoConnect() {
        viewModelScope.launch {
            connectionManager.cancelAutoConnect()
            setState(State.Idle)
        }
    }
}