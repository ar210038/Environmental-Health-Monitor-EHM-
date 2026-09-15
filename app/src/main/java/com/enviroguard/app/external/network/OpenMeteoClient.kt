package com.enviroguard.app.external.network

import com.enviroguard.app.external.location.ExternalCoordinates
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ExternalWeatherHour(
    val time: String?,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val precipitationProbabilityPercent: Double?,
    val precipitationMm: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?
)

internal data class ExternalWeatherData(
    val currentTemperatureC: Double?,
    val apparentTemperatureC: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?,
    val hourly: List<ExternalWeatherHour>
)

internal data class ExternalAirQualityHour(
    val time: String?,
    val usAqi: Int?,
    val pm25MicrogramsPerCubicMetre: Double?,
    val pm10MicrogramsPerCubicMetre: Double?
)

internal data class ExternalAirQualityData(
    val usAqi: Int?,
    val pm25MicrogramsPerCubicMetre: Double?,
    val pm10MicrogramsPerCubicMetre: Double?,
    val hourly: List<ExternalAirQualityHour>
)

internal interface OpenMeteoService {
    suspend fun fetchWeather(
        coordinates: ExternalCoordinates,
        requestPermit: () -> Boolean
    ): ExternalWeatherData

    suspend fun fetchAirQuality(
        coordinates: ExternalCoordinates,
        requestPermit: () -> Boolean
    ): ExternalAirQualityData
}

internal data class ExternalHttpResponse(val statusCode: Int, val body: String)

internal fun interface ExternalHttpTransport {
    suspend fun get(url: String): ExternalHttpResponse
}

internal class HttpUrlConnectionExternalTransport(
    private val connectTimeoutMillis: Int = 7_000,
    private val readTimeoutMillis: Int = 12_000
) : ExternalHttpTransport {
    override suspend fun get(url: String): ExternalHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            ExternalHttpResponse(statusCode, body)
        } finally {
            connection.disconnect()
        }
    }
}

internal class OpenMeteoHttpException(val statusCode: Int) : IOException("Open-Meteo request failed.")
internal class OpenMeteoRateLimitException : IOException("Open-Meteo rate limit reached.")
internal class ExternalDailyBudgetException : IOException("Daily external-data request budget reached.")
internal class OpenMeteoResponseException : IOException("Open-Meteo returned an invalid response.")

internal class OpenMeteoClient(
    private val transport: ExternalHttpTransport = HttpUrlConnectionExternalTransport()
) : OpenMeteoService {
    override suspend fun fetchWeather(
        coordinates: ExternalCoordinates,
        requestPermit: () -> Boolean
    ): ExternalWeatherData {
        val response = requestWithOneRetry(weatherUrl(coordinates), requestPermit)
        return OpenMeteoJsonParser.parseWeather(response.body) ?: throw OpenMeteoResponseException()
    }

    override suspend fun fetchAirQuality(
        coordinates: ExternalCoordinates,
        requestPermit: () -> Boolean
    ): ExternalAirQualityData {
        val response = requestWithOneRetry(airQualityUrl(coordinates), requestPermit)
        return OpenMeteoJsonParser.parseAirQuality(response.body) ?: throw OpenMeteoResponseException()
    }

    private suspend fun requestWithOneRetry(
        url: String,
        requestPermit: () -> Boolean
    ): ExternalHttpResponse {
        var lastIoFailure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (!requestPermit()) throw ExternalDailyBudgetException()
            val response = try {
                transport.get(url)
            } catch (error: IOException) {
                lastIoFailure = error
                if (attempt == MAX_ATTEMPTS - 1) throw error
                return@repeat
            }
            when {
                response.statusCode == 429 -> throw OpenMeteoRateLimitException()
                response.statusCode in 500..599 && attempt < MAX_ATTEMPTS - 1 -> return@repeat
                response.statusCode !in 200..299 -> throw OpenMeteoHttpException(response.statusCode)
                else -> return response
            }
        }
        throw lastIoFailure ?: OpenMeteoHttpException(HttpURLConnection.HTTP_UNAVAILABLE)
    }

    private fun weatherUrl(coordinates: ExternalCoordinates): String = buildString {
        append(WEATHER_ENDPOINT)
        append("?latitude=").append(formatCoordinate(coordinates.latitude))
        append("&longitude=").append(formatCoordinate(coordinates.longitude))
        append("&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
        append("&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m")
        append("&forecast_hours=12&timezone=auto")
    }

    private fun airQualityUrl(coordinates: ExternalCoordinates): String = buildString {
        append(AIR_QUALITY_ENDPOINT)
        append("?latitude=").append(formatCoordinate(coordinates.latitude))
        append("&longitude=").append(formatCoordinate(coordinates.longitude))
        append("&current=us_aqi,pm2_5,pm10")
        append("&hourly=us_aqi,pm2_5,pm10")
        append("&forecast_hours=12&timezone=auto")
    }

    private fun formatCoordinate(value: Double) = String.format(Locale.US, "%.5f", value)

    private companion object {
        const val WEATHER_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val AIR_QUALITY_ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"
        const val MAX_ATTEMPTS = 2
    }
}

internal object OpenMeteoJsonParser {
    private val gson = Gson()

    fun parseWeather(json: String): ExternalWeatherData? = runCatching {
        val response = gson.fromJson(json, WeatherResponse::class.java) ?: return@runCatching null
        ExternalWeatherData(
            currentTemperatureC = response.current?.temperatureC.validTemperature(),
            apparentTemperatureC = response.current?.apparentTemperatureC.validApparentTemperature(),
            weatherCode = response.current?.weatherCode.validWeatherCode(),
            windSpeedKmh = response.current?.windSpeedKmh.validWindSpeed(),
            hourly = response.hourly.toWeatherHours()
        )
    }.getOrNull()

