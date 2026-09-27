package com.enviroguard.app.forecast

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ForecastInference {
    suspend fun initialize()
    suspend fun predict(features: FloatArray): ForecastResult
}

class ForecastModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

class EnvironmentalForecastModel(context: Context) : ForecastInference, Closeable {
    private val appContext = context.applicationContext
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()
    @Volatile private var sessions: Map<ForecastTarget, OrtSession> = emptyMap()

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        getOrCreateSessions()
        Unit
    }

    override suspend fun predict(features: FloatArray): ForecastResult = withContext(Dispatchers.Default) {
        ForecastFeatureSchema.validate(features)
        val activeSessions = getOrCreateSessions()
        val values = ForecastTarget.entries.map { target ->
            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(features),
                longArrayOf(1, ForecastFeatureSchema.featureCount.toLong())
            )
            tensor.use {
                activeSessions.getValue(target)
                    .run(mapOf(ForecastModelConfig.INPUT_NAME to it)).use { outputs ->
                        val output = outputs.get(ForecastModelConfig.OUTPUT_NAME).orElseThrow {
                            ForecastModelException("The ${target.name} forecast output is missing.")
                        } as? OnnxTensor
                            ?: throw ForecastModelException("The ${target.name} forecast output is not a tensor.")
                        val buffer = output.floatBuffer
                        if (buffer.remaining() != 1) {
                            throw ForecastModelException("The ${target.name} model returned an unexpected output size.")
                        }
                        buffer.get()
                    }
            }
        }.toFloatArray()
        ForecastOutputDecoder.decode(values)
    }

    private fun getOrCreateSessions(): Map<ForecastTarget, OrtSession> {
        sessions.takeIf { it.size == ForecastTarget.entries.size }?.let { return it }
        return synchronized(lock) {
            sessions.takeIf { it.size == ForecastTarget.entries.size } ?: createValidatedSessions().also {
                sessions = it
            }
        }
    }

    private fun createValidatedSessions(): Map<ForecastTarget, OrtSession> {
        val created = linkedMapOf<ForecastTarget, OrtSession>()
        try {
            ForecastTarget.entries.forEach { target ->
                val modelBytes = appContext.assets.open(target.assetPath).use { it.readBytes() }
                val session = OrtSession.SessionOptions().use { options ->
                    environment.createSession(modelBytes, options)
                }
                validateContract(session, target)
                created[target] = session
            }
        } catch (error: Exception) {
            created.values.forEach(OrtSession::close)
            if (error is ForecastModelException) throw error
            throw ForecastModelException("The experimental forecast models could not be loaded.", error)
        }
        return created
    }

    private fun validateContract(created: OrtSession, target: ForecastTarget) {
        requireTensorShape(
            created.inputInfo[ForecastModelConfig.INPUT_NAME]?.info,
            expectedLastDimension = ForecastFeatureSchema.featureCount,
            label = "${target.name} input"
        )
        requireTensorShape(
            created.outputInfo[ForecastModelConfig.OUTPUT_NAME]?.info,
            expectedLastDimension = 1,
            label = "${target.name} output"
        )
    }

    private fun requireTensorShape(info: Any?, expectedLastDimension: Int, label: String) {
        val tensorInfo = info as? TensorInfo
            ?: throw ForecastModelException("The configured forecast $label tensor is missing.")
        val shape = tensorInfo.shape
        if (tensorInfo.type != OnnxJavaType.FLOAT || shape.size != 2 ||
            shape.last() != expectedLastDimension.toLong() || shape.first() !in listOf(-1L, 1L)
        ) {
            throw ForecastModelException(
                "Unexpected forecast $label contract: type=${tensorInfo.type}, shape=${shape.contentToString()}."
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            sessions.values.forEach(OrtSession::close)
            sessions = emptyMap()
        }
    }
}
