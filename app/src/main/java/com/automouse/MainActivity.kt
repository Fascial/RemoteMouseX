package com.automouse

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.automouse.networking.ConnectionManager
import com.automouse.networking.bluetooth.BluetoothAdapterWrapper
import com.automouse.viewmodels.ConnectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    interface PermissionResultListener {
        fun onBluetoothPermissionsGranted()
    }

    private var permissionListener: PermissionResultListener? = null

    fun setPermissionListener(listener: PermissionResultListener?) {
        permissionListener = listener
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    private fun allRequestPermissionsGranted(
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (grantResults.isEmpty() || permissions.size != grantResults.size) return false
        for (i in grantResults.indices) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needsConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            val needsScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            if (needsConnect || needsScan) {
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1000)
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1000)
                return
            }
        }

        // All permissions already granted — initialize immediately
        onPermissionsGranted()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1000) {
            if (!allRequestPermissionsGranted(permissions, grantResults)) {
                AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("Please enable all required bluetooth and location permissions in settings and restart the app.")
                    .setPositiveButton("Go to settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                onPermissionsGranted()
                permissionListener?.onBluetoothPermissionsGranted()
            }
        }
    }

    private fun onPermissionsGranted() {
        try {
            BluetoothAdapterWrapper.initialize(applicationContext)
            
            // CRITICAL: Add delay to let Android Bluetooth cache refresh after permissions granted
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    delay(1000)  // Wait 1 second for Bluetooth stack to update

                    // Attempt to auto-connect to remembered device (with error handling)
                    attemptAutoConnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during auto-connect: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPermissionsGranted: ${e.message}")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        val adapter = BluetoothAdapterWrapper.getInstance()?.adapter
        if (adapter?.isEnabled == true) {
            Log.d(TAG, "Starting Bluetooth discovery")
            try {
                adapter.startDiscovery()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery", e)
            }
        }
    }

    private fun attemptAutoConnect() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Give the BluetoothAdapter a moment to initialize
                delay(500)
                
                val connectionManager = ConnectionManager.getInstance()
                
                // Load the remembered device first
                val (macAddress, deviceName) = connectionManager.loadRememberedDevice() ?: run {
                    Log.d(TAG, "No remembered device found")
                    return@launch
                }
                
                if (macAddress == null) {
                    Log.d(TAG, "No remembered device MAC address found")
                    return@launch
                }
                
                Log.d(TAG, "Attempting to auto-connect to $deviceName ($macAddress)")
                
                // Mark as auto-connecting
                connectionManager.setAutoConnecting(true)
                
                // Use unified connection workflow (exact same as manual connect)
                connectionManager.initiateConnection(applicationContext, macAddress)
                
                // Mark as not auto-connecting after connection is initiated
                connectionManager.setAutoConnecting(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-connect: ${e.message}", e)
            }
        }
    }
}