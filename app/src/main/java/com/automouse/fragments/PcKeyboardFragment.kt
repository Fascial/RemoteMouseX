package com.automouse.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.automouse.R
import com.automouse.mkinput.KeyboardStateManager
import com.automouse.viewmodels.KeyboardViewModel

class PcKeyboardFragment : Fragment() {

    private val keyboardViewModel: KeyboardViewModel by activityViewModels()

    // Implementing the requested Stateful HID Report Generator
    private lateinit var stateManager: KeyboardStateManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_pc_keyboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        stateManager = KeyboardStateManager { modifiers, keys ->
            keyboardViewModel.sendKeyboardState(modifiers, keys)
        }

        // Native Layout Injected Navigation logic
        view.findViewById<Button>(R.id.btn_drawer_keyboard)?.setOnVibratingClickListener {
            requireActivity().findViewById<DrawerLayout>(R.id.drawerLayout)?.openDrawer(GravityCompat.START)
            stateManager.clearState() // Avoid stuck keys when opening drawer
        }
        view.findViewById<Button>(R.id.btn_toggle_keyboard)?.setOnVibratingClickListener {
            requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            stateManager.clearState()
        }

        val modifierKeys = mapOf(
            R.id.key_lctrl to 0x01.toByte(), 
            R.id.key_lshift to 0x02.toByte(), 
            R.id.key_lalt to 0x04.toByte(), 
            R.id.key_lwin to 0x08.toByte(),
            R.id.key_rctrl to 0x10.toByte(), 
            R.id.key_rshift to 0x20.toByte(), 
            R.id.key_ralt to 0x40.toByte()
        )

        val defaultColor = ContextCompat.getColor(requireContext(), R.color.auto_mouse_cyan_dim)
        val activeColor = ContextCompat.getColor(requireContext(), R.color.auto_mouse_cyan)

