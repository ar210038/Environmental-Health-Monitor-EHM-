package com.enviroguard.app.ai

/** Release builds intentionally expose no Firebase AI diagnostic logging. */
internal object GeminiDebugDiagnostics {
    fun log(@Suppress("UNUSED_PARAMETER") error: Throwable) = Unit
}
