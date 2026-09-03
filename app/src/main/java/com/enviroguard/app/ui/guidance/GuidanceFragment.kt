package com.enviroguard.app.ui.guidance

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.databinding.FragmentGuidanceBinding
import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.EnvironmentalGuidance
import com.enviroguard.app.ui.ConditionUi
import com.enviroguard.app.ui.home.HomeViewModel
import com.enviroguard.app.utils.ViewModelFactory
import com.enviroguard.app.ai.AiExplanationState
import com.google.android.material.card.MaterialCardView

class GuidanceFragment : Fragment() {
    private var _binding: FragmentGuidanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentGuidanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val app = requireActivity().application as EnviroGuardApp
        viewModel = ViewModelProvider(
            this,
            ViewModelFactory(app.repository, app.alertManager, app.geminiExplanationService)
        )[HomeViewModel::class.java]
        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.tvGuidanceStatus.text = when (status) {
                "No Device" -> "No monitoring device configured."
                "Waiting for environmental data" -> "Waiting for environmental data..."
                "Environmental data unavailable" -> "Guidance is temporarily unavailable because current data could not be loaded."
                "Demo Mode" -> "Guidance based on clearly labelled demo measurements."
                else -> "Guidance based on the current deterministic environmental assessment."
            }
        }
        viewModel.assessment.observe(viewLifecycleOwner) { assessment ->
            assessment?.let(::renderAssessment) ?: renderUnavailable()
        }
        viewModel.aiExplanationState.observe(viewLifecycleOwner, ::renderAiState)
        binding.btnAskAi.setOnClickListener { viewModel.requestAiExplanation() }
        viewModel.initialise()
    }

    private fun renderAiState(state: AiExplanationState) {
        binding.btnAskAi.isEnabled = state !is AiExplanationState.Loading
        binding.aiLoading.visibility = if (state is AiExplanationState.Loading) View.VISIBLE else View.GONE
        when (state) {
            AiExplanationState.Idle -> {
                binding.tvAiStatus.text = ""
                binding.cardAiExplanation.visibility = View.GONE
            }
            AiExplanationState.Loading -> {
                binding.tvAiStatus.setText(R.string.ai_explanation_loading)
                binding.cardAiExplanation.visibility = View.GONE
            }
            is AiExplanationState.Success -> {
                binding.tvAiStatus.text = ""
                binding.tvAiExplanationLabel.setText(
                    if (state.isDemo) R.string.ai_explanation_demo_label else R.string.ai_explanation_live_label
                )
                binding.tvAiExplanation.text = state.text
                binding.cardAiExplanation.visibility = View.VISIBLE
            }
            is AiExplanationState.Error -> {
                binding.tvAiStatus.text = state.message
                binding.cardAiExplanation.visibility = View.GONE
            }
        }
    }

    private fun renderAssessment(assessment: EnvironmentalAssessment) {
        if (assessment.overallCondition == null) {
            renderUnavailable()
            return
        }
        ConditionUi.applyChip(binding.tvGuidanceCondition, assessment.overallCondition)
        binding.tvGuidanceConcernsLabel.setText(if (assessment.primaryConcerns.size > 1) R.string.main_concerns else R.string.main_concern)
        binding.tvGuidanceConcerns.text = if (assessment.primaryConcerns.isEmpty()) {
            "No primary concern\nCurrent measurements are within normal advisory ranges. Continue monitoring for changes."
        } else {
            assessment.primaryConcerns.joinToString(" and ") { dimensionLabel(it) }
        }
        val relevant = if (assessment.primaryConcerns.isEmpty()) {
            assessment.guidance
        } else {
            assessment.guidance.sortedByDescending { it.dimension in assessment.primaryConcerns }.filter { it.condition.severity > 0 }
        }
        showGuidance(relevant, assessment.primaryConcerns)
    }

    private fun renderUnavailable() {
        binding.tvGuidanceConcernsLabel.setText(R.string.main_concern)
        binding.tvGuidanceCondition.apply {
            text = "NO CURRENT DATA"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.ehm_surface_tonal))
        }
        binding.tvGuidanceConcerns.text = "Connect or select a device to receive measurement-based guidance."
        binding.guidanceContainer.removeAllViews()
    }

    private fun showGuidance(items: List<EnvironmentalGuidance>, primary: List<EnvironmentalDimension>) {
        binding.guidanceContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val isPrimary = item.dimension in primary || primary.isEmpty() && index == 0
            binding.guidanceContainer.addView(guidanceCard(item, isPrimary))
        }
    }

    private fun guidanceCard(item: EnvironmentalGuidance, prominent: Boolean): MaterialCardView {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, if (prominent) R.color.ehm_surface else R.color.ehm_surface_tonal))
            if (prominent) {
                strokeWidth = dp(2)
                strokeColor = ContextCompat.getColor(context, ConditionUi.accent(item.condition))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val header = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ImageView(requireContext()).apply {
            setImageResource(dimensionIcon(item.dimension))
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, ConditionUi.accent(item.condition)))
            contentDescription = item.dimension.displayName
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
        })
        header.addView(TextView(requireContext()).apply {
            text = item.title
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_condition_chip)
            ConditionUi.applyChip(this, item.condition)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(header)
        content.addView(sectionLabel("Possible effects"))
        item.possibleEffects.forEach { content.addView(bodyRow(it, false)) }
        content.addView(sectionLabel("Recommended actions"))
        item.recommendations.forEach { content.addView(bodyRow(it, true)) }
        content.addView(TextView(requireContext()).apply {
            text = "Reference: ${item.sourceLabel}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface_variant))
            setPadding(0, dp(14), 0, 0)
        })
        item.disclaimer?.let { note ->
            content.addView(TextView(requireContext()).apply {
                text = note
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface_variant))
                setPadding(0, dp(6), 0, 0)
            })
        }
        card.addView(content)
        return card
    }

    private fun sectionLabel(label: String) = TextView(requireContext()).apply {
        text = label
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface_variant))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun bodyRow(textValue: String, action: Boolean) = TextView(requireContext()).apply {
        text = if (action) "✓  $textValue" else textValue
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dimensionIcon(dimension: EnvironmentalDimension) = when (dimension) {
        EnvironmentalDimension.THERMAL -> R.drawable.ic_thermostat
        EnvironmentalDimension.AIR -> R.drawable.ic_air
        EnvironmentalDimension.NOISE -> R.drawable.ic_noise
    }

    private fun dimensionLabel(dimension: EnvironmentalDimension) = when (dimension) {
        EnvironmentalDimension.THERMAL -> "Thermal"
        EnvironmentalDimension.AIR -> "Air"
        EnvironmentalDimension.NOISE -> "Noise"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.refreshSettings() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