        // Rule 3 and 4 mapping for Modifiers
        for ((id, code) in modifierKeys) {
            val btn = view.findViewById<Button>(id)
            btn?.let { button ->
                val originalTint = button.backgroundTintList
                button.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            stateManager.onModifierDown(code)
                            button.backgroundTintList = ColorStateList.valueOf(activeColor)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            stateManager.onModifierUp(code)
                            button.backgroundTintList = originalTint
                        }
                    }
                    true
                }
            }
        }

        val standardMappings = mapOf(
            // Row 1
            R.id.key_esc to 0x29.toByte(), R.id.key_f1 to 0x3A.toByte(), R.id.key_f2 to 0x3B.toByte(),
            R.id.key_f3 to 0x3C.toByte(), R.id.key_f4 to 0x3D.toByte(), R.id.key_f5 to 0x3E.toByte(),
            R.id.key_f6 to 0x3F.toByte(), R.id.key_f7 to 0x40.toByte(), R.id.key_f8 to 0x41.toByte(),
            R.id.key_f9 to 0x42.toByte(), R.id.key_f10 to 0x43.toByte(), R.id.key_f11 to 0x44.toByte(),
            R.id.key_f12 to 0x45.toByte(), R.id.key_del to 0x4C.toByte(),

            // Row 2
            R.id.key_tilde to 0x35.toByte(), R.id.key_1 to 0x1E.toByte(), R.id.key_2 to 0x1F.toByte(),
            R.id.key_3 to 0x20.toByte(), R.id.key_4 to 0x21.toByte(), R.id.key_5 to 0x22.toByte(),
            R.id.key_6 to 0x23.toByte(), R.id.key_7 to 0x24.toByte(), R.id.key_8 to 0x25.toByte(),
            R.id.key_9 to 0x26.toByte(), R.id.key_0 to 0x27.toByte(), R.id.key_minus to 0x2D.toByte(),
            R.id.key_equals to 0x2E.toByte(), R.id.key_backspace to 0x2A.toByte(), R.id.key_home to 0x4A.toByte(),

            // Row 3
            R.id.key_tab to 0x2B.toByte(), R.id.key_q to 0x14.toByte(), R.id.key_w to 0x1A.toByte(),
            R.id.key_e to 0x08.toByte(), R.id.key_r to 0x15.toByte(), R.id.key_t to 0x17.toByte(),
            R.id.key_y to 0x1C.toByte(), R.id.key_u to 0x18.toByte(), R.id.key_i to 0x0C.toByte(),
            R.id.key_o to 0x12.toByte(), R.id.key_p to 0x13.toByte(), R.id.key_lbracket to 0x2F.toByte(),
            R.id.key_rbracket to 0x30.toByte(), R.id.key_backslash to 0x31.toByte(), R.id.key_pgup to 0x4B.toByte(),

            // Row 4
            R.id.key_caps to 0x39.toByte(), R.id.key_a to 0x04.toByte(), R.id.key_s to 0x16.toByte(),
            R.id.key_d to 0x07.toByte(), R.id.key_f to 0x09.toByte(), R.id.key_g to 0x0A.toByte(),
            R.id.key_h to 0x0B.toByte(), R.id.key_j to 0x0D.toByte(), R.id.key_k to 0x0E.toByte(),
            R.id.key_l to 0x0F.toByte(), R.id.key_semicolon to 0x33.toByte(), R.id.key_quote to 0x34.toByte(),
            R.id.key_enter to 0x28.toByte(), R.id.key_pgdn to 0x4E.toByte(),

            // Row 5
            R.id.key_z to 0x1D.toByte(), R.id.key_x to 0x1B.toByte(), R.id.key_c to 0x06.toByte(),
            R.id.key_v to 0x19.toByte(), R.id.key_b to 0x05.toByte(), R.id.key_n to 0x11.toByte(),
            R.id.key_m to 0x10.toByte(), R.id.key_comma to 0x36.toByte(), R.id.key_period to 0x37.toByte(),
            R.id.key_slash to 0x38.toByte(), R.id.key_up to 0x52.toByte(), R.id.key_end to 0x4D.toByte(),

            // Row 6
            R.id.key_space to 0x2C.toByte(), R.id.key_left to 0x50.toByte(),
            R.id.key_down to 0x51.toByte(), R.id.key_right to 0x4F.toByte()
        )

        val alphabetKeys = listOf(
            R.id.key_a, R.id.key_b, R.id.key_c, R.id.key_d, R.id.key_e, R.id.key_f, R.id.key_g,
            R.id.key_h, R.id.key_i, R.id.key_j, R.id.key_k, R.id.key_l, R.id.key_m, R.id.key_n,
            R.id.key_o, R.id.key_p, R.id.key_q, R.id.key_r, R.id.key_s, R.id.key_t, R.id.key_u,
            R.id.key_v, R.id.key_w, R.id.key_x, R.id.key_y, R.id.key_z
        )

        var isCapsOn = false
        val capsLockBtn = view.findViewById<Button>(R.id.key_caps)
        val capsOriginalTint = capsLockBtn?.backgroundTintList
        
        capsLockBtn?.setOnTouchListener { button, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    stateManager.onKeyDown(0x39.toByte())
                    isCapsOn = !isCapsOn
                    if (isCapsOn) {
                        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        alphabetKeys.forEach { view.findViewById<Button>(it)?.isAllCaps = true }
                    } else {
                        button.backgroundTintList = capsOriginalTint
                        alphabetKeys.forEach { view.findViewById<Button>(it)?.isAllCaps = false }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stateManager.onKeyUp(0x39.toByte())
                }
            }
            true
        }

        // Rule 3 and 4 mapping for Standard Keys
        for ((id, code) in standardMappings) {
            if (id == R.id.key_caps) continue // Handled separately
            
            val btn = view.findViewById<Button>(id)
            btn?.let { button ->
                val originalTint = button.backgroundTintList
                button.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            stateManager.onKeyDown(code)
                            button.backgroundTintList = ColorStateList.valueOf(activeColor)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            stateManager.onKeyUp(code)
                            button.backgroundTintList = originalTint
                        }
                    }
                    true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Extremely important: If the layout is destroyed and they were holding a key, release all keys immediately!
        stateManager.clearState()
    }
}
