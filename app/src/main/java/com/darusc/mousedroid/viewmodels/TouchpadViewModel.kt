package com.darusc.mousedroid.viewmodels

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.darusc.mousedroid.MediaButtonLogger
import com.darusc.mousedroid.R
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.ConnectionManager

class TouchpadViewModel: BaseViewModel<TouchpadViewModel.State, TouchpadViewModel.Event>(State()) {

    class State: BaseViewModel.State()
    sealed class Event: BaseViewModel.Event()

    private val connectionManager = ConnectionManager.getInstance()
    private var context: Context? = null
    private var vibrator: Vibrator? = null

    fun setContext(context: Context) {
        this.context = context
        vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
    }

    private fun vibrateButton() {
        vibrator?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(20) // 20ms vibration
                }
            } catch (e: Exception) {
                Log.e("Mousedroid", "Vibration failed: ${e.message}")
            }
        }
    }

    private fun highlightButton(view: View) {
        if (view is ImageButton) {
            // Change icon color to indicate press
            view.alpha = 0.6f
        }
    }

    private fun resetButtonHighlight(view: View) {
        if (view is ImageButton) {
            view.alpha = 1f
        }
    }

    fun sendMouseEvent(event: InputEvent) {
        connectionManager.send(event)
    }

    fun onMediaButtonClick(view: View) {
        try {
            Log.d("Mousedroid", "Media button clicked! View ID: ${view.id}")
            val ctx = context ?: return
            
            // Vibrate and highlight button
            vibrateButton()
            highlightButton(view)
            view.postDelayed({ resetButtonHighlight(view) }, 100)
        
        when(view.id) {
            R.id.btnPrev -> {
                val data = byteArrayOf(0x08.toByte())  // Bitmask for PREVIOUS
                MediaButtonLogger.logMediaButtonPress(ctx, "Previous", "PREVIOUS", 
                    "Bitmask: 0x0008 (bits: 0b0000000000001000)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.PREVIOUS))
            }
            R.id.btnPlayPause -> {
                val data = byteArrayOf(0x10.toByte())  // Bitmask for PLAY_PAUSE
                MediaButtonLogger.logMediaButtonPress(ctx, "Play/Pause", "PLAY_PAUSE", 
                    "Bitmask: 0x0010 (bits: 0b0000000000010000)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.PLAY_PAUSE))
            }
            R.id.btnNext -> {
                val data = byteArrayOf(0x04.toByte())  // Bitmask for NEXT
                MediaButtonLogger.logMediaButtonPress(ctx, "Next", "NEXT", 
                    "Bitmask: 0x0004 (bits: 0b0000000000000100)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.NEXT))
            }
            R.id.btnSeekForward -> {
                val data = byteArrayOf(0x01.toByte())  // Bitmask for FORWARD
                MediaButtonLogger.logMediaButtonPress(ctx, "Seek Forward", "FORWARD", 
                    "Bitmask: 0x0001 (bits: 0b0000000000000001)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.FORWARD))
            }
            R.id.btnVolDown -> {
                val data = byteArrayOf(0x80.toByte())  // Bitmask for VOLUME_DOWN (bit 7)
                MediaButtonLogger.logMediaButtonPress(ctx, "Volume Down", "VOLUME_DOWN", 
                    "Bitmask: 0x0080 (bits: 0b0000000010000000)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.VOLUME_DOWN))
            }
            R.id.btnVolUp -> {
                val data = byteArrayOf(0x40.toByte())  // Bitmask for VOLUME_UP (bit 6)
                MediaButtonLogger.logMediaButtonPress(ctx, "Volume Up", "VOLUME_UP", 
                    "Bitmask: 0x0040 (bits: 0b0000000001000000)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.VOLUME_UP))
            }
            R.id.btnVolOff -> {
                val data = byteArrayOf(0x20.toByte())  // Bitmask for VOLUME_MUTE (bit 5)
                MediaButtonLogger.logMediaButtonPress(ctx, "Mute", "VOLUME_MUTE", 
                    "Bitmask: 0x0020 (bits: 0b0000000000100000)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.VOLUME_MUTE))
            }
            R.id.btnSeekBackward -> {
                val data = byteArrayOf(0x02.toByte())  // Bitmask for REPLAY (bit 1)
                MediaButtonLogger.logMediaButtonPress(ctx, "Seek Backward", "REPLAY", 
                    "Bitmask: 0x0002 (bits: 0b0000000000000010)")
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.REPLAY))
            }
        }
        } catch (e: Exception) {
            Log.e("Mousedroid", "Error in onMediaButtonClick: ${e.message}", e)
        }
    }

    fun onMouseButtonClick(view: View) {
        when(view.id) {
            R.id.btnLeftClick -> connectionManager.send(InputEvent.MouseClick(InputEvent.MouseButton.LEFT))
            R.id.btnRightClick -> connectionManager.send(InputEvent.MouseClick(InputEvent.MouseButton.RIGHT))
            R.id.btnMiddleClick -> connectionManager.send(InputEvent.MouseClick(InputEvent.MouseButton.MIDDLE))
        }
    }
}