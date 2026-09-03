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
            title = "Loading forecast pipeline test",
            detail = "Loading the local test-only ONNX model…",
            showProgress = true
        )
        ForecastPipelineState.Ready -> ForecastPresentation(
            title = "Forecast pipeline test",
            detail = "Local test model loaded. Running inference…",
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
        val sourceNotice = when (result.source) {
            ForecastSource.SMOKE_TEST ->
                "Android ONNX pipeline verification only — not based on the final trained environmental model."
            ForecastSource.FINAL_MODEL ->
                "Approximately ${result.horizonMinutes}-minute model forecast. Keep current measurements separate."
        }
        val assessment = state.assessment
        val concerns = assessment.primaryConcerns
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" and ") { it.displayName }
            ?: "None at the highest unfavorable level"
        return ForecastPresentation(
            title = if (result.source == ForecastSource.SMOKE_TEST) "Test forecast" else "Next-hour forecast",
            detail = buildString {
                appendLine(sourceNotice)
                appendLine()
                appendLine("Heat Index: ${decimal(result.heatIndexCelsius)} °C")
                appendLine("TVOC: ${decimal(result.tvocPpb)} ppb")
                appendLine("eCO2 equivalent: ${decimal(result.eco2EquivalentPpm)} ppm")
                appendLine("Estimated Noise Level: ${decimal(result.estimatedNoiseLevelDb)} dB")
                appendLine()
                appendLine("Rule-based forecast condition: ${assessment.overallCondition?.displayName ?: "Unavailable"}")
                append("Forecast primary concerns: $concerns")
            },
            showProgress = false
        )
    }

    private fun decimal(value: Float) = String.format(Locale.US, "%.1f", value)
}
