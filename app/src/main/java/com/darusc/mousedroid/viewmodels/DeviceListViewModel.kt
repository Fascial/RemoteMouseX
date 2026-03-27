package com.darusc.mousedroid.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.darusc.mousedroid.networking.Connection
import com.darusc.mousedroid.networking.ConnectionManager

/**
 * @param devices The list of bluetooth devices
 */
class DeviceListViewModel(
    private val devices: List<Pair<String, String>>
): BaseViewModel<DeviceListViewModel.State, DeviceListViewModel.Event>(State(emptyList())) {

    sealed class Event: BaseViewModel.Event()
    data class State(val devices: List<Pair<String, String>>): BaseViewModel.State()

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

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onDeviceClick(context: Context, name: String, address: String) {
        connectionManager.connectBluetooth(address)
    }
}