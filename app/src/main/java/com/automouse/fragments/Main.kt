package com.automouse.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GravityCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.automouse.MainActivity
import com.automouse.R
import com.automouse.adapters.DeviceAdapter
import com.automouse.databinding.FragmentMainBinding
import com.automouse.networking.Connection
import com.automouse.networking.ConnectionManager
import com.automouse.networking.bluetooth.BluetoothAdapterWrapper
import com.automouse.viewmodels.ConnectionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class Main : Fragment() {

    companion object {
        private const val TAG = "Main"
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
    }

    private lateinit var binding: FragmentMainBinding
    private lateinit var loadingPopup: PopupWindow

    private val viewModel: ConnectionViewModel by activityViewModels()
    private val connectionManager = ConnectionManager.getInstance()

    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter

    private val pairedDevices = ArrayList<Pair<String, String>>()
    /** MAC address → (display name, address); insertion order = discovery order */
    private val availableByMac = LinkedHashMap<String, Pair<String, String>>()
    private val availableDevices = ArrayList<Pair<String, String>>()

    private var isScanning = false
    private var receiverRegistered = false
    private var discoveryTimeoutJob: Job? = null

    @SuppressLint("MissingPermission")
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                viewModel.initBluetoothEarlyRegistration(requireContext())
                loadPairedDevices()
            } else {
                Toast.makeText(context, "Bluetooth is required.", Toast.LENGTH_SHORT).show()
            }
        }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            try {
                // Safety check: ignore events if fragment is not attached
                if (!isAdded || view == null) {
                    return
                }

                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND,
                    BluetoothDevice.ACTION_NAME_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { updateOrAddDevice(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stopScanning()
                    }
                }
            } catch (e: Exception) {
                // Fragment may have been destroyed, silently ignore
                android.util.Log.d("Main", "BroadcastReceiver error: ${e.message}")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_main, container, false)
        binding.lifecycleOwner = viewLifecycleOwner

        loadingPopup = PopupWindow(
            layoutInflater.inflate(R.layout.loading_fragment, null),
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT,
        )

        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.initBluetoothEarlyRegistration(requireContext())

        // Setup RecyclerViews
        val deviceClickListener = object : DeviceAdapter.OnItemClickListener {
            override fun onItemClick(name: String, address: String) {
                connectToDevice(address)
            }
            override fun onItemLongClick(position: Int) {}
        }

        pairedAdapter = DeviceAdapter(pairedDevices, deviceClickListener)
        availableAdapter = DeviceAdapter(availableDevices, deviceClickListener)

        binding.recyclerPaired.layoutManager = LinearLayoutManager(context)
        binding.recyclerPaired.adapter = pairedAdapter

        binding.recyclerAvailable.layoutManager = LinearLayoutManager(context)
        binding.recyclerAvailable.adapter = availableAdapter

        (activity as? MainActivity)?.setPermissionListener(object : MainActivity.PermissionResultListener {
            override fun onBluetoothPermissionsGranted() {
                if (!isAdded || view == null) return
                try {
                    if (!isScanning) startScanning()
                } catch (e: Exception) {
                    android.util.Log.d("Main", "Error starting scan after permissions: ${e.message}")
                }
            }
        })

        // Set up reactive refresh of paired devices (repeats every 500ms for first 5 seconds)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    var refreshCount = 0
                    while (refreshCount < 10 && isAdded && view != null) {  // Refresh 10 times = 5 seconds total
                        loadPairedDevices()
                        delay(500)
                        refreshCount++
                    }
                } catch (e: Exception) {
                    // Fragment may have been destroyed, silently ignore
                    android.util.Log.d("Main", "Refresh loop error: ${e.message}")
                }
            }
        }

        // Scan button
        binding.btnScan.setOnClickListener {
            if (isScanning) {
                stopScanning()
            } else {
                startScanning()
            }
        }

        // Drawer
        binding.btnOpenDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // Set up nav drawer with all modes
        binding.navigation.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.mode_touchpad, R.id.mode_keyboard, R.id.mode_automouse, R.id.mode_recorder -> {
                    if (connectionManager.isConnected()) {
                        // Navigate to input fragment, which handles sub-navigation
                        findNavController().navigate(R.id.action_main_to_touchpad)
                    } else {
                        Toast.makeText(context, "Connect to a device first", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.mode_connection -> {
                    // Already on connection page
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
                R.id.mode_disconnect -> {
                    if (connectionManager.isConnected()) {
                        viewModel.disconnect()
                    } else {
                        Toast.makeText(context, "Not connected", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.mode_view_logs -> {
                    showConnectionLogsDialog()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Update drawer header connection status
        updateDrawerConnectionStatus()

        // Observe viewmodel state and events
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect {
                        when (it) {
                            is ConnectionViewModel.State.Connecting -> {
                                if (!loadingPopup.isShowing) {
                                    loadingPopup.contentView.findViewById<TextView>(R.id.loadingMessage).text = it.message
                                    loadingPopup.contentView.findViewById<android.widget.Button>(R.id.cancelButton)?.visibility = View.GONE
                                    loadingPopup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
                                }
                            }
                            is ConnectionViewModel.State.Idle -> {
                                loadingPopup.dismiss()
                                updateDrawerConnectionStatus()
                            }
                            is ConnectionViewModel.State.Connected -> {
                                loadingPopup.dismiss()
                                updateDrawerConnectionStatus()
                            }
                            is ConnectionViewModel.State.Reconnecting -> {
                                loadingPopup.dismiss()
                                updateDrawerConnectionStatus()
                            }
                            is ConnectionViewModel.State.AutoConnecting -> {
                                if (!loadingPopup.isShowing) {
                                    val cancelBtn = loadingPopup.contentView.findViewById<android.widget.Button>(R.id.cancelButton)
                                    loadingPopup.contentView.findViewById<TextView>(R.id.loadingMessage).text = "Reconnecting to ${it.deviceName}..."
                                    cancelBtn?.visibility = View.VISIBLE
                                    cancelBtn?.setOnClickListener {
                                        viewModel.cancelAutoConnect()
                                    }
                                    loadingPopup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.events.collect {
                        when (it) {
                            is ConnectionViewModel.Event.NavigateToInput -> {
                                findNavController().navigate(R.id.action_main_to_touchpad)
                            }

                            is ConnectionViewModel.Event.NavigateToMain -> {}

                            is ConnectionViewModel.Event.ConnectionDisconnected -> {
                                updateDrawerConnectionStatus()
                                showPopupDialog(R.layout.connection_disconnected_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Disconnected from ${it.hostName}"
                                    findViewById<TextView>(R.id.description).text = "You manually disconnected from this device"
                                }
                            }

                            is ConnectionViewModel.Event.ConnectionFailed -> {
                                showPopupDialog(R.layout.connection_failed_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Bluetooth connection failed"
                                    findViewById<TextView>(R.id.description).text = "Make sure the device is on and in range"
                                }
                            }

                            is ConnectionViewModel.Event.EnableBluetooth -> {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                enableBluetoothLauncher.launch(intent)
                            }

                            is ConnectionViewModel.Event.ReconnectFailed -> {
                                showPopupDialog(R.layout.connection_failed_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Reconnection failed"
                                    findViewById<TextView>(R.id.description).text = "Could not reconnect after multiple attempts. Please reconnect manually."
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        // Cold start / already-granted: scan from lifecycle. First-run grant is handled via
        // MainActivity.PermissionResultListener (bridge). startScanning() is idempotent via receiver guard.
        try {
            if (isAdded && view != null && !isScanning) {
                android.util.Log.d("Main", "Auto-starting Bluetooth discovery (onViewCreated)")
                startScanning()
            }
        } catch (e: Exception) {
            android.util.Log.d("Main", "Error auto-starting scan in onViewCreated: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        // Safety check: don't access binding if view is destroyed
        try {
            pairedDevices.clear()
            try {
                val devices = BluetoothAdapterWrapper.getInstance()?.pairedDevices ?: emptySet()
                for (device in devices) {
                    val name = device.name ?: "Unknown"
                    pairedDevices.add(Pair(name, device.address))
                }
            } catch (e: SecurityException) {
                // Permission not granted
            }
            
            // Only update UI if view is still valid
            if (isAdded && view != null) {
                pairedAdapter.notifyDataSetChanged()
                binding.tvNoPaired.visibility = if (pairedDevices.isEmpty()) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            // Fragment may have been destroyed, silently ignore
            android.util.Log.d("Main", "loadPairedDevices error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateOrAddDevice(device: BluetoothDevice) {
        val address = device.address
        if (pairedDevices.any { it.second == address }) return
        val name = device.name ?: "Unknown Device (${address.takeLast(5)})"
        val existing = availableByMac[address]
        if (existing == null) {
            availableByMac[address] = Pair(name, address)
            availableDevices.add(Pair(name, address))
            availableAdapter.notifyItemInserted(availableDevices.size - 1)
            updateAvailableVisibility()
        } else if (existing.first != name) {
            availableByMac[address] = Pair(name, address)
            val idx = availableDevices.indexOfFirst { it.second == address }
            if (idx >= 0) {
                availableDevices[idx] = Pair(name, address)
                availableAdapter.notifyItemChanged(idx)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (receiverRegistered) {
            return
        }

        val wrapper = BluetoothAdapterWrapper.getInstance()
        val btAdapter = wrapper?.adapter
        if (wrapper == null || btAdapter == null || !btAdapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(intent)
            return
        }

        // Flush any in-flight inquiry BEFORE registering for DISCOVERY_FINISHED so we don't
        // tear down UI when cancelDiscovery() broadcasts FINISHED.
        if (btAdapter.isDiscovering) {
            btAdapter.cancelDiscovery()
        }

        isScanning = true
        binding.btnScan.text = "Stop Scanning"
        binding.scanProgressBar.visibility = View.VISIBLE

        binding.labelAvailable.visibility = View.VISIBLE
        binding.recyclerAvailable.visibility = View.VISIBLE
        binding.tvNoAvailable.visibility = View.VISIBLE

        availableByMac.clear()
        availableDevices.clear()
        availableAdapter.notifyDataSetChanged()

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            requireContext().registerReceiver(bluetoothReceiver, filter)
            receiverRegistered = true
        } catch (e: IllegalArgumentException) {
            receiverRegistered = true
        }

        val started = try {
            btAdapter.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "startDiscovery threw", e)
            false
        }

        if (!started) {
            Log.e(TAG, "Hardware refused to start discovery — throttling or busy")
            stopScanningAfterFailedStart()
            Toast.makeText(
                context,
                "Could not start Bluetooth scan. Try again in a moment.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (isAdded && isScanning) {
                Toast.makeText(context, "Scan timed out", Toast.LENGTH_SHORT).show()
                stopScanning()
            }
        }
    }

    private fun stopScanningAfterFailedStart() {
        isScanning = false
        binding.btnScan.text = "Start Scanning"
        binding.scanProgressBar.visibility = View.GONE
        try {
            if (receiverRegistered) {
                requireContext().unregisterReceiver(bluetoothReceiver)
            }
        } catch (_: IllegalArgumentException) {
        } finally {
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null

        isScanning = false
        binding.btnScan.text = "Start Scanning"
        binding.scanProgressBar.visibility = View.GONE

        try {
            BluetoothAdapterWrapper.getInstance()?.adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }

        try {
            if (receiverRegistered) {
                requireContext().unregisterReceiver(bluetoothReceiver)
            }
        } catch (_: IllegalArgumentException) {
        } finally {
            receiverRegistered = false
        }

        if (availableDevices.isEmpty()) {
            binding.tvNoAvailable.text = "No new devices found"
        }
    }

    private fun updateAvailableVisibility() {
        try {
            // Safety check: only update if view is still valid
            if (isAdded && view != null && availableDevices.isNotEmpty()) {
                binding.tvNoAvailable.visibility = View.GONE
            }
        } catch (e: Exception) {
            // Fragment may have been destroyed
            android.util.Log.d("Main", "updateAvailableVisibility error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(macAddress: String) {
        // Use unified connection workflow (same as auto-reconnect)
        connectionManager.initiateConnection(requireContext(), macAddress)
    }

    private fun updateDrawerConnectionStatus() {
        try {
            val header = binding.navigation.getHeaderView(0)
            val statusText = header.findViewById<TextView>(R.id.connectionStatus)
            val dot = header.findViewById<View>(R.id.connectionDot)

            if (connectionManager.isConnected()) {
                statusText.text = "Connected to ${connectionManager.lastConnectedDeviceName ?: "device"}"
                dot?.setBackgroundResource(R.drawable.circle_green)
            } else {
                statusText.text = "Not connected"
                // No red dot drawable, just hide or use same
                dot?.visibility = View.GONE
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        // EVENT-BASED: Auto-start scanning when fragment becomes visible
        // This fires on:
        // 1. First open (after permissions granted) - onStart fires immediately after onViewCreated
        // 2. Every subsequent app open - onStart fires when fragment is shown
        try {
            if (isAdded && view != null && !isScanning) {
                android.util.Log.d("Main", "Auto-starting Bluetooth discovery (onStart event)")
                startScanning()
            }
        } catch (e: Exception) {
            android.util.Log.d("Main", "Error auto-starting scan: ${e.message}")
        }
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setPermissionListener(null)
        if (isScanning) {
            stopScanning()
        }
        super.onDestroyView()
    }

    private fun showConnectionLogsDialog() {
        val logs = connectionManager.getConnectionLogs()
        val logText = if (logs.isEmpty()) {
            "No logs recorded yet"
        } else {
            logs.joinToString("\n")
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val textView = TextView(requireContext()).apply {
            text = logText
            setPadding(20, 20, 20, 20)
            textSize = 11f
            setTextIsSelectable(true)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(textView)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Connection Logs")
            .setView(scrollView)
            .setPositiveButton("Clear Logs") { _, _ ->
                connectionManager.clearConnectionLogs()
                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()

        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }
}