package com.enviroguard.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enviroguard.app.forecast.EnvironmentalForecastModel
import com.enviroguard.app.forecast.ForecastSource
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxForecastInstrumentedTest {
    @Test
    fun bundledExperimentalModelsMatchVerifiedPythonOnnxFixture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = context.assets.open("models/android_parity_fixture.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val features = fixture.getAsJsonArray("features_float32")
            .map { it.asFloat }
            .toFloatArray()
        val model = EnvironmentalForecastModel(context)
        try {
            model.initialize()
            val result = model.predict(features)
            val predictions = fixture.getAsJsonObject("predictions")

            assertEquals(ForecastSource.EXPERIMENTAL_V1, result.source)
            assertEquals(predictions.getAsJsonObject("heat_index").get("onnx").asFloat, result.heatIndexCelsius, 0.001f)
            assertEquals(predictions.getAsJsonObject("tvoc").get("onnx").asFloat, result.tvocPpb, 0.001f)
            assertEquals(predictions.getAsJsonObject("eco2").get("onnx").asFloat, result.eco2EquivalentPpm, 0.001f)
            assertEquals(predictions.getAsJsonObject("noise_level").get("onnx").asFloat, result.estimatedNoiseLevel, 0.001f)
            assertTrue(listOf(result.heatIndexCelsius, result.tvocPpb, result.eco2EquivalentPpm, result.estimatedNoiseLevel).all(Float::isFinite))
        } finally {
            model.close()
        }
    }
}
