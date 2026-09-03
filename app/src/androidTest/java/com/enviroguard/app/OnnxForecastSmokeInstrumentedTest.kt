package com.enviroguard.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enviroguard.app.forecast.EnvironmentalForecastModel
import com.enviroguard.app.forecast.ForecastSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxForecastSmokeInstrumentedTest {
    @Test
    fun bundledSmokeTestModelLoadsAndRunsFourOutputInference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = EnvironmentalForecastModel(context)
        try {
            model.initialize()
            val result = model.predict(
                floatArrayOf(30f, 70f, 35f, 300f, 900f, 82f, 14f, 3f)
            )

            assertEquals(ForecastSource.SMOKE_TEST, result.source)
            assertTrue(result.heatIndexCelsius.isFinite())
            assertTrue(result.tvocPpb.isFinite())
            assertTrue(result.eco2EquivalentPpm.isFinite())
            assertTrue(result.estimatedNoiseLevelDb.isFinite())
        } finally {
            model.close()
        }
    }
}
