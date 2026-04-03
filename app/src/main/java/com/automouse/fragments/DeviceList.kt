package com.automouse.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.automouse.adapters.DeviceAdapter
import com.automouse.R
import com.automouse.databinding.FragmentDeviceListBinding
import com.automouse.networking.Connection
import com.automouse.networking.bluetooth.BluetoothAdapterWrapper
import com.automouse.viewmodels.ConnectionViewModel
import com.automouse.viewmodels.DeviceListViewModel
import kotlinx.coroutines.launch

class DeviceList : Fragment() {

    private lateinit var binding: FragmentDeviceListBinding
    private lateinit var loadingPopup: PopupWindow

    private lateinit var deviceAdapter: DeviceAdapter

    private val connectionMode: Connection.Mode
        get() = arguments?.getSerializable("CONNECTION_MODE") as Connection.Mode

    private val isPairingMode: Boolean
        get() = arguments?.getBoolean("IS_PAIRING", false) ?: false

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_FOUND -> {
                    val device: android.bluetooth.BluetoothDevice? = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val name = it.name ?: "Unknown Device (${it.address.takeLast(5)})"
                        deviceListViewModel.addDevice(name, it.address)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    deviceListViewModel.onScanFinished()
                }
            }
        }
    }

    private val connectionViewModel: ConnectionViewModel by activityViewModels()
    private val deviceListViewModel: DeviceListViewModel by activityViewModels {
        val devices = BluetoothAdapterWrapper.getInstance()?.pairedDevices ?: emptySet()
        DeviceListViewModel.Factory(devices)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_device_list, container, false)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.recyclerView.layoutManager = LinearLayoutManager(context)

        loadingPopup = PopupWindow (
            layoutInflater.inflate(R.layout.loading_fragment, null),
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT,
        )

        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isPairingMode) {
            val filter = android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            requireContext().registerReceiver(bluetoothReceiver, filter)
            binding.root.post {
                deviceListViewModel.resetForScanning()
                BluetoothAdapterWrapper.getInstance()?.adapter?.startDiscovery()
            }
        }

        deviceAdapter = DeviceAdapter(arrayListOf(), object : DeviceAdapter.OnItemClickListener {
            override fun onItemLongClick(position: Int) {
                // Long click not used for Bluetooth devices
            }

            @RequiresApi(Build.VERSION_CODES.P)
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onItemClick(name: String, address: String) {
                deviceListViewModel.onDeviceClick(requireContext(), name, address)
            }
        })
        binding.recyclerView.adapter = deviceAdapter

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    deviceListViewModel.state.collect { state ->
                        deviceAdapter.devices.clear()
                        deviceAdapter.devices.addAll(state.devices)
                        deviceAdapter.notifyDataSetChanged()

                        // Show/hide scanning indicators
                        val progressBar = binding.root.findViewById<android.widget.ProgressBar>(R.id.scanProgressBar)
                        val tvStatus = binding.root.findViewById<android.widget.TextView>(R.id.tvScanStatus)
                        if (state.isScanning) {
                            progressBar?.visibility = View.VISIBLE
                            tvStatus?.visibility = View.VISIBLE
                            tvStatus?.text = if (state.devices.isEmpty()) "Searching for nearby devices..." else "Scanning... ${state.devices.size} found"
                        } else {
                            progressBar?.visibility = View.GONE
                            tvStatus?.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
                            tvStatus?.text = "No devices found"
                        }
                    }
                }

                launch {
                    connectionViewModel.state.collect {
                        when(it) {
                            is ConnectionViewModel.State.Connecting -> {
                                if (!loadingPopup.isShowing) {
                                    loadingPopup.contentView.findViewById<TextView>(R.id.loadingMessage).text = it.message
                                    loadingPopup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
                                }
                            }
                            is ConnectionViewModel.State.Idle -> loadingPopup.dismiss()
                            is ConnectionViewModel.State.Connected -> loadingPopup.dismiss()
                        }
                    }
                }

                launch {
                    connectionViewModel.events.collect {
                        when(it) {
                            is ConnectionViewModel.Event.NavigateToInput -> findNavController().navigate(R.id.action_devicelist_to_touchpad)
                            is ConnectionViewModel.Event.NavigateToMain -> findNavController().popBackStack(R.id.mainFragment, false)
                            is ConnectionViewModel.Event.ConnectionDisconnected -> {
                                showPopupDialog(R.layout.connection_disconnected_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Bluetooth connection to ${it.hostName} was terminated"
                                    findViewById<TextView>(R.id.description).text = "Host device turned bluetooth off or disconnected this device"
                                }
                            }
                            is ConnectionViewModel.Event.ConnectionFailed -> {
                                showPopupDialog(R.layout.connection_failed_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Bluetooth connection failed"
                                    findViewById<TextView>(R.id.description).text = "Make sure the device is on and in range"
                                }
                            }
                            else -> { }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroyView() {
        super.onDestroyView()
        if (isPairingMode) {
            try {
                requireContext().unregisterReceiver(bluetoothReceiver)
                BluetoothAdapterWrapper.getInstance()?.adapter?.cancelDiscovery()
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
        }
    }
}