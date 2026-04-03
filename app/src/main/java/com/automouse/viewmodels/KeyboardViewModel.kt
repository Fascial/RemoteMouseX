package com.automouse.viewmodels

import com.automouse.layouts.KeyboardLayout
import com.automouse.layouts.languages.KeyboardLayoutES
import com.automouse.layouts.languages.KeyboardLayoutFR
import com.automouse.layouts.languages.KeyboardLayoutRO
import com.automouse.layouts.languages.KeyboardLayoutUS
import com.automouse.mkinput.InputEvent
import com.automouse.networking.ConnectionManager

class KeyboardViewModel : BaseViewModel<KeyboardViewModel.State, KeyboardViewModel.Event>(State()) {

    sealed class Event : BaseViewModel.Event()
    class State : BaseViewModel.State()

    private val connectionManager = ConnectionManager.getInstance()

    private val layoutMap: Map<String, Class<out KeyboardLayout>> = mapOf(
        KeyboardLayoutUS.NAME to KeyboardLayoutUS::class.java,
        KeyboardLayoutES.NAME to KeyboardLayoutES::class.java,
        KeyboardLayoutFR.NAME to KeyboardLayoutFR::class.java,
        KeyboardLayoutRO.NAME to KeyboardLayoutRO::class.java,
    )

    val layouts: Set<String>
        get() = layoutMap.keys

    var activeKeyboardLayout: KeyboardLayout = KeyboardLayoutUS()

    fun setKeyboardLayout(layout: String): Boolean {
        val layoutClass = layoutMap[layout]

        if (layoutClass != null) {
            try {
                activeKeyboardLayout = layoutClass.getDeclaredConstructor().newInstance()
                return true
            } catch (e: Exception) {
                return false
            }
        }

        return false
    }

    fun handleKeypress(chars: CharArray) {
        for (char in chars) {
            val mapping = activeKeyboardLayout.getMapping(char)
            if (mapping != null) {
                connectionManager.send(InputEvent.KeyPress(mapping))
            }
        }
    }

    fun sendRawKeys(modifier: Byte, code: Byte) {
        connectionManager.send(InputEvent.KeyPress(listOf(com.automouse.layouts.KeyboardLayout.Key(modifier, code))))
    }

    fun sendState(keys: List<com.automouse.layouts.KeyboardLayout.Key>) {
        connectionManager.send(InputEvent.KeyPress(keys))
    }

    fun sendKeyboardState(modifier: Byte, keys: ByteArray) {
        connectionManager.send(InputEvent.KeyboardState(modifier, keys))
    }
}