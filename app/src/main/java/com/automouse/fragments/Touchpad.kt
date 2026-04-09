package com.automouse.fragments

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.automouse.mkinput.GestureHandler
import com.automouse.R
import com.automouse.databinding.FragmentTouchpadBinding
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.transition.TransitionManager
import com.automouse.viewmodels.TouchpadViewModel

class Touchpad : Fragment() {

    private lateinit var binding: FragmentTouchpadBinding

    private val viewModel: TouchpadViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_touchpad, container, false)
        binding.viewmodel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner

        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup touchpad gesture listener and button listeners
        val gestureHandler = GestureHandler(requireContext()) { event ->
            viewModel.sendMouseEvent(event)
        }
        binding.touchpadSensor.setOnTouchListener(gestureHandler)

        // Multimedia dropdown
        binding.btnToggleMedia.setOnVibratingClickListener {
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup)

            val isHidden = binding.mediaControls.isGone
            binding.mediaControls.visibility = if (isHidden) View.VISIBLE else View.GONE
            binding.btnToggleMedia.setIconResource(
                if (isHidden) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Reset the orientation when leaving the Touchpad
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

}
