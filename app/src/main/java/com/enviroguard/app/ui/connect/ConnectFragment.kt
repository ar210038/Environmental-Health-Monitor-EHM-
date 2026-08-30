package com.enviroguard.app.ui.connect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.enviroguard.app.R
import com.enviroguard.app.databinding.FragmentConnectBinding
import com.enviroguard.app.provisioning.ProvisioningDevice
import com.enviroguard.app.provisioning.ProvisioningFailureCode
import com.enviroguard.app.provisioning.ProvisioningNetwork
import com.enviroguard.app.provisioning.ProvisioningRetryAction
import com.enviroguard.app.provisioning.ProvisioningState
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class ConnectFragment : Fragment() {
    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ConnectViewModel
    private var requestedPermission: String = Manifest.permission.ACCESS_FINE_LOCATION
    private var selectedNetwork: ProvisioningNetwork? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grants[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        } else {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        if (granted) viewModel.findDevices() else viewModel.permissionDenied(requestedPermission)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        viewModel = ViewModelProvider(this, ConnectViewModelFactory(requireContext()))[ConnectViewModel::class.java]
        configureMode()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelAndClose()
        })
        binding.btnBackToSettings.setOnClickListener { cancelAndClose() }
        binding.btnSecondaryAction.setOnClickListener { cancelAndClose() }
        binding.btnProvisionCredentials.setOnClickListener { sendCredentials() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.completed.collect {
                        clearPassword()
                        val controller = findNavController()
                        if (!controller.popBackStack(R.id.homeFragment, false)) {
                            controller.navigate(R.id.homeFragment)
                        }
                    }
                }
            }
        }
    }

    private fun configureMode() {
        val reconfigure = arguments?.getString("mode") == MODE_RECONFIGURE
        binding.tvSetupTitle.setText(if (reconfigure) R.string.setup_reconfigure_title else R.string.setup_add_title)
        binding.tvSetupIntroduction.text = if (reconfigure) {
            getString(R.string.setup_reconfigure_intro)
        } else {
            getString(R.string.setup_add_intro)
        }
    }

    private fun render(state: ProvisioningState) {
        binding.progressProvisioning.visibility = if (state.isBusy()) View.VISIBLE else View.GONE
        binding.deviceSection.visibility = View.GONE
        binding.networkSection.visibility = View.GONE
        if (state !is ProvisioningState.NetworksAvailable) binding.credentialsCard.visibility = View.GONE
        binding.btnPrimaryAction.visibility = View.VISIBLE
        binding.btnPrimaryAction.isEnabled = !state.isBusy()
        binding.btnPrimaryAction.setOnClickListener(null)
        renderStep(state.step())

        when (state) {
            ProvisioningState.Idle -> showAction(
                "Find Monitoring Device",
                "Only setup networks whose names match EHM_XXXXXX will be shown.",
                "Find Device"
            ) { viewModel.findDevices() }

            is ProvisioningState.PermissionRequired -> {
                requestedPermission = state.permission
                showAction(
                    "Nearby-device permission needed",
                    permissionExplanation(state.permission, state.previouslyDenied),
                    "Allow and Find Device"
                ) { requestOrOpenPermissionSettings(state) }
            }

            ProvisioningState.WifiDisabled -> showAction(
                "Turn on Wi-Fi",
                "Wi-Fi is required to find and communicate with the temporary EHM setup network.",
                "Open Wi-Fi controls"
            ) { openWifiControls() }

            ProvisioningState.SearchingForDevice -> showBusy(
                "Finding monitoring devices...",
                "Scanning only for EHM setup networks."
            )

            is ProvisioningState.DevicesAvailable -> {
                binding.tvProvisioningStatusTitle.setText(R.string.setup_select_device_title)
                binding.tvProvisioningStatusDetail.text = if (state.devices.size == 1) {
                    getString(R.string.setup_one_device_found)
                } else {
                    getString(R.string.setup_devices_found, state.devices.size)
                }
                binding.deviceSection.visibility = View.VISIBLE
                showDevices(state.devices)
                showPrimary("Scan again") { viewModel.findDevices() }
            }

            is ProvisioningState.ConnectingToDevice -> showBusy(
                "Connecting to ${state.device.serviceName}",
                "Approve the Android Wi-Fi connection prompt if it appears. The setup network does not provide internet access."
            )

            is ProvisioningState.DeviceConnected -> showBusy(
                "Monitoring device connected",
                "Secure communication is ready. Preparing the Wi-Fi scan."
            )

            is ProvisioningState.ScanningNetworks -> showBusy(
                "Scanning Wi-Fi networks...",
                "${state.device.serviceName} is checking the networks it can reach."
            )

            is ProvisioningState.NetworksAvailable -> {
                binding.tvProvisioningStatusTitle.setText(R.string.setup_choose_wifi_title)
                binding.tvProvisioningStatusDetail.text = getString(R.string.setup_select_router, state.device.serviceName)
                binding.networkSection.visibility = View.VISIBLE
                showNetworks(state.networks)
                showPrimary("Scan again") { viewModel.scanNetworks() }
            }

            is ProvisioningState.SendingCredentials -> {
                clearPassword()
                showBusy("Sending Wi-Fi credentials...", "Credentials are being sent through the secure provisioning session.")
            }

            is ProvisioningState.ConnectingToWifi -> {
                clearPassword()
                showBusy("Connecting monitoring device to Wi-Fi...", "The device is applying ${state.ssid} and checking the router connection.")
            }

            is ProvisioningState.Provisioned -> {
                clearPassword()
                binding.tvProvisioningStatusTitle.setText(R.string.setup_provisioned_title)
                binding.tvProvisioningStatusDetail.text = getString(R.string.setup_provisioned_detail, state.deviceId)
                binding.btnPrimaryAction.visibility = View.GONE
            }

            is ProvisioningState.StatusUnknown -> {
                clearPassword()
                showAction("Device status not confirmed", state.message, "Try setup again") { viewModel.findDevices() }
            }

            is ProvisioningState.Failed -> {
                clearPassword()
                val title = failureTitle(state.code)
                showAction(title, state.message, retryLabel(state.retryAction)) {
                    if (state.retryAction == ProvisioningRetryAction.REQUEST_PERMISSION) {
                        permissionLauncher.launch(runtimePermissions())
                    } else {
                        viewModel.retry(state.retryAction)
                    }
                }
            }

            ProvisioningState.Cancelled -> showAction(
                "Setup cancelled",
                "No Wi-Fi password or monitoring device was saved.",
                "Start again"
            ) { viewModel.findDevices() }
        }
    }

    private fun showDevices(devices: List<ProvisioningDevice>) {
        binding.deviceContainer.removeAllViews()
        devices.forEach { device ->
            binding.deviceContainer.addView(selectionCard(
                title = device.serviceName,
                detail = "EHM setup network • ${signalLabel(device.rssi)}",
                contentDescription = "Select monitoring device ${device.serviceName}"
            ) { viewModel.connect(device) })
        }
    }

    private fun showNetworks(networks: List<ProvisioningNetwork>) {
        binding.networkContainer.removeAllViews()
        networks.forEach { network ->
            val security = if (network.isSecured) "Secured" else "Open network"
            binding.networkContainer.addView(selectionCard(
                title = network.ssid,
                detail = "$security • ${signalLabel(network.rssi)}",
                contentDescription = "Select Wi-Fi network ${network.ssid}"
            ) { selectNetwork(network) })
        }
    }

    private fun selectionCard(title: String, detail: String, contentDescription: String, action: () -> Unit): MaterialCardView {
        return MaterialCardView(requireContext()).apply {
            radius = resources.getDimension(R.dimen.card_radius)
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.ehm_outline)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.ehm_surface))
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface))
                })
                addView(TextView(context).apply {
                    text = detail
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ContextCompat.getColor(context, R.color.ehm_on_surface_variant))
                })
            })
            setOnClickListener { action() }
        }
    }

    private fun selectNetwork(network: ProvisioningNetwork) {
        selectedNetwork = network
        binding.credentialsCard.visibility = View.VISIBLE
        binding.tvSelectedNetwork.text = getString(R.string.setup_selected_network, network.ssid)
        binding.inputWifiPassword.hint = getString(if (network.isSecured) R.string.setup_wifi_password else R.string.setup_password_not_required)
        binding.inputWifiPassword.error = null
        binding.editWifiPassword.requestFocus()
    }

    private fun sendCredentials() {
        val network = selectedNetwork ?: return
        val password = binding.editWifiPassword.text?.toString().orEmpty()
        if (network.isSecured && password.isBlank()) {
            binding.inputWifiPassword.error = getString(R.string.setup_password_required, network.ssid)
            return
        }
        binding.inputWifiPassword.error = null
        val sensitiveValue = password.toCharArray()
        clearPassword()
        viewModel.provision(network, sensitiveValue)
    }

    private fun clearPassword() {
        _binding?.editWifiPassword?.text?.clear()
        _binding?.inputWifiPassword?.error = null
    }

    private fun requestOrOpenPermissionSettings(state: ProvisioningState.PermissionRequired) {
        val canRequestAgain = !state.previouslyDenied || shouldShowRequestPermissionRationale(state.permission)
        if (canRequestAgain) {
            permissionLauncher.launch(runtimePermissions())
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${requireContext().packageName}".toUri()))
        }
    }

    private fun permissionExplanation(permission: String, denied: Boolean): String {
        val permissionName = if (permission == Manifest.permission.NEARBY_WIFI_DEVICES) "Nearby devices" else "Location"
        val suffix = if (denied) " Permission was denied; allow it to continue." else ""
        return "$permissionName permission lets Android scan for temporary EHM setup networks. EHM does not collect or store GPS location.$suffix"
    }

    private fun runtimePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun openWifiControls() {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        startActivity(Intent(action))
    }

    private fun showBusy(title: String, detail: String) {
        binding.tvProvisioningStatusTitle.text = title
        binding.tvProvisioningStatusDetail.text = detail
        binding.btnPrimaryAction.visibility = View.GONE
    }

    private fun showAction(title: String, detail: String, actionLabel: String, action: () -> Unit) {
        binding.tvProvisioningStatusTitle.text = title
        binding.tvProvisioningStatusDetail.text = detail
        showPrimary(actionLabel, action)
    }

    private fun showPrimary(label: String, action: () -> Unit) {
        binding.btnPrimaryAction.visibility = View.VISIBLE
        binding.btnPrimaryAction.isEnabled = true
        binding.btnPrimaryAction.text = label
        binding.btnPrimaryAction.setOnClickListener { action() }
    }

    private fun renderStep(activeStep: Int) {
        listOf(binding.tvStepFind, binding.tvStepWifi, binding.tvStepConnect).forEachIndexed { index, view ->
            val selected = index + 1 == activeStep
            view.setBackgroundResource(if (selected) R.drawable.bg_tab_active else 0)
            view.setTextColor(ContextCompat.getColor(requireContext(), if (selected) R.color.ehm_primary else R.color.ehm_on_surface_variant))
            view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun failureTitle(code: ProvisioningFailureCode) = when (code) {
        ProvisioningFailureCode.PERMISSION_DENIED -> "Permission required"
        ProvisioningFailureCode.WIFI_DISABLED -> "Wi-Fi is turned off"
        ProvisioningFailureCode.NO_DEVICE_FOUND -> "No EHM setup device found"
        ProvisioningFailureCode.DEVICE_CONNECTION_TIMEOUT -> "Device connection timed out"
        ProvisioningFailureCode.DEVICE_DISCONNECTED -> "Monitoring device disconnected"
        ProvisioningFailureCode.NETWORK_SCAN_FAILED -> "Wi-Fi scan failed"
        ProvisioningFailureCode.EMPTY_NETWORK_LIST -> "No Wi-Fi networks found"
        ProvisioningFailureCode.AUTHENTICATION_FAILED -> "Wi-Fi password rejected"
        ProvisioningFailureCode.NETWORK_NOT_FOUND -> "Wi-Fi network unavailable"
        ProvisioningFailureCode.SECURE_SESSION_FAILED -> "Secure connection failed"
        ProvisioningFailureCode.PROVISIONING_TIMEOUT -> "Wi-Fi setup timed out"
        ProvisioningFailureCode.PROTOCOL_FAILED -> "Provisioning could not complete"
        ProvisioningFailureCode.INVALID_DEVICE -> "Invalid monitoring device"
    }

    private fun retryLabel(action: ProvisioningRetryAction?) = when (action) {
        ProvisioningRetryAction.REQUEST_PERMISSION -> "Allow permission"
        ProvisioningRetryAction.ENABLE_WIFI -> "Open Wi-Fi controls"
        ProvisioningRetryAction.FIND_DEVICE -> "Find Device Again"
        ProvisioningRetryAction.CONNECT_DEVICE -> "Retry Connection"
        ProvisioningRetryAction.SCAN_NETWORKS -> "Scan Networks Again"
        ProvisioningRetryAction.SEND_CREDENTIALS -> "Choose Wi-Fi Again"
        null -> "Start Again"
    }

    private fun signalLabel(rssi: Int) = when {
        rssi >= -55 -> "Strong signal"
        rssi >= -70 -> "Good signal"
        else -> "Weak signal"
    }

    private fun ProvisioningState.step() = when (this) {
        is ProvisioningState.DeviceConnected,
        is ProvisioningState.ScanningNetworks,
        is ProvisioningState.NetworksAvailable -> 2
        is ProvisioningState.SendingCredentials,
        is ProvisioningState.ConnectingToWifi,
        is ProvisioningState.Provisioned,
        is ProvisioningState.StatusUnknown -> 3
        else -> 1
    }

    private fun ProvisioningState.isBusy() = this is ProvisioningState.SearchingForDevice ||
        this is ProvisioningState.ConnectingToDevice || this is ProvisioningState.DeviceConnected ||
        this is ProvisioningState.ScanningNetworks || this is ProvisioningState.SendingCredentials ||
        this is ProvisioningState.ConnectingToWifi

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun cancelAndClose() {
        clearPassword()
        viewModel.cancel()
        findNavController().navigateUp()
    }

    override fun onResume() {
        super.onResume()
        if (!::viewModel.isInitialized) return
        when (val current = viewModel.state.value) {
            ProvisioningState.WifiDisabled -> {
                val wifi = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wifi.isWifiEnabled) viewModel.findDevices()
            }
            is ProvisioningState.PermissionRequired -> {
                if (ContextCompat.checkSelfPermission(requireContext(), current.permission) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.findDevices()
                }
            }
            else -> Unit
        }
    }

    override fun onDestroyView() {
        clearPassword()
        selectedNetwork = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val MODE_RECONFIGURE = "reconfigure"
    }
}
