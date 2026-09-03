package com.enviroguard.app.forecast

import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.HeatIndex
import java.time.Instant
import java.time.ZoneId

/** Builds smoke-test-v1 without inventing a historical series. */
class TimeSeriesFeatureBuilder {
    fun build(reading: SensorReading, zoneId: ZoneId = ZoneId.systemDefault()): FloatArray {
        val time = Instant.ofEpochMilli(reading.timestamp).atZone(zoneId)
        val features = FloatArray(ForecastFeatureSchema.featureCount)
        features[ForecastFeature.TEMPERATURE_CELSIUS.index] = reading.temperature
        features[ForecastFeature.HUMIDITY_PERCENT.index] = reading.humidity
        features[ForecastFeature.HEAT_INDEX_CELSIUS.index] =
            HeatIndex.calculateCelsius(reading.temperature, reading.humidity)
        features[ForecastFeature.TVOC_PPB.index] = reading.tvoc
        features[ForecastFeature.ECO2_EQUIVALENT_PPM.index] = reading.eco2
        features[ForecastFeature.ESTIMATED_NOISE_LEVEL_DB.index] = reading.noiseLevel
        features[ForecastFeature.HOUR_OF_DAY.index] = time.hour.toFloat()
        features[ForecastFeature.ISO_DAY_OF_WEEK.index] = time.dayOfWeek.value.toFloat()
        ForecastFeatureSchema.validate(features)
        return features
    }
}
