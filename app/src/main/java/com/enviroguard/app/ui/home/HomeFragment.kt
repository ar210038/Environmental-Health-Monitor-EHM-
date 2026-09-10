package com.enviroguard.app.ui.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.databinding.FragmentHomeBinding
import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.ui.ConditionUi
import com.enviroguard.app.utils.DeviceStatusEvaluator
import com.enviroguard.app.utils.TemperatureUtils
import com.enviroguard.app.utils.ViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel
    private var lastReading: SensorReading? = null

    private val freshnessUpdater = object : Runnable {
        override fun run() {
            if (_binding == null) return
            renderFreshness()
            binding.root.postDelayed(this, 5_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val app = requireActivity().application as EnviroGuardApp
        viewModel = ViewModelProvider(this, ViewModelFactory(app.repository, app.alertManager))[HomeViewModel::class.java]
        showUnavailableReadings()
        renderActiveDevice()

        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.tvDashboardStatus.text = when (status) {
                "No Device" -> "No monitoring device configured. Add a device from Settings when provisioning is available."
                "Waiting for environmental data" -> "Waiting for environmental data..."
                "Environmental data unavailable" -> "Current data is temporarily unavailable. Last valid values remain visible when available."
                "Demo Mode" -> "Demo values are shown for interface testing and are not live measurements."
                "Scenario Test" -> "Simulated values — not live sensor data"
                else -> "Live environmental measurements"
            }
            renderActiveDevice()
            renderFreshness()
        }

        viewModel.sensorReading.observe(viewLifecycleOwner) { reading ->
            lastReading = reading
            if (reading == null) showUnavailableReadings() else renderReading(reading)
            renderFreshness()
        }

        viewModel.assessment.observe(viewLifecycleOwner) { assessment ->
            if (assessment == null) return@observe
            renderAssessment(assessment)
        }

        binding.btnOpenSettings.setOnClickListener { findNavController().navigate(R.id.settingsFragment) }
        binding.cardActiveDevice.setOnClickListener { showDeviceSelector() }
        viewModel.initialise()
        binding.root.post(freshnessUpdater)
    }

    private fun renderActiveDevice() {
        val active = DeviceManager.getActiveDevice()
        binding.tvActiveDeviceName.text = if (viewModel.status.value == "Scenario Test") {
            "Scenario Test"
        } else {
            active?.displayName ?: "No monitoring device"
        }
    }

    private fun renderReading(reading: SensorReading) {
        binding.tvTemperature.text = formatTemperature(reading.temperature)
        binding.tvHumidity.text = format(reading.humidity, "%.0f%%")
        binding.tvTvoc.text = format(reading.tvoc, "%.0f ppb")
        binding.tvEco2.text = format(reading.eco2, "%.0f ppm")
        binding.tvNoiseLevel.text = format(reading.noiseLevel, "%.0f dB estimated")
        binding.tvAirHeadline.text = if (reading.tvoc.isFinite()) "TVOC %.0f ppb".format(reading.tvoc) else "TVOC unavailable"
        binding.tvNoiseHeadline.text = if (reading.noiseLevel.isFinite()) "Estimated Noise Level %.0f dB".format(reading.noiseLevel) else "Estimated Noise Level unavailable"
    }

    private fun renderAssessment(assessment: EnvironmentalAssessment) {
        ConditionUi.applyChip(binding.tvEnvironmentalCondition, assessment.overallCondition)
        ConditionUi.applyChip(binding.tvThermalCondition, assessment.thermalCondition)
        ConditionUi.applyChip(binding.tvAirCondition, assessment.airCondition)
        ConditionUi.applyChip(binding.tvNoiseCondition, assessment.noiseCondition)
        binding.tvPrimaryConcernsLabel.setText(if (assessment.primaryConcerns.size > 1) R.string.main_concerns else R.string.main_concern)
        binding.tvPrimaryConcerns.text = when {
            assessment.overallCondition == null -> "Unavailable"
            assessment.primaryConcerns.isEmpty() -> "No primary concern"
            else -> assessment.primaryConcerns.joinToString(" and ") { dimensionLabel(it) }
        }
        binding.tvHeatIndex.text = formatTemperature(assessment.heatIndexCelsius)
        binding.tvThermalHeadline.text = if (assessment.heatIndexCelsius?.isFinite() == true) "Heat index ${formatTemperature(assessment.heatIndexCelsius)}" else "Heat index unavailable"
        if (assessment.overallCondition == null) {
            binding.tvConditionContext.setText(R.string.assessment_unavailable_detail)
            return
        }
        val priorityGuidance = assessment.guidance.firstOrNull { it.dimension in assessment.primaryConcerns }
            ?: assessment.guidance.firstOrNull()
        binding.tvConditionContext.text = priorityGuidance?.recommendations?.firstOrNull()
            ?: "Continue monitoring for environmental changes."
    }

    private fun renderFreshness() {
        val active = DeviceManager.getActiveDevice()
        val status = viewModel.status.value
        binding.tvConnectionStatus.text = when {
            status == "Scenario Test" -> "Simulated values — not live sensor data"
            DeviceManager.isDemoMode -> "Demo data • Not a live measurement"
            active == null -> "No monitoring device configured"
            status == "Environmental data unavailable" && lastReading == null -> "Current data unavailable"
            lastReading == null -> "Waiting for environmental data..."
            else -> {
                val age = (System.currentTimeMillis() - lastReading!!.timestamp).coerceAtLeast(0L)
                when {
                    DeviceStatusEvaluator.isRecentlySeen(lastReading!!.timestamp, System.currentTimeMillis()) -> "● Live • Updated ${age / 1_000}s ago"
                    age <= 5 * 60_000L -> "Last update ${maxOf(1L, age / 60_000L)} min ago"
                    else -> "● Offline • Last update ${age / 60_000L} min ago"
                }
            }
        }
        val color = when {
            DeviceManager.isDemoMode -> R.color.ehm_moderate
            active == null || lastReading == null -> R.color.ehm_on_surface_variant
            DeviceStatusEvaluator.isRecentlySeen(lastReading!!.timestamp, System.currentTimeMillis()) -> R.color.ehm_good
            else -> R.color.ehm_poor
        }
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    private fun showDeviceSelector() {
        val devices = DeviceManager.getSavedDevices()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Your Devices")
                .setMessage("No monitoring devices are registered yet. Device provisioning will be completed in the next setup phase.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add device") { _, _ -> findNavController().navigate(R.id.connectFragment) }
                .show()
            return
        }
        val activeId = DeviceManager.activeDeviceId
        val labels = devices.map { device ->
            buildString {
                append(device.displayName).append('\n').append(device.deviceId)
                if (device.deviceId == activeId) append("  •  Current device")
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Your Devices")
            .setSingleChoiceItems(labels, devices.indexOfFirst { it.deviceId == activeId }) { dialog, index ->
                DeviceManager.setActiveDevice(devices[index].deviceId)
                renderActiveDevice()
                lastReading = null
                showUnavailableReadings()
                viewModel.initialise()
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Manage") { _, _ -> findNavController().navigate(R.id.settingsFragment) }
            .setPositiveButton("Add another device") { _, _ -> findNavController().navigate(R.id.connectFragment) }
            .show()
    }

    private fun showUnavailableReadings() {
        listOf(binding.tvEnvironmentalCondition, binding.tvThermalCondition, binding.tvAirCondition, binding.tvNoiseCondition).forEach { view ->
            view.text = "Unavailable"
            view.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant))
            view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.ehm_surface_variant))
        }
        binding.tvPrimaryConcernsLabel.setText(R.string.main_concern)
        binding.tvPrimaryConcerns.text = "Waiting for data"
        binding.tvConditionContext.text = "Condition guidance will appear when data is available."
        binding.tvTemperature.text = "Unavailable"
        binding.tvHumidity.text = "Unavailable"
        binding.tvHeatIndex.text = "Unavailable"
        binding.tvTvoc.text = "Unavailable"
        binding.tvEco2.text = "Unavailable"
        binding.tvNoiseLevel.text = "Unavailable"
        binding.tvThermalHeadline.text = "Heat index unavailable"
        binding.tvAirHeadline.text = "TVOC unavailable"
        binding.tvNoiseHeadline.text = "Estimated Noise Level unavailable"
    }

    private fun dimensionLabel(dimension: EnvironmentalDimension) = when (dimension) {
        EnvironmentalDimension.THERMAL -> "Thermal"
        EnvironmentalDimension.AIR -> "Air"
        EnvironmentalDimension.NOISE -> "Noise"
    }

    private fun format(value: Float, pattern: String) = if (value.isFinite()) pattern.format(value) else "Unavailable"
    private fun formatTemperature(value: Float?): String {
        if (value == null || !value.isFinite()) return "Unavailable"
        return if (DeviceManager.useCelsius) "%.1f°C".format(value) else "%.1f°F".format(TemperatureUtils.celsiusToFahrenheit(value))
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            renderActiveDevice()
            lastReading = null
            viewModel.refreshSettings()
        }
    }

    override fun onDestroyView() {
        binding.root.removeCallbacks(freshnessUpdater)
        super.onDestroyView()
        _binding = null
    }
}
