package com.enviroguard.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.databinding.FragmentHomeBinding
import com.enviroguard.app.external.advisory.ExternalAdvisoryEngine
import com.enviroguard.app.external.model.ExternalAdvisoryType
import com.enviroguard.app.external.model.ExternalAqiCategory
import com.enviroguard.app.external.model.ExternalEnvironmentSnapshot
import com.enviroguard.app.external.model.ExternalEnvironmentState
import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.ui.ConditionUi
import com.enviroguard.app.utils.DeviceStatusEvaluator
import com.enviroguard.app.utils.TemperatureUtils
import com.enviroguard.app.utils.ViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: HomeViewModel
    private lateinit var externalViewModel: ExternalEnvironmentViewModel
    private var lastReading: SensorReading? = null
    private var lastExternalSnapshot: ExternalEnvironmentSnapshot? = null

    private val externalLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (::externalViewModel.isInitialized) {
                externalViewModel.onLocationPermissionResult(granted || hasAnyLocationPermission())
            }
        }

    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (::externalViewModel.isInitialized) externalViewModel.retryAfterLocationSettings()
        }

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
        externalViewModel = ViewModelProvider(
            this,
            ExternalEnvironmentViewModelFactory(
                app.externalEnvironmentRepository,
                app.externalLocationProvider
            )
        )[ExternalEnvironmentViewModel::class.java]
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

        externalViewModel.state.observe(viewLifecycleOwner, ::renderExternalEnvironment)

        binding.btnOpenSettings.setOnClickListener { findNavController().navigate(R.id.settingsFragment) }
        binding.cardActiveDevice.setOnClickListener { showDeviceSelector() }
        binding.btnExternalRefresh.setOnClickListener { handleExternalRefreshAction() }
        binding.btnExternalDetails.setOnClickListener { showExternalDetails() }
        viewModel.initialise()
        externalViewModel.loadIfNeeded()
        binding.root.post(freshnessUpdater)
    }

    private fun handleExternalRefreshAction() {
        when (externalViewModel.state.value) {
            ExternalEnvironmentState.PermissionRequired ->
                externalLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            ExternalEnvironmentState.LocationDisabled ->
                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> externalViewModel.manualRefresh()
        }
    }

    private fun renderExternalEnvironment(state: ExternalEnvironmentState) {
        binding.progressExternalEnvironment.visibility = View.GONE
        binding.groupExternalContent.visibility = View.GONE
        binding.tvExternalStatus.visibility = View.VISIBLE
        binding.tvExternalNotice.visibility = View.GONE
        binding.btnExternalDetails.visibility = View.GONE
        binding.btnExternalRefresh.isEnabled = true
        binding.btnExternalRefresh.text = "Refresh"

        when (state) {
            ExternalEnvironmentState.NotLoaded ->
                binding.tvExternalStatus.text = "Outdoor conditions have not been loaded."
            ExternalEnvironmentState.Loading -> {
                binding.progressExternalEnvironment.visibility = View.VISIBLE
                binding.tvExternalStatus.text = "Loading outdoor conditions..."
                binding.btnExternalRefresh.isEnabled = false
            }
            is ExternalEnvironmentState.Success -> renderExternalSuccess(state)
            ExternalEnvironmentState.PermissionRequired -> {
                binding.tvExternalStatus.text = "Location permission is required to show outdoor conditions."
                binding.btnExternalRefresh.text = "Allow location"
            }
            ExternalEnvironmentState.LocationDisabled -> {
                binding.tvExternalStatus.text = "Turn on location to view outdoor conditions."
                binding.btnExternalRefresh.text = "Open settings"
            }
            ExternalEnvironmentState.LocationUnavailable -> {
                binding.tvExternalStatus.text = "Current location unavailable."
                binding.btnExternalRefresh.text = "Try again"
            }
            ExternalEnvironmentState.Offline -> {
                binding.tvExternalStatus.text = "Outdoor data unavailable offline."
                binding.btnExternalRefresh.text = "Try again"
            }
            is ExternalEnvironmentState.Cooldown -> {
                val minutes = ((state.retryAfterMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
                binding.tvExternalStatus.text = "Recently refreshed. Try again in $minutes min."
                binding.btnExternalRefresh.isEnabled = false
            }
            is ExternalEnvironmentState.RateLimited -> {
                binding.tvExternalStatus.text = if (state.dailyLimitReached) {
                    "Outdoor data refresh limit reached for today."
                } else {
                    "Outdoor data is temporarily rate limited."
                }
                binding.btnExternalRefresh.isEnabled = false
            }
            ExternalEnvironmentState.Error -> {
                binding.tvExternalStatus.text = "Outdoor data temporarily unavailable."
                binding.btnExternalRefresh.text = "Try again"
            }
        }
    }

    private fun renderExternalSuccess(state: ExternalEnvironmentState.Success) {
        val snapshot = state.snapshot
        lastExternalSnapshot = snapshot
        binding.tvExternalStatus.visibility = View.GONE
        binding.groupExternalContent.visibility = View.VISIBLE
        binding.btnExternalDetails.visibility = View.VISIBLE
        binding.tvExternalAqiValue.text = snapshot.usAqi?.toString() ?: "Unavailable"
        binding.tvExternalAqiCategory.text = ExternalAqiCategory.from(snapshot.usAqi)?.displayName
            ?: "AQI unavailable"
        binding.tvExternalTemperature.text = formatExternalTemperature(snapshot.currentTemperatureC)
        binding.tvExternalFeelsLike.text = snapshot.apparentTemperatureC?.let {
            "Feels like ${formatExternalTemperature(it)}"
        } ?: "Feels like unavailable"

        val featuredAdvisory = state.advisories.firstOrNull { it.type == ExternalAdvisoryType.FORECAST }
            ?: state.advisories.firstOrNull()
        binding.tvExternalAdvisoryTitle.text = featuredAdvisory?.title ?: "Forecast Advisory"
        binding.tvExternalAdvisoryMessage.text = featuredAdvisory?.message
            ?: "No forecast advisory at this time."
        binding.tvExternalUpdated.text = "Updated ${formatExternalTime(snapshot.fetchedAt)}"
        state.notice?.let { notice ->
            binding.tvExternalNotice.text = notice
            binding.tvExternalNotice.visibility = View.VISIBLE
        }
    }

    private fun showExternalDetails() {
        val snapshot = lastExternalSnapshot ?: return
        val advisories = ExternalAdvisoryEngine.evaluate(snapshot)
        val details = buildString {
            appendLine("Outdoor AQI: ${snapshot.usAqi?.toString() ?: "Unavailable"}")
            appendLine("AQI category: ${ExternalAqiCategory.from(snapshot.usAqi)?.displayName ?: "Unavailable"}")
            appendLine("PM2.5: ${formatExternalMeasurement(snapshot.pm25MicrogramsPerCubicMetre, "µg/m³")}")
            appendLine("PM10: ${formatExternalMeasurement(snapshot.pm10MicrogramsPerCubicMetre, "µg/m³")}")
            appendLine()
            appendLine("Temperature: ${formatExternalTemperature(snapshot.currentTemperatureC)}")
            appendLine("Feels like: ${formatExternalTemperature(snapshot.apparentTemperatureC)}")
            appendLine("Weather: ${weatherDescription(snapshot.weatherCode)}")
            appendLine("Wind: ${formatExternalMeasurement(snapshot.windSpeedKmh, "km/h")}")
            appendLine()
            if (advisories.isEmpty()) {
                appendLine("No forecast advisory at this time.")
            } else {
                advisories.forEach { advisory -> appendLine("${advisory.title}: ${advisory.message}") }
            }
            appendLine()
            appendLine("Updated ${formatExternalTime(snapshot.fetchedAt)}")
            appendLine("Weather and air-quality data: Open-Meteo")
            append("Outdoor AQI is regional/modelled external data and may not represent the exact conditions at this building.")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Outdoor & Forecast")
            .setMessage(details)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun formatExternalTemperature(value: Double?): String =
        value?.takeIf(Double::isFinite)?.let { String.format(Locale.getDefault(), "%.1f°C", it) } ?: "Unavailable"

    private fun formatExternalMeasurement(value: Double?, unit: String): String =
        value?.takeIf(Double::isFinite)?.let { String.format(Locale.getDefault(), "%.1f %s", it, unit) } ?: "Unavailable"

    private fun formatExternalTime(timestamp: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

    private fun weatherDescription(code: Int?): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy or overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        null -> "Unavailable"
        else -> "Weather code $code"
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
