package com.enviroguard.app.data

object SavedDeviceCollection {
    fun upsert(devices: List<SavedDevice>, device: SavedDevice): List<SavedDevice> {
        if (device.deviceId.isBlank()) return devices
        val result = devices.toMutableList()
        val index = result.indexOfFirst { it.deviceId == device.deviceId }
        if (index >= 0) result[index] = device else result.add(device)
        return result
    }
}
