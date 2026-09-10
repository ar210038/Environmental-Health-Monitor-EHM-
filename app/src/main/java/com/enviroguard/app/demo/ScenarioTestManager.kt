package com.enviroguard.app.demo

import com.enviroguard.app.model.SensorReading

data class ScenarioTestInput(
    val temperatureCelsius: Float,
    val humidityPercent: Float,
    val tvocPpb: Float,
    val eco2EquivalentPpm: Float,
    val estimatedNoiseLevelDb: Float
)

enum class ScenarioInputField {
    DEMO_MODE,
    TEMPERATURE,
    HUMIDITY,
    TVOC,
    ECO2,
    NOISE
}

sealed class ScenarioApplyResult {
    data class Success(val reading: SensorReading) : ScenarioApplyResult()
    data class Invalid(val field: ScenarioInputField, val message: String) : ScenarioApplyResult()
}

/** Process-local demo state. This type has no persistence or network dependency by design. */
object ScenarioTestManager {
    @Volatile
    private var scenarioReading: SensorReading? = null

    val isActive: Boolean
        get() = scenarioReading != null

    @Synchronized
    fun apply(input: ScenarioTestInput, demoModeEnabled: Boolean): ScenarioApplyResult {
        if (!demoModeEnabled) {
            clear()
            return ScenarioApplyResult.Invalid(
                ScenarioInputField.DEMO_MODE,
                "Enable Demo Mode before applying a Scenario Test."
            )
        }

        validate(input)?.let { return it }
        val reading = SensorReading(
            temperature = input.temperatureCelsius,
            humidity = input.humidityPercent,
            tvoc = input.tvocPpb,
            eco2 = input.eco2EquivalentPpm,
            noiseLevel = input.estimatedNoiseLevelDb
        )
        scenarioReading = reading
        return ScenarioApplyResult.Success(reading)
    }

    @Synchronized
    fun currentReading(demoModeEnabled: Boolean): SensorReading? {
        if (!demoModeEnabled) clear()
        return scenarioReading
    }

    @Synchronized
    fun clear() {
        scenarioReading = null
    }

    private fun validate(input: ScenarioTestInput): ScenarioApplyResult.Invalid? {
        if (!input.temperatureCelsius.isFinite()) {
            return invalid(ScenarioInputField.TEMPERATURE, "Enter a finite temperature.")
        }
        if (input.temperatureCelsius !in MIN_TEMPERATURE_C..MAX_TEMPERATURE_C) {
            return invalid(
                ScenarioInputField.TEMPERATURE,
                "Temperature must be between -40°C and 80°C."
            )
        }
        if (!input.humidityPercent.isFinite()) {
            return invalid(ScenarioInputField.HUMIDITY, "Enter a finite humidity value.")
        }
        if (input.humidityPercent !in 0f..100f) {
            return invalid(ScenarioInputField.HUMIDITY, "Humidity must be between 0% and 100%.")
        }
        validateNonNegative(input.tvocPpb, ScenarioInputField.TVOC, "TVOC")?.let { return it }
        validateNonNegative(input.eco2EquivalentPpm, ScenarioInputField.ECO2, "eCO2 equivalent")?.let { return it }
        validateNonNegative(input.estimatedNoiseLevelDb, ScenarioInputField.NOISE, "Estimated Noise Level")?.let { return it }
        return null
    }

    private fun validateNonNegative(
        value: Float,
        field: ScenarioInputField,
        label: String
    ): ScenarioApplyResult.Invalid? = when {
        !value.isFinite() -> invalid(field, "Enter a finite $label value.")
        value < 0f -> invalid(field, "$label must be zero or greater.")
        else -> null
    }

    private fun invalid(field: ScenarioInputField, message: String) =
        ScenarioApplyResult.Invalid(field, message)

    private const val MIN_TEMPERATURE_C = -40f
    private const val MAX_TEMPERATURE_C = 80f
}
