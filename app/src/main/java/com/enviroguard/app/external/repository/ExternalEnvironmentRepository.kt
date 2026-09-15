package com.enviroguard.app.external.repository

import com.enviroguard.app.external.location.ExternalCoordinates
import com.enviroguard.app.external.model.ExternalEnvironmentSnapshot
import com.enviroguard.app.external.model.ExternalHourlyForecast
import com.enviroguard.app.external.network.ExternalAirQualityData
import com.enviroguard.app.external.network.ExternalDailyBudgetException
import com.enviroguard.app.external.network.ExternalWeatherData
import com.enviroguard.app.external.network.OpenMeteoHttpException
import com.enviroguard.app.external.network.OpenMeteoRateLimitException
import com.enviroguard.app.external.network.OpenMeteoResponseException
import com.enviroguard.app.external.network.OpenMeteoService
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ExternalRefreshPreflight {
    data class Fresh(val snapshot: ExternalEnvironmentSnapshot) : ExternalRefreshPreflight
    data class Cooldown(val retryAfterMillis: Long) : ExternalRefreshPreflight
    data object DailyLimitReached : ExternalRefreshPreflight
    data object Ready : ExternalRefreshPreflight
}

internal sealed interface ExternalRefreshResult {
    data class Success(
        val snapshot: ExternalEnvironmentSnapshot,
        val fromCache: Boolean
    ) : ExternalRefreshResult

    data class Cooldown(val retryAfterMillis: Long) : ExternalRefreshResult
    data object DailyLimitReached : ExternalRefreshResult
    data object ServiceRateLimited : ExternalRefreshResult
    data object Offline : ExternalRefreshResult
    data object Error : ExternalRefreshResult
}

internal class ExternalEnvironmentRepository(
    private val service: OpenMeteoService,
    private val quota: DailyRequestQuota,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val refreshMutex = Mutex()

    @Volatile
    private var cachedSnapshot: ExternalEnvironmentSnapshot? = null

    @Volatile
    private var lastManualRefreshStartedAt: Long? = null

    fun preflight(manual: Boolean): ExternalRefreshPreflight {
        val now = clock()
        if (!manual) {
            freshSnapshot(now)?.let { return ExternalRefreshPreflight.Fresh(it) }
        } else {
            manualCooldownRemaining(now)?.let { return ExternalRefreshPreflight.Cooldown(it) }
        }
        return if (quota.remaining() >= REQUESTS_PER_REFRESH) {
            ExternalRefreshPreflight.Ready
        } else {
            ExternalRefreshPreflight.DailyLimitReached
        }
    }

    suspend fun refresh(
        coordinates: ExternalCoordinates,
        manual: Boolean
    ): ExternalRefreshResult = refreshMutex.withLock {
        if (!coordinates.isValid()) return@withLock ExternalRefreshResult.Error

        when (val preflight = preflight(manual)) {
            is ExternalRefreshPreflight.Fresh ->
                return@withLock ExternalRefreshResult.Success(preflight.snapshot, fromCache = true)
            is ExternalRefreshPreflight.Cooldown ->
                return@withLock ExternalRefreshResult.Cooldown(preflight.retryAfterMillis)
            ExternalRefreshPreflight.DailyLimitReached ->
                return@withLock ExternalRefreshResult.DailyLimitReached
            ExternalRefreshPreflight.Ready -> Unit
        }

        if (manual) lastManualRefreshStartedAt = clock()
        try {
            val weather = service.fetchWeather(coordinates, quota::tryAcquire)
            val airQuality = service.fetchAirQuality(coordinates, quota::tryAcquire)
            val snapshot = createSnapshot(weather, airQuality, clock())
            cachedSnapshot = snapshot
            ExternalRefreshResult.Success(snapshot, fromCache = false)
        } catch (_: ExternalDailyBudgetException) {
            ExternalRefreshResult.DailyLimitReached
        } catch (_: OpenMeteoRateLimitException) {
            ExternalRefreshResult.ServiceRateLimited
        } catch (_: OpenMeteoHttpException) {
            ExternalRefreshResult.Error
        } catch (_: OpenMeteoResponseException) {
            ExternalRefreshResult.Error
        } catch (_: IOException) {
            ExternalRefreshResult.Offline
        } catch (_: RuntimeException) {
            ExternalRefreshResult.Error
        }
    }

    private fun freshSnapshot(now: Long): ExternalEnvironmentSnapshot? = cachedSnapshot?.takeIf {
        now - it.fetchedAt in 0 until CACHE_FRESHNESS_MILLIS
    }

    private fun manualCooldownRemaining(now: Long): Long? {
        val startedAt = lastManualRefreshStartedAt ?: return null
        val elapsed = now - startedAt
        return (MANUAL_REFRESH_COOLDOWN_MILLIS - elapsed).takeIf { elapsed >= 0 && it > 0 }
    }

    private fun createSnapshot(
        weather: ExternalWeatherData,
        airQuality: ExternalAirQualityData,
        fetchedAt: Long
    ): ExternalEnvironmentSnapshot {
        val forecastSize = maxOf(weather.hourly.size, airQuality.hourly.size).coerceAtMost(FORECAST_HOURS)
        val forecast = List(forecastSize) { index ->
            val weatherHour = weather.hourly.getOrNull(index)
            val airQualityHour = airQuality.hourly.getOrNull(index)
            ExternalHourlyForecast(
                time = weatherHour?.time ?: airQualityHour?.time,
                temperatureC = weatherHour?.temperatureC,
                apparentTemperatureC = weatherHour?.apparentTemperatureC,
                precipitationProbabilityPercent = weatherHour?.precipitationProbabilityPercent,
                precipitationMm = weatherHour?.precipitationMm,
                weatherCode = weatherHour?.weatherCode,
                windSpeedKmh = weatherHour?.windSpeedKmh,
                usAqi = airQualityHour?.usAqi,
                pm25MicrogramsPerCubicMetre = airQualityHour?.pm25MicrogramsPerCubicMetre,
                pm10MicrogramsPerCubicMetre = airQualityHour?.pm10MicrogramsPerCubicMetre
            )
        }
        return ExternalEnvironmentSnapshot(
            currentTemperatureC = weather.currentTemperatureC,
            apparentTemperatureC = weather.apparentTemperatureC,
            weatherCode = weather.weatherCode,
            windSpeedKmh = weather.windSpeedKmh,
            usAqi = airQuality.usAqi,
            pm25MicrogramsPerCubicMetre = airQuality.pm25MicrogramsPerCubicMetre,
            pm10MicrogramsPerCubicMetre = airQuality.pm10MicrogramsPerCubicMetre,
            hourlyForecast = forecast,
            fetchedAt = fetchedAt
        )
    }

    companion object {
        const val CACHE_FRESHNESS_MILLIS = 30 * 60_000L
        const val MANUAL_REFRESH_COOLDOWN_MILLIS = 5 * 60_000L
        private const val REQUESTS_PER_REFRESH = 2
        private const val FORECAST_HOURS = 12
    }
}
