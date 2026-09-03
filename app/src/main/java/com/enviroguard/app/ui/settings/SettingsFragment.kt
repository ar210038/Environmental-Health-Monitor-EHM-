package com.enviroguard.app.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.databinding.FragmentSettingsBinding
import com.enviroguard.app.dataset.DatasetExportManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SettingsViewModel
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (::viewModel.isInitialized) viewModel.setNotifications(granted)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val app = requireActivity().application as EnviroGuardApp
        viewModel = ViewModelProvider(this, SettingsViewModelFactory(requireContext(), app.repository))[SettingsViewModel::class.java]

        viewModel.notificationsEnabled.observe(viewLifecycleOwner) { binding.switchNotifications.isChecked = it }
        viewModel.demoMode.observe(viewLifecycleOwner) { binding.switchDemoMode.isChecked = it }
        viewModel.useCelsius.observe(viewLifecycleOwner, ::renderTemperatureUnit)
        viewModel.collectedSamples.observe(viewLifecycleOwner) { binding.tvCollectedSamples.text = "Collected raw samples: $it" }
        viewModel.activeDevice.observe(viewLifecycleOwner) { device ->
            renderDeviceState(device, viewModel.savedDevices.value.orEmpty())
        }
        viewModel.savedDevices.observe(viewLifecycleOwner) { devices ->
            renderDeviceState(viewModel.activeDevice.value, devices)
        }

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            if (checked && needsNotificationPermission()) {
                viewModel.setNotifications(false)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setNotifications(checked)
            }
        }
        binding.switchDemoMode.setOnCheckedChangeListener { _, checked -> viewModel.setDemoMode(checked) }
        binding.btnCelsius.setOnClickListener { viewModel.setUseCelsius(true) }
        binding.btnFahrenheit.setOnClickListener { viewModel.setUseCelsius(false) }
        binding.btnSetUpDevice.setOnClickListener {
            findNavController().navigate(R.id.connectFragment, bundleOf("mode" to "add"))
        }
        binding.btnChangeWifi.setOnClickListener {
            findNavController().navigate(R.id.connectFragment, bundleOf("mode" to "reconfigure"))
        }
        binding.btnManageDevices.setOnClickListener { showDeviceManager() }
        binding.btnForgetDevice.setOnClickListener { confirmForgetDevice() }
        binding.btnExportDataset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val readings = viewModel.getDatasetReadings()
                if (readings.isEmpty()) {
                    binding.tvExportStatus.text = "No collected readings are available to export."
                } else {
                    startActivity(android.content.Intent.createChooser(DatasetExportManager.createShareIntent(requireContext(), readings), "Export raw EHM dataset"))
                }
            }
        }
        viewModel.refreshDevice()
    }

    private fun renderDeviceState(device: com.enviroguard.app.data.SavedDevice?, devices: List<com.enviroguard.app.data.SavedDevice>) {
        binding.tvCurrentDeviceName.text = device?.displayName ?: "No monitoring device"
        binding.tvCurrentDeviceId.text = device?.deviceId ?: "Not configured"
        binding.tvCurrentDeviceBadge.visibility = if (device == null) View.GONE else View.VISIBLE
        binding.btnManageDevices.isEnabled = devices.isNotEmpty()
        binding.btnChangeWifi.isEnabled = device != null
        binding.btnForgetDevice.isEnabled = device != null
        binding.btnSetUpDevice.setText(if (devices.isEmpty()) R.string.add_monitoring_device else R.string.add_another_monitoring_device)
        binding.tvDeviceCount.text = when (devices.size) {
            0 -> "No registered devices"
            1 -> "1 registered device • one active device at a time"
            else -> "${devices.size} registered devices • one active device at a time"
        }
    }

    private fun needsNotificationPermission() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun renderTemperatureUnit(celsius: Boolean) {
        binding.tvTempUnitStatus.text = if (celsius) "Currently showing Celsius" else "Currently showing Fahrenheit"
        val selected = if (celsius) binding.btnCelsius else binding.btnFahrenheit
        val unselected = if (celsius) binding.btnFahrenheit else binding.btnCelsius
        selected.setBackgroundResource(R.drawable.bg_tab_active)
        selected.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_primary))
        selected.setTypeface(null, Typeface.BOLD)
        unselected.setBackgroundResource(0)
        unselected.setTextColor(ContextCompat.getColor(requireContext(), R.color.ehm_on_surface_variant))
        unselected.setTypeface(null, Typeface.NORMAL)
    }

    private fun showDeviceManager() {
        val devices = viewModel.savedDevices.value.orEmpty()
        if (devices.isEmpty()) {
            findNavController().navigate(R.id.connectFragment)
            return
        }
        val activeId = DeviceManager.activeDeviceId
        val labels = devices.map { device -> "${device.displayName}\n${device.deviceId}${if (device.deviceId == activeId) "  •  Current device" else ""}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Registered Devices")
            .setSingleChoiceItems(labels, devices.indexOfFirst { it.deviceId == activeId }) { dialog, index ->
                viewModel.selectDevice(devices[index].deviceId)
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .setPositiveButton("Add device") { _, _ -> findNavController().navigate(R.id.connectFragment) }
            .show()
    }

    private fun confirmForgetDevice() {
        val device = viewModel.activeDevice.value ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove this monitoring device from the app?")
            .setMessage("This removes ${device.displayName} from this phone. It does not reset the ESP32's stored Wi-Fi configuration or delete Firebase/Room history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ -> viewModel.forgetDevice() }
            .show()
    }

    override fun onResume() { super.onResume(); if (::viewModel.isInitialized) viewModel.refreshDevice() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
