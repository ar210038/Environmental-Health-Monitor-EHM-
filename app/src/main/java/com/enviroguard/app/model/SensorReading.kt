package com.enviroguard.app.model

/** Raw ESP32 measurement. Derived conditions are never stored in the raw record. */
data class SensorReading(
    val temperature: Float = 0f,
    val humidity: Float = 0f,
    val tvoc: Float = 0f,
    val eco2: Float = 0f,
    val noiseLevel: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)
