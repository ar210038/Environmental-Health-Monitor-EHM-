package com.enviroguard.app

import com.enviroguard.app.forecast.EnvironmentalForecastService
import com.enviroguard.app.forecast.ForecastAssessmentAdapter
import com.enviroguard.app.forecast.ForecastFeatureSchema
import com.enviroguard.app.forecast.ForecastInference
import com.enviroguard.app.forecast.ForecastModelConfig
import com.enviroguard.app.forecast.ForecastModelException
import com.enviroguard.app.forecast.ForecastOutputDecoder
import com.enviroguard.app.forecast.ForecastPipelineState
import com.enviroguard.app.forecast.ForecastPresentationFactory
import com.enviroguard.app.forecast.ForecastResult
import com.enviroguard.app.forecast.ForecastSource
import com.enviroguard.app.forecast.TimeSeriesFeatureBuilder
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.utils.HeatIndex
import com.google.gson.Gson
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ForecastPipelineUnitTest {
    @Test
    fun featureSchemaHasExplicitStableOrderAndCount() {
        assertEquals(32, ForecastFeatureSchema.featureCount)
        assertEquals(
            listOf(
                "temperature", "humidity", "tvoc", "eco2", "noise_level", "heat_index",
                "temperature_lag5m", "humidity_lag5m", "tvoc_lag5m", "eco2_lag5m", "noise_level_lag5m", "heat_index_lag5m",
                "temperature_lag15m", "humidity_lag15m", "tvoc_lag15m", "eco2_lag15m", "noise_level_lag15m", "heat_index_lag15m",
                "temperature_lag30m", "humidity_lag30m", "tvoc_lag30m", "eco2_lag30m", "noise_level_lag30m", "heat_index_lag30m",
                "temperature_change5m", "humidity_change5m", "tvoc_change5m", "eco2_change5m", "noise_level_change5m", "heat_index_change5m",
                "hour_sin", "hour_cos"
            ),
            ForecastFeatureSchema.names
        )
    }

    @Test
    fun featureBuilderUsesExactCurrentLagChangeAndDhakaTimeOrder() {
        val history = validHistory()
        val features = TimeSeriesFeatureBuilder().build(history, ZoneId.of("Asia/Dhaka"))

        assertArrayEquals(values(history.last()), features.copyOfRange(0, 6), 0.0001f)
        assertArrayEquals(values(history[25]), features.copyOfRange(6, 12), 0.0001f)
        assertArrayEquals(values(history[15]), features.copyOfRange(12, 18), 0.0001f)
        assertArrayEquals(values(history.first()), features.copyOfRange(18, 24), 0.0001f)
        assertArrayEquals(
            FloatArray(6) { index -> values(history.last())[index] - values(history[25])[index] },
            features.copyOfRange(24, 30),
            0.0001f
        )
        assertEquals(0.0f, features[30], 0.0001f)
        assertEquals(-1.0f, features[31], 0.0001f)
    }

    @Test
    fun unavailableInputFailsFiniteValueValidation() {
        val history = validHistory().toMutableList()
        history[25] = history[25].copy(tvoc = null)

        val error = assertThrows(IllegalArgumentException::class.java) {
            TimeSeriesFeatureBuilder().build(history)
        }

        assertTrue(error.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun featureBuilderRejectsLargeGapAndMissingHistory() {
        val history = validHistory().filterNot { it.timestamp in (historyStart() + 10 * 60_000L)..(historyStart() + 20 * 60_000L) }
        val error = assertThrows(IllegalArgumentException::class.java) {
            TimeSeriesFeatureBuilder().build(history)
        }
        assertTrue(error.message.orEmpty().contains("same-session"))
    }

    @Test
    fun featureBuilderRequiresFullThirtyMinuteWindowFromOneDevice() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeSeriesFeatureBuilder().build(validHistory().drop(3))
        }

        val mixedDevices = validHistory().toMutableList().also {
            it[0] = it[0].copy(deviceId = "another-device")
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            TimeSeriesFeatureBuilder().build(mixedDevices)
        }
        assertTrue(error.message.orEmpty().contains("one device"))
    }

    @Test
    fun kotlinFeaturesMatchPythonRealHistoryParityFixture() {
        val fixtureFile = modelAsset("android_parity_fixture.json")
        val fixture = Gson().fromJson(fixtureFile.readText(), ParityFixture::class.java)
        assertEquals(ForecastFeatureSchema.names, fixture.feature_order)
        val history = fixture.history.map { record ->
            SensorReadingEntity(
                deviceId = record.deviceId,
                timestamp = record.timestamp,
                temperature = record.temperature,
                humidity = record.humidity,
                tvoc = record.tvoc,
                eco2 = record.eco2,
                noiseLevel = record.noise_level
            )
        }
        assertArrayEquals(
            fixture.features_float32.toFloatArray(),
            TimeSeriesFeatureBuilder().build(history),
            0.001f
        )
    }

    @Test
    fun deployedSchemaMatchesKotlinModelContract() {
        val schemaFile = modelAsset("feature_schema.json")
        val schema = Gson().fromJson(schemaFile.readText(), DeployedSchema::class.java)

        assertEquals(ForecastModelConfig.MODEL_VERSION, schema.model_version)
        assertEquals("float32", schema.input_dtype)
        assertEquals(ForecastModelConfig.HORIZON_MINUTES, schema.one_hour_horizon_minutes)
        assertEquals(ForecastFeatureSchema.names, schema.feature_order)
        assertEquals(ForecastModelConfig.TIME_ZONE, schema.time_zone_for_hour_features)
        assertEquals(2, schema.lag_matching_tolerance_minutes)
        assertEquals(3, schema.max_allowed_gap_between_sensor_samples_minutes)
    }

    @Test
    fun outputDecoderMapsAllFourExperimentalTargets() {
        val result = ForecastOutputDecoder.decode(floatArrayOf(31.4f, 210f, 820f, 62f))

        assertEquals(31.4f, result.heatIndexCelsius)
        assertEquals(210f, result.tvocPpb)
        assertEquals(820f, result.eco2EquivalentPpm)
        assertEquals(62f, result.estimatedNoiseLevel)
        assertEquals(60, result.horizonMinutes)
        assertEquals(ForecastSource.EXPERIMENTAL_V1, result.source)
    }

    @Test
    fun outputDecoderRejectsWrongCountAndUnavailableValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ForecastOutputDecoder.decode(floatArrayOf(1f, 2f, 3f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForecastOutputDecoder.decode(floatArrayOf(1f, 2f, Float.NaN, 4f))
        }
    }

    @Test
    fun forecastAssessmentReusesRulesAndEco2AloneCannotCreateCritical() {
        val assessment = ForecastAssessmentAdapter.assess(
            ForecastResult(heatIndexCelsius = 20f, tvocPpb = 100f, eco2EquivalentPpm = 10_000f, estimatedNoiseLevel = 40f)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.eco2Condition)
        assertEquals(EnvironmentalCondition.POOR, assessment.airCondition)
        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertFalse(assessment.overallCondition == EnvironmentalCondition.CRITICAL)
    }

    @Test
    fun forecastAssessmentRetainsTiedPrimaryConcerns() {
        val assessment = ForecastAssessmentAdapter.assess(
            ForecastResult(heatIndexCelsius = 35f, tvocPpb = 800f, eco2EquivalentPpm = 800f, estimatedNoiseLevel = 40f)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.AIR), assessment.primaryConcerns)
    }

    @Test
    fun serviceTransitionsThroughLoadingReadyAndDerivedSuccess() = runBlocking {
        val expected = ForecastResult(31.4f, 210f, 820f, 62f)
        val states = EnvironmentalForecastService(StubInference(result = expected))
            .forecast(validHistory())
            .toList()

        assertEquals(ForecastPipelineState.Loading, states[0])
        assertEquals(ForecastPipelineState.Ready, states[1])
        assertEquals(expected, (states[2] as ForecastPipelineState.Success).result)
    }

    @Test
    fun modelInitializationFailureBecomesNonCrashingErrorState() = runBlocking {
        val states = EnvironmentalForecastService(
            StubInference(initializationError = ForecastModelException("The experimental models are unavailable."))
        ).forecast(validHistory()).toList()

        assertEquals(ForecastPipelineState.Loading, states[0])
        assertEquals(
            ForecastPipelineState.Error("The experimental models are unavailable."),
            states[1]
        )
    }

    @Test
    fun unavailableForecastPresentationKeepsMeasuredTrendsUsable() {
        val presentation = ForecastPresentationFactory.create(
            ForecastPipelineState.Unavailable("No measured reading is available.")
        )

        assertEquals("Forecast unavailable", presentation.title)
        assertTrue(presentation.detail.contains("Measured trends remain available"))
        assertFalse(presentation.showProgress)
    }

    private fun historyStart() = Instant.parse("2026-09-27T05:30:00Z").toEpochMilli()

    private fun validHistory(): List<SensorReadingEntity> = (0..30).map { minute ->
        SensorReadingEntity(
            deviceId = "EHM_24D304",
            timestamp = historyStart() + minute * 60_000L,
            temperature = 25f + minute / 10f,
            humidity = 60f + minute / 5f,
            tvoc = 100f + minute,
            eco2 = 400f + minute * 2f,
            noiseLevel = 55f + minute / 10f
        )
    }

    private fun values(reading: SensorReadingEntity) = floatArrayOf(
        reading.temperature!!,
        reading.humidity!!,
        reading.tvoc!!,
        reading.eco2!!,
        reading.noiseLevel!!,
        HeatIndex.calculateCelsius(reading.temperature, reading.humidity)
    )

    private fun modelAsset(name: String): File = listOf(
        File("src/main/assets/models/$name"),
        File("app/src/main/assets/models/$name")
    ).first(File::exists)

    private data class DeployedSchema(
        val model_version: String,
        val input_dtype: String,
        val one_hour_horizon_minutes: Int,
        val feature_order: List<String>,
        val time_zone_for_hour_features: String,
        val lag_matching_tolerance_minutes: Int,
        val max_allowed_gap_between_sensor_samples_minutes: Int
    )

    private data class ParityFixture(
        val feature_order: List<String>,
        val features_float32: List<Float>,
        val history: List<ParityReading>
    )

    private data class ParityReading(
        val timestamp: Long,
        val deviceId: String,
        val temperature: Float,
        val humidity: Float,
        val tvoc: Float,
        val eco2: Float,
        val noise_level: Float
    )

    private class StubInference(
        private val result: ForecastResult = ForecastResult(31.4f, 210f, 820f, 62f),
        private val initializationError: Exception? = null
    ) : ForecastInference {
        override suspend fun initialize() {
            initializationError?.let { throw it }
        }

        override suspend fun predict(features: FloatArray): ForecastResult {
            ForecastFeatureSchema.validate(features)
            return result
        }
    }
}
