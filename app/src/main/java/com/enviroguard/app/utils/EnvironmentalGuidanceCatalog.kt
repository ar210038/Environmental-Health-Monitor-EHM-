package com.enviroguard.app.utils

import com.enviroguard.app.model.*

object EnvironmentalGuidanceCatalog {
    fun forAssessment(thermal: EnvironmentalCondition, air: EnvironmentalCondition, noise: EnvironmentalCondition) =
        listOf(guidance(EnvironmentalDimension.THERMAL, thermal), guidance(EnvironmentalDimension.AIR, air), guidance(EnvironmentalDimension.NOISE, noise)).filter { it.condition != EnvironmentalCondition.GOOD }
    fun guidance(dimension: EnvironmentalDimension, condition: EnvironmentalCondition): EnvironmentalGuidance = when (dimension) {
        EnvironmentalDimension.THERMAL -> EnvironmentalGuidance(dimension, condition, "Heat and humidity guidance", listOf("Heat exposure may contribute to discomfort or heat-related illness, especially during exertion."), listOf("Move to a cooler or shaded place.", "Drink water and reduce strenuous activity.", "Use cooling methods where appropriate."), "U.S. National Weather Service / CDC")
        EnvironmentalDimension.AIR -> EnvironmentalGuidance(dimension, condition, "Air-quality guidance", listOf("Some VOC exposures may be associated with irritation, headaches, dizziness or nausea depending on the compounds and exposure duration."), listOf("Improve ventilation when outdoor air is cleaner.", "Identify or reduce possible VOC sources.", "Leave the area if it becomes uncomfortable."), "German Environment Agency / U.S. EPA", "TVOC is advisory; eCO2 is a CO2-equivalent ventilation indicator, not direct CO2.")
        EnvironmentalDimension.NOISE -> EnvironmentalGuidance(dimension, condition, "Estimated noise guidance", listOf("Prolonged exposure to high noise levels may contribute to hearing-related problems."), listOf("Move away from the source or reduce exposure duration.", "Control the source where possible.", "Use suitable hearing protection in appropriate occupational situations."), "WHO safe-listening guidance — estimated sensor reference only", "The microphone value is estimated and is not a certified sound-level measurement.")
    }
}
