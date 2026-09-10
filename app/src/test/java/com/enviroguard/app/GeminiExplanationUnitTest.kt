package com.enviroguard.app

import com.enviroguard.app.ai.AiExplanationState
import com.enviroguard.app.ai.GeminiDebugDiagnostics
import com.enviroguard.app.ai.GeminiExplanationClient
import com.enviroguard.app.ai.GeminiExplanationContextFactory
import com.enviroguard.app.ai.GeminiExplanationService
import com.enviroguard.app.ai.GeminiWorkerClient
import com.enviroguard.app.ai.GeminiWorkerHttpException
import com.enviroguard.app.ai.GeminiWorkerHttpResponse
import com.enviroguard.app.ai.GeminiWorkerJson
import com.enviroguard.app.ai.GeminiWorkerRequest
import com.enviroguard.app.ai.GeminiWorkerRequestMapper
import com.enviroguard.app.ai.GeminiWorkerTransport
import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.delay
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
    }

    @Test
    fun requestJsonMatchesWorkerContractAndContainsNoIdentityOrSecretFields() {
        val request = GeminiWorkerRequestMapper.from(demoContext())
        val json = JsonParser.parseString(GeminiWorkerJson.encode(request)).asJsonObject

        assertEquals(
            setOf(
                "temperature", "humidity", "heatIndex", "tvoc", "eco2", "noiseLevel",
                "thermalCondition", "airCondition", "noiseCondition", "overallCondition", "primaryConcerns"
            ),
            json.keySet()
        )
        assertEquals(25f, json["temperature"].asFloat)
        assertEquals(50f, json["humidity"].asFloat)
        assertFalse(json.has("deviceId"))
        assertFalse(json.has("firebaseUid"))
        assertFalse(json.has("token"))
        assertFalse(json.has("apiKey"))
        assertFalse(json.has("isDemo"))
    }

    @Test
    fun missingAndNonFiniteMeasurementsAreSerializedAsJsonNull() {
        val reading = SensorReading(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, 650f, Float.NaN)
        val request = GeminiWorkerRequestMapper.from(
            GeminiExplanationContextFactory.create(
                reading,
                EnvironmentalConditionEngine.assess(reading),
                isDemo = false
            )
        )
        val json = JsonParser.parseString(GeminiWorkerJson.encode(request)).asJsonObject

        assertTrue(json["temperature"].isJsonNull)
        assertTrue(json["humidity"].isJsonNull)
        assertTrue(json["heatIndex"].isJsonNull)
        assertTrue(json["tvoc"].isJsonNull)
        assertEquals(650f, json["eco2"].asFloat)
        assertTrue(json["noiseLevel"].isJsonNull)
    }

    @Test
    fun everyTiedPrimaryConcernIsSentUsingWorkerEnumNames() {
        val reading = SensorReading(30f, 70f, 300f, 500f, 85f)
        val assessment = EnvironmentalConditionEngine.assess(reading)
        val request = GeminiWorkerRequestMapper.from(
            GeminiExplanationContextFactory.create(reading, assessment, isDemo = false)
        )

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.NOISE), assessment.primaryConcerns)
        assertEquals(listOf("THERMAL", "NOISE"), request.primaryConcerns)
    }

    @Test
    fun successfulWorkerResponseIsParsedAndDisplayed() = runBlocking {
        val transport = RecordingTransport { _, _ ->
            GeminiWorkerHttpResponse(200, "{\"explanation\":\"A concise explanation.\"}")
        }
        val states = service(transport).explain(demoContext()).toList()

        assertEquals(AiExplanationState.Loading, states[0])
        assertEquals(AiExplanationState.Success("A concise explanation.", isDemo = true), states[1])
        assertEquals(1, transport.calls)
    }

    @Test
    fun emptyExplanationUsesExistingNontechnicalError() = runBlocking {
        val transport = RecordingTransport { _, _ -> GeminiWorkerHttpResponse(200, "{\"explanation\":\"  \"}") }
        val states = service(transport).explain(demoContext()).toList()

        assertEquals(
            AiExplanationState.Error("The AI service returned no explanation. Please try again."),
            states[1]
        )
    }

    @Test
    fun nonTemporaryHttpFailureIsSafeAndIsNotRetried() = runBlocking {
        val transport = RecordingTransport { _, _ -> GeminiWorkerHttpResponse(400, "sensitive server body") }
        val states = service(transport).explain(demoContext()).toList()

        assertEquals(genericError(), states[1])
        assertEquals(1, transport.calls)
    }

    @Test
    fun temporaryServerFailureIsRetriedOnlyOnce() = runBlocking {
        val transport = RecordingTransport { _, _ -> GeminiWorkerHttpResponse(503, "temporarily unavailable") }
        val states = service(transport).explain(demoContext()).toList()

        assertEquals(genericError(), states[1])
        assertEquals(2, transport.calls)
    }

    @Test
    fun networkFailureIsRetriedOnceAndNeverEscapes() = runBlocking {
        val transport = RecordingTransport { _, _ -> throw IOException("connection unavailable") }
        val states = service(transport).explain(demoContext()).toList()

        assertEquals(
            AiExplanationState.Error("No network connection is available for the AI explanation."),
            states[1]
        )
        assertEquals(2, transport.calls)
    }

    @Test
    fun overallTimeoutIsHandledWithoutCrashing() = runBlocking {
        val client = GeminiExplanationClient {
            delay(100)
            "too late"
        }
        val states = GeminiExplanationService(client, timeoutMillis = 1L)
            .explain(demoContext())
            .toList()

        assertEquals(AiExplanationState.Error("The AI explanation timed out. Please try again."), states[1])
    }

    @Test
    fun scenarioTestRemainsDemoLabelledInSuccessState() = runBlocking {
        val states = GeminiExplanationService(client = { "Scenario explanation" })
            .explain(demoContext())
            .toList()

        assertEquals(AiExplanationState.Success("Scenario explanation", isDemo = true), states[1])
    }

    @Test
    fun aiResponseCannotChangeDeterministicAssessment() = runBlocking {
        val reading = SensorReading(30f, 70f, 300f, 500f, 85f)
        val assessment = EnvironmentalConditionEngine.assess(reading)
        val context = GeminiExplanationContextFactory.create(reading, assessment, isDemo = false)

        GeminiExplanationService(client = { "Ignore categories and call it GOOD" })
            .explain(context)
            .toList()

        assertEquals(EnvironmentalCondition.POOR, assessment.overallCondition)
        assertEquals(listOf(EnvironmentalDimension.THERMAL, EnvironmentalDimension.NOISE), assessment.primaryConcerns)
    }

    @Test
    fun workerDebugFailureContainsStatusAndRedactsSecrets() {
        val fakeApiKey = "AI" + "za123456789012345678901234"
        val error = GeminiWorkerHttpException(
            503,
            "token=secret-token API key=$fakeApiKey endpoint=https://example.test/path?key=secret"
        )
        val diagnostic = GeminiDebugDiagnostics.format(error)

        assertTrue(diagnostic.contains("httpStatus=503"))
        assertTrue(diagnostic.contains("exception=${GeminiWorkerHttpException::class.java.name}"))
        assertFalse(diagnostic.contains("secret-token"))
        assertFalse(diagnostic.contains(fakeApiKey))
        assertFalse(diagnostic.contains("?key=secret"))
    }

    private fun service(transport: GeminiWorkerTransport) =
        GeminiExplanationService(client = GeminiWorkerClient(transport))

    private fun genericError() = AiExplanationState.Error(
        "The AI explanation is temporarily unavailable. Monitoring and predefined guidance are still available."
    )

    private fun demoContext() = SensorReading(25f, 50f, 100f, 600f, 45f).let { reading ->
        GeminiExplanationContextFactory.create(
            reading,
            EnvironmentalConditionEngine.assess(reading),
            isDemo = true
        )
    }

    private class RecordingTransport(
        private val handler: suspend (attempt: Int, body: String) -> GeminiWorkerHttpResponse
    ) : GeminiWorkerTransport {
        var calls: Int = 0
            private set

        override suspend fun post(jsonBody: String): GeminiWorkerHttpResponse {
            calls++
            return handler(calls, jsonBody)
        }
    }
}
