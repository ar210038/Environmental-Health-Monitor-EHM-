package com.enviroguard.app.dataset

import com.enviroguard.app.data.local.entity.SensorReadingEntity

/** Chronological raw export for future forecasting. No condition or legacy score labels are exported. */
object DatasetCsvExporter {
    val columns = listOf("timestamp", "deviceId", "temperature", "humidity", "tvoc", "eco2", "noise_level")
    fun generate(readings: List<SensorReadingEntity>) = buildString {
        append(columns.joinToString(",")).append('\n')
        readings.sortedBy { it.timestamp }.forEach { r -> append(listOf(r.timestamp, csv(r.deviceId), r.temperature, r.humidity, r.tvoc, r.eco2, r.noiseLevel).joinToString(",") { it?.toString().orEmpty() }).append('\n') }
    }
    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' }) "\"${value.replace("\"", "\"\"")}\"" else value
}
