package com.enviroguard.app.data.local.dao

import androidx.room.*
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: SensorReadingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistorical(readings: List<SensorReadingEntity>): List<Long>

    @Query("SELECT * FROM sensor_readings ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReading(): Flow<SensorReadingEntity?>

    @Query("SELECT * FROM sensor_readings WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getReadingsSince(startTime: Long): Flow<List<SensorReadingEntity>>

    @Query("SELECT * FROM sensor_readings WHERE timestamp >= :start AND timestamp < :endExclusive ORDER BY timestamp ASC")
    fun getReadingsBetween(start: Long, endExclusive: Long): Flow<List<SensorReadingEntity>>

    @Query("SELECT * FROM sensor_readings WHERE deviceId = :deviceId AND timestamp >= :start AND timestamp < :endExclusive ORDER BY timestamp ASC")
    fun getReadingsBetweenForDevice(
        deviceId: String,
        start: Long,
        endExclusive: Long
    ): Flow<List<SensorReadingEntity>>

    @Query("DELETE FROM sensor_readings WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long)

    @Query("SELECT COUNT(*) FROM sensor_readings")
    fun observeDatasetCount(): Flow<Int>

    @Query("SELECT * FROM sensor_readings ORDER BY timestamp ASC")
    suspend fun getAllForDatasetExport(): List<SensorReadingEntity>
}
