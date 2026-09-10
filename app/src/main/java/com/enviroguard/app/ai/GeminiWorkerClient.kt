package com.enviroguard.app.ai

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GeminiWorkerRequest(
    val temperature: Float?,
    val humidity: Float?,
    val heatIndex: Float?,
    val tvoc: Float?,
    val eco2: Float?,
    val noiseLevel: Float?,
    val thermalCondition: String,
    val airCondition: String,
    val noiseCondition: String,
    val overallCondition: String,
    val primaryConcerns: List<String>
)

data class GeminiWorkerResponse(val explanation: String?)

object GeminiWorkerRequestMapper {
    fun from(context: GeminiExplanationContext) = GeminiWorkerRequest(
        temperature = context.measurements.temperatureCelsius.finiteOrNull(),
        humidity = context.measurements.humidityPercent.finiteOrNull(),
        heatIndex = context.measurements.heatIndexCelsius.finiteOrNull(),
        tvoc = context.measurements.tvocPpb.finiteOrNull(),
        eco2 = context.measurements.eco2EquivalentPpm.finiteOrNull(),
        noiseLevel = context.measurements.estimatedNoiseLevelDb.finiteOrNull(),
        thermalCondition = context.assessment.thermalCondition.orUnavailable(),
        airCondition = context.assessment.airCondition.orUnavailable(),
        noiseCondition = context.assessment.noiseCondition.orUnavailable(),
        overallCondition = context.assessment.overallCondition.orUnavailable(),
        primaryConcerns = context.assessment.primaryConcerns.map { it.uppercase(Locale.ROOT) }
    )

    private fun Float?.finiteOrNull() = this?.takeIf(Float::isFinite)
    private fun String?.orUnavailable() = this?.takeIf(String::isNotBlank) ?: "UNAVAILABLE"
}

internal object GeminiWorkerJson {
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    fun encode(request: GeminiWorkerRequest): String = gson.toJson(request)

    fun decodeExplanation(json: String): String = try {
        gson.fromJson(json, GeminiWorkerResponse::class.java)?.explanation.orEmpty()
    } catch (error: Exception) {
        throw GeminiWorkerResponseException("Worker returned an invalid response.", error)
    }
}

internal data class GeminiWorkerHttpResponse(val statusCode: Int, val body: String)

internal fun interface GeminiWorkerTransport {
    suspend fun post(jsonBody: String): GeminiWorkerHttpResponse
}

internal class HttpUrlConnectionGeminiWorkerTransport(
    private val endpoint: String = ENDPOINT,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000
) : GeminiWorkerTransport {
    override suspend fun post(jsonBody: String): GeminiWorkerHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            val requestBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.outputStream.use { it.write(requestBytes) }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            GeminiWorkerHttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://ehm-ai.alrafi210038.workers.dev/"
    }
}

class GeminiWorkerHttpException(
    val statusCode: Int,
    message: String
) : Exception(message)

class GeminiWorkerResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class GeminiWorkerClient(
    private val transport: GeminiWorkerTransport = HttpUrlConnectionGeminiWorkerTransport()
) : GeminiExplanationClient {
    override suspend fun explain(request: GeminiWorkerRequest): String {
        val body = GeminiWorkerJson.encode(request)
        val response = postWithOneRetry(body)
        if (response.statusCode !in 200..299) {
            throw GeminiWorkerHttpException(
                statusCode = response.statusCode,
                message = "Worker returned HTTP ${response.statusCode}."
            )
        }
        return GeminiWorkerJson.decodeExplanation(response.body)
    }

    private suspend fun postWithOneRetry(body: String): GeminiWorkerHttpResponse {
        var firstNetworkFailure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = try {
                transport.post(body)
            } catch (error: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw error
                firstNetworkFailure = error
                return@repeat
            }
            if (!response.isTemporaryFailure() || attempt == MAX_ATTEMPTS - 1) return response
        }
        throw firstNetworkFailure ?: IOException("Worker request failed.")
    }

    private fun GeminiWorkerHttpResponse.isTemporaryFailure() =
        statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            statusCode == 429 ||
            statusCode in 500..599

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
