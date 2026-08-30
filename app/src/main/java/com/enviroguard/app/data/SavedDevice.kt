package com.enviroguard.app.data

/** A locally registered monitoring unit. Only one device is active at a time. */
data class SavedDevice(
    val deviceId: String,
    val displayName: String
)
