package com.enviroguard.app.external.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class ExternalCoordinates(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

internal sealed interface ExternalLocationResult {
    data class Available(val coordinates: ExternalCoordinates) : ExternalLocationResult
    data object PermissionRequired : ExternalLocationResult
    data object Disabled : ExternalLocationResult
    data object Unavailable : ExternalLocationResult
}

internal interface ExternalLocationProvider {
    suspend fun currentLocation(): ExternalLocationResult
}

internal class AndroidExternalLocationProvider(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis
) : ExternalLocationProvider {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun currentLocation(): ExternalLocationResult {
        if (!hasLocationPermission()) return ExternalLocationResult.PermissionRequired
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) return ExternalLocationResult.Disabled

        val providers = buildList {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) return ExternalLocationResult.Disabled

        val cached = providers.mapNotNull(::lastKnownLocationOrNull)
            .filter { now() - it.time in 0..MAX_LAST_KNOWN_AGE_MILLIS }
            .maxByOrNull(Location::getTime)
            ?.toCoordinatesOrNull()
        if (cached != null) return ExternalLocationResult.Available(cached)

        for (provider in providers) {
            val current = requestCurrentLocation(provider)?.toCoordinatesOrNull()
            if (current != null) return ExternalLocationResult.Available(current)
        }
        return ExternalLocationResult.Unavailable
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastKnownLocationOrNull(provider: String): Location? = try {
        locationManager.getLastKnownLocation(provider)
    } catch (_: SecurityException) {
        null
    }

    private suspend fun requestCurrentLocation(provider: String): Location? =
        withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestCurrentLocationModern(provider)
            else requestCurrentLocationLegacy(provider)
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestCurrentLocationModern(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(appContext)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun requestCurrentLocationLegacy(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun Location.toCoordinatesOrNull(): ExternalCoordinates? =
        ExternalCoordinates(latitude, longitude).takeIf(ExternalCoordinates::isValid)

    private companion object {
        const val LOCATION_TIMEOUT_MILLIS = 10_000L
        const val MAX_LAST_KNOWN_AGE_MILLIS = 30 * 60_000L
    }
}
