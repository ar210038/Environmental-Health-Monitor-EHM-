package com.enviroguard.app.ui.connect

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.databinding.FragmentConnectBinding

class ConnectFragment : Fragment() {
    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View { _binding = FragmentConnectBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        binding.btnOpenWifiSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        binding.btnBackToSettings.setOnClickListener { findNavController().navigateUp() }
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
