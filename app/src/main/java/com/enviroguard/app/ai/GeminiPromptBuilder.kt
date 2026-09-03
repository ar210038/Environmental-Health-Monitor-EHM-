package com.enviroguard.app.ai

import com.enviroguard.app.model.EnvironmentalDimension
import java.util.Locale

object GeminiPromptBuilder {
    const val SYSTEM_INSTRUCTION = """
        You are the optional natural-language explanation layer for the Environmental Health Monitor (EHM).
        Explain the supplied measurements in simple, concise language suitable for a general user.
        Use only the supplied environmental data. Never invent unavailable information.
        The supplied deterministic condition categories and primary concerns are authoritative. Do not calculate, reclassify, infer, or override them.
        Do not contradict, replace, or extend the supplied predefined guidance.
        Do not provide a medical diagnosis or emergency diagnosis.
        Describe eCO2 only as an equivalent signal, never as a measured carbon-dioxide concentration.
        Describe Estimated Noise Level only as an uncalibrated estimate; never claim sound-level calibration.
        If forecast values are supplied, clearly distinguish them from current measurements. Do not change forecast values.
        If information is marked unavailable, say it is unavailable.
        Keep the response focused and brief.
    """

    fun buildUserPrompt(context: GeminiExplanationContext): String = buildString {
        appendLine("Explain this EHM environmental snapshot using the authoritative assessment and guidance below.")
        appendLine(
            if (context.isDemo) {
                "Measurement source: DEMO/TEST measurements, not live environmental readings. State this clearly in the explanation."
            } else {
                "Measurement source: Current live environmental measurements."
            }
        )
        appendLine()
        appendLine("Current raw measurements:")
        appendLine("- Temperature: ${measurement(context.measurements.temperatureCelsius, "°C")}")
        appendLine("- Humidity: ${measurement(context.measurements.humidityPercent, "%")}")
        appendLine("- Heat Index: ${measurement(context.measurements.heatIndexCelsius, "°C")}")
        appendLine("- TVOC: ${measurement(context.measurements.tvocPpb, "ppb")}")
        appendLine("- eCO2 equivalent: ${measurement(context.measurements.eco2EquivalentPpm, "ppm")}")
        appendLine("- Estimated Noise Level: ${measurement(context.measurements.estimatedNoiseLevelDb, "dB")}")
        appendLine()
        appendLine("Authoritative deterministic assessment (copy these categories exactly):")
        appendLine("- Thermal: ${available(context.assessment.thermalCondition)}")
        appendLine("- Air: ${available(context.assessment.airCondition)}")
        appendLine("- Noise: ${available(context.assessment.noiseCondition)}")
        appendLine("- Overall: ${available(context.assessment.overallCondition)}")
        appendLine("- Primary concerns: ${context.assessment.primaryConcerns.ifEmpty { listOf("None") }.joinToString(", ")}")
        appendLine()
        appendGuidance(context)
        appendHistorical(context.historical)
        appendForecast(context.forecast)
    }.trim()

    private fun StringBuilder.appendGuidance(context: GeminiExplanationContext) {
        appendLine("Predefined guidance (do not contradict or replace):")
        if (context.guidance.isEmpty()) {
            appendLine("- Unavailable")
        } else {
            context.guidance.forEach { item ->
                appendLine("- ${item.dimension} [${item.condition}]")
                appendLine("  Possible effects: ${item.possibleEffects.joinToString("; ")}")
                appendLine("  Recommended actions: ${item.recommendedActions.joinToString("; ")}")
                appendLine("  Reference organization: ${item.referenceOrganization}")
                item.sensorLimitation?.let { appendLine("  Sensor limitation: $it") }
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendHistorical(context: GeminiHistoricalContext?) {
        appendLine("Historical context:")
        if (context == null) {
            appendLine("- Unavailable")
        } else {
            val durations = EnvironmentalDimension.entries.joinToString(", ") { dimension ->
                "${dimension.displayName}: ${context.conditionDurationMinutes[dimension]?.let { "$it min" } ?: "Unavailable"}"
            }
            appendLine("- Condition durations: $durations")
            appendLine("- Dominant measured environmental contributor: ${available(context.dominantContributor)}")
            appendLine("- Recurring time-based pattern: ${available(context.recurringPattern)}")
        }
        appendLine()
    }

    private fun StringBuilder.appendForecast(context: GeminiForecastContext?) {
        appendLine("Forecast context (predicted values, not current measurements):")
        if (context == null) {
            appendLine("- Unavailable")
        } else {
            appendLine("- Predicted Heat Index: ${measurement(context.predictedHeatIndexCelsius, "°C")}")
            appendLine("- Predicted TVOC: ${measurement(context.predictedTvocPpb, "ppb")}")
            appendLine("- Predicted eCO2 equivalent: ${measurement(context.predictedEco2EquivalentPpm, "ppm")}")
            appendLine("- Predicted Estimated Noise Level: ${measurement(context.predictedEstimatedNoiseLevelDb, "dB")}")
        }
    }

    private fun measurement(value: Float?, unit: String): String =
        value?.let { "${String.format(Locale.US, "%.1f", it)} $unit" } ?: "Unavailable"

    private fun available(value: String?) = value?.takeIf { it.isNotBlank() } ?: "Unavailable"
}
