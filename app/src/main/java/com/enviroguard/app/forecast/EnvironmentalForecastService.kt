package com.enviroguard.app.forecast

import com.enviroguard.app.data.local.entity.SensorReadingEntity
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class EnvironmentalForecastService(
    private val inference: ForecastInference,
    private val featureBuilder: TimeSeriesFeatureBuilder = TimeSeriesFeatureBuilder()
) {
    fun forecast(
        history: List<SensorReadingEntity>,
        zoneId: ZoneId = ZoneId.of(ForecastModelConfig.TIME_ZONE)
    ): Flow<ForecastPipelineState> = flow {
        emit(ForecastPipelineState.Loading)
        try {
            val features = featureBuilder.build(history, zoneId)
            inference.initialize()
            emit(ForecastPipelineState.Ready)
            val result = inference.predict(features)
            emit(ForecastPipelineState.Success(result, ForecastAssessmentAdapter.assess(result)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            emit(ForecastPipelineState.Unavailable(error.message ?: "Required forecast input is unavailable."))
        } catch (error: ForecastModelException) {
            emit(ForecastPipelineState.Error(error.message ?: "The experimental forecast pipeline is unavailable."))
        } catch (_: Exception) {
            emit(ForecastPipelineState.Error("The experimental forecast pipeline is unavailable. Measured trends remain available."))
        }
    }
}
