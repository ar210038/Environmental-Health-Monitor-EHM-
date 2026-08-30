package com.enviroguard.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.databinding.FragmentSettingsBinding
import com.enviroguard.app.dataset.DatasetExportManager
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SettingsViewModel
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View { _binding = FragmentSettingsBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        val app = requireActivity().application as EnviroGuardApp
        viewModel = ViewModelProvider(this, SettingsViewModelFactory(requireContext(), app.repository))[SettingsViewModel::class.java]
        viewModel.notificationsEnabled.observe(viewLifecycleOwner) { binding.switchNotifications.isChecked = it }
        viewModel.demoMode.observe(viewLifecycleOwner) { binding.switchDemoMode.isChecked = it }
        viewModel.useCelsius.observe(viewLifecycleOwner) { celsius -> binding.tvTempUnitStatus.text = if (celsius) "Currently showing Celsius" else "Currently showing Fahrenheit" }
        viewModel.collectedSamples.observe(viewLifecycleOwner) { binding.tvCollectedSamples.text = "Collected raw samples: $it" }
        viewModel.deviceSummary.observe(viewLifecycleOwner) { binding.tvCurrentDevice.text = it }
        binding.switchNotifications.setOnCheckedChangeListener { _, checked -> viewModel.setNotifications(checked) }
        binding.switchDemoMode.setOnCheckedChangeListener { _, checked -> viewModel.setDemoMode(checked) }
        binding.btnCelsius.setOnClickListener { viewModel.setUseCelsius(true) }
        binding.btnFahrenheit.setOnClickListener { viewModel.setUseCelsius(false) }
        binding.btnSetUpDevice.setOnClickListener { findNavController().navigate(R.id.connectFragment) }
        binding.btnChangeWifi.setOnClickListener { findNavController().navigate(R.id.connectFragment) }
        binding.btnForgetDevice.setOnClickListener { viewModel.forgetDevice() }
        binding.btnExportDataset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val readings = viewModel.getDatasetReadings()
                if (readings.isEmpty()) binding.tvExportStatus.text = "No collected readings are available to export."
                else startActivity(android.content.Intent.createChooser(DatasetExportManager.createShareIntent(requireContext(), readings), "Export raw EHM dataset"))
            }
        }
        viewModel.refreshDevice()
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
