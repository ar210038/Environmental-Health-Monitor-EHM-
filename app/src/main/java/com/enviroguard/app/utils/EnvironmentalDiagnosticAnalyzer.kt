package com.enviroguard.app.utils

import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.model.EnvironmentalFactor
import com.enviroguard.app.model.SensorReading

data class FactorOccurrence(val factor: EnvironmentalFactor, val abnormalReadings: Int, val percentage: Float)
data class EnvironmentalDiagnosticResult(val occurrences: List<FactorOccurrence>, val dominantFactor: String)

object EnvironmentalDiagnosticAnalyzer {
    fun analyze(readings: List<SensorReadingEntity>): EnvironmentalDiagnosticResult {
        if (readings.isEmpty()) return EnvironmentalDiagnosticResult(emptyList(), "No readings are available for this period.")
        val ordered = readings.sortedBy { it.timestamp }
        val occurrences = EnvironmentalFactor.entries.map { factor ->
            val abnormal = ordered.count { reading -> statusFor(factor, reading, ordered) }
            FactorOccurrence(factor, abnormal, abnormal * 100f / ordered.size)
        }
        val max = occurrences.maxOf { it.abnormalReadings }
        val leaders = occurrences.filter { it.abnormalReadings == max && max > 0 }
        val phrase = when {
            max == 0 -> "No abnormal factors were detected in this period."
            leaders.size > 1 -> "Multiple factors showed similar levels of abnormal occurrence."
            else -> "${leaders.first().factor.displayName} was the most frequently elevated factor."
        }
        return EnvironmentalDiagnosticResult(occurrences, phrase)
    }

    private fun statusFor(factor: EnvironmentalFactor, reading: SensorReadingEntity, all: List<SensorReadingEntity>): Boolean {
        val noiseHistory = all.filter { it.timestamp <= reading.timestamp }.map { it.noiseDb }
        return when (factor) {
            EnvironmentalFactor.HEAT_INDEX -> EnvironmentalConditionEngine.assess(reading.toSensorReading(), noiseHistory).heatIndexStatus.level > 0
            EnvironmentalFactor.TVOC -> EnvironmentalConditionEngine.classifyTvoc(reading.tvoc).level > 0
            EnvironmentalFactor.NOISE -> EnvironmentalConditionEngine.assess(reading.toSensorReading(), noiseHistory).noiseStatus.level > 0
        }
    }
}

private fun SensorReadingEntity.toSensorReading() = SensorReading(
    temperature = temperature, humidity = humidity, tvoc = tvoc, eco2 = eco2,
    noiseDb = noiseDb, timestamp = timestamp
)
