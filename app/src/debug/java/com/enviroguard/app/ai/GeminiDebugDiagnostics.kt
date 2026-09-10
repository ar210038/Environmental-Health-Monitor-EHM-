package com.enviroguard.app.ai

import android.util.Log

/** Never logs request JSON, environmental context, or credentials. */
internal object GeminiDebugDiagnostics {
    fun log(error: Throwable) {
        runCatching { Log.e("EHM-Gemini", GeminiDiagnosticFormatter.format(error)) }
    }
}
