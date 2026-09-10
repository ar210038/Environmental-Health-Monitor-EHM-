package com.enviroguard.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.enviroguard.app.model.SensorReading

@Entity(tableName = "sensor_readings", indices = [Index(value = ["deviceId", "timestamp"], unique = true)])
data class SensorReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val temperature: Float? = null,
    val humidity: Float? = null,
    val eco2: Float? = null,
    val tvoc: Float? = null,
    val noiseLevel: Float? = null
) {
    fun toReading() = SensorReading(
        temperature ?: Float.NaN, humidity ?: Float.NaN, tvoc ?: Float.NaN,
        eco2 ?: Float.NaN, noiseLevel ?: Float.NaN, timestamp
    )

    companion object {
        fun fromReading(reading: SensorReading, deviceId: String) = SensorReadingEntity(
            deviceId = deviceId,
            timestamp = reading.timestamp,
            temperature = reading.temperature.takeIf { it.isFinite() },
            humidity = reading.humidity.takeIf { it.isFinite() },
            tvoc = reading.tvoc.takeIf { it.isFinite() },
            eco2 = reading.eco2.takeIf { it.isFinite() },
            noiseLevel = reading.noiseLevel.takeIf { it.isFinite() }
        )
    }
}
