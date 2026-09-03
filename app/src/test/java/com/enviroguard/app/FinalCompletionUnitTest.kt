package com.enviroguard.app

import com.enviroguard.app.alerts.AlertDecisionEngine
import com.enviroguard.app.alerts.EnvironmentalAlertCoordinator
import com.enviroguard.app.data.local.dao.SensorReadingDao
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.data.repository.HistoryRecordSynchronizer
import com.enviroguard.app.data.repository.RoomHistoricalReadingStore
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalCompletionUnitTest {
    @Test
    fun highestSeverityTiesRetainEveryPrimaryConcern() {
        val assessment = EnvironmentalConditionEngine.assess(
            SensorReading(temperature = 30f, humidity = 70f, tvoc = 300f, eco2 = 500f, noiseLevel = 85f)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.NOISE), assessment.primaryConcerns)
        assertFalse(assessment.primaryConcerns.contains(EnvironmentalDimension.AIR))
    }

    @Test
    fun missingMeasurementsRemainUnavailableAndNeverCreateFakeGood() {
        val assessment = EnvironmentalConditionEngine.assess(
            SensorReading(
                temperature = Float.NaN,
                humidity = Float.NaN,
                tvoc = Float.NaN,
                eco2 = Float.NaN,
                noiseLevel = Float.NaN
            )
        )

        assertNull(assessment.heatIndexCelsius?.takeIf { it.isFinite() })
        assertNull(assessment.thermalCondition)
        assertNull(assessment.tvocCondition)
        assertNull(assessment.eco2Condition)
        assertNull(assessment.airCondition)
        assertNull(assessment.noiseCondition)
        assertNull(assessment.overallCondition)
        assertTrue(assessment.primaryConcerns.isEmpty())
    }

    @Test
    fun airUsesAvailableMeasurementAndChoosesWorseSeverity() {
        val eco2Only = EnvironmentalConditionEngine.assess(
            SensorReading(20f, 40f, Float.NaN, 1_500f, Float.NaN)
        )
        val bothAvailable = EnvironmentalConditionEngine.assess(
            SensorReading(20f, 40f, 300f, 2_500f, 40f)
        )

        assertEquals(EnvironmentalCondition.MODERATE, eco2Only.airCondition)
        assertEquals(EnvironmentalCondition.POOR, bothAvailable.airCondition)
    }

    @Test
    fun eco2AloneCannotCreateCritical() {
        val assessment = EnvironmentalConditionEngine.assess(
            SensorReading(20f, 40f, Float.NaN, 10_000f, Float.NaN)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.eco2Condition)
        assertEquals(EnvironmentalCondition.POOR, assessment.airCondition)
        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
    }

    @Test
    fun historicalSynchronizationIsIdempotentByDeviceAndTimestamp() = runBlocking {
        val dao = RecordingSensorReadingDao()
        val synchronizer = HistoryRecordSynchronizer(RoomHistoricalReadingStore(dao))
        val reading = SensorReading(25f, 55f, 120f, 650f, 42f, timestamp = 1_700_000_000_000L)

        assertEquals(1, synchronizer.synchronize("EHM_A1B2C3", listOf(reading, reading)))
        assertEquals(0, synchronizer.synchronize("EHM_A1B2C3", listOf(reading)))
        assertEquals(1, dao.records.size)
    }

    @Test
    fun roomStoreReceivesExactRawFirebaseHistoryFields() = runBlocking {
        val dao = RecordingSensorReadingDao()
        val synchronizer = HistoryRecordSynchronizer(RoomHistoricalReadingStore(dao))
        val reading = SensorReading(24.5f, 51f, 321f, 876f, 63f, timestamp = 1_700_000_060_000L)

        synchronizer.synchronize("EHM_FEDCBA", listOf(reading))

        assertEquals(
            SensorReadingEntity(
                id = 0,
                deviceId = "EHM_FEDCBA",
                timestamp = reading.timestamp,
                temperature = reading.temperature,
                humidity = reading.humidity,
                tvoc = reading.tvoc,
                eco2 = reading.eco2,
                noiseLevel = reading.noiseLevel
            ),
            dao.records.single()
        )
    }

    @Test
    fun moderatePoorAndCriticalRequireConfiguredPersistence() {
        assertPersistence(moderateReading(), 5 * 60_000L)
        assertPersistence(poorReading(), 3 * 60_000L)
        assertPersistence(criticalReading(), 10_000L)
    }

    @Test
    fun alertCooldownPreventsDuplicateSpam() {
        val engine = AlertDecisionEngine()
        val assessment = EnvironmentalConditionEngine.assess(poorReading())
        val firstAlertAt = 3 * 60_000L

        assertNull(engine.evaluate(assessment, 0L, enabled = true, isDemo = false))
        assertNotNull(engine.evaluate(assessment, firstAlertAt, enabled = true, isDemo = false))
        assertNull(engine.evaluate(assessment, firstAlertAt + 1L, enabled = true, isDemo = false))
        assertNotNull(engine.evaluate(assessment, firstAlertAt + 20 * 60_000L, enabled = true, isDemo = false))
    }

    @Test
    fun demoModeCannotPostEnvironmentalAlerts() {
        val posted = mutableListOf<String>()
        val coordinator = EnvironmentalAlertCoordinator(AlertDecisionEngine()) { posted += it.title }
        val assessment = EnvironmentalConditionEngine.assess(criticalReading())

        coordinator.evaluate(assessment, criticalReading(), 0L, enabled = true, isDemo = true)
        coordinator.evaluate(assessment, criticalReading(), 60_000L, enabled = true, isDemo = true)

        assertTrue(posted.isEmpty())
    }

    private fun assertPersistence(reading: SensorReading, required: Long) {
        val engine = AlertDecisionEngine()
        val assessment = EnvironmentalConditionEngine.assess(reading)
        assertNull(engine.evaluate(assessment, 1_000L, enabled = true, isDemo = false, reading = reading))
        assertNull(engine.evaluate(assessment, 1_000L + required - 1L, enabled = true, isDemo = false, reading = reading))
        val decision = engine.evaluate(assessment, 1_000L + required, enabled = true, isDemo = false, reading = reading)
        assertNotNull(decision)
        assertTrue(decision!!.body.contains(assessment.overallCondition!!.displayName))
    }

    private fun moderateReading() = SensorReading(20f, 40f, 300f, 500f, 40f)
    private fun poorReading() = SensorReading(20f, 40f, 800f, 500f, 40f)
    private fun criticalReading() = SensorReading(20f, 40f, 3_000f, 500f, 40f)

    private class RecordingSensorReadingDao : SensorReadingDao {
        val records = mutableListOf<SensorReadingEntity>()
        private val uniqueKeys = mutableSetOf<Pair<String, Long>>()

        override suspend fun insert(reading: SensorReadingEntity) {
            records += reading
        }

        override suspend fun insertHistorical(readings: List<SensorReadingEntity>): List<Long> = readings.map { reading ->
            if (uniqueKeys.add(reading.deviceId to reading.timestamp)) {
                records += reading
                records.size.toLong()
            } else {
                -1L
            }
        }

        override fun getLatestReading(): Flow<SensorReadingEntity?> = emptyFlow()
        override fun getReadingsSince(startTime: Long): Flow<List<SensorReadingEntity>> = emptyFlow()
        override fun getReadingsBetween(start: Long, endExclusive: Long): Flow<List<SensorReadingEntity>> = emptyFlow()
        override fun getReadingsBetweenForDevice(deviceId: String, start: Long, endExclusive: Long): Flow<List<SensorReadingEntity>> = emptyFlow()
        override suspend fun deleteOlderThan(beforeTime: Long) = Unit
        override fun observeDatasetCount(): Flow<Int> = emptyFlow()
        override suspend fun getAllForDatasetExport(): List<SensorReadingEntity> = records.toList()
    }
}
