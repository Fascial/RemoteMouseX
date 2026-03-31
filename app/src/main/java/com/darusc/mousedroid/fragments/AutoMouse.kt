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
    private var activePattern = AutoMouseService.PATTERN_SMOOTH_LINEAR
    
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
            if (isChecked) {
                updateServiceState()
            } else {
                stopAutoMouse()
            }
        }
        
        // Setup Run Infinitely Toggle
        binding.runInfinitelyToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.durationInputsLayout.visibility = View.GONE
                durationMinutes = 0
                durationSeconds = 0
                updateInputFields()
            } else {
                binding.durationInputsLayout.visibility = View.VISIBLE
            }
            val updateSummary = {
                updateConfigurationSummary()
                if (isEnabled) updateServiceState()
            }
            updateSummary()
        }

        // Settings always visible regardless of toggle state
        binding.settingsContainer.visibility = View.VISIBLE

        // Move interval controls
        setupNumberFieldAdvanced(
            binding.moveIntervalInput,
            binding.moveIntervalMinus, binding.moveIntervalMinus10,
            binding.moveIntervalPlus, binding.moveIntervalPlus10,
            moveInterval, 5, 200, 5, 25,
            { moveInterval = it }
        )

        // Click interval controls
        setupNumberFieldAdvanced(
            binding.clickIntervalInput,
            binding.clickIntervalMinus, binding.clickIntervalMinus10,
            binding.clickIntervalPlus, binding.clickIntervalPlus10,
            clickInterval, 50, 5000, 50, 500,
            { clickInterval = it }
        )

        // Max distance controls
        setupNumberFieldAdvanced(
            binding.maxDistanceInput,
            binding.maxDistanceMinus, binding.maxDistanceMinus10,
            binding.maxDistancePlus, binding.maxDistancePlus10,
            maxDistance.toLong(), 5, 500, 5, 50,
            { maxDistance = it.toInt() }
        )

        // Duration minute controls (simple setup)
        setupNumberField(
            binding.durationMinutesInput,
            binding.durationMinutesMinus,
            binding.durationMinutesPlus,
            durationMinutes.toLong(),
            0, 59, 1,
            { durationMinutes = it.toInt() }
        )

        // Duration second controls (simple setup)
        setupNumberField(
            binding.durationSecondsInput,
            binding.durationSecondsMinus,
            binding.durationSecondsPlus,
            durationSeconds.toLong(),
            0, 59, 5,
            { durationSeconds = it.toInt() }
        )

        // Preset patterns
        binding.presetSmoothLinear.setOnClickListener {
            activePattern = AutoMouseService.PATTERN_SMOOTH_LINEAR
            updatePatternSelectionUI()
            if (isEnabled) updateServiceState()
        }
        binding.presetCircularTrace.setOnClickListener {
            activePattern = AutoMouseService.PATTERN_CIRCULAR_TRACE
            updatePatternSelectionUI()
            if (isEnabled) updateServiceState()
        }
        binding.presetErraticJumps.setOnClickListener {
            activePattern = AutoMouseService.PATTERN_ERRATIC_JUMPS
            updatePatternSelectionUI()
            if (isEnabled) updateServiceState()
        }
        binding.presetPatternRepeat.setOnClickListener {
            activePattern = AutoMouseService.PATTERN_PATTERN_REPEAT
            updatePatternSelectionUI()
            if (isEnabled) updateServiceState()
        }

        // Initial select
        updatePatternSelectionUI()

        // Initialize configuration summary
        updateConfigurationSummary()
    }

    private fun updatePatternSelectionUI() {
        binding.presetSmoothLinear.isSelected = activePattern == AutoMouseService.PATTERN_SMOOTH_LINEAR
        binding.presetCircularTrace.isSelected = activePattern == AutoMouseService.PATTERN_CIRCULAR_TRACE
        binding.presetErraticJumps.isSelected = activePattern == AutoMouseService.PATTERN_ERRATIC_JUMPS
        binding.presetPatternRepeat.isSelected = activePattern == AutoMouseService.PATTERN_PATTERN_REPEAT
    }

    private fun setupNumberFieldAdvanced(
        editText: EditText,
        minusBtn: Button, minus10Btn: Button,
        plusBtn: Button, plus10Btn: Button,
        initialValue: Long, min: Long, max: Long, step: Long, largeStep: Long,
        onValueChange: (Long) -> Unit
    ) {
        editText.setText(initialValue.toString())
        
        val updateSummary = {
            updateConfigurationSummary()
            if (isEnabled) updateServiceState()
        }
        
        minusBtn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = maxOf(min, current - step)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            updateSummary()
        }

        minus10Btn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = maxOf(min, current - largeStep)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            updateSummary()
        }

        plusBtn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = minOf(max, current + step)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            updateSummary()
        }

        plus10Btn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = minOf(max, current + largeStep)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            updateSummary()
        }

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = editText.text.toString().toLongOrNull() ?: initialValue
                val clamped = value.coerceIn(min, max)
                editText.setText(clamped.toString())
                onValueChange(clamped)
                updateSummary()
            }
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
        val updateSummary = {
            updateConfigurationSummary()
            if (isEnabled) updateServiceState()
        }
        minusBtn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = maxOf(min, current - step)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            updateSummary()
        }
        plusBtn.setOnClickListener {
            val current = editText.text.toString().toLongOrNull() ?: initialValue
            val newValue = minOf(max, current + step)
            editText.setText(newValue.toString())
            onValueChange(newValue)
            updateSummary()
        }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = editText.text.toString().toLongOrNull() ?: initialValue
                val clamped = value.coerceIn(min, max)
                editText.setText(clamped.toString())
                onValueChange(clamped)
                updateSummary()
            }
        }
    }

    private fun updateInputFields() {
        binding.moveIntervalInput.setText(moveInterval.toString())
        binding.clickIntervalInput.setText(clickInterval.toString())
        binding.maxDistanceInput.setText(maxDistance.toString())
        binding.durationMinutesInput.setText(durationMinutes.toString())
        binding.durationSecondsInput.setText(durationSeconds.toString())
        updateConfigurationSummary()
    }

    private fun updateConfigurationSummary() {
        binding.summaryMove.text = "${moveInterval}ms"
        binding.summaryClick.text = if (clickInterval >= 1000) "${clickInterval/1000f}s" else "${clickInterval}ms"
        binding.summaryDist.text = "${maxDistance}px"

        if (durationMinutes == 0 && durationSeconds == 0) {
            binding.summaryDuration.text = "Unlimited"
        } else {
            binding.summaryDuration.text = String.format("%02d:%02d", durationMinutes, durationSeconds)
        }
    }

    private fun checkConnectionExists(): Boolean {
        return try {
            connectionManager.isConnected()
        } catch (e: Exception) {
            false
        }
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
            intent.putExtra(AutoMouseService.EXTRA_PATTERN_TYPE, activePattern)
            
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
