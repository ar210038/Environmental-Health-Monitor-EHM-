package com.enviroguard.app.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

sealed interface AiExplanationState {
    data object Idle : AiExplanationState
    data object Loading : AiExplanationState
    data class Success(val text: String, val isDemo: Boolean) : AiExplanationState
    data class Error(val message: String) : AiExplanationState
}

fun interface GeminiExplanationClient {
    suspend fun explain(request: GeminiWorkerRequest): String
}

class GeminiExplanationService(
    private val client: GeminiExplanationClient = GeminiWorkerClient(),
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MS
) {
    fun explain(context: GeminiExplanationContext): Flow<AiExplanationState> = flow {
        emit(AiExplanationState.Loading)
        val result = try {
            val request = GeminiWorkerRequestMapper.from(context)
            val text = withTimeout(timeoutMillis) { client.explain(request) }.trim()
            if (text.isEmpty()) {
                AiExplanationState.Error("The AI service returned no explanation. Please try again.")
            } else {
                AiExplanationState.Success(text, context.isDemo)
            }
        } catch (error: TimeoutCancellationException) {
            GeminiDebugDiagnostics.log(error)
            AiExplanationState.Error("The AI explanation timed out. Please try again.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            GeminiDebugDiagnostics.log(error)
            AiExplanationState.Error("No network connection is available for the AI explanation.")
        } catch (error: Exception) {
            GeminiDebugDiagnostics.log(error)
            AiExplanationState.Error("The AI explanation is temporarily unavailable. Monitoring and predefined guidance are still available.")
        }
        emit(result)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
