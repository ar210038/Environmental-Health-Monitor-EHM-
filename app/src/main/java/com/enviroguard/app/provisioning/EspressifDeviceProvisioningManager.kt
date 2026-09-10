package com.enviroguard.app.provisioning

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPDevice
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.WiFiAccessPoint
import com.espressif.provisioning.listeners.ProvisionListener
import com.espressif.provisioning.listeners.WiFiScanListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList

class EspressifDeviceProvisioningManager(
    context: Context,
    private val popProvider: ProofOfPossessionProvider = PrototypeProofOfPossessionProvider()
) : ProvisioningGateway {
    private val appContext = context.applicationContext
    private val provisionManager = ESPProvisionManager.getInstance(appContext)
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ProvisioningState>(ProvisioningState.Idle)
    override val state: StateFlow<ProvisioningState> = _state.asStateFlow()

    private val accessPoints = linkedMapOf<String, WiFiAccessPoint>()
    private var selectedDevice: ProvisioningDevice? = null
    private var espDevice: ESPDevice? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var timeoutJob: Job? = null
    private var operationToken = 0L
    private var credentialsSent = false
    private var closed = false

    init {
        EventBus.getDefault().register(this)
    }

    override fun findDevices() {
        val permission = requiredRuntimePermission()
        if (permission != null) {
            _state.value = ProvisioningState.PermissionRequired(permission)
            return
        }
        if (!wifiManager.isWifiEnabled) {
            _state.value = ProvisioningState.WifiDisabled
            return
        }
        val token = nextOperation()
        accessPoints.clear()
        selectedDevice = null
        _state.value = ProvisioningState.SearchingForDevice
        armTimeout(token, DISCOVERY_TIMEOUT_MS) {
            fail(
                ProvisioningFailureCode.NO_DEVICE_FOUND,
                "No EHM setup device found. Make sure the device is powered on and in setup mode.",
                ProvisioningRetryAction.FIND_DEVICE
            )
        }
        try {
            discoverWithEspressif(token)
        } catch (_: SecurityException) {
            nextOperation()
            _state.value = ProvisioningState.PermissionRequired(permission ?: Manifest.permission.ACCESS_FINE_LOCATION)
        } catch (_: Exception) {
            fail(
                ProvisioningFailureCode.NO_DEVICE_FOUND,
                "The setup-device scan could not start. Check Wi-Fi and try again.",
                ProvisioningRetryAction.FIND_DEVICE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverWithEspressif(token: Long) {
        provisionManager.searchWiFiEspDevices(ProvisioningConfig.SERVICE_PREFIX, object : WiFiScanListener {
            override fun onWifiListReceived(results: ArrayList<WiFiAccessPoint>) {
                publish(token) {
                    timeoutJob?.cancel()
                    results.forEach { accessPoint ->
                        val name = accessPoint.wifiName ?: return@forEach
                        val normalized = EhmDeviceIdentity.normalize(name) ?: return@forEach
                        accessPoints[normalized] = accessPoint
                    }
                    val devices = accessPoints.map { (name, accessPoint) ->
                        ProvisioningDevice(name, accessPoint.rssi)
                    }.sortedByDescending(ProvisioningDevice::rssi)
                    if (devices.isEmpty()) {
                        fail(
                            ProvisioningFailureCode.NO_DEVICE_FOUND,
                            "No EHM setup device found. Make sure the device is powered on and in setup mode.",
                            ProvisioningRetryAction.FIND_DEVICE
                        )
                    } else {
                        _state.value = ProvisioningState.DevicesAvailable(devices)
                    }
                }
            }

            override fun onWiFiScanFailed(error: Exception) {
                publish(token) {
                    fail(
                        ProvisioningFailureCode.NO_DEVICE_FOUND,
                        "The phone could not scan for EHM setup devices. Check Wi-Fi permissions and try again.",
                        ProvisioningRetryAction.FIND_DEVICE
                    )
                }
            }
        })
    }

    override fun permissionDenied(permission: String) {
        nextOperation()
        _state.value = ProvisioningState.PermissionRequired(permission, previouslyDenied = true)
    }

    override fun connect(device: ProvisioningDevice) {
        val normalized = EhmDeviceIdentity.normalize(device.serviceName)
        val accessPoint = normalized?.let(accessPoints::get)
        if (normalized == null || accessPoint == null) {
            fail(
                ProvisioningFailureCode.INVALID_DEVICE,
                "That setup network is not a valid EHM monitoring device.",
                ProvisioningRetryAction.FIND_DEVICE
            )
            return
        }
        if (!wifiManager.isWifiEnabled) {
            _state.value = ProvisioningState.WifiDisabled
            return
        }
        val token = nextOperation()
        selectedDevice = ProvisioningDevice(normalized, device.rssi)
        credentialsSent = false
        _state.value = ProvisioningState.ConnectingToDevice(selectedDevice!!)
        armTimeout(token, CONNECTION_TIMEOUT_MS) {
            fail(
                ProvisioningFailureCode.DEVICE_CONNECTION_TIMEOUT,
                "Connection to the monitoring device timed out. Keep the phone near the device and try again.",
                ProvisioningRetryAction.CONNECT_DEVICE
            )
            releaseSoftApBinding()
        }
        try {
            val created = provisionManager.createESPDevice(
                ESPConstants.TransportType.TRANSPORT_SOFTAP,
                ESPConstants.SecurityType.SECURITY_1
            ).apply {
                deviceName = normalized
                proofOfPossession = popProvider.forDevice(normalized)
                accessPoint.password = ProvisioningConfig.SOFTAP_SERVICE_KEY
                wifiDevice = accessPoint
            }
            espDevice = created
            connectToSoftAp(created, normalized, token)
        } catch (_: SecurityException) {
            val permission = requiredRuntimePermission() ?: Manifest.permission.ACCESS_FINE_LOCATION
            nextOperation()
            releaseSoftApBinding()
            _state.value = ProvisioningState.PermissionRequired(permission)
        } catch (_: Exception) {
            fail(
                ProvisioningFailureCode.DEVICE_DISCONNECTED,
                "The phone could not connect to the monitoring device setup network.",
                ProvisioningRetryAction.CONNECT_DEVICE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToSoftAp(device: ESPDevice, serviceName: String, token: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            device.connectToDevice()
            return
        }
        releaseSoftApBinding()
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(serviceName)
        ProvisioningConfig.SOFTAP_SERVICE_KEY.takeIf(String::isNotEmpty)?.let(specifierBuilder::setWpa2Passphrase)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(token) {
                    connectivityManager.bindProcessToNetwork(network)
                    try {
                        device.connectWiFiDevice()
                    } catch (_: Exception) {
                        fail(
                            ProvisioningFailureCode.DEVICE_DISCONNECTED,
                            "The monitoring device setup service could not be reached.",
                            ProvisioningRetryAction.CONNECT_DEVICE
                        )
                    }
                }
            }

            override fun onUnavailable() {
                publish(token) {
                    fail(
                        ProvisioningFailureCode.DEVICE_CONNECTION_TIMEOUT,
                        "The phone did not join the monitoring device setup network.",
                        ProvisioningRetryAction.CONNECT_DEVICE
                    )
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    if (!closed) handleSoftApDisconnect()
                }
            }
        }
        networkCallback = callback
        connectivityManager.requestNetwork(request, callback, CONNECTION_TIMEOUT_MS.toInt())
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDeviceConnectionEvent(event: DeviceConnectionEvent) {
        if (closed || selectedDevice == null) return
        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_CONNECTED -> {
                timeoutJob?.cancel()
                _state.value = ProvisioningState.DeviceConnected(selectedDevice!!)
                scanNetworks()
            }
            ESPConstants.EVENT_DEVICE_CONNECTION_FAILED -> fail(
                ProvisioningFailureCode.DEVICE_CONNECTION_TIMEOUT,
                "The monitoring device setup service did not respond. Try again while the device is in setup mode.",
                ProvisioningRetryAction.CONNECT_DEVICE
            )
            ESPConstants.EVENT_DEVICE_DISCONNECTED -> handleSoftApDisconnect()
        }
    }

    override fun scanNetworks() {
        val device = espDevice
        val selected = selectedDevice
        if (device == null || selected == null) {
            fail(
                ProvisioningFailureCode.DEVICE_DISCONNECTED,
                "Connect to a monitoring device before scanning for Wi-Fi networks.",
                ProvisioningRetryAction.FIND_DEVICE
            )
            return
        }
        val token = nextOperation()
        _state.value = ProvisioningState.ScanningNetworks(selected)
        armTimeout(token, NETWORK_SCAN_TIMEOUT_MS) {
            fail(
                ProvisioningFailureCode.NETWORK_SCAN_FAILED,
                "The monitoring device did not return a Wi-Fi network list in time.",
                ProvisioningRetryAction.SCAN_NETWORKS
            )
        }
        try {
            device.scanNetworks(object : WiFiScanListener {
                override fun onWifiListReceived(results: ArrayList<WiFiAccessPoint>) {
                    publish(token) {
                        timeoutJob?.cancel()
                        val networks = results
                            .mapNotNull { point ->
                                val ssid = point.wifiName?.trim().orEmpty()
                                ssid.takeIf(String::isNotEmpty)?.let {
                                    ProvisioningNetwork(
                                        ssid = it,
                                        rssi = point.rssi,
                                        isSecured = point.security != ESPConstants.WIFI_OPEN.toInt()
                                    )
                                }
                            }
                            .groupBy(ProvisioningNetwork::ssid)
                            .map { (_, matches) -> matches.maxBy(ProvisioningNetwork::rssi) }
                            .sortedByDescending(ProvisioningNetwork::rssi)
                        if (networks.isEmpty()) {
                            fail(
                                ProvisioningFailureCode.EMPTY_NETWORK_LIST,
                                "No Wi-Fi networks were visible to the monitoring device. Move it closer to the router and retry.",
                                ProvisioningRetryAction.SCAN_NETWORKS
                            )
                        } else {
                            _state.value = ProvisioningState.NetworksAvailable(selected, networks)
                        }
                    }
                }

                override fun onWiFiScanFailed(error: Exception) {
                    publish(token) {
                        fail(
                            ProvisioningFailureCode.NETWORK_SCAN_FAILED,
                            "The monitoring device could not scan nearby Wi-Fi networks.",
                            ProvisioningRetryAction.SCAN_NETWORKS
                        )
                    }
                }
            })
        } catch (_: Exception) {
            fail(
                ProvisioningFailureCode.NETWORK_SCAN_FAILED,
                "The Wi-Fi scan could not be started on the monitoring device.",
                ProvisioningRetryAction.SCAN_NETWORKS
            )
        }
    }

    override fun provision(network: ProvisioningNetwork, password: CharArray) {
        val device = espDevice
        val selected = selectedDevice
        if (device == null || selected == null) {
            password.fill('\u0000')
            fail(
                ProvisioningFailureCode.DEVICE_DISCONNECTED,
                "The monitoring device is no longer connected. Find it again before sending Wi-Fi details.",
                ProvisioningRetryAction.FIND_DEVICE
            )
            return
        }
        val token = nextOperation()
        credentialsSent = false
        _state.value = ProvisioningState.SendingCredentials(selected, network.ssid)
        armTimeout(token, PROVISIONING_TIMEOUT_MS) {
            if (credentialsSent) showUnknownStatus()
            else fail(
                ProvisioningFailureCode.PROVISIONING_TIMEOUT,
                "The monitoring device did not complete Wi-Fi setup in time.",
                ProvisioningRetryAction.SEND_CREDENTIALS
            )
        }
        try {
            SensitiveCredential.consume(password) { passphrase ->
                device.provision(network.ssid, passphrase, provisionListener(token, selected, network.ssid))
            }
        } catch (_: Exception) {
            fail(
                ProvisioningFailureCode.PROTOCOL_FAILED,
                "Wi-Fi credentials could not be sent through the secure provisioning session.",
                ProvisioningRetryAction.SEND_CREDENTIALS
            )
        }
    }

    private fun provisionListener(token: Long, device: ProvisioningDevice, ssid: String) = object : ProvisionListener {
        override fun createSessionFailed(error: Exception) = publish(token) {
            fail(
                ProvisioningFailureCode.SECURE_SESSION_FAILED,
                "A secure provisioning session could not be established. Confirm the device is in EHM setup mode.",
                ProvisioningRetryAction.CONNECT_DEVICE
            )
        }

        override fun wifiConfigSent() = publish(token) {
            credentialsSent = true
            _state.value = ProvisioningState.SendingCredentials(device, ssid)
        }

        override fun wifiConfigFailed(error: Exception) = publish(token) {
            fail(
                ProvisioningFailureCode.PROTOCOL_FAILED,
                "The monitoring device did not accept the Wi-Fi credentials message.",
                ProvisioningRetryAction.SEND_CREDENTIALS
            )
        }

        override fun wifiConfigApplied() = publish(token) {
            credentialsSent = true
            _state.value = ProvisioningState.ConnectingToWifi(device, ssid)
        }

        override fun wifiConfigApplyFailed(error: Exception) = publish(token) {
            fail(
                ProvisioningFailureCode.PROTOCOL_FAILED,
                "The monitoring device could not apply the selected Wi-Fi configuration.",
                ProvisioningRetryAction.SEND_CREDENTIALS
            )
        }

        override fun provisioningFailedFromDevice(reason: ESPConstants.ProvisionFailureReason) = publish(token) {
            when (reason) {
                ESPConstants.ProvisionFailureReason.AUTH_FAILED -> fail(
                    ProvisioningFailureCode.AUTHENTICATION_FAILED,
                    "The router rejected the Wi-Fi password. Check it and try again.",
                    ProvisioningRetryAction.SEND_CREDENTIALS
                )
                ESPConstants.ProvisionFailureReason.NETWORK_NOT_FOUND -> fail(
                    ProvisioningFailureCode.NETWORK_NOT_FOUND,
                    "The selected Wi-Fi network was no longer available to the monitoring device.",
                    ProvisioningRetryAction.SCAN_NETWORKS
                )
                ESPConstants.ProvisionFailureReason.DEVICE_DISCONNECTED -> {
                    if (credentialsSent) showUnknownStatus() else fail(
                        ProvisioningFailureCode.DEVICE_DISCONNECTED,
                        "The monitoring device disconnected before receiving Wi-Fi credentials.",
                        ProvisioningRetryAction.FIND_DEVICE
                    )
                }
                ESPConstants.ProvisionFailureReason.UNKNOWN -> {
                    if (credentialsSent) showUnknownStatus() else fail(
                        ProvisioningFailureCode.PROTOCOL_FAILED,
                        "The monitoring device reported that provisioning could not be completed.",
                        ProvisioningRetryAction.SEND_CREDENTIALS
                    )
                }
            }
        }

        override fun deviceProvisioningSuccess() = publish(token) {
            timeoutJob?.cancel()
            val deviceId = EhmDeviceIdentity.normalize(device.serviceName)
            if (deviceId == null) {
                fail(
                    ProvisioningFailureCode.INVALID_DEVICE,
                    "Provisioning finished but the monitoring device identity was invalid."
                )
            } else {
                _state.value = ProvisioningState.Provisioned(deviceId)
                releaseSoftApBinding()
            }
        }

        override fun onProvisioningFailed(error: Exception) = publish(token) {
            if (credentialsSent) showUnknownStatus() else fail(
                ProvisioningFailureCode.PROTOCOL_FAILED,
                "The secure provisioning protocol did not complete.",
                ProvisioningRetryAction.SEND_CREDENTIALS
            )
        }
    }

    private fun handleSoftApDisconnect() {
        if (_state.value is ProvisioningState.Provisioned || _state.value is ProvisioningState.StatusUnknown) return
        if (credentialsSent) showUnknownStatus() else fail(
            ProvisioningFailureCode.DEVICE_DISCONNECTED,
            "The phone lost its connection to the monitoring device setup network.",
            ProvisioningRetryAction.FIND_DEVICE
        )
    }

    private fun showUnknownStatus() {
        timeoutJob?.cancel()
        val deviceId = selectedDevice?.serviceName ?: return
        _state.value = ProvisioningState.StatusUnknown(
            deviceId,
            "Wi-Fi credentials were sent, but the final device status could not be confirmed. Do not add the device yet; put it back into setup mode and retry if needed."
        )
        releaseSoftApBinding()
    }

    override fun cancel() {
        if (closed) return
        nextOperation()
        credentialsSent = false
        timeoutJob?.cancel()
        runCatching { espDevice?.disconnectDevice() }
        releaseSoftApBinding()
        accessPoints.clear()
        selectedDevice = null
        espDevice = null
        _state.value = ProvisioningState.Cancelled
    }

    override fun close() {
        if (closed) return
        cancel()
        closed = true
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        scope.cancel()
    }

    private fun fail(code: ProvisioningFailureCode, message: String, retryAction: ProvisioningRetryAction? = null) {
        timeoutJob?.cancel()
        _state.value = ProvisioningState.Failed(code, message, retryAction)
    }

    private fun requiredRuntimePermission(): String? =
        ProvisioningPermissions.firstMissing(Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun nextOperation(): Long {
        timeoutJob?.cancel()
        operationToken += 1
        return operationToken
    }

    private fun publish(token: Long, action: () -> Unit) {
        scope.launch {
            if (!closed && token == operationToken) action()
        }
    }

    private fun armTimeout(token: Long, timeoutMillis: Long, action: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            if (!closed && token == operationToken) action()
        }
    }

    private fun releaseSoftApBinding() {
        networkCallback?.let { callback -> runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
        networkCallback = null
        runCatching { connectivityManager.bindProcessToNetwork(null) }
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val NETWORK_SCAN_TIMEOUT_MS = 30_000L
        private const val PROVISIONING_TIMEOUT_MS = 75_000L
    }
}
