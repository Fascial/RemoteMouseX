package com.automouse.fragments

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.automouse.mkinput.GestureHandler
import com.automouse.R
import com.automouse.databinding.FragmentRecorderBinding
import androidx.fragment.app.activityViewModels
import com.automouse.viewmodels.RecorderViewModel

class Recorder : Fragment() {

    private lateinit var binding: FragmentRecorderBinding

    private val viewModel: RecorderViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_recorder, container, false)
        binding.viewmodel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner

        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup touchpad gesture listener
        val gestureHandler = GestureHandler(requireContext()) { event ->
            viewModel.recordEvent(event)
        }
        binding.touchpadSensor.setOnTouchListener(gestureHandler)

        // Record action with Haptic feedback
        binding.btnRecord.setOnVibratingClickListener {
            val vibrator = requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
            viewModel.toggleRecording()
        }

        // Recording state observers
        viewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            binding.btnRecord.text = if (isRecording) "STOP" else "REC"
            updateStatusSummary()
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            binding.btnPlayback.setIconResource(
                if (isPlaying) com.automouse.R.drawable.ic_pause else com.automouse.R.drawable.ic_play
            )
            updateStatusSummary()
        }

        viewModel.recordedEventCount.observe(viewLifecycleOwner) { count ->
            val hasEvents = count > 0
            binding.btnPlayback.isEnabled = hasEvents
            binding.btnSave.isEnabled = hasEvents
            binding.btnClearRecording.isEnabled = hasEvents
            
            val activeColor = android.graphics.Color.WHITE
            val inactiveColor = android.graphics.Color.parseColor("#666666")
            binding.btnClearRecording.setTextColor(if (hasEvents) activeColor else inactiveColor)
            
            updateStatusSummary()
        }

        viewModel.playbackMultiplier.observe(viewLifecycleOwner) { multiplier ->
            if (binding.playbackIntervalInput.text.toString() != multiplier.toString()) {
                binding.playbackIntervalInput.setText(multiplier.toString())
            }
        }

        binding.playbackIntervalInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = binding.playbackIntervalInput.text.toString()
                if (text.isNotEmpty()) {
                    try {
                        val value = text.toFloat()
                        viewModel.playbackMultiplier.value = value.coerceIn(0.1f, 10.0f)
                    } catch (e: NumberFormatException) {
                        binding.playbackIntervalInput.setText(viewModel.playbackMultiplier.value.toString())
                    }
                }
            }
        }

        // Button click handlers
        binding.btnSave.setOnVibratingClickListener {
            if (viewModel.saveRecording(requireContext())) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Recording saved successfully!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                viewModel.refreshSavedRecordings(requireContext())
            } else {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Failed to save recording",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnClearRecording.setOnVibratingClickListener {
            viewModel.clearRecording()
        }

        binding.btnPlayback.setOnVibratingClickListener {
            viewModel.togglePlayback()
        }
        binding.btnIntervalMinus.setOnVibratingClickListener {
            viewModel.decrementPlaybackInterval()
        }
        binding.btnIntervalPlus.setOnVibratingClickListener {
            viewModel.incrementPlaybackInterval()
        }

        // Saved recordings observer
        viewModel.savedRecordings.observe(viewLifecycleOwner) { recordings ->
            updateSavedRecordingsList(recordings)
        }

        // Initialize saved recordings list
        viewModel.refreshSavedRecordings(requireContext())

        // Initialize UI
        updateStatusSummary()
    }

    private fun updateStatusSummary() {
        val recordingState = viewModel.isRecording.value ?: false
        val playingState = viewModel.isPlaying.value ?: false
        val eventCount = viewModel.recordedEventCount.value ?: 0

        val status = when {
            recordingState -> "Recording"
            playingState -> "Playing"
            eventCount > 0 -> "Ready to play"
            else -> "Ready"
        }

        // binding.recordingEventCount.text = ... (removed from UI)
    }

    private fun updateSavedRecordingsList(recordings: List<String>) {
        binding.savedRecordingsList.removeAllViews()

        if (recordings.isEmpty()) {
            binding.savedRecordingsList.addView(
                android.widget.TextView(requireContext()).apply {
                    text = "(No saved recordings yet)"
                    setTextColor(resources.getColor(R.color.card_text2, null))
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 12; bottomMargin = 12 }
                    setPadding(16, 12, 16, 12)
                }
            )
        } else {
            for (filename in recordings) {
                val recordingItemLayout = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8 }
                }

                val recordingNameView = android.widget.TextView(requireContext()).apply {
                    text = filename.replace(".recording", "").replace("recording_", "")
                    setTextColor(resources.getColor(android.R.color.white, null))
                    textSize = 12f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val loadButton = android.widget.Button(requireContext()).apply {
                    text = "Load"
                    setOnVibratingClickListener {
                        if (viewModel.loadRecording(requireContext(), filename)) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Recording loaded!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Failed to load recording",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 8 }
                    textSize = 10f
                }

                val deleteButton = android.widget.Button(requireContext()).apply {
                    text = "Delete"
                    setOnVibratingClickListener {
                        if (viewModel.deleteRecording(requireContext(), filename)) {
                            viewModel.refreshSavedRecordings(requireContext())
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Recording deleted",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    textSize = 10f
                }

                recordingItemLayout.addView(recordingNameView)
                recordingItemLayout.addView(loadButton)
                recordingItemLayout.addView(deleteButton)
                binding.savedRecordingsList.addView(recordingItemLayout)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Reset the orientation when leaving the Recorder
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
