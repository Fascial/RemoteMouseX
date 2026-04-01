package com.darusc.mousedroid.mkinput

import com.darusc.mousedroid.layouts.KeyboardLayout

/**
 * Stateful HID Report Generator as requested via the core HID logic rules.
 * Maintains a live physical state of up to 6 pressed scancodes and 1 combined modifier mask bit.
 */
class KeyboardStateManager(private val sendCallback: (Byte, ByteArray) -> Unit) {
    
    private var currentModifiers: Byte = 0x00
    private val activeKeys = mutableSetOf<Byte>()

    // Core rule 3: Action Down
    fun onModifierDown(modifierCode: Byte) {
        currentModifiers = (currentModifiers.toInt() or modifierCode.toInt()).toByte()
        sendReport()
    }

    // Core rule 4: Action Up
    fun onModifierUp(modifierCode: Byte) {
        currentModifiers = (currentModifiers.toInt() and modifierCode.toInt().inv()).toByte()
        sendReport()
    }

    // Core rule 3: Action Down (Standard Key)
    fun onKeyDown(scancode: Byte) {
        if (activeKeys.size < 6) {
            activeKeys.add(scancode)
            sendReport()
        }
    }

    // Core rule 4: Action Up (Standard Key)
    fun onKeyUp(scancode: Byte) {
        activeKeys.remove(scancode)
        // If the key is gone, we broadcast it immediately removing it from the array
        sendReport()
    }

    fun clearState() {
        currentModifiers = 0x00.toByte()
        activeKeys.clear()
        sendReport()
    }

    // Core Rule 1, 2, 3, 4: Compile and Send using active keys limit
    private fun sendReport() {
        sendCallback(currentModifiers, activeKeys.toByteArray())
    }
}
