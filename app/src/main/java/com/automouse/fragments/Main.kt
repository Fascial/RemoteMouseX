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
import com.automouse.R
import com.automouse.adapters.DeviceAdapter
import com.automouse.databinding.FragmentMainBinding
import com.automouse.networking.Connection
import com.automouse.networking.ConnectionManager
import com.automouse.networking.bluetooth.BluetoothAdapterWrapper
import com.automouse.viewmodels.ConnectionViewModel
import kotlinx.coroutines.launch

class Main : Fragment() {

    private lateinit var binding: FragmentMainBinding
    private lateinit var loadingPopup: PopupWindow

    private val viewModel: ConnectionViewModel by activityViewModels()
    private val connectionManager = ConnectionManager.getInstance()

    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter

    private val pairedDevices = ArrayList<Pair<String, String>>()
    private val availableDevices = ArrayList<Pair<String, String>>()

    private var isScanning = false

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
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val name = it.name ?: "Unknown Device (${it.address.takeLast(5)})"
                        val address = it.address
                        // Don't add if already in paired or available list
                        if (pairedDevices.none { d -> d.second == address } &&
                            availableDevices.none { d -> d.second == address }) {
                            availableDevices.add(Pair(name, address))
                            availableAdapter.notifyItemInserted(availableDevices.size - 1)
                            updateAvailableVisibility()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    stopScanning()
                }
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

        // Load paired devices
        loadPairedDevices()

        // Auto-connect to last connected device if available
        if (!connectionManager.isConnected()) {
            tryAutoConnect()
        }
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
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
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
        pairedAdapter.notifyDataSetChanged()

        if (pairedDevices.isEmpty()) {
            binding.tvNoPaired.visibility = View.VISIBLE
        } else {
            binding.tvNoPaired.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val btAdapter = BluetoothAdapterWrapper.getInstance()
        if (btAdapter == null || btAdapter.isEnabled != true) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(intent)
            return
        }

        isScanning = true
        binding.btnScan.text = "Stop Scanning"
        binding.scanProgressBar.visibility = View.VISIBLE

        // Show available section
        binding.labelAvailable.visibility = View.VISIBLE
        binding.recyclerAvailable.visibility = View.VISIBLE
        binding.tvNoAvailable.visibility = View.VISIBLE

        // Clear previous scan results
        availableDevices.clear()
        availableAdapter.notifyDataSetChanged()

        // Register receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        requireContext().registerReceiver(bluetoothReceiver, filter)

        // Start discovery
        btAdapter.adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        isScanning = false
        binding.btnScan.text = "Start Scanning"
        binding.scanProgressBar.visibility = View.GONE

        try {
            BluetoothAdapterWrapper.getInstance()?.adapter?.cancelDiscovery()
        } catch (_: Exception) {}

        try {
            requireContext().unregisterReceiver(bluetoothReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver not registered
        }

        if (availableDevices.isEmpty()) {
            binding.tvNoAvailable.text = "No new devices found"
        }
    }

    private fun updateAvailableVisibility() {
        if (availableDevices.isNotEmpty()) {
            binding.tvNoAvailable.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(macAddress: String) {
        // Register HID and connect
        viewModel.initBluetoothEarlyRegistration(requireContext())
        connectionManager.registerBluetoothHID(requireContext())
        connectionManager.connectBluetooth(macAddress)
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

    override fun onDestroyView() {
        super.onDestroyView()
        if (isScanning) {
            stopScanning()
        }
    }
}