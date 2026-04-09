package com.automouse.viewmodels

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ImageButton
import com.automouse.R
import com.automouse.layouts.Keycode
import com.automouse.layouts.KeyboardLayout
import com.automouse.mkinput.InputEvent
import com.automouse.networking.ConnectionManager

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
            } catch (_: Exception) {
            }
        }
    }

    private fun highlightButton(view: View) {
        if (view is ImageButton) {
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
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            highlightButton(view)
            view.postDelayed({ resetButtonHighlight(view) }, 100)
        } catch (_: Exception) {}
        
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
    }

    fun onMouseButtonClick(view: View) {
        try { view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) } catch (_: Exception) {}
        when(view.id) {
            R.id.btnLeftClick -> connectionManager.send(InputEvent.MouseClick(InputEvent.MouseButton.LEFT))
            R.id.btnRightClick -> connectionManager.send(InputEvent.MouseClick(InputEvent.MouseButton.RIGHT))
            R.id.btnMiddleClick -> connectionManager.send(InputEvent.MouseClick(InputEvent.MouseButton.MIDDLE))
        }
    }
}