package com.enviroguard.app.ai

import android.util.Log

/** Debug-only Worker failure details. Never logs request JSON, environmental context, or credentials. */
internal object GeminiDebugDiagnostics {
    private const val TAG = "EHM-Gemini"
    private const val MAX_MESSAGE_LENGTH = 300
    private val labelledSecret = Regex(
        "(?i)(api[ _-]?key|app[ _-]?check|authorization|bearer|token|password|credential|wi-?fi|pop)(\\s*[:=]\\s*)([^\\s,;]+)"
    )
    private val googleApiKey = Regex("AIza[0-9A-Za-z_-]{20,}")
    private val jwt = Regex("eyJ[0-9A-Za-z_-]+\\.[0-9A-Za-z_-]+\\.[0-9A-Za-z_-]+")
    private val longOpaqueValue = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{40,}(?![A-Za-z0-9_-])")
    private val urlQuery = Regex("(https?://[^\\s?]+)\\?[^\\s]+")

    fun log(error: Throwable) {
        val details = format(error)
        runCatching { Log.e(TAG, details) }
    }

    internal fun format(error: Throwable): String {
        val status = throwableChain(error)
            .filterIsInstance<GeminiWorkerHttpException>()
            .firstOrNull()
            ?.statusCode
            ?.toString()
            ?: "unavailable"
        val message = sanitize(error.message)
        return "httpStatus=$status; exception=${error.javaClass.name}; message=$message"
    }

    private fun throwableChain(error: Throwable): Sequence<Throwable> = sequence {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            yield(current)
            current = current.cause
            depth++
        }
    }

    private fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return "No safe message provided"
        return raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(urlQuery, "$1?[REDACTED]")
            .replace(labelledSecret, "$1$2[REDACTED]")
            .replace(googleApiKey, "[REDACTED]")
            .replace(jwt, "[REDACTED]")
            .replace(longOpaqueValue, "[REDACTED]")
            .trim()
            .take(MAX_MESSAGE_LENGTH)
    }
}
