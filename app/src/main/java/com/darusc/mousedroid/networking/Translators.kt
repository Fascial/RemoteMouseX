package com.darusc.mousedroid.networking

import android.util.Log
import com.darusc.mousedroid.layouts.KeyboardLayout
import com.darusc.mousedroid.layouts.Keycode
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.bluetooth.BatteryReport
import com.darusc.mousedroid.networking.bluetooth.HIDReport
import com.darusc.mousedroid.networking.bluetooth.KeyboardReport
import com.darusc.mousedroid.networking.bluetooth.MediaReport
import com.darusc.mousedroid.networking.bluetooth.MouseReport
import kotlin.experimental.and

/**
 * HID usage IDs used in translating the events into raw bytes for the HID protocol
 * are taken from the official usb specification
 *
 * https://www.usb.org/sites/default/files/documents/hut1_12v2.pdf
 */


/**
 * Socket events for the TCP/UDP communication
 */
private object RawSocketEvents {
    const val LCLICK: Byte = 0x01
    const val RCLICK: Byte = 0x02
    const val DOWN: Byte = 0x03
    const val UP: Byte = 0x04
    const val MOVE: Byte = 0x05
    const val SCROLL: Byte = 0x06
    const val KEYPRESS: Byte = 0x07
    const val SCROLL_H: Byte = 0x08
    const val ZOOM: Byte = 0x09
    const val MEDIA: Byte = 0x0A
}

private fun getMouseButtonHIDCode(button: InputEvent.MouseButton): Byte {
    return when (button) {
        InputEvent.MouseButton.NONE -> 0
        InputEvent.MouseButton.LEFT -> 1
        InputEvent.MouseButton.RIGHT -> 2
        InputEvent.MouseButton.MIDDLE -> 4
    }
}

/**
 * Transforms a media action into its corresponding bitmask.
 * Bit positions MUST match the exact order usages appear in the HID descriptor.
 * Descriptor order: NextTrack, PrevTrack, Stop, Play/Pause, Mute, VolUp, VolDown, Home, ...
 */
private fun getMediaActionHIDBitmask(action: InputEvent.MediaAction): Short {
    return when(action) {
        InputEvent.MediaAction.FORWARD       -> 0x0001 // bit 0  = Scan Next Track  (0xB5)
        InputEvent.MediaAction.REPLAY        -> 0x0002 // bit 1  = Scan Prev Track  (0xB6)
        InputEvent.MediaAction.NEXT          -> 0x0001 // bit 0  = Scan Next Track  (same as FORWARD)
        InputEvent.MediaAction.PREVIOUS      -> 0x0002 // bit 1  = Scan Prev Track  (same as REPLAY)
        InputEvent.MediaAction.PLAY_PAUSE    -> 0x0008 // bit 3  = Play/Pause       (0xCD)
        InputEvent.MediaAction.VOLUME_MUTE   -> 0x0010 // bit 4  = Mute             (0xE2)
        InputEvent.MediaAction.VOLUME_UP     -> 0x0020 // bit 5  = Volume Increment (0xE9)
        InputEvent.MediaAction.VOLUME_DOWN   -> 0x0040 // bit 6  = Volume Decrement (0xEA)
    }
}

/**
 * Translate the input event to 1 or more bluetooth HID reports
 * (e.g mouse click requires 2 reports -> one for pressing the button and one for releasing)
 */
