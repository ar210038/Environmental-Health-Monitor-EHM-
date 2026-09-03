package com.enviroguard.app

import com.enviroguard.app.ai.AiExplanationState
import com.enviroguard.app.ai.GeminiExplanationContextFactory
import com.enviroguard.app.ai.GeminiExplanationService
import com.enviroguard.app.ai.GeminiPromptBuilder
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiExplanationUnitTest {
    @Test
    fun contextContainsOnlyFiniteMeasurementsAndDeterministicAssessment() {
        val reading = SensorReading(30f, 70f, 300f, 500f, Float.NaN)
        val assessment = EnvironmentalConditionEngine.assess(reading)

        val context = GeminiExplanationContextFactory.create(reading, assessment, isDemo = false)

        assertEquals(30f, context.measurements.temperatureCelsius)
        assertNull(context.measurements.estimatedNoiseLevelDb)
        assertEquals(assessment.overallCondition?.displayName, context.assessment.overallCondition)
        assertEquals(assessment.primaryConcerns.map { it.displayName }, context.assessment.primaryConcerns)
        assertTrue(context.guidance.all { it.condition in EnvironmentalCondition.entries.map(EnvironmentalCondition::displayName) })
    }

    @Test
    fun promptLabelsDemoAndLiveMeasurementsClearly() {
        val reading = SensorReading(25f, 50f, 100f, 600f, 45f)
        val assessment = EnvironmentalConditionEngine.assess(reading)
        val demoPrompt = GeminiPromptBuilder.buildUserPrompt(
            GeminiExplanationContextFactory.create(reading, assessment, isDemo = true)
        )
        val livePrompt = GeminiPromptBuilder.buildUserPrompt(
            GeminiExplanationContextFactory.create(reading, assessment, isDemo = false)
        )

        assertTrue(demoPrompt.contains("DEMO/TEST measurements, not live"))
        assertTrue(livePrompt.contains("Current live environmental measurements"))
    }

    @Test
    fun promptIncludesAuthoritativeConditionAndEveryTiedConcern() {
        val reading = SensorReading(30f, 70f, 300f, 500f, 85f)
        val assessment = EnvironmentalConditionEngine.assess(reading)
        val prompt = GeminiPromptBuilder.buildUserPrompt(
            GeminiExplanationContextFactory.create(reading, assessment, isDemo = false)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.NOISE), assessment.primaryConcerns)
        assertTrue(prompt.contains("Overall: POOR"))
        assertTrue(prompt.contains("Primary concerns: Thermal, Noise"))
        assertTrue(prompt.contains("copy these categories exactly"))
    }

    @Test
    fun promptUsesEquivalentAndEstimatedSensorLanguageWithoutFalseClaims() {
        val reading = SensorReading(25f, 50f, 100f, 600f, 45f)
        val assessment = EnvironmentalConditionEngine.assess(reading)
        val fullPrompt = (
            GeminiPromptBuilder.SYSTEM_INSTRUCTION + "\n" +
                GeminiPromptBuilder.buildUserPrompt(
                    GeminiExplanationContextFactory.create(reading, assessment, isDemo = false)
                )
            ).lowercase()

        assertTrue(fullPrompt.contains("eco2 equivalent"))
        assertTrue(fullPrompt.contains("estimated noise level"))
        assertFalse(fullPrompt.contains("direct co2"))
        assertFalse(fullPrompt.contains("dba"))
        assertFalse(fullPrompt.contains("calibrated spl"))
    }

    @Test
    fun explanationStateTransitionsFromLoadingToSuccess() = runBlocking {
        val context = demoContext()
        val states = GeminiExplanationService(generator = { "A concise explanation." })
            .explain(context)
            .toList()

        assertEquals(AiExplanationState.Loading, states[0])
        assertEquals(AiExplanationState.Success("A concise explanation.", isDemo = true), states[1])
    }

    @Test
    fun explanationFailuresAreNonTechnicalAndDoNotEscape() = runBlocking {
        val states = GeminiExplanationService(generator = { throw IOException("socket details") })
            .explain(demoContext())
            .toList()

        assertEquals(AiExplanationState.Loading, states[0])
        assertEquals(
            AiExplanationState.Error("No network connection is available for the AI explanation."),
            states[1]
        )
    }

    @Test
    fun emptyBackendResponseBecomesAnErrorState() = runBlocking {
        val states = GeminiExplanationService(generator = { "   " })
            .explain(demoContext())
            .toList()

        assertEquals(AiExplanationState.Loading, states[0])
        assertEquals(
            AiExplanationState.Error("The AI service returned no explanation. Please try again."),
            states[1]
        )
    }

    private fun demoContext() = SensorReading(25f, 50f, 100f, 600f, 45f).let { reading ->
        GeminiExplanationContextFactory.create(
            reading,
            EnvironmentalConditionEngine.assess(reading),
            isDemo = true
        )
    }
}
