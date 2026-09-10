package com.enviroguard.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.enviroguard.app.data.local.EnviroGuardDatabase
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.data.repository.HistoryRecordSynchronizer
import com.enviroguard.app.data.repository.RoomHistoricalReadingStore
import com.enviroguard.app.model.SensorReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real generated Room DAO and native SQLite, not a fake historical store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPersistenceRegressionTest {
    private val complete = SensorReading(29f, 65f, 250f, 800f, 60f, 1000L)

    @Test fun missingDhtSurvivesHistorySynchronization() = verifyPartial(
        complete.copy(temperature = Float.NaN, humidity = Float.NaN))

    @Test fun missingSgpSurvivesHistorySynchronization() = verifyPartial(
        complete.copy(tvoc = Float.NaN, eco2 = Float.NaN))

    @Test fun missingNoiseSurvivesHistorySynchronization() = verifyPartial(
        complete.copy(noiseLevel = Float.NaN))

    @Test fun everyMeasurementCanIndependentlyBeNull() {
        listOf(complete.copy(temperature = Float.NaN), complete.copy(humidity = Float.NaN),
            complete.copy(tvoc = Float.NaN), complete.copy(eco2 = Float.NaN),
            complete.copy(noiseLevel = Float.NaN)).forEach(::verifyPartial)
    }

    private fun verifyPartial(reading: SensorReading) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, EnviroGuardDatabase::class.java).build()
        try {
            val dao = db.sensorReadingDao()
            val sync = HistoryRecordSynchronizer(RoomHistoricalReadingStore(dao))
            assertEquals(1, sync.synchronize("EHM_A7F2C1", listOf(reading)))
            assertEquals(0, sync.synchronize("EHM_A7F2C1", listOf(reading)))
            val restored = dao.getAllForDatasetExport().single()
            assertEquals(SensorReadingEntity.fromReading(reading, "EHM_A7F2C1"), restored.copy(id = 0))
            assertEquals(reading, restored.toReading())
            db.openHelper.readableDatabase.query("SELECT temperature, humidity, tvoc, eco2, noiseLevel FROM sensor_readings").use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(reading.temperature, reading.humidity, reading.tvoc, reading.eco2, reading.noiseLevel)
                    .forEachIndexed { index, value -> assertEquals(!value.isFinite(), cursor.isNull(index)) }
            }
        } finally {
            db.close()
        }
    }

    @Test fun migrationPreservesRowsIdsRequiredColumnsAndUniqueIndex() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "pre-hardware-v7-migration-test"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE sensor_readings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, deviceId TEXT NOT NULL, timestamp INTEGER NOT NULL, temperature REAL NOT NULL, humidity REAL NOT NULL, eco2 REAL NOT NULL, tvoc REAL NOT NULL, noiseLevel REAL NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_sensor_readings_deviceId_timestamp ON sensor_readings(deviceId, timestamp)")
            old.execSQL("INSERT INTO sensor_readings VALUES (42, 'EHM_A7F2C1', 1000, 29, 65, 800, 250, 60)")
            old.execSQL("INSERT INTO sensor_readings VALUES (43, 'EHM_B7F2C1', 1000, 30, 66, 900, 300, 61)")
            old.version = 7
        }
        val db = Room.databaseBuilder(context, EnviroGuardDatabase::class.java, name)
            .addMigrations(EnviroGuardDatabase.MIGRATION_7_8).build()
        try {
            val dao = db.sensorReadingDao()
            val oldRows = dao.getAllForDatasetExport()
            assertEquals(2, oldRows.size)
            assertEquals(SensorReadingEntity.fromReading(complete, "EHM_A7F2C1").copy(id = 42), oldRows.first { it.id == 42L })
            assertEquals(SensorReadingEntity(deviceId = "EHM_B7F2C1", id = 43, timestamp = 1000,
                temperature = 30f, humidity = 66f, eco2 = 900f, tvoc = 300f, noiseLevel = 61f), oldRows.first { it.id == 43L })
            assertEquals(listOf(-1L), dao.insertHistorical(listOf(oldRows.first().copy(id = 0))))
            val partial = SensorReadingEntity(deviceId = "EHM_A7F2C1", timestamp = 2000,
                tvoc = 250f, eco2 = 800f, noiseLevel = 60f)
            assertTrue(dao.insertHistorical(listOf(partial)).single() > 43)
            assertEquals(partial, dao.getAllForDatasetExport().last().copy(id = 0))
            db.openHelper.readableDatabase.query("PRAGMA table_info(sensor_readings)").use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val required = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                    assertEquals(if (column in listOf("id", "deviceId", "timestamp")) 1 else 0, required)
                }
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
