package com.enviroguard.app.ui.guidance

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.databinding.FragmentGuidanceBinding
import com.enviroguard.app.model.EnvironmentalGuidance
import com.enviroguard.app.ui.home.HomeViewModel
import com.enviroguard.app.utils.ViewModelFactory

class GuidanceFragment : Fragment() {
    private var _binding: FragmentGuidanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentGuidanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val repository = (requireActivity().application as EnviroGuardApp).repository
        viewModel = ViewModelProvider(this, ViewModelFactory(repository))[HomeViewModel::class.java]
        viewModel.status.observe(viewLifecycleOwner) { binding.tvGuidanceStatus.text = it }
        viewModel.assessment.observe(viewLifecycleOwner) { assessment ->
            binding.tvGuidanceCondition.text = assessment.overallCondition.displayName
            binding.tvGuidanceConcerns.text = if (assessment.primaryConcerns.isEmpty()) "Current measured environmental conditions are within the system's normal advisory ranges.\nContinue monitoring for changes." else "Primary concerns: ${assessment.primaryConcerns.joinToString { it.displayName }}"
            showGuidance(if (assessment.primaryConcerns.isEmpty()) assessment.guidance else assessment.guidance.filter { it.condition.severity > 0 })
        }
        binding.btnAskAi.setOnClickListener { binding.tvAiStatus.text = "AI environmental guidance is not connected yet." }
        viewModel.initialise()
    }

    private fun showGuidance(items: List<EnvironmentalGuidance>) {
        binding.guidanceContainer.removeAllViews()
        items.forEach { item ->
            val text = buildString {
                append(item.title).append(" — ").append(item.condition.displayName).append("\n\n")
                append("Possible effects\n").append(item.possibleEffects.joinToString("\n") { "• $it" }).append("\n\n")
                append("Practical steps\n").append(item.recommendations.joinToString("\n") { "• $it" }).append("\n\n")
                append("Reference: ").append(item.sourceLabel)
                item.disclaimer?.let { append("\n").append(it) }
            }
            binding.guidanceContainer.addView(TextView(requireContext()).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.parseColor("#1A3A50"))
                setBackgroundResource(com.enviroguard.app.R.drawable.bg_suggestion_box)
                setPadding(32, 28, 32, 28)
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
            })
        }
    }

    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.refreshSettings() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
