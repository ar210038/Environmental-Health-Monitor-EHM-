package com.enviroguard.app

import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.DailyExposureCalculator
import com.enviroguard.app.utils.DateRangeUtils
import com.enviroguard.app.utils.ExposureAnalytics
import com.enviroguard.app.utils.DeviceStatusEvaluator
import com.enviroguard.app.alerts.AlertCondition
import com.enviroguard.app.alerts.AlertDecisionEngine
import com.enviroguard.app.alerts.AlertThresholds
import com.enviroguard.app.dataset.DatasetCsvExporter
import com.enviroguard.app.utils.MlFeatureBuilder
import com.enviroguard.app.model.MlFeatureConfig
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import com.enviroguard.app.utils.EnvironmentalDiagnosticAnalyzer
import com.enviroguard.app.utils.HeatIndex
import com.enviroguard.app.utils.HeatIndexConfig
import com.enviroguard.app.utils.NoiseSmoother
import com.enviroguard.app.utils.NoiseThresholdConfig
import com.enviroguard.app.utils.TvocThresholdConfig
import java.util.UUID
import com.enviroguard.app.utils.RiskEngine
import com.enviroguard.app.utils.TemperatureUtils
import org.junit.Test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun assessment_containsPersistableRiskFields() {
        val reading = SensorReading(
            temperature = 35f,
            humidity = 80f,
            tvoc = 700f,
            eco2 = 2200f,
            noiseDb = 80f,
            timestamp = 123456789L
        )

        val assessment = RiskEngine.assess(reading)
        val enriched = reading.copy(
            ers = assessment.ers,
            ersClass = assessment.ersClass,
            mainContributor = assessment.mainContributor
        )

        assertTrue(enriched.ers > 0)
        assertNotEquals("", enriched.ersClass)
        assertNotEquals("", enriched.mainContributor)
        assertEquals(reading.timestamp, enriched.timestamp)
    }

    @Test
    fun allSafeReading_hasNoMainContributorAndNoMlConfidence() {
        val assessment = RiskEngine.assess(
            SensorReading(
                temperature = 24f,
                humidity = 45f,
                tvoc = 100f,
                eco2 = 700f,
                noiseDb = 40f
            )
        )

        assertEquals(0, assessment.ers)
        assertEquals(Constants.CLASS_GOOD, assessment.ersClass)
        assertEquals("None", assessment.mainContributor)
        assertEquals(RiskEngine.AssessmentSource.RULE_BASED, assessment.source)
        assertNull(assessment.confidence)
    }

    @Test
    fun ersClassBoundaries_areStable() {
        assertEquals(Constants.CLASS_GOOD, RiskEngine.classifyErs(25))
        assertEquals(Constants.CLASS_CAUTION, RiskEngine.classifyErs(26))
        assertEquals(Constants.CLASS_CAUTION, RiskEngine.classifyErs(50))
        assertEquals(Constants.CLASS_HIGH_RISK, RiskEngine.classifyErs(51))
        assertEquals(Constants.CLASS_HIGH_RISK, RiskEngine.classifyErs(75))
        assertEquals(Constants.CLASS_CRITICAL, RiskEngine.classifyErs(76))
    }

    @Test
    fun demoReadings_areExcludedFromNormalHistory() {
        assertFalse(SensorRepository.shouldPersistReading(isDemo = true))
        assertTrue(SensorRepository.shouldPersistReading(isDemo = false))
    }

    @Test
    fun celsiusToFahrenheit_convertsDisplayValueOnly() {
        assertEquals(32f, TemperatureUtils.celsiusToFahrenheit(0f), 0.001f)
        assertEquals(77f, TemperatureUtils.celsiusToFahrenheit(25f), 0.001f)
    }

    @Test
    fun calendarRanges_startAtMidnightAndContainExactDayCounts() {
        val zone = ZoneId.of("Asia/Dhaka")
        val today = LocalDate.of(2026, 8, 17)

        listOf(1L, 7L, 30L).forEach { dayCount ->
            val range = DateRangeUtils.calendarDays(dayCount, today, zone)
            val start = Instant.ofEpochMilli(range.startInclusive).atZone(zone)
            val endDate = Instant.ofEpochMilli(range.endExclusive).atZone(zone).toLocalDate()

            assertEquals(0, start.hour)
            assertEquals(0, start.minute)
            assertEquals(0, start.second)
            assertEquals(dayCount, ChronoUnit.DAYS.between(start.toLocalDate(), endDate))
        }
    }

    @Test
    fun highRiskDuration_countsNormalGapsButNotMissingData() {
        val normal = listOf(
            readingAt(0L, 70, Constants.CLASS_HIGH_RISK),
            readingAt(5_000L, 72, Constants.CLASS_HIGH_RISK),
            readingAt(10_000L, 80, Constants.CLASS_CRITICAL)
        )
        assertEquals(10_000L, ExposureAnalytics.highRiskDurationMs(normal))

        val withLargeGap = normal + readingAt(60_000L, 82, Constants.CLASS_CRITICAL)
        assertEquals(10_000L, ExposureAnalytics.highRiskDurationMs(withLargeGap))
    }

    @Test
    fun commonContributor_ignoresBlankAndNone() {
        val readings = listOf(
            readingAt(0L, contributor = ""),
            readingAt(1L, contributor = "None"),
            readingAt(2L, contributor = "TVOC"),
            readingAt(3L, contributor = "TVOC"),
            readingAt(4L, contributor = "Noise")
        )
        assertEquals("TVOC", ExposureAnalytics.commonContributor(readings))
        assertNull(ExposureAnalytics.commonContributor(readings.take(2)))
    }

    @Test
    fun dailyExposureCalculation_isDeterministic() {
        val zone = ZoneId.of("UTC")
        val date = LocalDate.of(2026, 8, 17)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val readings = listOf(
            readingAt(start, 20, Constants.CLASS_GOOD, "None"),
            readingAt(start + 5_000L, 60, Constants.CLASS_HIGH_RISK, "TVOC"),
            readingAt(start + 10_000L, 80, Constants.CLASS_CRITICAL, "TVOC")
        )

        val summary = DailyExposureCalculator.calculate(date, readings, zone)!!
        assertEquals("2026-08-17", summary.date)
        assertEquals(160f / 3f, summary.avgErs, 0.001f)
        assertEquals(80, summary.peakErs)
        assertEquals("00:00", summary.peakTime)
        assertEquals(0, summary.totalHighRiskMinutes)
        assertTrue(summary.recommendation.contains("TVOC"))
    }

    private fun readingAt(
        timestamp: Long,
        ers: Int = 50,
        ersClass: String = Constants.CLASS_CAUTION,
        contributor: String = "None"
    ) = SensorReadingEntity(
        deviceId = "test-device",
        timestamp = timestamp,
        ers = ers,
        ersClass = ersClass,
        mainContributor = contributor
    )

    @Test
    fun alertDecision_crossingCooldownResetAndLaterCrossing() {
        val engine = AlertDecisionEngine(cooldownMs = 20 * 60_000L)
        val thresholds = AlertThresholds.validated(50, 220, 1_000, 55)
        val alertReading = SensorReading(tvoc = 300f)
        val alertAssessment = RiskEngine.assess(alertReading)

        val first = engine.evaluate(
            alertReading, alertAssessment, thresholds,
            notificationsEnabled = true, isDemo = false, now = 0L
        )
        assertEquals(AlertCondition.TVOC, first?.condition)
        assertNull(engine.evaluate(
            alertReading, alertAssessment, thresholds,
            notificationsEnabled = true, isDemo = false, now = 5_000L
        ))

        val normal = SensorReading(tvoc = 100f)
        assertNull(engine.evaluate(
            normal, RiskEngine.assess(normal), thresholds,
            notificationsEnabled = true, isDemo = false, now = 10_000L
        ))
        assertEquals(AlertCondition.TVOC, engine.evaluate(
            alertReading, alertAssessment, thresholds,
            notificationsEnabled = true, isDemo = false, now = 15_000L
        )?.condition)
    }

    @Test
    fun alertDecision_suppressesBelowThresholdToggleOffAndDemo() {
        val thresholds = AlertThresholds.validated(50, 220, 1_000, 55)
        val normal = SensorReading(tvoc = 100f, eco2 = 800f, noiseDb = 40f)
        val elevated = SensorReading(tvoc = 300f)

        assertNull(AlertDecisionEngine().evaluate(
            normal, RiskEngine.assess(normal), thresholds, true, false, 0L
        ))
        assertNull(AlertDecisionEngine().evaluate(
            elevated, RiskEngine.assess(elevated), thresholds, false, false, 0L
        ))
        assertNull(AlertDecisionEngine().evaluate(
            elevated, RiskEngine.assess(elevated), thresholds, true, true, 0L
        ))
    }

    @Test
    fun alertThresholds_invalidValuesUseSafeFallbacks() {
        assertEquals(
            AlertThresholds(220, 1_000, 55),
            AlertThresholds.validated(0, -1, 50_000, 101)
        )
    }

    @Test
    fun deviceStatus_usesTwentyFiveSecondTimeout() {
        val lastSeen = 1_000_000L
        assertTrue(DeviceStatusEvaluator.isRecentlySeen(lastSeen, lastSeen + 25_000L))
        assertFalse(DeviceStatusEvaluator.isRecentlySeen(lastSeen, lastSeen + 25_001L))
        assertEquals(5_000L, DeviceStatusEvaluator.delayUntilOffline(lastSeen, lastSeen + 20_000L))
    }

    @Test
    fun environmentLabels_validateWithoutGuessing() {
        assertEquals("Bedroom", DeviceManager.normalizeEnvironmentLabel("Bedroom"))
        assertEquals("Unspecified", DeviceManager.normalizeEnvironmentLabel("Unknown place"))
    }

    @Test
    fun csvHeaderOrderAndEscaping_areStable() {
        val row = readingAt(123L).copy(
            environmentLabel = "Office, \"North\"",
            collectionSessionId = "session-123",
            deviceId = "device,1"
        )
        val csv = DatasetCsvExporter.generate(listOf(row), ZoneId.of("UTC"))
        assertEquals(DatasetCsvExporter.columns.joinToString(","), csv.lineSequence().first())
        assertTrue(csv.contains("\"device,1\""))
        assertTrue(csv.contains("session-123"))
        assertTrue(csv.contains("\"Office, \"\"North\"\"\""))
    }

    @Test
    fun mlFeatures_areConfigurableWithConservativeRawDefault() {
        val timestamp = LocalDate.of(2026, 8, 17)
            .atTime(7, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val current = readingAt(timestamp, 40, contributor = "TVOC").copy(
            temperature = 25f, humidity = 50f, tvoc = 200f, eco2 = 800f, noiseDb = 45f
        )
        val features = MlFeatureBuilder.build(current, listOf(current), ZoneId.of("UTC"))
        assertEquals(
            listOf(25f, 50f, 200f, 45f, 800f),
            features.valuesFor(MlFeatureConfig.CONSERVATIVE_RAW_FEATURES)
        )
        assertEquals(2, features.valuesFor(MlFeatureConfig.CANDIDATE_ENGINEERED_FEATURES).size)
        assertEquals(1f, features.valuesFor(MlFeatureConfig.CANDIDATE_ENGINEERED_FEATURES)[1])
        assertEquals(-1, MlFeatureBuilder.dominantFactor("None"))
        assertEquals(-1, MlFeatureBuilder.dominantFactor(""))
        assertTrue(MlFeatureConfig.FORBIDDEN_ERS_DERIVED_FEATURES.contains("ers"))
        assertEquals("condition_class", MlFeatureConfig.TARGET_COLUMN)
    }

    @Test
    fun rollingAverage_usesFiveMinutesOfPastAndCurrentOnly() {
        val currentTime = 600_000L
        val oldOutsideWindow = readingAt(299_999L, 100)
        val pastBoundary = readingAt(300_000L, 20)
        val current = readingAt(currentTime, 60)
        val future = readingAt(605_000L, 100)
        val features = MlFeatureBuilder.build(
            current,
            listOf(oldOutsideWindow, pastBoundary, current, future),
            ZoneId.of("UTC")
        )
        assertEquals(40f, features.rollingAvgErs, 0.001f)
    }

    @Test
    fun timeOfDayMappings_matchDocumentedRanges() {
        val zone = ZoneId.of("UTC")
        fun atHour(hour: Int) = LocalDate.of(2026, 8, 17)
            .atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(0, MlFeatureBuilder.timeOfDay(atHour(0), zone))
        assertEquals(1, MlFeatureBuilder.timeOfDay(atHour(6), zone))
        assertEquals(2, MlFeatureBuilder.timeOfDay(atHour(12), zone))
        assertEquals(3, MlFeatureBuilder.timeOfDay(atHour(18), zone))
    }

    @Test
    fun collectionSessionIds_areValidUuids() {
        val sessionId = DeviceManager.generateCollectionSessionId()
        assertEquals(sessionId, UUID.fromString(sessionId).toString())
    }

    @Test
    fun realReadingEntity_preservesOptionalSessionId() {
        val reading = SensorReading(
            temperature = 25f,
            ers = 30,
            ersClass = Constants.CLASS_CAUTION,
            mainContributor = "TVOC",
            timestamp = 1234L
        )
        val withSession = SensorRepository.createDatasetEntity(
            reading, "device-1", "Unspecified", "session-1"
        )
        assertEquals("session-1", withSession.collectionSessionId)
        assertEquals("Unspecified", withSession.environmentLabel)

        val normalMonitoring = SensorRepository.createDatasetEntity(reading, "device-1")
        assertNull(normalMonitoring.collectionSessionId)
        assertEquals("Unspecified", normalMonitoring.environmentLabel)
    }

    @Test
    fun heatIndex_usesNwsRegressionOnlyWithinApplicabilityRange() {
        assertNull(HeatIndex.calculateCelsius(20f, 80f))
        assertNull(HeatIndex.calculateCelsius(32f, 30f))
        val heatIndex = HeatIndex.calculateCelsius(32.2222f, 70f)!! // 90 F / 70% RH
        assertTrue(heatIndex in 40f..44f)
    }

    @Test
    fun factorClasses_coverBoundariesAndOverallUsesMaximumSeverity() {
        assertEquals(EnvironmentalCondition.GOOD, EnvironmentalConditionEngine.classifyTvoc(TvocThresholdConfig.CAUTION_MAX_PPB))
        assertEquals(EnvironmentalCondition.CAUTION, EnvironmentalConditionEngine.classifyTvoc(TvocThresholdConfig.CAUTION_MAX_PPB + .1f))
        assertEquals(EnvironmentalCondition.HIGH_RISK, EnvironmentalConditionEngine.classifyTvoc(TvocThresholdConfig.HIGH_RISK_MAX_PPB + .1f))
        assertEquals(EnvironmentalCondition.CRITICAL, EnvironmentalConditionEngine.classifyTvoc(TvocThresholdConfig.CRITICAL_MAX_PPB + .1f))
        val result = EnvironmentalConditionEngine.assess(SensorReading(temperature = 20f, humidity = 50f, tvoc = 100f, noiseDb = 90f))
        assertEquals(EnvironmentalCondition.CRITICAL, result.overallCondition)
        assertEquals(listOf(com.enviroguard.app.model.EnvironmentalFactor.NOISE), result.activeFactors)
    }

    @Test
    fun noiseSmoothing_handlesFluctuationsAndMissingValuesWithoutClaimingLeq() {
        assertEquals(60f, NoiseSmoother.estimatedSmoothedDb(listOf(50f, 70f, Float.NaN))!!, .001f)
        assertNull(NoiseSmoother.estimatedSmoothedDb(listOf(Float.NaN)))
        val many = (1..(NoiseThresholdConfig.SMOOTHING_WINDOW_SAMPLES + 2)).map { it.toFloat() }
        assertEquals(8.5f, NoiseSmoother.estimatedSmoothedDb(many)!!, .001f)
    }

    @Test
    fun diagnostic_reportsDominantTiesAndNoAbnormalFactors() {
        val noiseDominant = listOf(
            readingAt(1L).copy(noiseDb = 90f), readingAt(2L).copy(noiseDb = 90f), readingAt(3L).copy(tvoc = 300f, noiseDb = 40f)
        )
        assertTrue(EnvironmentalDiagnosticAnalyzer.analyze(noiseDominant).dominantFactor.contains("Environmental Noise"))
        val none = listOf(readingAt(1L).copy(temperature = 20f, humidity = 50f, tvoc = 100f, noiseDb = 40f))
        assertTrue(EnvironmentalDiagnosticAnalyzer.analyze(none).dominantFactor.contains("No abnormal"))
        assertTrue(EnvironmentalDiagnosticAnalyzer.analyze(emptyList()).dominantFactor.contains("No readings"))
    }
}
