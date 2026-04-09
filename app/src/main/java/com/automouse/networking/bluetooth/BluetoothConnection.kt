package com.automouse.networking.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.automouse.mkinput.InputEvent
import com.automouse.networking.Connection
import com.automouse.networking.toHIDReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * HID Profile lifecycle states - replaces hardcoded delays with state-driven transitions
 */
sealed class HidState {
    object Initializing : HidState()           // Waiting for profile proxy
    object ProxyConnected : HidState()         // Proxy connected, ready to unregister
    object Unregistering : HidState()          // Unregistration in progress
    object Ready : HidState()                  // Previously unregistered, ready to register
    object Registering : HidState()            // Registration in progress
    object Registered : HidState()             // Successfully registered
    object Disconnecting : HidState()          // Disconnecting device
    object Closing : HidState()                // Cleanup in progress
    class Error(val message: String) : HidState()  // Error state
}

@SuppressLint("MissingPermission") // For BLUETOOTH_CONNECT permission
class BluetoothConnection(
    private val context: Context,
    private val listener: Listener
) : Connection() {

    companion object {
        private const val TAG = "BluetoothConnection"
        private const val REGISTRATION_TIMEOUT_MS = 5000L
        private const val BOND_TIMEOUT_MS = 30000L
    }

    // State tracking - replaces all delay() calls with state-driven transitions
    internal val hidState = MutableStateFlow<HidState>(HidState.Initializing)
    internal val appRegisteredState = MutableStateFlow(false)
    
    private var isClosing = false
    private var isClosed = false
    private var connectionEstablished = false
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
     * Shared executor for HID profile
     */
    private var hidExecutor: ExecutorService? = null

    /**
     * Track the bond state receiver to ensure proper cleanup
     */
    private var activeBondReceiver: BroadcastReceiver? = null

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

    // Scope for managing coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val callback: BluetoothHidDevice.Callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)

            val hostname = bluetoothHostDevice?.name ?: "Unknown"
            bluetoothHostDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null

            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "Device connecting...")
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    connectionEstablished = true
                    Log.d(TAG, "Connected to ${bluetoothHostDevice?.name}")
                    if (!isClosing && !isClosed) {
                        listener.onConnected(Mode.BLUETOOTH, bluetoothHostDevice?.name ?: "Unknown device")
                    } else {
                        Log.d(TAG, "Connection ignored because connection is closing")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    Log.d(TAG, "Device disconnecting...")
                    hidState.value = HidState.Disconnecting
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected")
                    if (isClosing) {
                        Log.d(TAG, "Disconnect confirmed during close - proceeding to cleanup")
                        connectionScope.launch {
                            cleanupProxy()
                        }
                    } else {
                        if (connectionEstablished) {
                            Log.d(TAG, "Unexpected disconnect from $hostname")
                            listener.onDisconnected(Mode.BLUETOOTH, hostname)
                            connectionEstablished = false
                        } else {
                            Log.d(TAG, "Connection failed")
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
            Log.d(TAG, "App registration status changed: $registered")
            appRegisteredState.value = registered
            
            // Update state machine based on registration status
            if (registered) {
                // Only transition to Registered if we're in Registering state
                if (hidState.value is HidState.Registering) {
                    hidState.value = HidState.Registered
                    Log.d(TAG, "HID profile successfully registered")
                }
            } else {
                // Registration lost, reset to Ready state for re-registration
                if (hidState.value is HidState.Registered) {
                    hidState.value = HidState.Ready
                    Log.w(TAG, "HID registration lost - will re-register on next attempt")
                }
            }
        }
    }

    /**
     * Non blocking queue of reports to be sent over the bluetooth connection
     */
    private val reportChannel = Channel<Array<HIDReport>>(Channel.UNLIMITED)
    private val sendReportJob = connectionScope.launch {
        try {
            for (reports in reportChannel) {
                try {
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
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending report", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Report channel error", e)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE && !isClosing) {
                Log.d(TAG, "HID profile service connected")
                bluetoothHIDDevice = proxy as BluetoothHidDevice
                hidState.value = HidState.ProxyConnected
                
                // Launch async unregistration + registration sequence
                connectionScope.launch {
                    try {
                        // Perform Active Unregistration before registering
                        if (!performActiveUnregistration()) {
                            Log.w(TAG, "Active unregistration failed, attempting registration anyway")
                        }
                        
                        // Now perform registration
                        performRegistration()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in profile initialization", e)
                        hidState.value = HidState.Error("Profile initialization failed: ${e.message}")
                        appRegisteredState.value = false
                    }
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID profile service disconnected")
            bluetoothHIDDevice = null
            appRegisteredState.value = false
            hidState.value = HidState.Initializing
        }
    }
    
    /**
     * Active Unregistration: Safely unregisters app if currently registered
     * Returns true if successful, false if already unregistered or error
     */
    private suspend fun performActiveUnregistration(): Boolean {
        return withTimeoutOrNull(2000) {
            try {
                hidState.value = HidState.Unregistering
                Log.d(TAG, "Attempting active unregistration...")
                
                // Check if already registered before attempting unregister
                val wasRegistered = appRegisteredState.value
                if (!wasRegistered) {
                    Log.d(TAG, "App not registered, skipping unregistration")
                    hidState.value = HidState.Ready
                    return@withTimeoutOrNull true
                }
                
                try {
                    bluetoothHIDDevice?.unregisterApp()
                    Log.d(TAG, "Unregister call completed")
                } catch (e: Exception) {
                    Log.e(TAG, "UnregisterApp failed (may be normal if stale)", e)
                    // Continue - system may have already unregistered
                }
                
                // Wait for the system to process the unregistration
                // ONLY while monitoring the state, not a blind delay
                val unregistered = withTimeoutOrNull(2000) {
                    while (appRegisteredState.value) {
                        yield()  // Cooperatively relinquish control
                    }
                    true
                }
                
                if (unregistered == true) {
                    Log.d(TAG, "Active unregistration confirmed")
                    hidState.value = HidState.Ready
                    return@withTimeoutOrNull true
                } else {
                    Log.w(TAG, "Unregistration confirmation timeout (will reset state on registration attempt)")
                    // Still proceed - state will update when callback fires
                    hidState.value = HidState.Ready
                    return@withTimeoutOrNull false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during unregistration", e)
                hidState.value = HidState.Ready
                return@withTimeoutOrNull false
            }
        } ?: false
    }
    
    /**
     * Performs HID profile registration with proper state management
     */
    private suspend fun performRegistration() {
        try {
            // Reset state machine if in error state
            if (hidState.value is HidState.Error) {
                Log.d(TAG, "Recovering from error state: ${(hidState.value as HidState.Error).message}")
                hidState.value = HidState.ProxyConnected
            }
            
            hidState.value = HidState.Registering
            Log.d(TAG, "Starting HID profile registration...")
            
            applyCacheBustingName()
            
            // Create executor if needed
            if (hidExecutor == null || hidExecutor!!.isShutdown) {
                hidExecutor = Executors.newSingleThreadExecutor()
                Log.d(TAG, "Created new HID executor")
            }
            
            if (bluetoothHIDDevice == null) {
                Log.e(TAG, "BluetoothHIDDevice is null, registration impossible")
                hidState.value = HidState.Error("HID device is null")
                appRegisteredState.value = false
                return
            }
            
            try {
                bluetoothHIDDevice!!.registerApp(sdp, qos, qos, hidExecutor!!, callback)
                Log.d(TAG, "RegisterApp call submitted")
                
                // Registration should trigger onAppStatusChanged callback within 5 seconds
                val registered = withTimeoutOrNull(5000) {
                    while (hidState.value is HidState.Registering) {
                        yield()
                    }
                    hidState.value is HidState.Registered
                }
                
                if (registered == true) {
                    Log.d(TAG, "Registration successful")
                } else if (registered == false) {
                    Log.e(TAG, "Registration failed - state did not transition to Registered")
                } else {
                    Log.e(TAG, "Registration timeout - callback did not fire")
                    hidState.value = HidState.Error("Registration timeout")
                    appRegisteredState.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling registerApp", e)
                hidState.value = HidState.Error("RegisterApp failed: ${e.message}")
                appRegisteredState.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during registration setup", e)
            hidState.value = HidState.Error("Registration setup failed: ${e.message}")
            appRegisteredState.value = false
        }
    }

    init {
        Log.d(TAG, "Initializing BluetoothConnection")
        try {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting profile proxy", e)
        }
    }

    override fun send(event: InputEvent) {
        val reports = event.toHIDReport()
        connectionScope.launch {
            try {
                if (!reportChannel.isClosedForSend) {
                    reportChannel.send(reports)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to report channel", e)
            }
        }
    }

    override fun isClosed(): Boolean {
        return isClosed
    }

    override fun close() {
        if (isClosing || isClosed) {
            Log.d(TAG, "Already closing or closed")
            return
        }
        isClosing = true
        hidState.value = HidState.Closing
        Log.d(TAG, "Closing BluetoothConnection - starting cleanup sequence")

        connectionScope.launch {
            try {
                // 1. Stop accepting new reports
                sendReportJob.cancel()
                Log.d(TAG, "Send job cancelled")
            
            try {
                reportChannel.close()
                Log.d(TAG, "Report channel closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing channel", e)
            }
            
            // 2. Clean up bond receiver
            activeBondReceiver?.let { receiver ->
                try {
                    context.applicationContext.unregisterReceiver(receiver)
                    Log.d(TAG, "Bond receiver unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering bond receiver", e)
                }
                activeBondReceiver = null
            }
            
            // 3. Disconnect device - state-driven wait
            if (bluetoothHostDevice != null && bluetoothHIDDevice != null) {
                try {
                    Log.d(TAG, "Disconnecting from device")
                    val disconnected = bluetoothHIDDevice?.disconnect(bluetoothHostDevice)
                    if (disconnected == true) {
                        Log.d(TAG, "Disconnect initiated, waiting for confirmation")
                        // Wait for STATE_DISCONNECTED callback with timeout
                        val disconnectConfirmed = withTimeoutOrNull(2000) {
                            while (bluetoothHostDevice != null) {
                                yield()  // Non-blocking wait
                            }
                            true
                        }
                        if (disconnectConfirmed == true) {
                            Log.d(TAG, "Disconnect confirmed")
                        } else {
                            Log.w(TAG, "Disconnect confirmation timeout, continuing cleanup")
                        }
                    } else {
                        Log.d(TAG, "Disconnect returned false, proceeding with cleanup")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting", e)
                }
            } else {
                Log.d(TAG, "No device connected, skipping disconnect")
            }
            
            // 4. Cleanup proxy (unregister + close) - THIS MUST COMPLETE BEFORE RETURNING
            cleanupProxy()
            
            // 5. Shutdown executor
            hidExecutor?.let {
                try {
                    it.shutdownNow()
                    Log.d(TAG, "Executor shut down")
                } catch (e: Exception) {
                    Log.e(TAG, "Error shutting down executor", e)
                }
            }
            hidExecutor = null
            
            Log.d(TAG, "Close cleanup sequence completed, cancelling scope")
        } catch (e: Exception) {
            Log.e(TAG, "Error during close", e)
        } finally {
            // 6. Cancel scope and mark closed - MUST be in finally block
            connectionScope.cancel()
            isClosed = true
            Log.d(TAG, "Close completed successfully, isClosed=true")
        }
        }
    }

    /**
     * Connect to a bluetooth device - State-locked implementation
     * This is called asynchronously but internally awaits registration
     */
    fun connect(hostMacAddress: String) {
        connectionScope.launch {
            try {
                if (isClosing) {
                    Log.d(TAG, "Already closing, cannot connect")
                    listener.onConnectionFailed(Mode.BLUETOOTH)
                    return@launch
                }
                
                if (bluetoothHostDevice != null) {
                    Log.d(TAG, "Already connected or connecting")
                    return@launch
                }
                
                Log.d(TAG, "Connecting to $hostMacAddress")
                
                // Await HID registration - replaces delay() with state-driven wait
                if (!awaitRegistration(REGISTRATION_TIMEOUT_MS)) {
                    Log.e(TAG, "HID registration failed or timed out after ${REGISTRATION_TIMEOUT_MS}ms")
                    listener.onConnectionFailed(Mode.BLUETOOTH)
                    return@launch
                }
                
                Log.d(TAG, "HID app registered, attempting connection")
                performConnection(hostMacAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Error in connect flow", e)
                listener.onConnectionFailed(Mode.BLUETOOTH)
            }
        }
    }
    
    /**
     * Awaits HID registration without blocking - replaces all delay() calls
     * Polls appRegisteredState or reacts to state changes
     */
    private suspend fun awaitRegistration(timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            Log.d(TAG, "Awaiting HID registration (timeout: ${timeoutMs}ms)...")
            
            // Fast path: already registered
            if (appRegisteredState.value) {
                Log.d(TAG, "Already registered, proceeding")
                return@withTimeoutOrNull true
            }
            
            // Slow path: wait for registration state change
            // This is non-blocking and cooperates with coroutine scheduler
            while (!appRegisteredState.value) {
                if (isClosing) {
                    Log.d(TAG, "Connection cancelled - device closing")
                    return@withTimeoutOrNull false
                }
                yield()  // Let other coroutines run
            }
            
            Log.d(TAG, "HID registration confirmed")
            true
        } ?: run {
            Log.e(TAG, "HID registration timeout after ${timeoutMs}ms")
            false
        }
    }
    
    /**
     * Performs the actual device connection - handles bonding if needed
     */
    private suspend fun performConnection(hostMacAddress: String) {
        try {
            val target = bluetoothAdapter.getRemoteDevice(hostMacAddress)
            
            when (target.bondState) {
                BluetoothDevice.BOND_NONE -> {
                    Log.d(TAG, "Device not bonded, initiating bonding")
                    performBonding(target)
                }
                BluetoothDevice.BOND_BONDING -> {
                    Log.d(TAG, "Device bonding already in progress, waiting...")
                    if (awaitBondingCompletion(target)) {
                        Log.d(TAG, "Bonding completed, connecting...")
                        bluetoothHIDDevice?.connect(target)
                    } else {
                        Log.e(TAG, "Bonding failed or timed out")
                        listener.onConnectionFailed(Mode.BLUETOOTH)
                    }
                }
                BluetoothDevice.BOND_BONDED -> {
                    Log.d(TAG, "Device already bonded, connecting")
                    bluetoothHIDDevice?.connect(target)
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid device address: $hostMacAddress", e)
            listener.onConnectionFailed(Mode.BLUETOOTH)
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection", e)
            listener.onConnectionFailed(Mode.BLUETOOTH)
        }
    }
    
    /**
     * Performs device bonding with state-driven waiting
     */
    private suspend fun performBonding(target: BluetoothDevice) {
        try {
            val filter = android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            var bondReceiver: BroadcastReceiver? = null
            
            bondReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    try {
                        if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            @Suppress("DEPRECATION")
                            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            
                            if (device?.address == target.address) {
                                val bondState = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE,
                                    BluetoothDevice.ERROR
                                )
                                
                                Log.d(TAG, "Bond state changed to: $bondState")
                                
                                if (bondState == BluetoothDevice.BOND_BONDED) {
                                    Log.d(TAG, "Bonding successful")
                                    // Don't unregister yet - let caller handle disconnect
                                    try {
                                        context.applicationContext.unregisterReceiver(this)
                                        activeBondReceiver = null
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error unregistering bond receiver", e)
                                    }
                                    bluetoothHIDDevice?.connect(target)
                                } else if (bondState == BluetoothDevice.BOND_NONE) {
                                    Log.d(TAG, "Bonding failed")
                                    try {
                                        context.applicationContext.unregisterReceiver(this)
                                        activeBondReceiver = null
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error unregistering bond receiver", e)
                                    }
                                    listener.onConnectionFailed(Mode.BLUETOOTH)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in bond receiver", e)
                    }
                }
            }
            
            activeBondReceiver = bondReceiver
            context.applicationContext.registerReceiver(bondReceiver, filter)
            Log.d(TAG, "Bond receiver registered, creating bond")
            target.createBond()
        } catch (e: Exception) {
            Log.e(TAG, "Error during bonding setup", e)
            listener.onConnectionFailed(Mode.BLUETOOTH)
        }
    }
    
    /**
     * Awaits bonding completion without blocking
     */
    private suspend fun awaitBondingCompletion(target: BluetoothDevice): Boolean {
        return withTimeoutOrNull(BOND_TIMEOUT_MS) {
            Log.d(TAG, "Awaiting bond completion...")
            while (target.bondState != BluetoothDevice.BOND_BONDED && 
                   target.bondState != BluetoothDevice.BOND_NONE) {
                yield()  // Non-blocking wait
            }
            
            if (target.bondState == BluetoothDevice.BOND_BONDED) {
                Log.d(TAG, "Bonding confirmed")
                true
            } else {
                Log.d(TAG, "Bonding failed - state is BOND_NONE")
                false
            }
        } ?: run {
            Log.e(TAG, "Bonding timeout after ${BOND_TIMEOUT_MS}ms")
            false
        }
    }

    /**
     * Unregisters the HID app and closes the HID_DEVICE profile proxy
     * No delays - all state-driven
     */
    private suspend fun cleanupProxy() {
        Log.d(TAG, "Cleaning up proxy")
        
        try {
            bluetoothHIDDevice?.unregisterApp()
            Log.d(TAG, "App unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering app", e)
        }
        
        try {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHIDDevice)
            Log.d(TAG, "Proxy closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing proxy", e)
        }
        
        bluetoothHIDDevice = null
        appRegisteredState.value = false
        
        try {
            restoreOriginalName()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring name", e)
        }
        
        Log.d(TAG, "Proxy cleanup completed")
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