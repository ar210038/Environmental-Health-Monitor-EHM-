package com.enviroguard.app

import com.enviroguard.app.forecast.EnvironmentalForecastService
import com.enviroguard.app.forecast.ForecastAssessmentAdapter
import com.enviroguard.app.forecast.ForecastFeatureSchema
import com.enviroguard.app.forecast.ForecastInference
import com.enviroguard.app.forecast.ForecastModelException
import com.enviroguard.app.forecast.ForecastOutputDecoder
import com.enviroguard.app.forecast.ForecastPipelineState
import com.enviroguard.app.forecast.ForecastPresentationFactory
import com.enviroguard.app.forecast.ForecastResult
import com.enviroguard.app.forecast.ForecastSource
import com.enviroguard.app.forecast.TimeSeriesFeatureBuilder
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.HeatIndex
import java.time.Instant
import java.time.ZoneOffset
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
        assertEquals(8, ForecastFeatureSchema.featureCount)
        assertEquals(
            listOf(
                "temperature_celsius",
                "humidity_percent",
                "heat_index_celsius",
                "tvoc_ppb",
                "eco2_equivalent_ppm",
                "estimated_noise_level_db",
                "hour_of_day",
                "iso_day_of_week"
            ),
            ForecastFeatureSchema.names
        )
    }

    @Test
    fun featureBuilderUsesCurrentValuesCalculatedHeatIndexAndUtcTime() {
        val timestamp = Instant.parse("2024-01-03T14:00:00Z").toEpochMilli()
        val reading = SensorReading(30f, 70f, 300f, 900f, 82f, timestamp)

        val features = TimeSeriesFeatureBuilder().build(reading, ZoneOffset.UTC)

        assertArrayEquals(
            floatArrayOf(
                30f,
                70f,
                HeatIndex.calculateCelsius(30f, 70f),
                300f,
                900f,
                82f,
                14f,
                3f
            ),
            features,
            0.0001f
        )
    }

    @Test
    fun unavailableInputFailsFiniteValueValidation() {
        val reading = SensorReading(30f, 70f, Float.NaN, 900f, 82f, 0L)

        val error = assertThrows(IllegalArgumentException::class.java) {
            TimeSeriesFeatureBuilder().build(reading, ZoneOffset.UTC)
        }

        assertTrue(error.message.orEmpty().contains("tvoc_ppb"))
    }

    @Test
    fun outputDecoderMapsAllFourTargetsAndSmokeTestSource() {
        val result = ForecastOutputDecoder.decode(floatArrayOf(31.4f, 210f, 820f, 62f))

        assertEquals(31.4f, result.heatIndexCelsius)
        assertEquals(210f, result.tvocPpb)
        assertEquals(820f, result.eco2EquivalentPpm)
        assertEquals(62f, result.estimatedNoiseLevelDb)
        assertEquals(60, result.horizonMinutes)
        assertEquals(ForecastSource.SMOKE_TEST, result.source)
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
            ForecastResult(heatIndexCelsius = 20f, tvocPpb = 100f, eco2EquivalentPpm = 10_000f, estimatedNoiseLevelDb = 40f)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.eco2Condition)
        assertEquals(EnvironmentalCondition.POOR, assessment.airCondition)
        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertFalse(assessment.overallCondition == EnvironmentalCondition.CRITICAL)
    }

    @Test
    fun forecastAssessmentRetainsTiedPrimaryConcerns() {
        val assessment = ForecastAssessmentAdapter.assess(
            ForecastResult(heatIndexCelsius = 35f, tvocPpb = 800f, eco2EquivalentPpm = 800f, estimatedNoiseLevelDb = 40f)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.AIR), assessment.primaryConcerns)
    }

    @Test
    fun serviceTransitionsThroughLoadingReadyAndDerivedSuccess() = runBlocking {
        val expected = ForecastResult(31.4f, 210f, 820f, 62f)
        val states = EnvironmentalForecastService(StubInference(result = expected))
            .forecast(validReading())
            .toList()

        assertEquals(ForecastPipelineState.Loading, states[0])
        assertEquals(ForecastPipelineState.Ready, states[1])
        assertEquals(expected, (states[2] as ForecastPipelineState.Success).result)
    }

    @Test
    fun modelInitializationFailureBecomesNonCrashingErrorState() = runBlocking {
        val states = EnvironmentalForecastService(
            StubInference(initializationError = ForecastModelException("The test model asset is unavailable."))
        ).forecast(validReading()).toList()

        assertEquals(ForecastPipelineState.Loading, states[0])
        assertEquals(
            ForecastPipelineState.Error("The test model asset is unavailable."),
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

    private fun validReading() = SensorReading(30f, 70f, 300f, 900f, 82f, 1_704_292_400_000L)

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
