package com.enviroguard.app.model

object MlFeatureConfig {
    val CONSERVATIVE_RAW_FEATURES = listOf("temperature", "humidity", "tvoc", "noise_db", "eco2")

    // Optional, non-ERS-derived candidates. Feature selection remains configurable.
    val CANDIDATE_ENGINEERED_FEATURES = listOf("heat_index", "time_of_day")
    const val TARGET_COLUMN = "condition_class"
    val FORBIDDEN_ERS_DERIVED_FEATURES = setOf("ers", "ersClass", "rolling_avg_ers", "dominant_factor")
}