fun InputEvent.toHIDReport(): Array<HIDReport> {
    return when (this) {
        is InputEvent.MouseMove -> {
            val report = MouseReport(
                getMouseButtonHIDCode(this.button),
                (-this.dx).toByte(),
                (-this.dy).toByte(),
                0
            )
            Log.d("Mousedroid", "MouseMove: dx=${this.dx}, dy=${this.dy}, button=${this.button}")
            arrayOf(report)
        }

        is InputEvent.MouseClick -> {
            val r1 = MouseReport(getMouseButtonHIDCode(this.button), 0, 0, 0)
            val r2 = MouseReport(0, 0, 0, 0)
            Log.d("Mousedroid", "MouseClick: button=${this.button}")
            arrayOf(r1, r2)
        }

        is InputEvent.MouseDragState -> {
            val state = if (this.isDown) getMouseButtonHIDCode(this.button) else 0
            val report = MouseReport(state, 0, 0, 0)
            Log.d("Mousedroid", "MouseDragState: button=${this.button}, isDown=${this.isDown}, state=$state")
            arrayOf(report)
        }

        is InputEvent.MouseScroll -> {
            val reports = arrayListOf<HIDReport>()

            val vScroll = (this.dy / 10).coerceIn(-127, 127).toByte()
            val hScroll = (this.dx / 10).coerceIn(-127, 127).toByte()

            if (vScroll.toInt() != 0) {
                reports.add(MouseReport(0, 0, 0, vScroll))
            } else if (hScroll.toInt() != 0) {
                reports.add(KeyboardReport(Keycode.MOD_LEFT_SHIFT, 0)) // Hold Shift
                reports.add(MouseReport(0, 0, 0, hScroll))              // Scroll
                reports.add(KeyboardReport(0, 0))                       // Release Shift
            }

            reports.toTypedArray()
        }

        is InputEvent.KeyPress -> {
            val reports = mutableListOf<KeyboardReport>()
            keyList.forEach {
                reports.add(KeyboardReport(it.modifier, it.code))
                reports.add(KeyboardReport(0, 0))
            }
            reports.toTypedArray()
        }

        is InputEvent.Zoom -> {
            // CTRL + mouse wheel rotation
            val zoom = this.scale.coerceIn(-127, 127).toByte()
            if (zoom.toInt() != 0) {
                arrayOf(
                    KeyboardReport(Keycode.MOD_LEFT_CTRL),   // Press CTRL
                    MouseReport(0, 0, 0, zoom),                 // Scroll
                    KeyboardReport(0)                                 // Release CTRL
                )
            } else {
                emptyArray()
            }
        }

        is InputEvent.NumpadKeyPress -> {
            arrayOf(
                KeyboardReport(0x00, this.key),
                KeyboardReport(0)
            )
        }

        is InputEvent.KeyboardState -> {
            arrayOf(KeyboardReport(this.modifiers, *this.keys))
        }

        is InputEvent.MediaEvent -> {
            val bitmask = getMediaActionHIDBitmask(this.action)
            val byte0 = (bitmask.toInt() and 0xFF).toByte()
            Log.d("Mousedroid", "MediaEvent: action=${this.action}, bitmask=0x${bitmask.toString(16).padStart(2, '0')}, byte=[0x${(byte0.toInt() and 0xFF).toString(16).padStart(2, '0')}]")
            val pressReport = MediaReport(bitmask)
            val releaseReport = MediaReport(0)
            Log.d("Mousedroid", "Press Report byte: ${pressReport.bytes.joinToString(", ") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }}")
            Log.d("Mousedroid", "Release Report byte: ${releaseReport.bytes.joinToString(", ") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }}")
            arrayOf(pressReport, releaseReport)
        }

        is InputEvent.BatteryEvent -> {
            arrayOf(BatteryReport(this.percentage))
        }
    }
}

private fun socketReport(vararg bytes: Byte): Array<ByteArray> {
    return arrayOf(bytes)
}

/**
 * Translate the input event to raw socket bytes
 */
fun InputEvent.toSocketReport(): Array<ByteArray> {
    return when (this) {
        is InputEvent.MouseMove -> {
            socketReport(RawSocketEvents.MOVE, this.dx.toByte(), this.dy.toByte())
        }

        is InputEvent.MouseScroll -> {
            if (this.dy != 0) {
                socketReport(RawSocketEvents.SCROLL, (this.dy).toByte())
            } else {
                socketReport(RawSocketEvents.SCROLL_H, (this.dx).toByte())
            }
        }

        is InputEvent.MouseClick -> {
            val code =
                if (this.button == InputEvent.MouseButton.LEFT) RawSocketEvents.LCLICK else RawSocketEvents.RCLICK
            socketReport(code)
        }

        is InputEvent.MouseDragState -> {
            val code = if (this.isDown) RawSocketEvents.DOWN else RawSocketEvents.UP
            socketReport(code)
        }

        is InputEvent.Zoom -> {
            socketReport(RawSocketEvents.ZOOM, this.scale.toByte())
        }

        is InputEvent.KeyPress -> {
            keyList.flatMap {
                listOf(
                    byteArrayOf(RawSocketEvents.KEYPRESS, it.code, it.modifier)
                )
            }.toTypedArray()
        }

        is InputEvent.NumpadKeyPress -> {
            socketReport(RawSocketEvents.KEYPRESS, this.key, 0x00)
        }

        is InputEvent.KeyboardState -> {
            if (this.keys.isEmpty()) {
                arrayOf(byteArrayOf(RawSocketEvents.KEYPRESS, 0x00, this.modifiers))
            } else {
                this.keys.map { key ->
                    byteArrayOf(RawSocketEvents.KEYPRESS, key, this.modifiers)
                }.toTypedArray()
            }
        }

        is InputEvent.MediaEvent -> {
            val bitmask = getMediaActionHIDBitmask(this.action)
            socketReport(RawSocketEvents.MEDIA, (bitmask and 0xFF).toByte())
        }

        is InputEvent.BatteryEvent -> { socketReport() }
    }
}