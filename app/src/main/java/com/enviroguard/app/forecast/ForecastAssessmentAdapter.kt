package com.enviroguard.app.forecast

import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.utils.EnvironmentalConditionEngine

/** Interprets predicted targets with the same classifiers as current readings. */
object ForecastAssessmentAdapter {
    fun assess(result: ForecastResult): ForecastEnvironmentalAssessment {
        val thermal = EnvironmentalConditionEngine.classifyThermal(result.heatIndexCelsius)
        val tvoc = EnvironmentalConditionEngine.classifyTvoc(result.tvocPpb)
        val eco2 = EnvironmentalConditionEngine.classifyEco2(result.eco2EquivalentPpm)
        val air = listOfNotNull(tvoc, eco2).maxByOrNull(EnvironmentalCondition::severity)
        val noise = EnvironmentalConditionEngine.classifyNoise(result.estimatedNoiseLevel)
        val overall = listOfNotNull(thermal, air, noise).maxByOrNull(EnvironmentalCondition::severity)
        val concerns = listOf(
            EnvironmentalDimension.THERMAL to thermal,
            EnvironmentalDimension.AIR to air,
            EnvironmentalDimension.NOISE to noise
        ).filter { overall != null && overall != EnvironmentalCondition.GOOD && it.second == overall }
            .map { it.first }
        return ForecastEnvironmentalAssessment(
            thermalCondition = thermal,
            tvocCondition = tvoc,
            eco2Condition = eco2,
            airCondition = air,
            noiseCondition = noise,
            overallCondition = overall,
            primaryConcerns = concerns
        )
    }
}
