package com.enviroguard.app

import com.enviroguard.app.alerts.AlertDecisionEngine
import com.enviroguard.app.demo.ScenarioApplyResult
import com.enviroguard.app.demo.ScenarioInputField
import com.enviroguard.app.demo.ScenarioTestInput
import com.enviroguard.app.demo.ScenarioTestManager
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTestUnitTest {
    @After
    fun clearScenario() = ScenarioTestManager.clear()

    @Test
    fun scenarioCanOnlyBeAppliedWhileDemoModeIsEnabled() {
        val result = ScenarioTestManager.apply(validInput(), demoModeEnabled = false)

        assertTrue(result is ScenarioApplyResult.Invalid)
        assertEquals(ScenarioInputField.DEMO_MODE, (result as ScenarioApplyResult.Invalid).field)
        assertFalse(ScenarioTestManager.isActive)
    }

    @Test
    fun enteredValuesCreateTheExactTemporarySensorReading() {
        val input = validInput()
        val result = ScenarioTestManager.apply(input, demoModeEnabled = true)

        assertTrue(result is ScenarioApplyResult.Success)
        val reading = (result as ScenarioApplyResult.Success).reading
        assertEquals(input.temperatureCelsius, reading.temperature)
        assertEquals(input.humidityPercent, reading.humidity)
        assertEquals(input.tvocPpb, reading.tvoc)
        assertEquals(input.eco2EquivalentPpm, reading.eco2)
        assertEquals(input.estimatedNoiseLevelDb, reading.noiseLevel)
        assertEquals(reading, ScenarioTestManager.currentReading(demoModeEnabled = true))
    }

    @Test
    fun scenarioUsesExistingAssessmentEngineAndRetainsTiedPrimaryConcerns() {
        val result = ScenarioTestManager.apply(
            ScenarioTestInput(30f, 70f, 300f, 500f, 85f),
            demoModeEnabled = true
        ) as ScenarioApplyResult.Success

        val assessment = EnvironmentalConditionEngine.assess(result.reading)

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(
            listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.NOISE),
            assessment.primaryConcerns
        )
    }

    @Test
    fun inputModelDoesNotExposeManualConditionSelection() {
        val inputFields = ScenarioTestInput::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }

        assertEquals(5, inputFields.size)
        assertTrue(inputFields.none { it.contains("condition", ignoreCase = true) })
    }

    @Test
    fun scenarioStateHasNoPersistenceFirebaseOrRepositoryCollaborator() {
        val collaboratorTypes = ScenarioTestManager::class.java.declaredFields
            .map { it.type.name.lowercase() }

        assertTrue(collaboratorTypes.none { type ->
            type.contains("firebase") || type.contains("room") || type.contains("repository") || type.contains("dao")
        })
    }

    @Test
    fun scenarioCannotTriggerARealEnvironmentalNotification() {
        val result = ScenarioTestManager.apply(
            ScenarioTestInput(20f, 40f, 3_000f, 500f, 40f),
            demoModeEnabled = true
        ) as ScenarioApplyResult.Success
        val assessment = EnvironmentalConditionEngine.assess(result.reading)
        val engine = AlertDecisionEngine()

        assertNull(engine.evaluate(assessment, 0L, enabled = true, isDemo = true, reading = result.reading))
        assertNull(engine.evaluate(assessment, 60_000L, enabled = true, isDemo = true, reading = result.reading))
    }

    @Test
    fun leavingDemoModeImmediatelyClearsScenario() {
        ScenarioTestManager.apply(validInput(), demoModeEnabled = true)

        assertNull(ScenarioTestManager.currentReading(demoModeEnabled = false))
        assertFalse(ScenarioTestManager.isActive)
    }

    @Test
    fun invalidHumidityAndNonFiniteOrNegativeInputsAreRejected() {
        assertInvalid(validInput().copy(humidityPercent = 101f), ScenarioInputField.HUMIDITY)
        assertInvalid(validInput().copy(temperatureCelsius = Float.NaN), ScenarioInputField.TEMPERATURE)
        assertInvalid(validInput().copy(tvocPpb = -1f), ScenarioInputField.TVOC)
        assertInvalid(validInput().copy(eco2EquivalentPpm = Float.POSITIVE_INFINITY), ScenarioInputField.ECO2)
        assertInvalid(validInput().copy(estimatedNoiseLevelDb = -0.1f), ScenarioInputField.NOISE)
    }

    private fun assertInvalid(input: ScenarioTestInput, expectedField: ScenarioInputField) {
        val result = ScenarioTestManager.apply(input, demoModeEnabled = true)
        assertTrue(result is ScenarioApplyResult.Invalid)
        assertEquals(expectedField, (result as ScenarioApplyResult.Invalid).field)
        assertFalse(ScenarioTestManager.isActive)
    }

    private fun validInput() = ScenarioTestInput(
        temperatureCelsius = 29.4f,
        humidityPercent = 72f,
        tvocPpb = 145f,
        eco2EquivalentPpm = 612f,
        estimatedNoiseLevelDb = 58f
    )
}
