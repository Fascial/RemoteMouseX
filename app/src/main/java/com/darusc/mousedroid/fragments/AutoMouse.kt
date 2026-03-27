package com.darusc.mousedroid.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.darusc.mousedroid.R
import com.darusc.mousedroid.databinding.FragmentAutoMouseBinding
import com.darusc.mousedroid.networking.ConnectionManager
import com.darusc.mousedroid.services.AutoMouseService

class AutoMouse : Fragment() {

    private lateinit var binding: FragmentAutoMouseBinding
    
    private var moveInterval = 50L
    private var clickInterval = 2000L
    private var maxDistance = 80
    private var durationMinutes = 0
    private var durationSeconds = 0
    private var isEnabled = false
    private val connectionManager = ConnectionManager.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_auto_mouse, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Main toggle - check connection before enabling
        binding.autoMouseToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !checkConnectionExists()) {
                Toast.makeText(requireContext(), "Bluetooth not connected. Connect first.", Toast.LENGTH_SHORT).show()
                binding.autoMouseToggle.isChecked = false
                return@setOnCheckedChangeListener
            }
            
            isEnabled = isChecked
            binding.settingsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                updateServiceState()
            } else {
                stopAutoMouse()
            }
        }
        binding.settingsContainer.visibility = View.GONE

        // Move interval controls
        setupNumberField(
            binding.moveIntervalInput,
            binding.moveIntervalMinus,
            binding.moveIntervalPlus,
            moveInterval,
            30, 200, 10,
            { moveInterval = it }
        )

        // Click interval controls
        setupNumberField(
            binding.clickIntervalInput,
            binding.clickIntervalMinus,
            binding.clickIntervalPlus,
            clickInterval,
            500, 5000, 100,
            { clickInterval = it }
        )

        // Max distance controls
        setupNumberField(
            binding.maxDistanceInput,
            binding.maxDistanceMinus,
            binding.maxDistancePlus,
            maxDistance.toLong(),
            20, 200, 10,
            { maxDistance = it.toInt() }
        )

        // Duration minute controls
        setupNumberField(
            binding.durationMinutesInput,
            binding.durationMinutesMinus,
            binding.durationMinutesPlus,
            durationMinutes.toLong(),
            0, 59, 1,
            { durationMinutes = it.toInt() }
        )

        // Duration second controls
        setupNumberField(
            binding.durationSecondsInput,
            binding.durationSecondsMinus,
            binding.durationSecondsPlus,
            durationSeconds.toLong(),
            0, 59, 5,
            { durationSeconds = it.toInt() }
        )

        // Preset buttons for speed
        binding.presetSlow.setOnClickListener {
            moveInterval = 100L
            clickInterval = 3000L
            updateInputFields()
            if (isEnabled) updateServiceState()
        }

        binding.presetNormal.setOnClickListener {
            moveInterval = 50L
            clickInterval = 2000L
            updateInputFields()
            if (isEnabled) updateServiceState()
        }

        binding.presetFast.setOnClickListener {
            moveInterval = 30L
            clickInterval = 1000L
            updateInputFields()
            if (isEnabled) updateServiceState()
        }
    }

    private fun setupNumberField(
        editText: EditText,
        minusBtn: Button,
        plusBtn: Button,
        initialValue: Long,
        min: Long,
        max: Long,
        step: Long,
        onValueChange: (Long) -> Unit
    ) {
        editText.setText(initialValue.toString())
        
        minusBtn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = maxOf(min, current - step)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            if (isEnabled) updateServiceState()
        }

        plusBtn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = minOf(max, current + step)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            if (isEnabled) updateServiceState()
        }

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = editText.text.toString().toLongOrNull() ?: initialValue
                val clamped = value.coerceIn(min, max)
                editText.setText(clamped.toString())
                onValueChange(clamped)
                if (isEnabled) updateServiceState()
            }
        }
    }

    private fun updateInputFields() {
        binding.moveIntervalInput.setText(moveInterval.toString())
        binding.clickIntervalInput.setText(clickInterval.toString())
        binding.maxDistanceInput.setText(maxDistance.toString())
        binding.durationMinutesInput.setText(durationMinutes.toString())
        binding.durationSecondsInput.setText(durationSeconds.toString())
    }

    private fun checkConnectionExists(): Boolean {
        // Check if there's an active Bluetooth connection
        val hasConnection = try {
            // Try to send a dummy event to test connection
            connectionManager.send(com.darusc.mousedroid.mkinput.InputEvent.MouseMove(0, 0, com.darusc.mousedroid.mkinput.InputEvent.MouseButton.NONE), false)
            true
        } catch (e: Exception) {
            false
        }
        return hasConnection
    }

    private fun stopAutoMouse() {
        val context = requireContext()
        val intent = Intent(context, AutoMouseService::class.java)
        intent.action = AutoMouseService.ACTION_STOP
        try {
            context.startService(intent)
        } catch (e: Exception) {
            // Silently handle errors
        }
    }

    private fun updateServiceState() {
        val context = requireContext()
        val intent = Intent(context, AutoMouseService::class.java)
        
        if (isEnabled) {
            val totalSeconds = durationMinutes * 60L + durationSeconds
            
            intent.action = AutoMouseService.ACTION_START
            intent.putExtra(AutoMouseService.EXTRA_MOVE_INTERVAL, moveInterval)
            intent.putExtra(AutoMouseService.EXTRA_CLICK_INTERVAL, clickInterval)
            intent.putExtra(AutoMouseService.EXTRA_MAX_DISTANCE, maxDistance)
            intent.putExtra(AutoMouseService.EXTRA_DURATION_SECONDS, totalSeconds)
            
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.autoMouseToggle.isChecked = false
                isEnabled = false
            }
        } else {
            stopAutoMouse()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isEnabled) {
            val context = requireContext()
            val intent = Intent(context, AutoMouseService::class.java)
            intent.action = AutoMouseService.ACTION_STOP
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }
}
