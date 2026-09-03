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
    @Volatile private var session: OrtSession? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        getOrCreateSession()
        Unit
    }

    override suspend fun predict(features: FloatArray): ForecastResult = withContext(Dispatchers.Default) {
        ForecastFeatureSchema.validate(features)
        val activeSession = getOrCreateSession()
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(features),
            longArrayOf(1, ForecastFeatureSchema.featureCount.toLong())
        )
        tensor.use {
            activeSession.run(mapOf(ForecastModelConfig.INPUT_NAME to it)).use { outputs ->
                val output = outputs.get(ForecastModelConfig.OUTPUT_NAME).orElseThrow {
                    ForecastModelException("The configured forecast output is missing.")
                } as? OnnxTensor ?: throw ForecastModelException("The forecast output is not a tensor.")
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                ForecastOutputDecoder.decode(values)
            }
        }
    }

    private fun getOrCreateSession(): OrtSession {
        session?.let { return it }
        return synchronized(lock) {
            session ?: createValidatedSession().also { session = it }
        }
    }

    private fun createValidatedSession(): OrtSession {
        val modelBytes = try {
            appContext.assets.open(ForecastModelConfig.ASSET_PATH).use { it.readBytes() }
        } catch (error: Exception) {
            throw ForecastModelException("The test forecast model asset is unavailable.", error)
        }
        val created = try {
            OrtSession.SessionOptions().use { options -> environment.createSession(modelBytes, options) }
        } catch (error: Exception) {
            throw ForecastModelException("ONNX Runtime could not load the test forecast model.", error)
        }
        try {
            validateMetadata(created)
        } catch (error: Exception) {
            created.close()
            if (error is ForecastModelException) throw error
            throw ForecastModelException("The test forecast model contract is invalid.", error)
        }
        return created
    }

    private fun validateMetadata(created: OrtSession) {
        requireTensorShape(
            created.inputInfo[ForecastModelConfig.INPUT_NAME]?.info,
            expectedLastDimension = ForecastFeatureSchema.featureCount,
            label = "input"
        )
        requireTensorShape(
            created.outputInfo[ForecastModelConfig.OUTPUT_NAME]?.info,
            expectedLastDimension = ForecastOutputDecoder.OUTPUT_COUNT,
            label = "output"
        )
        val metadata = created.metadata.customMetadata
        requireMetadata(metadata, "ehm_model_source", ForecastModelConfig.SOURCE.name)
        requireMetadata(metadata, "ehm_schema_version", ForecastModelConfig.SCHEMA_VERSION)
        requireMetadata(metadata, "ehm_horizon_minutes", ForecastModelConfig.HORIZON_MINUTES.toString())
        requireMetadata(metadata, "ehm_feature_order", ForecastFeatureSchema.names.joinToString(","))
    }

    private fun requireMetadata(metadata: Map<String, String>, key: String, expected: String) {
        if (metadata[key] != expected) {
            throw ForecastModelException("The test forecast model metadata does not match $key.")
        }
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
            session?.close()
            session = null
        }
    }
}
