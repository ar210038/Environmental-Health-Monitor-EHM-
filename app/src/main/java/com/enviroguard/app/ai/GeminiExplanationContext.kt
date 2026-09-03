package com.enviroguard.app.ai

import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading

data class GeminiMeasurementContext(
    val temperatureCelsius: Float?,
    val humidityPercent: Float?,
    val heatIndexCelsius: Float?,
    val tvocPpb: Float?,
    val eco2EquivalentPpm: Float?,
    val estimatedNoiseLevelDb: Float?
)

data class GeminiAssessmentContext(
    val thermalCondition: String?,
    val airCondition: String?,
    val noiseCondition: String?,
    val overallCondition: String?,
    val primaryConcerns: List<String>
)

data class GeminiGuidanceContext(
    val dimension: String,
    val condition: String,
    val possibleEffects: List<String>,
    val recommendedActions: List<String>,
    val referenceOrganization: String,
    val sensorLimitation: String?
)

data class GeminiHistoricalContext(
    val conditionDurationMinutes: Map<EnvironmentalDimension, Long> = emptyMap(),
    val dominantContributor: String? = null,
    val recurringPattern: String? = null
)

data class GeminiForecastContext(
    val predictedHeatIndexCelsius: Float? = null,
    val predictedTvocPpb: Float? = null,
    val predictedEco2EquivalentPpm: Float? = null,
    val predictedEstimatedNoiseLevelDb: Float? = null
)

data class GeminiExplanationContext(
    val isDemo: Boolean,
    val measurements: GeminiMeasurementContext,
    val assessment: GeminiAssessmentContext,
    val guidance: List<GeminiGuidanceContext>,
    val historical: GeminiHistoricalContext? = null,
    val forecast: GeminiForecastContext? = null
)

object GeminiExplanationContextFactory {
    fun create(
        reading: SensorReading,
        assessment: EnvironmentalAssessment,
        isDemo: Boolean,
        historical: GeminiHistoricalContext? = null,
        forecast: GeminiForecastContext? = null
    ) = GeminiExplanationContext(
        isDemo = isDemo,
        measurements = GeminiMeasurementContext(
            temperatureCelsius = reading.temperature.finiteOrNull(),
            humidityPercent = reading.humidity.finiteOrNull(),
            heatIndexCelsius = assessment.heatIndexCelsius?.finiteOrNull(),
            tvocPpb = reading.tvoc.finiteOrNull(),
            eco2EquivalentPpm = reading.eco2.finiteOrNull(),
            estimatedNoiseLevelDb = reading.noiseLevel.finiteOrNull()
        ),
        assessment = GeminiAssessmentContext(
            thermalCondition = assessment.thermalCondition?.displayName,
            airCondition = assessment.airCondition?.displayName,
            noiseCondition = assessment.noiseCondition?.displayName,
            overallCondition = assessment.overallCondition?.displayName,
            primaryConcerns = assessment.primaryConcerns.map { it.displayName }
        ),
        guidance = assessment.guidance.map { item ->
            GeminiGuidanceContext(
                dimension = item.dimension.displayName,
                condition = item.condition.displayName,
                possibleEffects = item.possibleEffects,
                recommendedActions = item.recommendations,
                referenceOrganization = item.sourceLabel,
                sensorLimitation = item.disclaimer
            )
        },
        historical = historical,
        forecast = forecast
    )

    private fun Float.finiteOrNull() = takeIf(Float::isFinite)
}
