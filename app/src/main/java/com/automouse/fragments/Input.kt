package com.automouse.fragments

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.automouse.R
import com.automouse.databinding.FragmentInputBinding
import com.automouse.mkinput.KeyboardInputWatcher
import com.automouse.networking.Connection
import com.automouse.viewmodels.ConnectionViewModel
import com.automouse.viewmodels.KeyboardViewModel
import kotlinx.coroutines.launch

/**
 * Input host fragment. All navigation between input modes
 * is happening inside this fragment
 */
class Input: Fragment() {

    private lateinit var binding: FragmentInputBinding

    private val connectionViewModel: ConnectionViewModel by activityViewModels()
    private val keyboardViewModel: KeyboardViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_input, container, false)
        binding.lifecycleOwner = viewLifecycleOwner

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            replaceChildFragment(Touchpad())
        }

        binding.hiddenInput.apply {
            addTextChangedListener(KeyboardInputWatcher(this) { bytes ->
                keyboardViewModel.handleKeypress(bytes)
            })
        }

        // Remove the focus from the hidden text input and clear its text
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if(!isKeyboardVisible && binding.hiddenInput.hasFocus()) {
                binding.hiddenInput.clearFocus()
                binding.hiddenInput.text.clear()
            }
            insets
        }

        // Set navigation listener for the side drawer
        binding.navigation.setCheckedItem(R.id.mode_touchpad)
        binding.btnOpenDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        
        binding.btnTogglePcKeyboard.setOnClickListener {
            val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            
            if (isPortrait) {
                requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        binding.navigation.setNavigationItemSelectedListener { item ->
            when(item.itemId) {
                R.id.mode_touchpad -> {
                    item.isChecked = true
                    closeSoftKeyboard()
                    binding.btnTogglePcKeyboard.visibility = View.GONE
                    replaceChildFragment(Touchpad())
                }
                R.id.mode_automouse -> {
                    item.isChecked = true
                    closeSoftKeyboard()
                    binding.btnTogglePcKeyboard.visibility = View.GONE
                    replaceChildFragment(AutoMouse())
                }
                R.id.mode_recorder -> {
                    item.isChecked = true
                    closeSoftKeyboard()
                    binding.btnTogglePcKeyboard.visibility = View.GONE
                    replaceChildFragment(Recorder())
                }
                R.id.mode_keyboard -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    binding.btnTogglePcKeyboard.visibility = View.VISIBLE
                    openSoftKeyboard()
                    return@setNavigationItemSelectedListener true
                }
                R.id.mode_disconnect -> {
                    closeSoftKeyboard()
                    connectionViewModel.disconnect()
                    findNavController().navigateUp()
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    connectionViewModel.state.collect {
                        when (it) {
                            is ConnectionViewModel.State.Connected -> {
                                binding.navigation
                                    .getHeaderView(0)
                                    .findViewById<TextView>(R.id.connectionStatus)
                                    .text = "Connected to ${it.hostName}"
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    connectionViewModel.events.collect {
                        when(it) {
                            is ConnectionViewModel.Event.NavigateToInput -> { }
                            is ConnectionViewModel.Event.NavigateToMain -> findNavController().popBackStack(R.id.mainFragment, false)
                            is ConnectionViewModel.Event.ConnectionDisconnected -> {
                                showPopupDialog(R.layout.connection_disconnected_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Bluetooth connection to ${it.hostName} was terminated"
                                    findViewById<TextView>(R.id.description).text = "Host device turned bluetooth off or disconnected this device"
                                }
                            }
                            is ConnectionViewModel.Event.ConnectionFailed -> {
                                showPopupDialog(R.layout.connection_failed_fragment)?.apply {
                                    findViewById<TextView>(R.id.subtitle).text = "Bluetooth connection failed"
                                    findViewById<TextView>(R.id.description).text = "Make sure the device is on and in range"
                                }
                            }
                            else -> { }
                        }
                    }
                }
            }
        }
    }

    private fun replaceChildFragment(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun openSoftKeyboard() {
        binding.hiddenInput.requestFocus()

        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.hiddenInput, InputMethodManager.SHOW_FORCED)
    }

    private fun closeSoftKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
        view?.clearFocus()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT || newConfig.orientation == android.content.res.Configuration.ORIENTATION_UNDEFINED) {
            binding.btnOpenDrawer.visibility = View.VISIBLE
            
            if (binding.navigation.checkedItem?.itemId == R.id.mode_keyboard) {
                binding.btnTogglePcKeyboard.visibility = View.VISIBLE
            } else {
                binding.btnTogglePcKeyboard.visibility = View.GONE
            }
            
            val current = childFragmentManager.findFragmentById(R.id.fragment_container)
            if (current is PcKeyboardFragment) {
                replaceChildFragment(androidx.fragment.app.Fragment())
                openSoftKeyboard()
            }
        } 
        else if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            if (binding.navigation.checkedItem?.itemId == R.id.mode_keyboard) {
                closeSoftKeyboard()
                binding.btnTogglePcKeyboard.visibility = View.GONE
                binding.btnOpenDrawer.visibility = View.GONE
                replaceChildFragment(PcKeyboardFragment())
            }
        }
    }
}