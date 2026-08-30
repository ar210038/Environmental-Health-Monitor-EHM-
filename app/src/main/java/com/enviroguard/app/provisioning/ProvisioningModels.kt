package com.enviroguard.app.provisioning

data class ProvisioningDevice(
    val serviceName: String,
    val rssi: Int
)

data class ProvisioningNetwork(
    val ssid: String,
    val rssi: Int,
    val isSecured: Boolean
)

enum class ProvisioningFailureCode {
    PERMISSION_DENIED,
    WIFI_DISABLED,
    NO_DEVICE_FOUND,
    DEVICE_CONNECTION_TIMEOUT,
    DEVICE_DISCONNECTED,
    NETWORK_SCAN_FAILED,
    EMPTY_NETWORK_LIST,
    AUTHENTICATION_FAILED,
    NETWORK_NOT_FOUND,
    SECURE_SESSION_FAILED,
    PROVISIONING_TIMEOUT,
    PROTOCOL_FAILED,
    INVALID_DEVICE
}

enum class ProvisioningRetryAction {
    REQUEST_PERMISSION,
    ENABLE_WIFI,
    FIND_DEVICE,
    CONNECT_DEVICE,
    SCAN_NETWORKS,
    SEND_CREDENTIALS
}

sealed interface ProvisioningState {
    data object Idle : ProvisioningState
    data class PermissionRequired(val permission: String, val previouslyDenied: Boolean = false) : ProvisioningState
    data object WifiDisabled : ProvisioningState
    data object SearchingForDevice : ProvisioningState
    data class DevicesAvailable(val devices: List<ProvisioningDevice>) : ProvisioningState
    data class ConnectingToDevice(val device: ProvisioningDevice) : ProvisioningState
    data class DeviceConnected(val device: ProvisioningDevice) : ProvisioningState
    data class ScanningNetworks(val device: ProvisioningDevice) : ProvisioningState
    data class NetworksAvailable(
        val device: ProvisioningDevice,
        val networks: List<ProvisioningNetwork>
    ) : ProvisioningState
    data class SendingCredentials(val device: ProvisioningDevice, val ssid: String) : ProvisioningState
    data class ConnectingToWifi(val device: ProvisioningDevice, val ssid: String) : ProvisioningState
    data class Provisioned(val deviceId: String) : ProvisioningState
    data class StatusUnknown(val deviceId: String, val message: String) : ProvisioningState
    data class Failed(
        val code: ProvisioningFailureCode,
        val message: String,
        val retryAction: ProvisioningRetryAction? = null
    ) : ProvisioningState
    data object Cancelled : ProvisioningState
}
