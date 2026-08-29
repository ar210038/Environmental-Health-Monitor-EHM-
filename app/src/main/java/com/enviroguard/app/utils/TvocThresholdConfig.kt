package com.enviroguard.app.utils

/**
 * Provisional SGP30-TVOC operational thresholds in the sensor's native ppb.
 * They are project thresholds pending literature review, not converted mass guidelines.
 */
object TvocThresholdConfig {
    const val CAUTION_MAX_PPB = 220f
    const val HIGH_RISK_MAX_PPB = 660f
    const val CRITICAL_MAX_PPB = 1_000f
}
