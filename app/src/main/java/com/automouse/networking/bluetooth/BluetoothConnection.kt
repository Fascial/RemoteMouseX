package com.automouse.networking.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.automouse.mkinput.InputEvent
import com.automouse.networking.Connection
import com.automouse.networking.toHIDReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

@SuppressLint("MissingPermission") // For BLUETOOTH_CONNECT permission
class BluetoothConnection(
    private val context: Context,
    private val listener: Listener
) : Connection() {

    private var isClosing = false
    private var connectionEstablished = false

    private val appRegisteredState = MutableStateFlow(false)
    private var originalBluetoothName: String? = null

    private val bluetoothAdapterWrapper = BluetoothAdapterWrapper.getInstance()!!
    private val bluetoothAdapter: BluetoothAdapter = bluetoothAdapterWrapper.adapter

    /**
     * The device that sends the reports
     */
    private var bluetoothHIDDevice: BluetoothHidDevice? = null

    /**
     * The device reports will be sent to
     */
    private var bluetoothHostDevice: BluetoothDevice? = null

    /**
     * SDP settings used for registering the app with the bluetooth HID device
     */
    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "Mousedroid", "Android HID", "Darusc Inc",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HID_REPORT_DESC
    )

    /**
     * QOS settings used for registering the app with the bluetooth HID device
     * Mathematically matched to valid strict SDP specs
     */
    private val qos = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
    )

    private val callback: BluetoothHidDevice.Callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)

            val hostname = bluetoothHostDevice?.name ?: "Unknown"
            bluetoothHostDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null

            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {}

                BluetoothProfile.STATE_CONNECTED -> {
                    connectionEstablished = true
                    listener.onConnected(Mode.BLUETOOTH, bluetoothHostDevice?.name ?: "Unknown device")
                }

                BluetoothProfile.STATE_DISCONNECTING -> {}

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (isClosing) {
                        cleanupProxy()
                    } else {
                        if (connectionEstablished) {
                            listener.onDisconnected(Mode.BLUETOOTH, hostname)
                            connectionEstablished = false
                        } else {
                            listener.onConnectionFailed(Mode.BLUETOOTH)
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            device?.let { bluetoothHIDDevice?.reportError(it, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            device?.let { bluetoothHIDDevice?.reportError(it, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)

            if (registered) {
                appRegisteredState.value = true
            } else {
                appRegisteredState.value = false
            }
        }
    }

    /**
     * Non blocking queue of reports to be sent over the bluetooth connection
     */
    private val reportChannel = Channel<Array<HIDReport>>(Channel.UNLIMITED)
    private val sendReportJob = CoroutineScope(Dispatchers.IO).launch {
        for (reports in reportChannel) {
            for (report in reports) {
                if (bluetoothHostDevice == null || bluetoothHIDDevice == null) {
                    continue
                }
                
                bluetoothHIDDevice?.sendReport(bluetoothHostDevice, report.id, report.bytes)
                
                // Add delay for control types to ensure proper delivery
                when (report) {
                    is KeyboardReport -> delay(2)
                    is MediaReport -> delay(5)
                    else -> {}
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHIDDevice = proxy as BluetoothHidDevice
                // Unregister first to clear "ghost states" caused by
                // incorrect closing
                bluetoothHIDDevice?.unregisterApp()
                applyCacheBustingName()
                bluetoothHIDDevice!!.registerApp(
                    sdp,
                    qos,
                    qos,
                    Executors.newSingleThreadExecutor(),
                    callback
                )
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            bluetoothHIDDevice = null
        }
    }

    init {
        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    override fun send(event: InputEvent) {
        val reports = event.toHIDReport()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reportChannel.send(reports)
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        if (isClosing) {
            return
        }
        isClosing = true

        sendReportJob.cancel()
        reportChannel.close()

        if (bluetoothHostDevice != null && bluetoothHIDDevice != null) {
            val disconnected = bluetoothHIDDevice?.disconnect(bluetoothHostDevice)
            if (disconnected == false) {
                cleanupProxy()
            }
        } else {
            cleanupProxy()
        }
    }

    /**
     * Connect to a bluetooth device
     */
    fun connect(hostMacAddress: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if(!isClosing && bluetoothHostDevice == null) {
                // Wait for the Bluetooth stack to fully register the HID SDP Profile
                val registered = withTimeoutOrNull(10000) {
                    appRegisteredState.first { it }
                }

                if (registered == true) {
                    try {
                        val target = bluetoothAdapter.getRemoteDevice(hostMacAddress)
                        
                        // Active Bonding Enforcement: Natively force binding if not already paired
                        if (target.bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                            val filter = android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                            val receiver = object : android.content.BroadcastReceiver() {
                                @android.annotation.SuppressLint("MissingPermission")
                                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                                    val action = intent.action
                                    @Suppress("DEPRECATION")
                                    val device: android.bluetooth.BluetoothDevice? = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)

                                    if (action == android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED && device?.address == target.address) {
                                        val bondState = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, android.bluetooth.BluetoothDevice.ERROR)

                                        if (bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                                            context.applicationContext.unregisterReceiver(this)
                                            bluetoothHIDDevice?.connect(target)
                                        } else if (bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                                            context.applicationContext.unregisterReceiver(this)
                                            listener.onConnectionFailed(Mode.BLUETOOTH)
                                        }
                                    }
                                }
                            }
                            
                            context.applicationContext.registerReceiver(receiver, filter)
                            target.createBond()
                        } else {
                            bluetoothHIDDevice?.connect(target)
                        }
                    } catch (e: IllegalArgumentException) {
                        listener.onConnectionFailed(Mode.BLUETOOTH)
                    }
                } else {
                    listener.onConnectionFailed(Mode.BLUETOOTH)
                }
            }
        }
    }

    /**
     * Unregisters the HID app and closes the HID_DEVICE profile proxy
     */
    private fun cleanupProxy() {
        bluetoothHIDDevice?.unregisterApp()
        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHIDDevice)
        bluetoothHIDDevice = null
        restoreOriginalName()
    }

    @SuppressLint("MissingPermission")
    private fun applyCacheBustingName() {
        try {
            if (originalBluetoothName == null) {
                originalBluetoothName = bluetoothAdapter.name
            }
            val randomSuffix = java.util.UUID.randomUUID().toString().substring(0, 4).uppercase()
            bluetoothAdapter.name = "${originalBluetoothName}_$randomSuffix"
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreOriginalName() {
        try {
            originalBluetoothName?.let {
                bluetoothAdapter.name = it
            }
        } catch (_: SecurityException) {
        }
    }
}