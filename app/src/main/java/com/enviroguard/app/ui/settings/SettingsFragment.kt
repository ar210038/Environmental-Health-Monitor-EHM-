package com.enviroguard.app.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.dataset.DatasetExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.enviroguard.app.R
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SettingsViewModel
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repository = (requireActivity().application as EnviroGuardApp).repository
        val factory = SettingsViewModelFactory(requireContext(), repository)
        viewModel = ViewModelProvider(this, factory)[SettingsViewModel::class.java]

        observeViewModel()
        setupDatasetCollection()
        setupListeners()
    }

    private fun observeViewModel() {

        viewModel.notificationsEnabled.observe(viewLifecycleOwner) {
            binding.switchNotifications.isChecked = it
        }

        viewModel.demoMode.observe(viewLifecycleOwner) {
            binding.switchDemoMode.isChecked = it
        }

        viewModel.useCelsius.observe(viewLifecycleOwner) { celsius ->
            if (celsius) {
                binding.btnCelsius.setTextColor(Color.parseColor("#1A6B8A"))
                binding.btnCelsius.setBackgroundResource(R.drawable.bg_tab_active)
                binding.btnFahrenheit.setTextColor(Color.parseColor("#9AA5A3"))
                binding.btnFahrenheit.setBackgroundResource(0)
                binding.tvTempUnitDesc.text = "Currently showing Celsius"
            } else {
                binding.btnFahrenheit.setTextColor(Color.parseColor("#1A6B8A"))
                binding.btnFahrenheit.setBackgroundResource(R.drawable.bg_tab_active)
                binding.btnCelsius.setTextColor(Color.parseColor("#9AA5A3"))
                binding.btnCelsius.setBackgroundResource(0)
                binding.tvTempUnitDesc.text = "Currently showing Fahrenheit"
            }
        }

        viewModel.ersThreshold.observe(viewLifecycleOwner) {
            binding.sliderErs.progress = it
            binding.tvErsThresholdValue.text = it.toString()
        }

        viewModel.tvocThreshold.observe(viewLifecycleOwner) {
            binding.sliderTvoc.progress = it
            binding.tvTvocThresholdValue.text = "$it ppb"
        }

        viewModel.eco2Threshold.observe(viewLifecycleOwner) {
            binding.sliderEco2.progress = it
            binding.tvEco2ThresholdValue.text = "$it ppm"
        }

        viewModel.noiseThreshold.observe(viewLifecycleOwner) {
            binding.sliderNoise.progress = it
            binding.tvNoiseThresholdValue.text = "$it dB"
        }

        viewModel.collectedSamples.observe(viewLifecycleOwner) {
            binding.tvCollectedSamples.text = "Collected Samples: $it"
        }
        viewModel.collectionSessionId.observe(viewLifecycleOwner) { sessionId ->
            binding.tvCollectionSessionStatus.text = if (sessionId == null) {
                "No research collection session active"
            } else {
                "Active Session: $sessionId"
            }
            binding.btnToggleCollectionSession.text = if (sessionId == null) {
                "Start Data Collection Session"
            } else {
                "Stop Data Collection Session"
            }
        }
    }

    private fun setupDatasetCollection() {
        binding.btnToggleCollectionSession.setOnClickListener {
            viewModel.toggleCollectionSession()
        }

        binding.btnExportDataset.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val readings = withContext(Dispatchers.IO) { viewModel.getDatasetReadings() }
                if (readings.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "No real sensor data available to export.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                runCatching {
                    val appContext = requireContext().applicationContext
                    val shareIntent = withContext(Dispatchers.IO) {
                        DatasetExportManager.createShareIntent(appContext, readings)
                    }
                    startActivity(Intent.createChooser(
                        shareIntent,
                        "Save or share EnviroGuard dataset"
                    ))
                }.onFailure {
                    Toast.makeText(
                        requireContext(),
                        "Unable to export dataset: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupListeners() {

        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            viewModel.setNotifications(checked)
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !DeviceManager.notificationPermissionRequested &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                DeviceManager.notificationPermissionRequested = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.switchDemoMode.setOnCheckedChangeListener { _, checked ->
            viewModel.setDemoMode(checked)
            DeviceManager.isDemoMode = checked
        }

        binding.btnCelsius.setOnClickListener {
            viewModel.setUseCelsius(true)
        }

        binding.btnFahrenheit.setOnClickListener {
            viewModel.setUseCelsius(false)
        }

        binding.sliderErs.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvErsThresholdValue.text = value.toString()
                    viewModel.setErsThreshold(value)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.sliderTvoc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvTvocThresholdValue.text = "$value ppb"
                    viewModel.setTvocThreshold(value)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.sliderEco2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvEco2ThresholdValue.text = "$value ppm"
                    viewModel.setEco2Threshold(value)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.sliderNoise.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvNoiseThresholdValue.text = "$value dB"
                    viewModel.setNoiseThreshold(value)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
