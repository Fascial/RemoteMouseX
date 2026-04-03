package com.darusc.mousedroid.viewmodels

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.lifecycle.viewModelScope
import com.darusc.mousedroid.getDeviceDetails
import com.darusc.mousedroid.networking.Connection
import com.darusc.mousedroid.networking.ConnectionManager
import com.darusc.mousedroid.networking.bluetooth.BluetoothAdapterWrapper
import kotlinx.coroutines.launch

class ConnectionViewModel :
    BaseViewModel<ConnectionViewModel.State, ConnectionViewModel.Event>(State.Idle),
    ConnectionManager.ConnectionStateCallback {

    sealed class State : BaseViewModel.State() {
        object Idle : State()
        data class Connecting(val message: String) : State()
        data class Connected(val connectionMode: Connection.Mode, val hostName: String) : State()
    }

    sealed class Event : BaseViewModel.Event() {
        data class Navigate(@IdRes val id: Int) : Event()
        object NavigateToInput : Event()
        object NavigateToMain : Event()
        data class NavigateToDeviceList(val mode: Connection.Mode, val isPairing: Boolean = false) : Event()

        object EnableBluetooth : Event()

        data class ConnectionFailed(val connectionMode: Connection.Mode) : Event()
        data class ConnectionDisconnected(val connectionMode: Connection.Mode, val hostName: String) : Event()
    }

    private val connectionManager = ConnectionManager.getInstance(this)

    override fun onConnectionInitiated(mode: Connection.Mode) {
        if (state.value is State.Idle) {
            setState(State.Connecting("Registering HID Profile..."))
        }
    }

    override fun onConnectionSuccessful(connectionMode: Connection.Mode, hostName: String) {
        setState(State.Connected(connectionMode, hostName))
        sendEvent(Event.NavigateToInput)
    }

    override fun onConnectionFailed(connectionMode: Connection.Mode) {
        setState(State.Idle)
        sendEvent(Event.ConnectionFailed(connectionMode))
    }

    override fun onDisconnected(connectionMode: Connection.Mode, hostName: String) {
        // Hardware link was lost (e.g host device's bluetooth was turned off)
        setState(State.Idle)
        sendEvent(Event.ConnectionDisconnected(connectionMode, hostName))
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
     * Should be called only when the user requests a manual disconnect
     */
    fun disconnect() {
        viewModelScope.launch {
            connectionManager.disconnect()
            setState(State.Idle)
            sendEvent(Event.NavigateToMain)
        }
    }
}