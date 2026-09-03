package com.enviroguard.app.alerts

import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import kotlin.math.roundToInt

data class AlertDecision(val title: String, val body: String)

/** Timestamp-based persistence and cooldown logic for local environmental alerts. */
class AlertDecisionEngine {
    private var elevatedSince: Long? = null
    private var trackedCondition: EnvironmentalCondition? = null
    private var lastAlertAt: Long? = null

    fun evaluate(
        assessment: EnvironmentalAssessment,
        now: Long,
        enabled: Boolean,
        isDemo: Boolean,
        reading: SensorReading? = null
    ): AlertDecision? {
        val condition = assessment.overallCondition
        if (!enabled || isDemo || condition == null || condition == EnvironmentalCondition.GOOD) {
            resetPersistence()
            return null
        }

        if (trackedCondition != condition) {
            trackedCondition = condition
            elevatedSince = now
            return null
        }

        val requiredPersistence = when (condition) {
            EnvironmentalCondition.MODERATE -> MODERATE_PERSISTENCE_MS
            EnvironmentalCondition.POOR -> POOR_PERSISTENCE_MS
            EnvironmentalCondition.CRITICAL -> CRITICAL_PERSISTENCE_MS
            EnvironmentalCondition.GOOD -> return null
        }
        val start = elevatedSince ?: now.also { elevatedSince = it }
        if (now < start || now - start < requiredPersistence) return null
        lastAlertAt?.let { last ->
            if (now >= last && now - last < ALERT_COOLDOWN_MS) return null
        }

        lastAlertAt = now
        return AlertDecision(
            title = "EHM: ${condition.displayName}",
            body = buildBody(condition, assessment.primaryConcerns, reading)
        )
    }

    private fun resetPersistence() {
        elevatedSince = null
        trackedCondition = null
    }

    private fun buildBody(
        condition: EnvironmentalCondition,
        concerns: List<EnvironmentalDimension>,
        reading: SensorReading?
    ): String {
        val concernText = if (concerns.isEmpty()) {
            "measured environmental conditions"
        } else {
            concerns.joinToString("; ") { dimension -> formatConcern(dimension, reading) }
        }
        return "${condition.displayName} has persisted. Main ${if (concerns.size == 1) "concern" else "concerns"}: $concernText. Open Guidance for practical actions."
    }

    private fun formatConcern(dimension: EnvironmentalDimension, reading: SensorReading?): String {
        if (reading == null) return dimension.displayName
        return when (dimension) {
            EnvironmentalDimension.THERMAL -> buildList {
                if (reading.temperature.isFinite()) add("${oneDecimal(reading.temperature)}°C")
                if (reading.humidity.isFinite()) add("${reading.humidity.roundToInt()}% humidity")
            }.joinToString(", ").let { values -> if (values.isEmpty()) "Thermal" else "Thermal ($values)" }

            EnvironmentalDimension.AIR -> buildList {
                if (reading.tvoc.isFinite()) add("TVOC ${reading.tvoc.roundToInt()} ppb")
                if (reading.eco2.isFinite()) add("eCO2 equivalent ${reading.eco2.roundToInt()} ppm")
            }.joinToString(", ").let { values -> if (values.isEmpty()) "Air" else "Air ($values)" }

            EnvironmentalDimension.NOISE -> if (reading.noiseLevel.isFinite()) {
                "Noise (Estimated Noise Level ${reading.noiseLevel.roundToInt()} dB)"
            } else {
                "Noise"
            }
        }
    }

    private fun oneDecimal(value: Float) = "%.1f".format(value)

    companion object {
        internal const val MODERATE_PERSISTENCE_MS = 5 * 60_000L
        internal const val POOR_PERSISTENCE_MS = 3 * 60_000L
        internal const val CRITICAL_PERSISTENCE_MS = 10_000L
        internal const val ALERT_COOLDOWN_MS = 20 * 60_000L
    }
}

internal class EnvironmentalAlertCoordinator(
    private val engine: AlertDecisionEngine,
    private val post: (AlertDecision) -> Unit
) {
    fun evaluate(
        assessment: EnvironmentalAssessment,
        reading: SensorReading?,
        now: Long,
        enabled: Boolean,
        isDemo: Boolean
    ): AlertDecision? = engine.evaluate(assessment, now, enabled, isDemo, reading)?.also(post)
}
