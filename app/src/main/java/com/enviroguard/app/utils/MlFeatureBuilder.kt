package com.enviroguard.app.utils

import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.model.MlFeatures
import java.time.Instant
import java.time.ZoneId

object MlFeatureBuilder {
    const val ROLLING_WINDOW_MS = 5 * 60_000L

    fun build(
        current: SensorReadingEntity,
        readings: List<SensorReadingEntity>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MlFeatures {
        // Leakage guard: only the current device's readings at or before the current
        // timestamp, within the previous five minutes, contribute to the average.
        val windowStart = current.timestamp - ROLLING_WINDOW_MS
        val pastAndCurrent = readings.filter {
            it.deviceId == current.deviceId &&
                it.timestamp in windowStart..current.timestamp
        }
        val rollingAverage = pastAndCurrent.map { it.ers }.average()
            .takeUnless { it.isNaN() }?.toFloat() ?: current.ers.toFloat()

        return MlFeatures(
            current.temperature,
            current.humidity,
            current.tvoc,
            current.eco2,
            current.noiseDb,
            HeatIndex.calculateCelsius(current.temperature, current.humidity) ?: Float.NaN,
            timeOfDay(current.timestamp, zoneId).toFloat(),
            rollingAverage,
            dominantFactor(current.mainContributor).toFloat()
        )
    }

    fun timeOfDay(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Int {
        val hour = Instant.ofEpochMilli(timestamp).atZone(zoneId).hour
        return when (hour) {
            in 0..5 -> 0
            in 6..11 -> 1
            in 12..17 -> 2
            else -> 3
        }
    }

    // -1 is an explicit sentinel; "None" is never silently mapped to Temperature.
    fun dominantFactor(contributor: String): Int = when (contributor.trim()) {
        "Temperature" -> 0
        "Humidity" -> 1
        "TVOC" -> 2
        "eCO₂", "eCO2" -> 3
        "Noise" -> 4
        else -> -1
    }
}
