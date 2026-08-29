package com.enviroguard.app.ui.connect

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.databinding.FragmentConnectBinding

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        checkPairedDevice()
    }

    private fun setupListeners() {

        // Open phone WiFi settings
        binding.btnOpenWifi.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        // Send credentials to device
        binding.btnSendToDevice.setOnClickListener {
            val ssid     = binding.etSsid.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val name     = binding.etDeviceName.text.toString().trim()
                .ifEmpty { "My EnviroGuard" }

            if (ssid.isEmpty()) {
                binding.tvSendStatus.text = "Please enter your WiFi network name"
                binding.tvSendStatus.setTextColor(
                    android.graphics.Color.parseColor("#993C1D")
                )
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                binding.tvSendStatus.text = "Please enter your WiFi password"
                binding.tvSendStatus.setTextColor(
                    android.graphics.Color.parseColor("#993C1D")
                )
                return@setOnClickListener
            }

            sendToDevice(ssid, password, name)
        }

        // Add another device
        binding.btnAddAnother.setOnClickListener {
            // Reset form
            binding.etSsid.text?.clear()
            binding.etPassword.text?.clear()
            binding.etDeviceName.text?.clear()
            binding.tvSendStatus.text = ""
            binding.tvStatusTitle.text = "No device connected"
            binding.tvStatusSubtitle.text =
                "Power on your EnviroGuard device to begin setup"
        }
    }

    private fun sendToDevice(ssid: String, password: String, name: String) {
        binding.tvSendStatus.text = "Sending to device..."
        binding.tvSendStatus.setTextColor(
            android.graphics.Color.parseColor("#1A6B8A")
        )

        // HTTP POST to ESP32 AP at 192.168.4.1
        // This works while phone is connected to EnviroGuard-Setup WiFi
        // Implemented fully when hardware is ready
        // For now simulate success after 2 seconds

        binding.root.postDelayed({
            onDeviceConnected(name)
        }, 2000)
    }

    private fun onDeviceConnected(name: String) {
        // Update status card
        binding.tvStatusTitle.text = "✅ Device Connected!"
        binding.tvStatusSubtitle.text =
            "$name is now sending live data to Firebase"

        // Show paired device card
        binding.cardPairedDevice.visibility = View.VISIBLE
        binding.tvPairedDeviceName.text = name
        binding.tvPairedDeviceStatus.text = "● Online"

        binding.tvSendStatus.text = "Successfully connected"
        binding.tvSendStatus.setTextColor(
            android.graphics.Color.parseColor("#0F6E56")
        )

        // Hardware provisioning will supply the real device ID in a later phase.
        // DeviceManager remains the single source of truth for device state.
        DeviceManager.activeDeviceName = name
        DeviceManager.isDeviceConnected = true
    }

    private fun checkPairedDevice() {
        val isConnected = DeviceManager.isDeviceConnected
        val deviceName = DeviceManager.activeDeviceName

        if (isConnected) {
            binding.tvStatusTitle.text = "✅ Device Connected!"
            binding.tvStatusSubtitle.text = "$deviceName is sending live data"
            binding.cardPairedDevice.visibility = View.VISIBLE
            binding.tvPairedDeviceName.text = deviceName
            binding.tvPairedDeviceStatus.text = "● Online"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
