package com.enviroguard.app.data.repository

import com.enviroguard.app.data.local.dao.SensorReadingDao
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.model.SensorReading

internal fun interface HistoricalReadingStore {
    suspend fun insert(readings: List<SensorReadingEntity>): Int
}

internal class RoomHistoricalReadingStore(
    private val dao: SensorReadingDao
) : HistoricalReadingStore {
    override suspend fun insert(readings: List<SensorReadingEntity>): Int =
        if (readings.isEmpty()) 0 else dao.insertHistorical(readings).count { it != -1L }
}

/** Converts canonical Firebase history into raw, duplicate-safe Room records. */
internal class HistoryRecordSynchronizer(
    private val store: HistoricalReadingStore
) {
    suspend fun synchronize(deviceId: String, readings: List<SensorReading>): Int {
        if (deviceId.isBlank()) return 0
        val records = readings
            .asSequence()
            .filter { it.timestamp > 0L }
            .distinctBy { it.timestamp }
            .map { SensorRepository.createRawEntity(it, deviceId) }
            .toList()
        return store.insert(records)
    }
}