    fun parseAirQuality(json: String): ExternalAirQualityData? = runCatching {
        val response = gson.fromJson(json, AirQualityResponse::class.java) ?: return@runCatching null
        ExternalAirQualityData(
            usAqi = response.current?.usAqi.validAqi(),
            pm25MicrogramsPerCubicMetre = response.current?.pm25.validParticulateMatter(),
            pm10MicrogramsPerCubicMetre = response.current?.pm10.validParticulateMatter(),
            hourly = response.hourly.toAirQualityHours()
        )
    }.getOrNull()

    private fun WeatherHourly?.toWeatherHours(): List<ExternalWeatherHour> {
        if (this == null) return emptyList()
        val size = listOf(
            time.orEmpty().size,
            temperatureC.orEmpty().size,
            apparentTemperatureC.orEmpty().size,
            precipitationProbability.orEmpty().size,
            precipitation.orEmpty().size,
            weatherCode.orEmpty().size,
            windSpeedKmh.orEmpty().size
        ).maxOrNull()?.coerceAtMost(FORECAST_HOURS) ?: 0
        return List(size) { index ->
            ExternalWeatherHour(
                time = time.valueAt(index)?.takeIf(String::isNotBlank),
                temperatureC = temperatureC.valueAt(index).validTemperature(),
                apparentTemperatureC = apparentTemperatureC.valueAt(index).validApparentTemperature(),
                precipitationProbabilityPercent = precipitationProbability.valueAt(index).validProbability(),
                precipitationMm = precipitation.valueAt(index).validPrecipitation(),
                weatherCode = weatherCode.valueAt(index).validWeatherCode(),
                windSpeedKmh = windSpeedKmh.valueAt(index).validWindSpeed()
            )
        }
    }

    private fun AirQualityHourly?.toAirQualityHours(): List<ExternalAirQualityHour> {
        if (this == null) return emptyList()
        val size = listOf(time.orEmpty().size, usAqi.orEmpty().size, pm25.orEmpty().size, pm10.orEmpty().size)
            .maxOrNull()?.coerceAtMost(FORECAST_HOURS) ?: 0
        return List(size) { index ->
            ExternalAirQualityHour(
                time = time.valueAt(index)?.takeIf(String::isNotBlank),
                usAqi = usAqi.valueAt(index).validAqi(),
                pm25MicrogramsPerCubicMetre = pm25.valueAt(index).validParticulateMatter(),
                pm10MicrogramsPerCubicMetre = pm10.valueAt(index).validParticulateMatter()
            )
        }
    }

    private fun Double?.validTemperature() = validIn(-100.0, 70.0)
    private fun Double?.validApparentTemperature() = validIn(-120.0, 80.0)
    private fun Double?.validWindSpeed() = validIn(0.0, 500.0)
    private fun Double?.validProbability() = validIn(0.0, 100.0)
    private fun Double?.validPrecipitation() = validIn(0.0, 1_000.0)
    private fun Double?.validParticulateMatter() = validIn(0.0, 5_000.0)

    private fun Double?.validAqi(): Int? = validIntegerIn(0, 1_000)
    private fun Double?.validWeatherCode(): Int? = validIntegerIn(0, 99)

    private fun Double?.validIn(minimum: Double, maximum: Double): Double? =
        this?.takeIf { it.isFinite() && it in minimum..maximum }

    private fun Double?.validIntegerIn(minimum: Int, maximum: Int): Int? {
        val value = this ?: return null
        if (!value.isFinite() || value % 1.0 != 0.0 || value !in minimum.toDouble()..maximum.toDouble()) return null
        return value.toInt()
    }

    private fun <T> List<T>?.valueAt(index: Int): T? = this?.getOrNull(index)

    private data class WeatherResponse(
        val current: WeatherCurrent?,
        val hourly: WeatherHourly?
    )

    private data class WeatherCurrent(
        @SerializedName("temperature_2m") val temperatureC: Double?,
        @SerializedName("apparent_temperature") val apparentTemperatureC: Double?,
        @SerializedName("weather_code") val weatherCode: Double?,
        @SerializedName("wind_speed_10m") val windSpeedKmh: Double?
    )

    private data class WeatherHourly(
        val time: List<String?>? = null,
        @SerializedName("temperature_2m") val temperatureC: List<Double?>? = null,
        @SerializedName("apparent_temperature") val apparentTemperatureC: List<Double?>? = null,
        @SerializedName("precipitation_probability") val precipitationProbability: List<Double?>? = null,
        val precipitation: List<Double?>? = null,
        @SerializedName("weather_code") val weatherCode: List<Double?>? = null,
        @SerializedName("wind_speed_10m") val windSpeedKmh: List<Double?>? = null
    )

    private data class AirQualityResponse(
        val current: AirQualityCurrent?,
        val hourly: AirQualityHourly?
    )

    private data class AirQualityCurrent(
        @SerializedName("us_aqi") val usAqi: Double?,
        @SerializedName("pm2_5") val pm25: Double?,
        val pm10: Double?
    )

    private data class AirQualityHourly(
        val time: List<String?>? = null,
        @SerializedName("us_aqi") val usAqi: List<Double?>? = null,
        @SerializedName("pm2_5") val pm25: List<Double?>? = null,
        val pm10: List<Double?>? = null
    )

    private const val FORECAST_HOURS = 12
}
