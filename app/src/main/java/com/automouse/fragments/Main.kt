package com.automouse.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import com.automouse.R
import com.automouse.databinding.FragmentMainBinding
import com.automouse.networking.Connection
import com.automouse.viewmodels.ConnectionViewModel
import kotlinx.coroutines.launch

class Main : Fragment() {

    private lateinit var binding: FragmentMainBinding
    private lateinit var loadingPopup: PopupWindow

    private val viewModel: ConnectionViewModel by activityViewModels()

    @SuppressLint("MissingPermission")
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                viewModel.startBluetoothMode(requireContext(), true)
            } else {
                Toast.makeText(context, "Bluetooth is required for this mode.", Toast.LENGTH_SHORT).show()
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.initBluetoothEarlyRegistration(requireContext())

        binding.btnConnectBT.setOnClickListener {
            viewModel.startBluetoothMode(requireContext())
        }

        binding.btnPairBT.setOnClickListener {
            viewModel.startPairingMode(requireContext())
        }

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

                            is ConnectionViewModel.State.Idle -> loadingPopup.dismiss()
                            is ConnectionViewModel.State.Connected -> loadingPopup.dismiss()
                        }
                    }
                }

                launch {
                    viewModel.events.collect {
                        when (it) {
                            is ConnectionViewModel.Event.NavigateToInput -> {
                                findNavController().navigate(R.id.action_main_to_touchpad)
                            }

                            is ConnectionViewModel.Event.NavigateToDeviceList -> {
                                findNavController().navigate(R.id.action_main_to_devicelist, androidx.core.os.bundleOf("CONNECTION_MODE" to it.mode, "IS_PAIRING" to it.isPairing))
                            }

                            is ConnectionViewModel.Event.NavigateToMain -> {}

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

                            is ConnectionViewModel.Event.EnableBluetooth -> {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                enableBluetoothLauncher.launch(intent)
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Don't disconnect if AutoMouse is running
        val prefs = requireContext().getSharedPreferences("automouse_state", android.content.Context.MODE_PRIVATE)
        val isAutoMouseRunning = prefs.getBoolean("is_running", false)
        
        if (!isAutoMouseRunning) {
            viewModel.disconnect()
        }
    }
}