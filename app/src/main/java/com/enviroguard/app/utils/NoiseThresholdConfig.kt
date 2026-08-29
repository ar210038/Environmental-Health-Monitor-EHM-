package com.enviroguard.app.utils

/** Project operational thresholds for the INMP441's estimated dB reading. */
object NoiseThresholdConfig {
    const val SMOOTHING_WINDOW_SAMPLES = 12
    const val CAUTION_MAX_DB = 55f
    const val HIGH_RISK_MAX_DB = 70f
    const val CRITICAL_MAX_DB = 85f
}
