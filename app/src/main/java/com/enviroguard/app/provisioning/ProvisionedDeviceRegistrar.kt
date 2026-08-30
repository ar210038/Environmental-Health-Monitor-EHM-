package com.enviroguard.app.provisioning

import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.SavedDevice

fun interface ProvisionedDeviceStore {
    fun addAndActivate(device: SavedDevice)
}

object DeviceManagerProvisionedDeviceStore : ProvisionedDeviceStore {
    override fun addAndActivate(device: SavedDevice) {
        val savedDevice = DeviceManager.getSavedDevices().firstOrNull { it.deviceId == device.deviceId } ?: device
        DeviceManager.addOrUpdateDevice(savedDevice)
        DeviceManager.setActiveDevice(savedDevice.deviceId)
        DeviceManager.isDemoMode = false
    }
}

class ProvisionedDeviceRegistrar(private val store: ProvisionedDeviceStore) {
    private var registeredDeviceId: String? = null

    fun register(state: ProvisioningState): SavedDevice? {
        if (state !is ProvisioningState.Provisioned) return null
        val deviceId = EhmDeviceIdentity.normalize(state.deviceId) ?: return null
        if (registeredDeviceId == deviceId) return null
        return SavedDevice(deviceId, EhmDeviceIdentity.displayName(deviceId)).also {
            store.addAndActivate(it)
            registeredDeviceId = deviceId
        }
    }
}
