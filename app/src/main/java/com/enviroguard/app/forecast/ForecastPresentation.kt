package com.enviroguard.app.forecast

import java.util.Locale

data class ForecastPresentation(
    val title: String,
    val detail: String,
    val showProgress: Boolean
)

object ForecastPresentationFactory {
    fun create(state: ForecastPipelineState): ForecastPresentation = when (state) {
        is ForecastPipelineState.Unavailable -> ForecastPresentation(
            title = "Forecast unavailable",
            detail = "${state.message}\nMeasured trends remain available.",
            showProgress = false
        )
        ForecastPipelineState.Loading -> ForecastPresentation(
            title = "Loading experimental forecast",
            detail = "Loading the four local ONNX models…",
            showProgress = true
        )
        ForecastPipelineState.Ready -> ForecastPresentation(
            title = "Experimental forecast",
            detail = "Models loaded. Running local inference…",
            showProgress = true
        )
        is ForecastPipelineState.Error -> ForecastPresentation(
            title = "Forecast pipeline unavailable",
            detail = "${state.message}\nMeasured trends remain available.",
            showProgress = false
        )
        is ForecastPipelineState.Success -> success(state)
    }

    private fun success(state: ForecastPipelineState.Success): ForecastPresentation {
        val result = state.result
        return ForecastPresentation(
            title = "Experimental forecast",
            detail = buildString {
                appendLine("Approximate ${result.horizonMinutes}-minute Random Forest forecast from continuous measured history.")
                appendLine()
                appendLine("Heat Index: ${decimal(result.heatIndexCelsius)} °C")
                appendLine("TVOC: ${decimal(result.tvocPpb)} ppb")
                appendLine("eCO2 equivalent: ${decimal(result.eco2EquivalentPpm)} ppm")
                appendLine("Estimated Noise Level: ${decimal(result.estimatedNoiseLevel)} estimated units")
                appendLine()
                append("Experimental only: this pilot model performed worse than persistence. Current measurements, guidance, and alerts remain authoritative.")
            },
            showProgress = false
        )
    }

    private fun decimal(value: Float) = String.format(Locale.US, "%.1f", value)
}
