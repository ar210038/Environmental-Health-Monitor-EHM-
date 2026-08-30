package com.enviroguard.app.ui.reports

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.EnvironmentalDimension
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.utils.DateRangeUtils
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

class ReportsViewModel(private val repository: SensorRepository) : ViewModel() {
    private val _selectedPeriod = MutableLiveData(0); val selectedPeriod: LiveData<Int> = _selectedPeriod
    private val _chartPoints = MutableLiveData<List<Pair<Long, Float>>>(emptyList()); val chartPoints: LiveData<List<Pair<Long, Float>>> = _chartPoints
    private val _thermalDuration = MutableLiveData("0 min"); val thermalDuration: LiveData<String> = _thermalDuration
    private val _airDuration = MutableLiveData("0 min"); val airDuration: LiveData<String> = _airDuration
    private val _noiseDuration = MutableLiveData("0 min"); val noiseDuration: LiveData<String> = _noiseDuration
    private val _dominantContributor = MutableLiveData("Insufficient history"); val dominantContributor: LiveData<String> = _dominantContributor
    private val _historicalPattern = MutableLiveData("Not enough historical data yet."); val historicalPattern: LiveData<String> = _historicalPattern
    private val _forecastState = MutableLiveData("Forecast not available yet. The forecasting model will use historical environmental data to estimate conditions approximately one hour ahead."); val forecastState: LiveData<String> = _forecastState
    private val _isEmpty = MutableLiveData(true); val isEmpty: LiveData<Boolean> = _isEmpty
    private var job: Job? = null

    init { selectPeriod(0) }

    fun selectPeriod(period: Int) {
        _selectedPeriod.value = period
        job?.cancel()
        job = viewModelScope.launch {
            val range = when (period) { 0 -> DateRangeUtils.today(); 1 -> DateRangeUtils.last7Days(); else -> DateRangeUtils.last30Days() }
            repository.getReadingsBetween(range.startInclusive, range.endExclusive, DeviceManager.activeDeviceId).collect(::render)
        }
    }

    private fun render(readings: List<SensorReadingEntity>) {
        _isEmpty.value = readings.size < 2
        if (readings.size < 2) {
            _chartPoints.value = emptyList()
            _thermalDuration.value = "0 min"; _airDuration.value = "0 min"; _noiseDuration.value = "0 min"
            _dominantContributor.value = "Insufficient history"
            _historicalPattern.value = "At least two timestamped readings are needed to estimate condition duration."
            return
        }
        val assessments = readings.map { EnvironmentalConditionEngine.assess(it.toReading()) }
        _chartPoints.value = readings.zip(assessments).map { it.first.timestamp to it.second.overallCondition.severity.toFloat() }
        val durations = concernDurations(readings, assessments)
        _thermalDuration.value = formatDuration(durations.getValue(EnvironmentalDimension.THERMAL))
        _airDuration.value = formatDuration(durations.getValue(EnvironmentalDimension.AIR))
        _noiseDuration.value = formatDuration(durations.getValue(EnvironmentalDimension.NOISE))
        val max = durations.values.maxOrNull() ?: 0L
        val leaders = durations.filterValues { it == max && max > 0 }.keys
        _dominantContributor.value = when {
            leaders.isEmpty() -> "No dominant environmental concern detected for this period."
            leaders.size == 1 -> "${leaders.first().displayName} • ${formatDuration(max)}\n${leaders.first().displayName} conditions accounted for the greatest duration of unfavorable measured conditions in this period."
            else -> "${leaders.joinToString(" and ") { it.displayName }} • ${formatDuration(max)}\nThese dimensions were tied for the greatest duration of unfavorable measured conditions in this period."
        }
        _historicalPattern.value = hourlyPattern(readings, assessments)
    }

    internal fun concernDurations(readings: List<SensorReadingEntity>, assessments: List<EnvironmentalAssessment>): Map<EnvironmentalDimension, Long> {
        val result = EnvironmentalDimension.entries.associateWith { 0L }.toMutableMap()
        readings.zipWithNext().forEachIndexed { index, pair ->
            val gap = pair.second.timestamp - pair.first.timestamp
            if (gap in 1..150_000) {
                val assessment = assessments[index]
                if (assessment.thermalCondition.severity > 0) result[EnvironmentalDimension.THERMAL] = result.getValue(EnvironmentalDimension.THERMAL) + gap
                if (assessment.airCondition.severity > 0) result[EnvironmentalDimension.AIR] = result.getValue(EnvironmentalDimension.AIR) + gap
                if (assessment.noiseCondition.severity > 0) result[EnvironmentalDimension.NOISE] = result.getValue(EnvironmentalDimension.NOISE) + gap
            }
        }
        return result
    }

    private fun formatDuration(milliseconds: Long): String = if (milliseconds < 60_000) "< 1 min" else "${milliseconds / 60_000} min"

    private fun hourlyPattern(readings: List<SensorReadingEntity>, assessments: List<EnvironmentalAssessment>): String {
        data class Sample(val dimension: EnvironmentalDimension, val hour: Int, val day: String, val severity: Int)
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val samples = readings.zip(assessments).flatMap { (reading, assessment) ->
            val date = Date(reading.timestamp)
            val hour = SimpleDateFormat("H", Locale.US).format(date).toInt()
            val day = dayFormat.format(date)
            listOf(
                Sample(EnvironmentalDimension.THERMAL, hour, day, assessment.thermalCondition.severity),
                Sample(EnvironmentalDimension.AIR, hour, day, assessment.airCondition.severity),
                Sample(EnvironmentalDimension.NOISE, hour, day, assessment.noiseCondition.severity)
            )
        }
        val recurring = samples.groupBy { it.dimension to it.hour }
            .filterValues { group -> group.map { it.day }.distinct().size >= 3 }
        if (recurring.isEmpty()) return "Not enough historical data yet. Hourly patterns require readings from at least three separate days."
        val best = recurring.maxByOrNull { (_, group) -> group.map { it.severity }.average() } ?: return "Not enough historical data yet."
        val averageSeverity = best.value.map { it.severity }.average()
        if (averageSeverity <= 0.0) return "No recurring unfavorable hourly pattern was detected in the available multi-day history."
        val hourLabel = SimpleDateFormat("h a", Locale.getDefault()).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, best.key.second); set(Calendar.MINUTE, 0) }.time)
        return "${best.key.first.displayName} conditions typically reached their strongest measured advisory level around $hourLabel. This is a historical measurement pattern, not a forecast."
    }

    fun getXAxisLabel(timestamp: Long, period: Int): String = SimpleDateFormat(if (period == 0) "HH:mm" else "MM/dd", Locale.getDefault()).format(Date(timestamp))
    private fun SensorReadingEntity.toReading() = SensorReading(temperature, humidity, tvoc, eco2, noiseLevel, timestamp)
}
