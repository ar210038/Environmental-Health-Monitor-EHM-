package com.enviroguard.app.alerts

import com.enviroguard.app.model.*

data class AlertDecision(val title: String, val body: String)

/** Timestamp-based debounce: a transient reading never immediately notifies. */
class AlertDecisionEngine {
    private var elevatedSince: Long? = null
    private var lastAlert: Long = Long.MIN_VALUE
    fun evaluate(assessment: EnvironmentalAssessment, now: Long, enabled: Boolean, isDemo: Boolean): AlertDecision? {
        if (!enabled || isDemo || assessment.overallCondition == EnvironmentalCondition.GOOD) { elevatedSince = null; return null }
        val required = when (assessment.overallCondition) { EnvironmentalCondition.MODERATE -> 5*60_000L; EnvironmentalCondition.POOR -> 3*60_000L; EnvironmentalCondition.CRITICAL -> 10_000L; else -> Long.MAX_VALUE }
        val start = elevatedSince ?: now.also { elevatedSince = it }
        if (now-start < required || now-lastAlert < 20*60_000L) return null
        lastAlert = now
        val concerns = assessment.primaryConcerns.joinToString { it.displayName }
        return AlertDecision("EHM: ${assessment.overallCondition.displayName}", "$concerns condition has persisted. Check Health Guidance for practical actions.")
    }
}
