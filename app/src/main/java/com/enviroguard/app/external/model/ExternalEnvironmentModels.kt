package com.enviroguard.app.external.model

data class ExternalHourlyForecast(
    val time: String?,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val precipitationProbabilityPercent: Double?,
    val precipitationMm: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?,
    val usAqi: Int?,
    val pm25MicrogramsPerCubicMetre: Double?,
    val pm10MicrogramsPerCubicMetre: Double?
)

data class ExternalEnvironmentSnapshot(
    val currentTemperatureC: Double?,
    val apparentTemperatureC: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?,
    val usAqi: Int?,
    val pm25MicrogramsPerCubicMetre: Double?,
    val pm10MicrogramsPerCubicMetre: Double?,
    val hourlyForecast: List<ExternalHourlyForecast>,
    val fetchedAt: Long
)

enum class ExternalAqiCategory(val displayName: String) {
    GOOD("Good"),
    MODERATE("Moderate"),
    UNHEALTHY_FOR_SENSITIVE_GROUPS("Unhealthy for Sensitive Groups"),
    UNHEALTHY("Unhealthy"),
    VERY_UNHEALTHY("Very Unhealthy"),
    HAZARDOUS("Hazardous");

    companion object {
        fun from(aqi: Int?): ExternalAqiCategory? = when (aqi) {
            null -> null
            in 0..50 -> GOOD
            in 51..100 -> MODERATE
            in 101..150 -> UNHEALTHY_FOR_SENSITIVE_GROUPS
            in 151..200 -> UNHEALTHY
            in 201..300 -> VERY_UNHEALTHY
            in 301..Int.MAX_VALUE -> HAZARDOUS
            else -> null
        }
    }
}

enum class ExternalAdvisoryType {
    OUTDOOR_AIR,
    FORECAST
}

data class ExternalAdvisory(
    val type: ExternalAdvisoryType,
    val title: String,
    val message: String
)

sealed interface ExternalEnvironmentState {
    data object NotLoaded : ExternalEnvironmentState
    data object Loading : ExternalEnvironmentState

    data class Success(
        val snapshot: ExternalEnvironmentSnapshot,
        val advisories: List<ExternalAdvisory>,
        val notice: String? = null
    ) : ExternalEnvironmentState

    data object PermissionRequired : ExternalEnvironmentState
    data object LocationDisabled : ExternalEnvironmentState
    data object LocationUnavailable : ExternalEnvironmentState
    data object Offline : ExternalEnvironmentState
    data class Cooldown(val retryAfterMillis: Long) : ExternalEnvironmentState
    data class RateLimited(val dailyLimitReached: Boolean) : ExternalEnvironmentState
    data object Error : ExternalEnvironmentState
}
