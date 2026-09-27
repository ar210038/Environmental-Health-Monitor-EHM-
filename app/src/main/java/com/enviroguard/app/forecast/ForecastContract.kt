package com.enviroguard.app.forecast

import com.enviroguard.app.model.EnvironmentalCondition
import com.enviroguard.app.model.EnvironmentalDimension

enum class ForecastSource {
    EXPERIMENTAL_V1
}

data class ForecastResult(
    val heatIndexCelsius: Float,
    val tvocPpb: Float,
    val eco2EquivalentPpm: Float,
    val estimatedNoiseLevel: Float,
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
    const val MODEL_VERSION = "EHM_RF_H60_3032cleanrecords"
    const val INPUT_NAME = "X"
    const val OUTPUT_NAME = "variable"
    const val FEATURE_COUNT = 32
    const val HORIZON_MINUTES = 60
    const val LAG_TOLERANCE_MS = 2 * 60_000L
    const val SESSION_GAP_MS = 3 * 60_000L
    const val HISTORY_LOOKBACK_MS = 40 * 60_000L
    const val TIME_ZONE = "Asia/Dhaka"
    val SOURCE = ForecastSource.EXPERIMENTAL_V1
}

enum class ForecastTarget(val assetPath: String) {
    HEAT_INDEX("models/rf_heat_index.onnx"),
    TVOC("models/rf_tvoc.onnx"),
    ECO2("models/rf_eco2.onnx"),
    NOISE_LEVEL("models/rf_noise_level.onnx")
}

enum class ForecastFeature(val index: Int, val wireName: String) {
    TEMPERATURE(0, "temperature"),
    HUMIDITY(1, "humidity"),
    TVOC(2, "tvoc"),
    ECO2(3, "eco2"),
    NOISE_LEVEL(4, "noise_level"),
    HEAT_INDEX(5, "heat_index"),
    TEMPERATURE_LAG_5M(6, "temperature_lag5m"),
    HUMIDITY_LAG_5M(7, "humidity_lag5m"),
    TVOC_LAG_5M(8, "tvoc_lag5m"),
    ECO2_LAG_5M(9, "eco2_lag5m"),
    NOISE_LEVEL_LAG_5M(10, "noise_level_lag5m"),
    HEAT_INDEX_LAG_5M(11, "heat_index_lag5m"),
    TEMPERATURE_LAG_15M(12, "temperature_lag15m"),
    HUMIDITY_LAG_15M(13, "humidity_lag15m"),
    TVOC_LAG_15M(14, "tvoc_lag15m"),
    ECO2_LAG_15M(15, "eco2_lag15m"),
    NOISE_LEVEL_LAG_15M(16, "noise_level_lag15m"),
    HEAT_INDEX_LAG_15M(17, "heat_index_lag15m"),
    TEMPERATURE_LAG_30M(18, "temperature_lag30m"),
    HUMIDITY_LAG_30M(19, "humidity_lag30m"),
    TVOC_LAG_30M(20, "tvoc_lag30m"),
    ECO2_LAG_30M(21, "eco2_lag30m"),
    NOISE_LEVEL_LAG_30M(22, "noise_level_lag30m"),
    HEAT_INDEX_LAG_30M(23, "heat_index_lag30m"),
    TEMPERATURE_CHANGE_5M(24, "temperature_change5m"),
    HUMIDITY_CHANGE_5M(25, "humidity_change5m"),
    TVOC_CHANGE_5M(26, "tvoc_change5m"),
    ECO2_CHANGE_5M(27, "eco2_change5m"),
    NOISE_LEVEL_CHANGE_5M(28, "noise_level_change5m"),
    HEAT_INDEX_CHANGE_5M(29, "heat_index_change5m"),
    HOUR_SIN(30, "hour_sin"),
    HOUR_COS(31, "hour_cos")
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
