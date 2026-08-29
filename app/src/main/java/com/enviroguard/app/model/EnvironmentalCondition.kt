package com.enviroguard.app.model

/** Operational environmental categories, not medical diagnoses. */
enum class EnvironmentalCondition(val severity: Int, val displayName: String) {
    GOOD(0, "GOOD"), MODERATE(1, "MODERATE"), POOR(2, "POOR"), CRITICAL(3, "CRITICAL")
}

enum class EnvironmentalDimension(val displayName: String) {
    THERMAL("Thermal"), AIR("Air Quality"), NOISE("Noise")
}

data class EnvironmentalGuidance(
    val dimension: EnvironmentalDimension,
    val condition: EnvironmentalCondition,
    val title: String,
    val possibleEffects: List<String>,
    val recommendations: List<String>,
    val sourceLabel: String,
    val disclaimer: String? = null
)

data class EnvironmentalAssessment(
    val heatIndexCelsius: Float?,
    val thermalCondition: EnvironmentalCondition,
    val tvocCondition: EnvironmentalCondition,
    val eco2Condition: EnvironmentalCondition,
    val airCondition: EnvironmentalCondition,
    val noiseCondition: EnvironmentalCondition,
    val overallCondition: EnvironmentalCondition,
    val primaryConcerns: List<EnvironmentalDimension>,
    val guidance: List<EnvironmentalGuidance>
)
