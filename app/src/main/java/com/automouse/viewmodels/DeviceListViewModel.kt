package com.automouse.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.automouse.networking.Connection
import com.automouse.networking.ConnectionManager

/**
 * @param devices The list of bluetooth devices
 */
class DeviceListViewModel(
    private val devices: List<Pair<String, String>>
): BaseViewModel<DeviceListViewModel.State, DeviceListViewModel.Event>(State(emptyList())) {

    sealed class Event: BaseViewModel.Event()
    data class State(val devices: List<Pair<String, String>>, val isScanning: Boolean = false): BaseViewModel.State()

    private val connectionManager = ConnectionManager.getInstance()

    class Factory: ViewModelProvider.Factory {

        private val devices: List<Pair<String, String>>

        /**
         * Create the viewmodel for bluetooth mode.
         * @param devices The list of paired bluetooth devices
         */
        @SuppressLint("MissingPermission")
        constructor(devices: Set<BluetoothDevice>) {
            this.devices = devices.map {
                Pair(it.name?: "Unknown", it.address)
            }
        }

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if(modelClass.isAssignableFrom(DeviceListViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return DeviceListViewModel(devices) as T
            }
            throw IllegalArgumentException("Unknown viewmodel class")
        }
    }

    init {
        setState(State(devices))
    }

    fun addDevice(name: String, address: String) {
        val currentList = state.value.devices.toMutableList()
        if (currentList.none { it.second == address }) {
            currentList.add(Pair(name, address))
            setState(State(currentList, state.value.isScanning))
        }
    }

    @SuppressLint("MissingPermission")
    fun loadPairedDevices() {
        val adapter = com.automouse.networking.bluetooth.BluetoothAdapterWrapper.getInstance()
        if (adapter == null) {
            android.util.Log.e("DeviceListViewModel", "BluetoothAdapter not initialized")
            return
        }

        val pairedDevices = adapter.pairedDevices
        android.util.Log.d("DeviceListViewModel", "Loaded ${pairedDevices.size} paired devices")
        
        val deviceList = pairedDevices.map {
            Pair(it.name ?: "Unknown", it.address)
        }
        
        // Always update state, even if list is empty
        setState(State(deviceList, state.value.isScanning))
    }

    fun resetForScanning() {
        setState(State(emptyList(), isScanning = true))
    }

    fun startScanning() {
        // Start scanning WITHOUT clearing existing devices
        setState(State(state.value.devices, isScanning = true))
    }

    fun onScanFinished() {
        setState(State(state.value.devices, isScanning = false))
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onDeviceClick(context: Context, name: String, address: String) {
        // Use unified workflow to ensure HID is properly registered
        // This is the same path used by auto-reconnect
        connectionManager.initiateConnection(context, address)
    }
}