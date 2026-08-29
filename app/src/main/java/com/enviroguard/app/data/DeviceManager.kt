package com.enviroguard.app.data

import android.content.Context
import android.content.SharedPreferences

object DeviceManager {

    private const val PREFS_NAME  = "enviroguard_prefs"
    private const val KEY_DEVICE_ID   = "active_device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_CONNECTED   = "device_connected"

    private lateinit var prefs: SharedPreferences

    // ── INIT — call once from Application ────────────────────
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── ACTIVE DEVICE ─────────────────────────────────────────
    var activeDeviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var activeDeviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "My EnviroGuard") ?: "My EnviroGuard"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var isDeviceConnected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONNECTED, value).apply()

    // ── DEMO MODE ─────────────────────────────────────────────
    var isDemoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true)
        set(value) = prefs.edit().putBoolean("demo_mode", value).apply()

    // ── NOTIFICATIONS ─────────────────────────────────────────
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean("notification_permission_requested", false)
        set(value) = prefs.edit().putBoolean("notification_permission_requested", value).apply()

    // ── TEMPERATURE UNIT ──────────────────────────────────────
    var useCelsius: Boolean
        get() = prefs.getBoolean("use_celsius", true)
        set(value) = prefs.edit().putBoolean("use_celsius", value).apply()


    // ── SAVE DEVICE AFTER PAIRING ─────────────────────────────
    fun saveDevice(deviceId: String, deviceName: String) {
        activeDeviceId = deviceId
        activeDeviceName = deviceName
        isDeviceConnected = true
    }

    // ── CLEAR DEVICE ──────────────────────────────────────────
    fun clearDevice() {
        activeDeviceId = null
        isDeviceConnected = false
    }

}
