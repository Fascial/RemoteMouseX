package com.darusc.mousedroid.viewmodels

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.darusc.mousedroid.R
import com.darusc.mousedroid.layouts.Keycode
import com.darusc.mousedroid.layouts.KeyboardLayout
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.ConnectionManager

class TouchpadViewModel: BaseViewModel<TouchpadViewModel.State, TouchpadViewModel.Event>(State()) {

    class State: BaseViewModel.State()
    sealed class Event: BaseViewModel.Event()

    private val connectionManager = ConnectionManager.getInstance()
    private var vibrator: Vibrator? = null

    fun setVibrator(vibrator: Vibrator?) {
        this.vibrator = vibrator
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
            
            // Vibrate and highlight button
            vibrateButton()
            highlightButton(view)
            view.postDelayed({ resetButtonHighlight(view) }, 100)
        
        when(view.id) {
            R.id.btnPrev -> {
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.PREVIOUS))
            }
            R.id.btnPlayPause -> {
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.PLAY_PAUSE))
            }
            R.id.btnNext -> {
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.NEXT))
            }
            R.id.btnSeekForward -> {
                val arrowRight = InputEvent.KeyPress(listOf(
                    KeyboardLayout.Key(Keycode.MOD_NONE, Keycode.KEY_RIGHT)
                ))
                connectionManager.send(arrowRight)
            }
            R.id.btnVolDown -> {
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.VOLUME_DOWN))
            }
            R.id.btnVolUp -> {
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.VOLUME_UP))
            }
            R.id.btnVolOff -> {
                connectionManager.send(InputEvent.MediaEvent(InputEvent.MediaAction.VOLUME_MUTE))
            }
            R.id.btnSeekBackward -> {
                val arrowLeft = InputEvent.KeyPress(listOf(
                    KeyboardLayout.Key(Keycode.MOD_NONE, Keycode.KEY_LEFT)
                ))
                connectionManager.send(arrowLeft)
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