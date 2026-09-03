package com.enviroguard.app.forecast

import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension

enum class ForecastSource {
    SMOKE_TEST,
    FINAL_MODEL
}

data class ForecastResult(
    val heatIndexCelsius: Float,
    val tvocPpb: Float,
    val eco2EquivalentPpm: Float,
    val estimatedNoiseLevelDb: Float,
    val horizonMinutes: Int = ForecastModelConfig.HORIZON_MINUTES,
    val source: ForecastSource = ForecastModelConfig.SOURCE
)

data class ForecastEnvironmentalAssessment(
    val thermalCondition: EnvironmentalCondition?,
    val tvocCondition: EnvironmentalCondition?,
    val eco2Condition: EnvironmentalCondition?,
    val airCondition: EnvironmentalCondition?,
    val noiseCondition: EnvironmentalCondition?,
    val overallCondition: EnvironmentalCondition?,
    val primaryConcerns: List<EnvironmentalDimension>
)

sealed interface ForecastPipelineState {
    data class Unavailable(val message: String) : ForecastPipelineState
    data object Loading : ForecastPipelineState
    data object Ready : ForecastPipelineState
    data class Success(
        val result: ForecastResult,
        val assessment: ForecastEnvironmentalAssessment
    ) : ForecastPipelineState
    data class Error(val message: String) : ForecastPipelineState
}

object ForecastModelConfig {
    const val ASSET_PATH = "models/ehm_forecast_smoke_test.onnx"
    const val INPUT_NAME = "features"
    const val OUTPUT_NAME = "forecast"
    const val SCHEMA_VERSION = "smoke-test-v1"
    const val FEATURE_COUNT = 8
    const val HORIZON_MINUTES = 60
    val SOURCE = ForecastSource.SMOKE_TEST
}

enum class ForecastFeature(val index: Int, val wireName: String) {
    TEMPERATURE_CELSIUS(0, "temperature_celsius"),
    HUMIDITY_PERCENT(1, "humidity_percent"),
    HEAT_INDEX_CELSIUS(2, "heat_index_celsius"),
    TVOC_PPB(3, "tvoc_ppb"),
    ECO2_EQUIVALENT_PPM(4, "eco2_equivalent_ppm"),
    ESTIMATED_NOISE_LEVEL_DB(5, "estimated_noise_level_db"),
    HOUR_OF_DAY(6, "hour_of_day"),
    ISO_DAY_OF_WEEK(7, "iso_day_of_week")
}

object ForecastFeatureSchema {
    val orderedFeatures: List<ForecastFeature> = ForecastFeature.entries.sortedBy(ForecastFeature::index)
    val names: List<String> = orderedFeatures.map(ForecastFeature::wireName)
    const val featureCount: Int = ForecastModelConfig.FEATURE_COUNT

    init {
        check(orderedFeatures.size == featureCount) {
            "Forecast feature count does not match the configured model contract."
        }
        check(orderedFeatures.map(ForecastFeature::index) == orderedFeatures.indices.toList()) {
            "Forecast feature indexes must be contiguous and ordered."
        }
    }

    fun validate(features: FloatArray) {
        require(features.size == featureCount) {
            "Expected $featureCount forecast features but received ${features.size}."
        }
        val invalid = features.indexOfFirst { !it.isFinite() }
        require(invalid < 0) {
            "Forecast feature ${orderedFeatures[invalid].wireName} is unavailable."
        }
    }
}
