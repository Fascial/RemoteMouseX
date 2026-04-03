package com.darusc.mousedroid.networking.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.Connection
import com.darusc.mousedroid.networking.toHIDReport
import com.darusc.mousedroid.helpers.DebugLogger
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
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d("Mousedroid", "Connecting...")
                    DebugLogger.log("Device Proxy connecting...")
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("Mousedroid", "Connected!")
                    DebugLogger.log("Device Proxy CONNECTED to ${bluetoothHostDevice?.name}")
                    listener.onConnected(Mode.BLUETOOTH, bluetoothHostDevice?.name ?: "Unknown device")

                    // Send a battery report after connecting
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    Log.d("Mousedroid", "Disconnecting...")
                    DebugLogger.log("Device Proxy disconnecting...")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    DebugLogger.log("Device Proxy DISCONNECTED. isClosing=$isClosing")
                    if (isClosing) {
                        cleanupProxy()
                    } else {
                        if (connectionEstablished) {
                            // Host turned off or went out of range after the connection
                            // was established. Instant disconnect
                            Log.d("Mousedroid", "Active session lost. Disconnecting instantly.")
                            listener.onDisconnected(Mode.BLUETOOTH, hostname)
                            connectionEstablished = false
                        } else {
                            // Connection failed
                            Log.d("Mousedroid", "Connection to $hostname failed")
                            listener.onConnectionFailed(Mode.BLUETOOTH)
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            DebugLogger.log("Host requested onGetReport (type: $type, id: $id)")
            device?.let { bluetoothHIDDevice?.reportError(it, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            DebugLogger.log("Host pushed onSetReport (type: $type, id: $id)")
            device?.let { bluetoothHIDDevice?.reportError(it, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)

            if (registered) {
                // HID service ready
                appRegisteredState.value = true
                Log.d("Mousedroid", "App registered successfully.")
                DebugLogger.log("System SDP: App registered successfully as MOUSE")
            } else {
                appRegisteredState.value = false
                Log.e("Mousedroid", "Failed to register app. Check permissions or device compatibility.")
                DebugLogger.log("System SDP: App registration FAILED")
            }
        }
    }

    /**
     * Non blocking queue of reports to be sent over the bluetooth connection
     */
    private var mediaReportCount = 0
    private val reportChannel = Channel<Array<HIDReport>>(Channel.UNLIMITED)
    private val sendReportJob = CoroutineScope(Dispatchers.IO).launch {
        for (reports in reportChannel) {
            Log.d("Mousedroid", "Processing array of ${reports.size} reports")
            for ((index, report) in reports.withIndex()) {
                if (bluetoothHostDevice == null) {
                    Log.w("Mousedroid", "Cannot send ${report::class.simpleName} - not connected to host device")
                    continue
                }
                if (bluetoothHIDDevice == null) {
                    Log.w("Mousedroid", "Cannot send ${report::class.simpleName} - HID device not initialized")
                    continue
                }
                
                // Log detailed info for media reports
                if (report is MediaReport) {
                    mediaReportCount++
                    val bytesStr = report.bytes.joinToString(", ") { byte -> 
                        "0x" + (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    }
                    val isRelease = report.bytes[0] == 0.toByte()
                    val eventType = if (isRelease) "RELEASE" else "PRESS"
                    Log.d("Mousedroid", "[$mediaReportCount] Sending MediaReport $eventType (ID: ${report.id}): bytes=[$bytesStr]")
                }
                
                val result = bluetoothHIDDevice?.sendReport(bluetoothHostDevice, report.id, report.bytes)
                if (result == false) {
                    Log.e("Mousedroid", "FAILED to send ${report::class.simpleName} (ID: ${report.id}), bytes: ${report.bytes.joinToString(", ") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }}")
                } else if (result == true) {
                    if (report is MediaReport) {
                        Log.d("Mousedroid", "Successfully sent MediaReport")
                    }
                }
                
                // Add delay for all control types to ensure proper delivery
                when (report) {
                    is KeyboardReport -> delay(2)
                    is MediaReport -> delay(5)  // Slightly longer delay for media to ensure proper processing
                    else -> {}
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            DebugLogger.log("onServiceConnected FIRED! profile=$profile")
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHIDDevice = proxy as BluetoothHidDevice
                // Unregister first to clear "ghost states" caused by
                // incorrect closing
                bluetoothHIDDevice?.unregisterApp()
                applyCacheBustingName()
                DebugLogger.log("Initiating SDP Registration with QoS...")
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
            DebugLogger.log("onServiceDisconnected FIRED! profile=$profile")
            bluetoothHIDDevice = null
        }
    }

    init {
        DebugLogger.log("BluetoothConnection INSTANTIATED.")
        val success = bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        DebugLogger.log("getProfileProxy (Activity Context) called. Result: $success")
    }

    override fun send(event: InputEvent) {
        val reports = event.toHIDReport()
        // Use send() instead of trySend() to ensure reports are queued even under load
        // This is important for consistent mouse and keyboard input
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reportChannel.send(reports)
            } catch (e: Exception) {
                Log.e("Mousedroid", "Failed to send HID report: ${e.message}")
            }
        }
    }

    override fun close() {
        if (isClosing) {
            return
        }
        isClosing = true

        Log.d("Mousedroid", "Cleaning up Bluetooth connection...")

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
                        DebugLogger.log("Evaluating target bond state: ${target.bondState}")
                        if (target.bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                            android.util.Log.d("Mousedroid", "Target is un-paired. Initiating createBond().")
                            DebugLogger.log("Bond is BOND_NONE. Calling target.createBond()...")
                            
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
                                            android.util.Log.d("Mousedroid", "Bonding successful. Triggering connect().")
                                            context.applicationContext.unregisterReceiver(this)
                                            bluetoothHIDDevice?.connect(target)
                                        } else if (bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                                            android.util.Log.e("Mousedroid", "Bonding failed or was rejected.")
                                            context.applicationContext.unregisterReceiver(this)
                                            listener.onConnectionFailed(Mode.BLUETOOTH)
                                        }
                                    }
                                }
                            }
                            
                            context.applicationContext.registerReceiver(receiver, filter)
                            target.createBond()
                        } else {
                            DebugLogger.log("Attempting native proxy connection to $hostMacAddress")
                            bluetoothHIDDevice?.connect(target)
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.d("Mousedroid", "Target device not found or invalid MAC")
                        listener.onConnectionFailed(Mode.BLUETOOTH)
                    }
                } else {
                    DebugLogger.log("CRITICAL TIMEOUT: 10s elapsed waiting for HID Profile SDP registration!")
                    Log.e("Mousedroid", "Timed out waiting for HID Profile Registration.")
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
            Log.d("Mousedroid", "Changed Bluetooth name to ${bluetoothAdapter.name} for cache busting")
        } catch (e: SecurityException) {
            Log.e("Mousedroid", "Missing BLUETOOTH_CONNECT permission. Skipping cache busting rename.", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreOriginalName() {
        try {
            originalBluetoothName?.let {
                bluetoothAdapter.name = it
                Log.d("Mousedroid", "Restored original Bluetooth name: $it")
            }
        } catch (e: SecurityException) {
            Log.e("Mousedroid", "Missing BLUETOOTH_CONNECT permission. Could not restore original name.", e)
        }
    }
}