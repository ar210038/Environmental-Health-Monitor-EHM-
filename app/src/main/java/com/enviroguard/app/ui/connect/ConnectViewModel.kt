package com.enviroguard.app.ui.connect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.enviroguard.app.data.SavedDevice
import com.enviroguard.app.provisioning.DeviceManagerProvisionedDeviceStore
import com.enviroguard.app.provisioning.EspressifDeviceProvisioningManager
import com.enviroguard.app.provisioning.ProvisionedDeviceRegistrar
import com.enviroguard.app.provisioning.ProvisioningDevice
import com.enviroguard.app.provisioning.ProvisioningGateway
import com.enviroguard.app.provisioning.ProvisioningNetwork
import com.enviroguard.app.provisioning.ProvisioningRetryAction
import com.enviroguard.app.provisioning.ProvisioningState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class ConnectViewModel(
    private val provisioning: ProvisioningGateway,
    private val registrar: ProvisionedDeviceRegistrar
) : ViewModel() {
    val state: StateFlow<ProvisioningState> = provisioning.state

    private val _completed = MutableSharedFlow<SavedDevice>(extraBufferCapacity = 1)
    val completed: SharedFlow<SavedDevice> = _completed.asSharedFlow()
    private var lastDevice: ProvisioningDevice? = null

    init {
        viewModelScope.launch {
            state.collect { current ->
                registrar.register(current)?.let { _completed.emit(it) }
            }
        }
    }

    fun findDevices() = provisioning.findDevices()
    fun permissionDenied(permission: String) = provisioning.permissionDenied(permission)

    fun connect(device: ProvisioningDevice) {
        lastDevice = device
        provisioning.connect(device)
    }

    fun scanNetworks() = provisioning.scanNetworks()
    fun provision(network: ProvisioningNetwork, password: CharArray) = provisioning.provision(network, password)

    fun retry(action: ProvisioningRetryAction?) {
        when (action) {
            ProvisioningRetryAction.REQUEST_PERMISSION -> Unit
            ProvisioningRetryAction.ENABLE_WIFI -> Unit
            ProvisioningRetryAction.FIND_DEVICE -> findDevices()
            ProvisioningRetryAction.CONNECT_DEVICE -> lastDevice?.let(::connect) ?: findDevices()
            ProvisioningRetryAction.SCAN_NETWORKS -> scanNetworks()
            ProvisioningRetryAction.SEND_CREDENTIALS -> scanNetworks()
            null -> findDevices()
        }
    }

    fun cancel() = provisioning.cancel()

    override fun onCleared() {
        provisioning.close()
        super.onCleared()
    }
}

class ConnectViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ConnectViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return ConnectViewModel(
            EspressifDeviceProvisioningManager(appContext),
            ProvisionedDeviceRegistrar(DeviceManagerProvisionedDeviceStore)
        ) as T
    }
}
