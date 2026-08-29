package com.enviroguard.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_readings", indices = [Index(value = ["deviceId", "timestamp"], unique = true)])
data class SensorReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val temperature: Float = 0f,
    val humidity: Float = 0f,
    val eco2: Float = 0f,
    val tvoc: Float = 0f,
    val noiseLevel: Float = 0f
)
