package com.enviroguard.app.ui.home

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.databinding.FragmentHomeBinding
import com.enviroguard.app.utils.TemperatureUtils
import com.enviroguard.app.utils.ViewModelFactory

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View { _binding = FragmentHomeBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        viewModel = ViewModelProvider(this, ViewModelFactory((requireActivity().application as EnviroGuardApp).repository))[HomeViewModel::class.java]
        viewModel.status.observe(viewLifecycleOwner) { binding.tvConnectionStatus.text = "● $it" }
        viewModel.sensorReading.observe(viewLifecycleOwner) { r ->
            binding.tvTemperature.text = "%.1f°C".format(r.temperature); binding.tvHumidity.text = "%.0f%%".format(r.humidity)
            binding.tvTvoc.text = "%.0f ppb".format(r.tvoc); binding.tvEco2.text = "%.0f ppm eCO₂".format(r.eco2); binding.tvNoise.text = "%.0f estimated".format(r.noiseLevel)
        }
        viewModel.assessment.observe(viewLifecycleOwner) { a ->
            binding.tvErsScore.text = a.overallCondition.displayName
            binding.tvErsClass.text = "Environmental Condition"
            binding.tvMainContributor.text = if (a.primaryConcerns.isEmpty()) "No primary concern" else "Primary concern: ${a.primaryConcerns.joinToString { it.displayName }}"
            binding.tvRecommendation.text = a.guidance.flatMap { it.recommendations }.firstOrNull() ?: "Conditions are currently within advisory bands."
            binding.tvHeatIndex.text = "%.1f°C".format(a.heatIndexCelsius)
            binding.tvHeatIndexStatus.text = "Heat Index"
        }
        binding.btnAiSuggestion.setOnClickListener { binding.tvRecommendation.text = "AI guidance will be enabled later." }
        viewModel.initialise()
    }
    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.refreshSettings() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
