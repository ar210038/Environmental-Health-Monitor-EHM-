package com.enviroguard.app.utils

import com.enviroguard.app.model.*

/** The only active deterministic rule engine. It has no score or weighted average. */
object EnvironmentalConditionEngine {
    fun assess(reading: SensorReading): EnvironmentalAssessment {
        val heatIndex = HeatIndex.calculateCelsius(reading.temperature, reading.humidity)
        val thermal = classifyThermal(heatIndex)
        val tvoc = classifyTvoc(reading.tvoc)
        val eco2 = classifyEco2(reading.eco2)
        val air = maxOf(tvoc, eco2, compareBy { it.severity })
        val noise = classifyNoise(reading.noiseLevel)
        val overall = maxOf(thermal, air, noise, compareBy { it.severity })
        val concerns = listOf(EnvironmentalDimension.THERMAL to thermal, EnvironmentalDimension.AIR to air, EnvironmentalDimension.NOISE to noise)
            .filter { it.second == overall && overall != EnvironmentalCondition.GOOD }.map { it.first }
        return EnvironmentalAssessment(heatIndex, thermal, tvoc, eco2, air, noise, overall, concerns,
            EnvironmentalGuidanceCatalog.forAssessment(thermal, air, noise))
    }
    fun classifyThermal(value: Float) = when { !value.isFinite() || value < 26.7f -> EnvironmentalCondition.GOOD; value < 32.2f -> EnvironmentalCondition.MODERATE; value < 39.4f -> EnvironmentalCondition.POOR; else -> EnvironmentalCondition.CRITICAL }
    fun classifyTvoc(value: Float) = when { !value.isFinite() || value <= 222f -> EnvironmentalCondition.GOOD; value <= 667f -> EnvironmentalCondition.MODERATE; value <= 2222f -> EnvironmentalCondition.POOR; else -> EnvironmentalCondition.CRITICAL }
    fun classifyEco2(value: Float) = when { !value.isFinite() || value < 1000f -> EnvironmentalCondition.GOOD; value <= 2000f -> EnvironmentalCondition.MODERATE; else -> EnvironmentalCondition.POOR }
    fun classifyNoise(value: Float) = when { !value.isFinite() || value < 80f -> EnvironmentalCondition.GOOD; value < 85f -> EnvironmentalCondition.MODERATE; value < 95f -> EnvironmentalCondition.POOR; else -> EnvironmentalCondition.CRITICAL }
}

object HeatIndex {
    /** U.S. National Weather Service heat-index procedure; output is Celsius. */
    fun calculateCelsius(temperatureC: Float, humidity: Float): Float {
        if (!temperatureC.isFinite() || !humidity.isFinite()) return Float.NaN
        val t = temperatureC * 9f / 5f + 32f
        val rh = humidity.coerceIn(0f, 100f)
        val simple = 0.5f * (t + 61f + (t - 68f) * 1.2f + rh * .094f)
        if ((simple + t) / 2f < 80f) return ((simple + t) / 2f - 32f) * 5f / 9f
        var hi = -42.379f + 2.04901523f*t + 10.14333127f*rh - .22475541f*t*rh - .00683783f*t*t - .05481717f*rh*rh + .00122874f*t*t*rh + .00085282f*t*rh*rh - .00000199f*t*t*rh*rh
        if (rh < 13f && t in 80f..112f) hi -= ((13f-rh)/4f) * kotlin.math.sqrt((17f-kotlin.math.abs(t-95f))/17f)
        if (rh > 85f && t in 80f..87f) hi += ((rh-85f)/10f) * ((87f-t)/5f)
        return (hi - 32f) * 5f / 9f
    }
}
