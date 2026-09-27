package com.enviroguard.app.forecast

import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.utils.HeatIndex
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Builds the exact EHM_RF_H60_3032cleanrecords 32-feature input contract. */
class TimeSeriesFeatureBuilder {
    fun build(
        history: List<SensorReadingEntity>,
        zoneId: ZoneId = ZoneId.of(ForecastModelConfig.TIME_ZONE)
    ): FloatArray {
        require(history.isNotEmpty()) { "At least 30 minutes of continuous history is required." }
        val ordered = history.sortedBy(SensorReadingEntity::timestamp)
        val deviceId = ordered.last().deviceId
        require(deviceId.isNotBlank() && ordered.all { it.deviceId == deviceId }) {
            "Forecast history must belong to one device."
        }

        val sessionStart = ordered.zipWithNext().indexOfLast { (before, after) ->
            after.timestamp - before.timestamp > ForecastModelConfig.SESSION_GAP_MS
        } + 1
        val session = ordered.drop(sessionStart)
        val current = session.last()
        val lag5 = findLag(session, current.timestamp, 5)
        val lag15 = findLag(session, current.timestamp, 15)
        val lag30 = findLag(session, current.timestamp, 30)
        val currentValues = values(current, "current")
        val lag5Values = values(lag5, "5-minute lag")
        val lag15Values = values(lag15, "15-minute lag")
        val lag30Values = values(lag30, "30-minute lag")
        val localTime = Instant.ofEpochMilli(current.timestamp).atZone(zoneId)
        val localHour = localTime.hour + localTime.minute / 60.0
        val radians = 2.0 * PI * localHour / 24.0

        return FloatArray(ForecastFeatureSchema.featureCount).also { features ->
            currentValues.copyInto(features, 0)
            lag5Values.copyInto(features, 6)
            lag15Values.copyInto(features, 12)
            lag30Values.copyInto(features, 18)
            currentValues.indices.forEach { index ->
                features[24 + index] = currentValues[index] - lag5Values[index]
            }
            features[ForecastFeature.HOUR_SIN.index] = sin(radians).toFloat()
            features[ForecastFeature.HOUR_COS.index] = cos(radians).toFloat()
            ForecastFeatureSchema.validate(features)
        }
    }

    private fun findLag(
        session: List<SensorReadingEntity>,
        currentTimestamp: Long,
        lagMinutes: Int
    ): SensorReadingEntity {
        val target = currentTimestamp - lagMinutes * 60_000L
        return session.asSequence()
            .filter { it.timestamp <= currentTimestamp }
            .minByOrNull { abs(it.timestamp - target) }
            ?.takeIf { abs(it.timestamp - target) <= ForecastModelConfig.LAG_TOLERANCE_MS }
            ?: throw IllegalArgumentException(
                "Forecast unavailable: a same-session reading near T−$lagMinutes minutes is missing."
            )
    }

    private fun values(reading: SensorReadingEntity, label: String): FloatArray {
        val temperature = reading.temperature
        val humidity = reading.humidity
        val tvoc = reading.tvoc
        val eco2 = reading.eco2
        val noise = reading.noiseLevel
        require(listOf(temperature, humidity, tvoc, eco2, noise).all { it?.isFinite() == true }) {
            "Forecast unavailable: $label measurements are incomplete."
        }
        return floatArrayOf(
            temperature!!,
            humidity!!,
            tvoc!!,
            eco2!!,
            noise!!,
            HeatIndex.calculateCelsius(temperature, humidity)
        )
    }
}
