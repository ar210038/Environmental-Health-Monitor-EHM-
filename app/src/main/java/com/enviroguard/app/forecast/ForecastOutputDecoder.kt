package com.enviroguard.app.forecast

object ForecastOutputDecoder {
    const val OUTPUT_COUNT = 4

    fun decode(
        values: FloatArray,
        horizonMinutes: Int = ForecastModelConfig.HORIZON_MINUTES,
        source: ForecastSource = ForecastModelConfig.SOURCE
    ): ForecastResult {
        require(values.size == OUTPUT_COUNT) {
            "Expected $OUTPUT_COUNT forecast outputs but received ${values.size}."
        }
        require(values.all(Float::isFinite)) { "Forecast output contains an unavailable value." }
        return ForecastResult(
            heatIndexCelsius = values[0],
            tvocPpb = values[1],
            eco2EquivalentPpm = values[2],
            estimatedNoiseLevel = values[3],
            horizonMinutes = horizonMinutes,
            source = source
        )
    }
}
