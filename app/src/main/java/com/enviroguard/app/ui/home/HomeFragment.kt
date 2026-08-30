package com.enviroguard.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.databinding.FragmentHomeBinding
import com.enviroguard.app.utils.TemperatureUtils
import com.enviroguard.app.utils.ViewModelFactory

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val repository = (requireActivity().application as EnviroGuardApp).repository
        viewModel = ViewModelProvider(this, ViewModelFactory(repository))[HomeViewModel::class.java]
        showUnavailableReadings()
        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.tvConnectionStatus.text = "● $status"
            binding.tvDashboardStatus.text = when (status) {
                "No Device" -> "No monitoring device configured."
                "Waiting for environmental data" -> "Waiting for environmental data..."
                "Environmental data unavailable" -> "Current environmental data is unavailable. Check the device and connection."
                "Demo Mode" -> "Demonstration values are shown and are not live measurements."
                else -> "Live environmental measurements"
            }
        }
        viewModel.sensorReading.observe(viewLifecycleOwner) { reading ->
            if (reading == null) showUnavailableReadings() else {
                binding.tvTemperature.text = formatTemperature(reading.temperature)
                binding.tvHumidity.text = format(reading.humidity, "%.0f%%")
                binding.tvTvoc.text = format(reading.tvoc, "%.0f ppb")
                binding.tvEco2.text = format(reading.eco2, "%.0f ppm")
                binding.tvNoiseLevel.text = format(reading.noiseLevel, "%.0f dB estimated")
            }
        }
        viewModel.assessment.observe(viewLifecycleOwner) { assessment ->
            if (assessment == null) return@observe
            binding.tvEnvironmentalCondition.text = assessment.overallCondition.displayName
            binding.tvPrimaryConcerns.text = if (assessment.primaryConcerns.isEmpty()) "No primary concerns" else assessment.primaryConcerns.joinToString { it.displayName }
            binding.tvThermalCondition.text = assessment.thermalCondition.displayName
            binding.tvAirCondition.text = assessment.airCondition.displayName
            binding.tvNoiseCondition.text = assessment.noiseCondition.displayName
            binding.tvHeatIndex.text = formatTemperature(assessment.heatIndexCelsius)
        }
        binding.btnOpenSettings.setOnClickListener { findNavController().navigate(R.id.settingsFragment) }
        viewModel.initialise()
    }

    private fun showUnavailableReadings() {
        binding.tvEnvironmentalCondition.text = "Unavailable"
        binding.tvPrimaryConcerns.text = "Waiting for data"
        binding.tvThermalCondition.text = "Unavailable"
        binding.tvAirCondition.text = "Unavailable"
        binding.tvNoiseCondition.text = "Unavailable"
        binding.tvTemperature.text = "Unavailable"
        binding.tvHumidity.text = "Unavailable"
        binding.tvHeatIndex.text = "Unavailable"
        binding.tvTvoc.text = "Unavailable"
        binding.tvEco2.text = "Unavailable"
        binding.tvNoiseLevel.text = "Unavailable"
    }

    private fun format(value: Float, pattern: String) = if (value.isFinite()) pattern.format(value) else "Unavailable"
    private fun formatTemperature(value: Float?): String {
        if (value == null || !value.isFinite()) return "Unavailable"
        return if (DeviceManager.useCelsius) "%.1f°C".format(value) else "%.1f°F".format(TemperatureUtils.celsiusToFahrenheit(value))
    }

    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.refreshSettings() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
