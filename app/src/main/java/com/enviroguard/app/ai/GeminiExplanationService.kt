package com.enviroguard.app.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

object GeminiModelConfig {
    const val MODEL_NAME = "gemini-3.5-flash"
    const val MAX_OUTPUT_TOKENS = 350
    const val REQUEST_TIMEOUT_MS = 25_000L
}

sealed interface AiExplanationState {
    data object Idle : AiExplanationState
    data object Loading : AiExplanationState
    data class Success(val text: String, val isDemo: Boolean) : AiExplanationState
    data class Error(val message: String) : AiExplanationState
}

fun interface GeminiTextGenerator {
    suspend fun generate(prompt: String): String
}

class FirebaseGeminiTextGenerator : GeminiTextGenerator {
    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = GeminiModelConfig.MODEL_NAME,
            generationConfig = generationConfig {
                maxOutputTokens = GeminiModelConfig.MAX_OUTPUT_TOKENS
            },
            systemInstruction = content {
                text(GeminiPromptBuilder.SYSTEM_INSTRUCTION.trimIndent())
            }
        )
    }

    override suspend fun generate(prompt: String): String = model.generateContent(prompt).text.orEmpty()
}

class GeminiExplanationService(
    private val generator: GeminiTextGenerator = FirebaseGeminiTextGenerator(),
    private val timeoutMillis: Long = GeminiModelConfig.REQUEST_TIMEOUT_MS
) {
    fun explain(context: GeminiExplanationContext): Flow<AiExplanationState> = flow {
        emit(AiExplanationState.Loading)
        val result = try {
            val text = withTimeout(timeoutMillis) { generator.generate(GeminiPromptBuilder.buildUserPrompt(context)) }.trim()
            if (text.isEmpty()) {
                AiExplanationState.Error("The AI service returned no explanation. Please try again.")
            } else {
                AiExplanationState.Success(text, context.isDemo)
            }
        } catch (_: TimeoutCancellationException) {
            AiExplanationState.Error("The AI explanation timed out. Please try again.")
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            AiExplanationState.Error("No network connection is available for the AI explanation.")
        } catch (_: Exception) {
            AiExplanationState.Error("The AI explanation is temporarily unavailable. Monitoring and predefined guidance are still available.")
        }
        emit(result)
    }
}
