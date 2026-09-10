package com.enviroguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.enviroguard.app.demo.ScenarioTestManager
import org.json.JSONArray
import org.json.JSONObject

object DeviceManager {
    private const val PREFS_NAME = "enviroguard_prefs"
    private const val KEY_DEVICE_ID = "active_device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_CONNECTED = "device_connected"
    private const val KEY_SAVED_DEVICES = "saved_devices_v1"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyDevice()
    }

    var activeDeviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var activeDeviceName: String
        get() = getActiveDevice()?.displayName
            ?: prefs.getString(KEY_DEVICE_NAME, "EHM Device")
            ?: "EHM Device"
        set(value) {
            prefs.edit().putString(KEY_DEVICE_NAME, value).apply()
            activeDeviceId?.let { addOrUpdateDevice(SavedDevice(it, value)) }
        }

    var isDeviceConnected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONNECTED, value).apply()

    var isDemoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true)
        set(value) {
            if (!value) ScenarioTestManager.clear()
            prefs.edit().putBoolean("demo_mode", value).apply()
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean("notification_permission_requested", false)
        set(value) = prefs.edit().putBoolean("notification_permission_requested", value).apply()

    var useCelsius: Boolean
        get() = prefs.getBoolean("use_celsius", true)
        set(value) = prefs.edit().putBoolean("use_celsius", value).apply()

    fun getSavedDevices(): List<SavedDevice> = readSavedDevices()

    fun getActiveDevice(): SavedDevice? {
        val id = activeDeviceId ?: return null
        return readSavedDevices().firstOrNull { it.deviceId == id }
            ?: SavedDevice(id, prefs.getString(KEY_DEVICE_NAME, "EHM Device") ?: "EHM Device")
                .also(::addOrUpdateDevice)
    }

    fun setActiveDevice(deviceId: String): Boolean {
        val device = readSavedDevices().firstOrNull { it.deviceId == deviceId } ?: return false
        prefs.edit()
            .putString(KEY_DEVICE_ID, device.deviceId)
            .putString(KEY_DEVICE_NAME, device.displayName)
            .apply()
        return true
    }

    fun addOrUpdateDevice(device: SavedDevice) {
        if (device.deviceId.isBlank()) return
        writeSavedDevices(SavedDeviceCollection.upsert(readSavedDevices(), device))
        if (activeDeviceId == null) setActiveDevice(device.deviceId)
    }

    fun removeDevice(deviceId: String) {
        val remaining = readSavedDevices().filterNot { it.deviceId == deviceId }
        writeSavedDevices(remaining)
        if (activeDeviceId == deviceId) {
            val replacement = remaining.firstOrNull()
            if (replacement != null) setActiveDevice(replacement.deviceId)
            else prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_DEVICE_NAME).putBoolean(KEY_CONNECTED, false).apply()
        }
    }

    fun saveDevice(deviceId: String, deviceName: String) {
        addOrUpdateDevice(SavedDevice(deviceId, deviceName))
        setActiveDevice(deviceId)
        isDeviceConnected = true
    }

    fun clearDevice() {
        activeDeviceId?.let(::removeDevice)
            ?: prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_DEVICE_NAME).putBoolean(KEY_CONNECTED, false).apply()
    }

    private fun migrateLegacyDevice() {
        val legacyId = prefs.getString(KEY_DEVICE_ID, null) ?: return
        if (readSavedDevices().none { it.deviceId == legacyId }) {
            addOrUpdateDevice(
                SavedDevice(
                    legacyId,
                    prefs.getString(KEY_DEVICE_NAME, "EHM Device") ?: "EHM Device"
                )
            )
        }
    }

    private fun readSavedDevices(): List<SavedDevice> {
        val raw = prefs.getString(KEY_SAVED_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.optString("deviceId")
                    if (id.isNotBlank()) add(SavedDevice(id, item.optString("displayName", "EHM Device")))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeSavedDevices(devices: List<SavedDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(JSONObject().put("deviceId", device.deviceId).put("displayName", device.displayName))
        }
        prefs.edit().putString(KEY_SAVED_DEVICES, array.toString()).apply()
    }
}
