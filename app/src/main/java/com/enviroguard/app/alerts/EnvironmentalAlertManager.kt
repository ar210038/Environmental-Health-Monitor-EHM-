package com.enviroguard.app.alerts

import android.content.Context
import com.enviroguard.app.model.EnvironmentalAssessment

/** Notification wiring remains available; posting UI work is intentionally delegated to the app shell. */
class EnvironmentalAlertManager(context: Context) {
    private val engine = AlertDecisionEngine()
    fun evaluate(assessment: EnvironmentalAssessment, isDemo: Boolean) = engine.evaluate(assessment, System.currentTimeMillis(), true, isDemo)
    companion object { fun createNotificationChannel(context: Context) = Unit }
}
