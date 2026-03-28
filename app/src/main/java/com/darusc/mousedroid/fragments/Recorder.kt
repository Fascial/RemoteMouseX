package com.darusc.mousedroid.fragments

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.darusc.mousedroid.mkinput.GestureHandler
import com.darusc.mousedroid.R
import com.darusc.mousedroid.databinding.FragmentRecorderBinding
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.transition.TransitionManager
import com.darusc.mousedroid.viewmodels.RecorderViewModel

class Recorder : Fragment() {

    private val TAG = "Mousedroid"
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

        // Recording state observers
        viewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            binding.btnRecord.apply {
                text = if (isRecording) "Stop" else "Record"
                setIconResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record)
            }
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) { isPlaying ->
            binding.btnPlayback.apply {
                text = if (isPlaying) "Stop" else "Play"
                setIconResource(if (isPlaying) R.drawable.ic_stop else R.drawable.ic_play)
            }
        }

        viewModel.recordedEventCount.observe(viewLifecycleOwner) { count ->
            binding.btnPlayback.isEnabled = count > 0
            binding.btnSave.isEnabled = count > 0
            binding.recordingEventCount.text = "Recorded: $count events"
        }

        viewModel.playbackInterval.observe(viewLifecycleOwner) { interval ->
            binding.playbackIntervalInput.setText(interval.toString())
        }

        // Button click handlers
        binding.btnSave.setOnClickListener {
            viewModel.saveRecording(requireContext())
        }

        binding.btnClearRecording.setOnClickListener {
            viewModel.clearRecording()
        }

        // Playback options toggle
        binding.playbackOptionsHeader.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup)

            val isHidden = binding.playbackOptions.isGone
            binding.playbackOptions.visibility = if (isHidden) View.VISIBLE else View.GONE
            binding.playbackOptionsHeader.setIconResource(
                if (isHidden) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down
            )
        }
        binding.playbackOptions.visibility = View.GONE

        // Saved recordings toggle
        binding.savedRecordingsHeader.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup)

            val isHidden = binding.savedRecordingsList.isGone
            binding.savedRecordingsList.visibility = if (isHidden) View.VISIBLE else View.GONE
            binding.savedRecordingsHeader.setIconResource(
                if (isHidden) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down
            )
        }
        binding.savedRecordingsList.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Reset the orientation when leaving the Recorder
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
