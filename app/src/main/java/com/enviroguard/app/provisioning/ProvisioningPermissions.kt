package com.enviroguard.app.provisioning

import android.Manifest

/** Scanning still needs precise location; Nearby Wi-Fi does not replace it. */
internal object ProvisioningPermissions {
    fun required(sdk: Int): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    // Android 12+ requires coarse and fine to be requested together for precise location.
    fun requested(sdk: Int): Array<String> =
        (listOf(Manifest.permission.ACCESS_COARSE_LOCATION) + required(sdk)).toTypedArray()

    fun firstMissing(sdk: Int, isGranted: (String) -> Boolean): String? =
        required(sdk).firstOrNull { !isGranted(it) }
}
