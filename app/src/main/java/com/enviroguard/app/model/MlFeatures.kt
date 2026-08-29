package com.enviroguard.app.model

data class MlFeatures(
    val temperature: Float,
    val humidity: Float,
    val tvoc: Float,
    val eco2: Float,
    val noiseDb: Float,
    val heatIndex: Float,
    val timeOfDay: Float,
    val rollingAvgErs: Float,
    val dominantFactor: Float
) {
    fun valuesFor(featureNames: List<String>): List<Float> = featureNames.map { name ->
        when (name) {
            "temperature" -> temperature
            "humidity" -> humidity
            "tvoc" -> tvoc
            "eco2" -> eco2
            "noise_db" -> noiseDb
            "heat_index" -> heatIndex
            "time_of_day" -> timeOfDay
            "rolling_avg_ers" -> rollingAvgErs
            "dominant_factor" -> dominantFactor
            else -> throw IllegalArgumentException("Unknown ML feature: $name")
        }
    }
}
