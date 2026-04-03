package com.automouse.mkinput

import com.automouse.layouts.KeyboardLayout

sealed class InputEvent : java.io.Serializable {

    enum class MouseButton {
        LEFT,
        RIGHT,
        MIDDLE,
        NONE
    }

    enum class MediaAction {
        PREVIOUS,
        PLAY_PAUSE,
        NEXT,
        FORWARD,
        REPLAY,
        VOLUME_DOWN,
        VOLUME_UP,
        VOLUME_MUTE
    }

    data class MouseMove(val dx: Int, val dy: Int, val button: MouseButton = MouseButton.NONE) : InputEvent()
    data class MouseScroll(val dx: Int, val dy: Int) : InputEvent() // Covers Vertical (dy) and Horizontal (dx)
    data class MouseClick(val button: MouseButton) : InputEvent()
    data class MouseDragState(val button: MouseButton, val isDown: Boolean) : InputEvent() // For DOWN/UP dragging
    data class Zoom(val scale: Int) : InputEvent()

    data class KeyPress(val keyList: List<KeyboardLayout.Key>) : InputEvent()
    data class KeyboardState(val modifiers: Byte, val keys: ByteArray) : InputEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KeyboardState
            if (modifiers != other.modifiers) return false
            if (!keys.contentEquals(other.keys)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = modifiers.toInt()
            result = 31 * result + keys.contentHashCode()
            return result
        }
    }

    data class MediaEvent(val action: MediaAction): InputEvent()

    data class BatteryEvent(val percentage: Int): InputEvent()
}