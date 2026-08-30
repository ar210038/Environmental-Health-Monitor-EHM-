package com.enviroguard.app.utils

import com.enviroguard.app.model.*

object EnvironmentalGuidanceCatalog {
    fun forAssessment(thermal: EnvironmentalCondition, air: EnvironmentalCondition, noise: EnvironmentalCondition) =
        listOf(guidance(EnvironmentalDimension.THERMAL, thermal), guidance(EnvironmentalDimension.AIR, air), guidance(EnvironmentalDimension.NOISE, noise))

    fun guidance(dimension: EnvironmentalDimension, condition: EnvironmentalCondition): EnvironmentalGuidance {
        if (condition == EnvironmentalCondition.GOOD) return when (dimension) {
            EnvironmentalDimension.THERMAL -> EnvironmentalGuidance(dimension, condition, "Heat and humidity", listOf("No heat-related concern is indicated by the current advisory band."), listOf("Continue normal hydration and monitor conditions if activity or heat increases."), "U.S. National Weather Service / CDC")
            EnvironmentalDimension.AIR -> EnvironmentalGuidance(dimension, condition, "Air quality", listOf("Current TVOC and eCO2-equivalent readings are within the app's good advisory bands."), listOf("Maintain ordinary ventilation and keep potential pollutant sources controlled."), "German Environment Agency / U.S. EPA", "Sensor note: SGP30 eCO2 is a CO2-equivalent signal, not direct CO2 measurement.")
            EnvironmentalDimension.NOISE -> EnvironmentalGuidance(dimension, condition, "Estimated noise", listOf("The current estimated noise reading is within the app's good advisory band."), listOf("Continue sensible listening habits and limit unnecessary prolonged noise exposure."), "WHO safe-listening guidance", "Sensor note: Noise level is estimated because the INMP441 was not calibrated against a reference sound-level meter.")
        }
        return when (dimension) {
            EnvironmentalDimension.THERMAL -> EnvironmentalGuidance(dimension, condition, "Heat and humidity", listOf("Heat exposure may contribute to discomfort or heat-related illness, especially during exertion."), listOf("Move to a cooler or shaded place.", "Drink water and reduce strenuous activity.", "Use cooling methods where appropriate."), "U.S. National Weather Service / CDC")
            EnvironmentalDimension.AIR -> EnvironmentalGuidance(dimension, condition, "Air quality", listOf("Some VOC exposures may be associated with irritation, headaches, dizziness or nausea depending on the compounds and exposure duration."), listOf("Improve ventilation when outdoor air is cleaner.", "Identify or reduce possible VOC sources.", "Leave the area if it becomes uncomfortable."), "German Environment Agency / U.S. EPA", "Sensor note: SGP30 eCO2 is a CO2-equivalent signal, not direct CO2 measurement.")
            EnvironmentalDimension.NOISE -> EnvironmentalGuidance(dimension, condition, "Estimated noise", listOf("Prolonged exposure to high noise levels may contribute to hearing-related problems."), listOf("Move away from the source or reduce exposure duration.", "Control the source where possible.", "Use suitable hearing protection in appropriate occupational situations."), "WHO safe-listening guidance", "Sensor note: Noise level is estimated because the INMP441 was not calibrated against a reference sound-level meter.")
        }
    }
}
