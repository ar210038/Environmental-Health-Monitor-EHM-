package com.enviroguard.app.external

import com.enviroguard.app.external.advisory.ExternalAdvisoryEngine
import com.enviroguard.app.external.location.ExternalCoordinates
import com.enviroguard.app.external.model.ExternalAdvisoryType
import com.enviroguard.app.external.model.ExternalAqiCategory
import com.enviroguard.app.external.model.ExternalEnvironmentSnapshot
import com.enviroguard.app.external.model.ExternalHourlyForecast
import com.enviroguard.app.external.network.ExternalAirQualityData
import com.enviroguard.app.external.network.ExternalAirQualityHour
import com.enviroguard.app.external.network.ExternalHttpResponse
import com.enviroguard.app.external.network.ExternalWeatherData
import com.enviroguard.app.external.network.ExternalWeatherHour
import com.enviroguard.app.external.network.OpenMeteoClient
import com.enviroguard.app.external.network.OpenMeteoJsonParser
import com.enviroguard.app.external.network.OpenMeteoRateLimitException
import com.enviroguard.app.external.network.OpenMeteoService
import com.enviroguard.app.external.repository.DailyRequestQuota
import com.enviroguard.app.external.repository.ExternalEnvironmentRepository
import com.enviroguard.app.external.repository.ExternalRefreshResult
import com.enviroguard.app.external.repository.QuotaControlStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEnvironmentUnitTest {
    @Test
    fun `US AQI categories include every boundary`() {
        val expectations = mapOf(
            0 to ExternalAqiCategory.GOOD,
            50 to ExternalAqiCategory.GOOD,
            51 to ExternalAqiCategory.MODERATE,
            100 to ExternalAqiCategory.MODERATE,
            101 to ExternalAqiCategory.UNHEALTHY_FOR_SENSITIVE_GROUPS,
            150 to ExternalAqiCategory.UNHEALTHY_FOR_SENSITIVE_GROUPS,
            151 to ExternalAqiCategory.UNHEALTHY,
            200 to ExternalAqiCategory.UNHEALTHY,
            201 to ExternalAqiCategory.VERY_UNHEALTHY,
            300 to ExternalAqiCategory.VERY_UNHEALTHY,
            301 to ExternalAqiCategory.HAZARDOUS,
            500 to ExternalAqiCategory.HAZARDOUS
        )
        expectations.forEach { (aqi, expected) -> assertEquals(expected, ExternalAqiCategory.from(aqi)) }
        assertNull(ExternalAqiCategory.from(null))
        assertNull(ExternalAqiCategory.from(-1))
    }

    @Test
    fun `advisory engine produces external AQI and rain advisories`() {
        val snapshot = snapshot(
            usAqi = 101,
            hourly = listOf(forecast(precipitationProbability = 70.0))
        )

        val advisories = ExternalAdvisoryEngine.evaluate(snapshot)

        assertEquals(2, advisories.size)
        assertEquals(ExternalAdvisoryType.OUTDOOR_AIR, advisories[0].type)
        assertEquals("Outdoor Air Advisory", advisories[0].title)
        assertEquals("Rain is likely within the next few hours.", advisories[1].message)
    }

    @Test
    fun `thunderstorm forecast takes precedence over rain message`() {
        val snapshot = snapshot(
            usAqi = 50,
            hourly = listOf(forecast(weatherCode = 95, precipitationProbability = 90.0))
        )

        val advisories = ExternalAdvisoryEngine.evaluate(snapshot)

        assertEquals(1, advisories.size)
        assertEquals("Thunderstorm conditions are forecast within the next few hours.", advisories.single().message)
    }

    @Test
    fun `automatic refresh uses snapshot younger than thirty minutes`() = runBlocking {
        var now = 1_000_000L
        val service = FakeOpenMeteoService()
        val repository = repository(service, clock = { now })

        assertTrue(repository.refresh(COORDINATES, manual = false) is ExternalRefreshResult.Success)
        assertEquals(2, service.calls)

        now += ExternalEnvironmentRepository.CACHE_FRESHNESS_MILLIS - 1
        val cached = repository.refresh(COORDINATES, manual = false) as ExternalRefreshResult.Success
        assertTrue(cached.fromCache)
        assertEquals(2, service.calls)

        now += 1
        val refreshed = repository.refresh(COORDINATES, manual = false) as ExternalRefreshResult.Success
        assertFalse(refreshed.fromCache)
        assertEquals(4, service.calls)
    }

    @Test
    fun `manual refresh observes five minute cooldown`() = runBlocking {
        var now = 1_000_000L
        val service = FakeOpenMeteoService()
        val repository = repository(service, clock = { now })

        assertTrue(repository.refresh(COORDINATES, manual = true) is ExternalRefreshResult.Success)
        now += ExternalEnvironmentRepository.MANUAL_REFRESH_COOLDOWN_MILLIS - 1
        assertTrue(repository.refresh(COORDINATES, manual = true) is ExternalRefreshResult.Cooldown)
        assertEquals(2, service.calls)

        now += 1
        assertTrue(repository.refresh(COORDINATES, manual = true) is ExternalRefreshResult.Success)
        assertEquals(4, service.calls)
    }

    @Test
    fun `daily quota permits exactly 120 calls and resets on a new date`() {
        var date = "2026-09-15"
        val store = MemoryQuotaStore()
        val quota = DailyRequestQuota(store, dateProvider = { date })

        repeat(120) { assertTrue(quota.tryAcquire()) }
        assertEquals(0, quota.remaining())
        assertFalse(quota.tryAcquire())

        date = "2026-09-16"
        assertEquals(120, quota.remaining())
        assertTrue(quota.tryAcquire())
        assertEquals(1, store.requestCount)
        assertEquals(date, store.quotaDate)
    }

    @Test
    fun `malformed response becomes repository error without crashing`() = runBlocking {
        val client = OpenMeteoClient { ExternalHttpResponse(200, "{not-json") }
        val repository = ExternalEnvironmentRepository(
            service = client,
            quota = DailyRequestQuota(MemoryQuotaStore())
        )

        assertEquals(ExternalRefreshResult.Error, repository.refresh(COORDINATES, manual = false))
    }

    @Test
    fun `representative Open-Meteo JSON parses requested fields`() {
        val weather = OpenMeteoJsonParser.parseWeather(WEATHER_JSON)
        val airQuality = OpenMeteoJsonParser.parseAirQuality(AIR_QUALITY_JSON)

        assertEquals(31.2, weather?.currentTemperatureC ?: Double.NaN, 0.001)
        assertEquals(36.0, weather?.apparentTemperatureC ?: Double.NaN, 0.001)
        assertEquals(80.0, weather?.hourly?.first()?.precipitationProbabilityPercent ?: Double.NaN, 0.001)
        assertEquals(156, airQuality?.usAqi)
        assertEquals(64.5, airQuality?.pm25MicrogramsPerCubicMetre ?: Double.NaN, 0.001)
        assertEquals(2, airQuality?.hourly?.size)
    }

    @Test
    fun `requests contain only the selected current and twelve-hour fields`() = runBlocking {
        val urls = mutableListOf<String>()
        val client = OpenMeteoClient { url ->
            urls += url
            ExternalHttpResponse(200, if (url.contains("air-quality")) AIR_QUALITY_JSON else WEATHER_JSON)
        }

        client.fetchWeather(COORDINATES) { true }
        client.fetchAirQuality(COORDINATES) { true }

        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"))
        assertTrue(urls[0].contains("hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m"))
        assertTrue(urls[1].contains("current=us_aqi,pm2_5,pm10"))
        assertTrue(urls[1].contains("hourly=us_aqi,pm2_5,pm10"))
        assertTrue(urls.all { it.contains("forecast_hours=12") && it.contains("timezone=auto") })
    }

    @Test
    fun `temporary server failure retries once while 429 does not retry`() = runBlocking {
        var serverCalls = 0
        val retryingClient = OpenMeteoClient {
            serverCalls += 1
            if (serverCalls == 1) ExternalHttpResponse(503, "")
            else ExternalHttpResponse(200, WEATHER_JSON)
        }
        retryingClient.fetchWeather(COORDINATES) { true }
        assertEquals(2, serverCalls)

        var rateLimitCalls = 0
        val rateLimitedClient = OpenMeteoClient {
            rateLimitCalls += 1
            ExternalHttpResponse(429, "")
        }
        val failure = runCatching { rateLimitedClient.fetchWeather(COORDINATES) { true } }.exceptionOrNull()
        assertTrue(failure is OpenMeteoRateLimitException)
        assertEquals(1, rateLimitCalls)
    }

    @Test
    fun `missing and invalid values remain null instead of becoming zero`() = runBlocking {
        val weather = OpenMeteoJsonParser.parseWeather(
            """{"current":{"temperature_2m":null,"apparent_temperature":999,"weather_code":-1,"wind_speed_10m":null},"hourly":{"time":["2026-09-15T14:00"],"temperature_2m":[null]}}"""
        )!!
        val airQuality = OpenMeteoJsonParser.parseAirQuality(
            """{"current":{"us_aqi":null,"pm2_5":-5,"pm10":null},"hourly":{"time":["2026-09-15T14:00"],"us_aqi":[null]}}"""
        )!!
        val service = FakeOpenMeteoService(weather, airQuality)
        val result = repository(service).refresh(COORDINATES, manual = false) as ExternalRefreshResult.Success

        assertNull(result.snapshot.currentTemperatureC)
        assertNull(result.snapshot.apparentTemperatureC)
        assertNull(result.snapshot.weatherCode)
        assertNull(result.snapshot.windSpeedKmh)
        assertNull(result.snapshot.usAqi)
        assertNull(result.snapshot.pm25MicrogramsPerCubicMetre)
        assertNull(result.snapshot.pm10MicrogramsPerCubicMetre)
        assertNull(result.snapshot.hourlyForecast.single().temperatureC)
        assertNull(result.snapshot.hourlyForecast.single().usAqi)
    }

    private fun repository(
        service: OpenMeteoService,
        clock: () -> Long = { 1_000_000L }
    ) = ExternalEnvironmentRepository(
        service = service,
        quota = DailyRequestQuota(MemoryQuotaStore()),
        clock = clock
    )

    private class MemoryQuotaStore : QuotaControlStore {
        override var quotaDate: String? = null
        override var requestCount: Int = 0
    }

    private class FakeOpenMeteoService(
        private val weather: ExternalWeatherData = ExternalWeatherData(
            currentTemperatureC = 31.0,
            apparentTemperatureC = 36.0,
            weatherCode = 2,
            windSpeedKmh = 12.0,
            hourly = listOf(
                ExternalWeatherHour("2026-09-15T14:00", 31.0, 36.0, 80.0, 2.0, 61, 12.0)
            )
        ),
        private val airQuality: ExternalAirQualityData = ExternalAirQualityData(
            usAqi = 156,
            pm25MicrogramsPerCubicMetre = 64.5,
            pm10MicrogramsPerCubicMetre = 82.0,
            hourly = listOf(ExternalAirQualityHour("2026-09-15T14:00", 156, 64.5, 82.0))
        )
    ) : OpenMeteoService {
        var calls = 0

        override suspend fun fetchWeather(
            coordinates: ExternalCoordinates,
            requestPermit: () -> Boolean
        ): ExternalWeatherData {
            check(requestPermit())
            calls += 1
            return weather
        }

        override suspend fun fetchAirQuality(
            coordinates: ExternalCoordinates,
            requestPermit: () -> Boolean
        ): ExternalAirQualityData {
            check(requestPermit())
            calls += 1
            return airQuality
        }
    }

    private fun snapshot(
        usAqi: Int?,
        hourly: List<ExternalHourlyForecast>
    ) = ExternalEnvironmentSnapshot(
        currentTemperatureC = null,
        apparentTemperatureC = null,
        weatherCode = null,
        windSpeedKmh = null,
        usAqi = usAqi,
        pm25MicrogramsPerCubicMetre = null,
        pm10MicrogramsPerCubicMetre = null,
        hourlyForecast = hourly,
        fetchedAt = 0L
    )

    private fun forecast(
        weatherCode: Int? = null,
        precipitationProbability: Double? = null
    ) = ExternalHourlyForecast(
        time = null,
        temperatureC = null,
        apparentTemperatureC = null,
        precipitationProbabilityPercent = precipitationProbability,
        precipitationMm = null,
        weatherCode = weatherCode,
        windSpeedKmh = null,
        usAqi = null,
        pm25MicrogramsPerCubicMetre = null,
        pm10MicrogramsPerCubicMetre = null
    )

    private companion object {
        val COORDINATES = ExternalCoordinates(23.8103, 90.4125)

        const val WEATHER_JSON = """
            {
              "current": {
                "temperature_2m": 31.2,
                "apparent_temperature": 36.0,
                "weather_code": 2,
                "wind_speed_10m": 12.4
              },
              "hourly": {
                "time": ["2026-09-15T14:00", "2026-09-15T15:00"],
                "temperature_2m": [31.2, 30.8],
                "apparent_temperature": [36.0, 35.2],
                "precipitation_probability": [80, 65],
                "precipitation": [2.1, 0.4],
                "weather_code": [61, 3],
                "wind_speed_10m": [12.4, 10.2]
              }
            }
        """

        const val AIR_QUALITY_JSON = """
            {
              "current": {"us_aqi": 156, "pm2_5": 64.5, "pm10": 82.0},
              "hourly": {
                "time": ["2026-09-15T14:00", "2026-09-15T15:00"],
                "us_aqi": [156, 148],
                "pm2_5": [64.5, 60.0],
                "pm10": [82.0, 79.5]
              }
            }
        """
    }
}
