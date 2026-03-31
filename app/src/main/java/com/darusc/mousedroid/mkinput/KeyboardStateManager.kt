package com.darusc.mousedroid.mkinput

import com.darusc.mousedroid.layouts.KeyboardLayout

/**
 * Stateful HID Report Generator as requested via the core HID logic rules.
 * Maintains a live physical state of up to 6 pressed scancodes and 1 combined modifier mask bit.
 */
class KeyboardStateManager(private val sendCallback: (List<KeyboardLayout.Key>) -> Unit) {
    
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
        val keysList = activeKeys.map { code ->
            KeyboardLayout.Key(0.toByte(), code)
        }.toMutableList()

        if (keysList.isEmpty()) {
            // Sends [Type, Modifiers, 0x00...] representing only modifiers active
            keysList.add(KeyboardLayout.Key(currentModifiers, 0x00.toByte()))
        } else {
            // Because Mousedroid's InputEvent.KeyPress loops and OR's the modifiers together across all keys,
            // we attach the currentModifiers to the first key so the overall 8-byte payload computes correctly.
            keysList[0] = KeyboardLayout.Key(currentModifiers, keysList[0].code)
        }
        
        sendCallback(keysList)
    }
}
