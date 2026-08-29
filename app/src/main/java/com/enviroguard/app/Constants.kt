package com.enviroguard.app

object Constants {

    // Firebase paths
    const val USERS_PATH           = "users"
    const val DEVICES_PATH         = "devices"
    const val SENSOR_READINGS_PATH = "sensor_readings"
    const val RISK_ASSESSMENTS_PATH = "risk_assessments"

    // ERS class boundaries
    const val ERS_GOOD_MAX      = 25
    const val ERS_CAUTION_MAX   = 50
    const val ERS_HIGH_RISK_MAX = 75

    // Project operational thresholds / baseline reference values
    // These are used for ERS calculation only and do not represent
    // certified regulatory limits for any specific standard.
    const val TEMP_MAX_SAFE      = 28.0f   // °C comfortable upper range
    const val HUMIDITY_MIN_SAFE  = 30.0f   // % RH preferred lower bound
    const val HUMIDITY_MAX_SAFE  = 60.0f   // % RH preferred upper bound
    const val TVOC_MAX_SAFE      = 220.0f  // ppb SGP30 baseline reference
    const val ECO2_MAX_SAFE      = 1000.0f // ppm estimated eCO2 threshold
    const val NOISE_MAX_SAFE     = 55.0f   // dB estimated noise reference

    // ERS class labels
    const val CLASS_GOOD      = "Good"
    const val CLASS_CAUTION   = "Caution"
    const val CLASS_HIGH_RISK = "High Risk"
    const val CLASS_CRITICAL  = "Critical"

    // Time of day categories
    const val TIME_NIGHT     = 0  // 00:00–05:59
    const val TIME_MORNING   = 1  // 06:00–11:59
    const val TIME_AFTERNOON = 2  // 12:00–17:59
    const val TIME_EVENING   = 3  // 18:00–23:59

    // Reading interval
    const val READING_INTERVAL_MS = 5000L

    // Demo mode simulated values
    const val DEMO_TEMP     = 29.4f
    const val DEMO_HUMIDITY = 72.0f
    const val DEMO_TVOC     = 145.0f
    const val DEMO_ECO2     = 612.0f
    const val DEMO_NOISE    = 58.0f
}
