package com.enviroguard.app.ui.reports

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.utils.DateRangeUtils
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import com.enviroguard.app.utils.EnvironmentalDiagnosticAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.ZoneId

class ReportsViewModel(private val repository: SensorRepository) : ViewModel() {

    private val _selectedPeriod = MutableLiveData(0)
    val selectedPeriod: LiveData<Int> = _selectedPeriod

    private val _chartPoints = MutableLiveData<List<Pair<Long, Float>>>()
    val chartPoints: LiveData<List<Pair<Long, Float>>> = _chartPoints

    private val _avgErs = MutableLiveData("--") // binding-compatible name; contains condition summary.
    val avgErs: LiveData<String> = _avgErs

    private val _peakTime = MutableLiveData("--")
    val peakTime: LiveData<String> = _peakTime

    private val _safestTime = MutableLiveData("--")
    val safestTime: LiveData<String> = _safestTime

    private val _highRiskDuration = MutableLiveData("0 min")
    val highRiskDuration: LiveData<String> = _highRiskDuration

    private val _patternTitle = MutableLiveData("Collecting data...")
    val patternTitle: LiveData<String> = _patternTitle

    private val _patternRecommendation = MutableLiveData(
        "Use the device for a few days to detect patterns."
    )
    val patternRecommendation: LiveData<String> = _patternRecommendation

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val _chartTitle = MutableLiveData("Environmental Condition — Today")
    val chartTitle: LiveData<String> = _chartTitle

    // Job cancellation — prevents multiple simultaneous collectors
    private var dataJob: Job? = null

    init { loadPeriod(0) }

    fun selectPeriod(period: Int) {
        _selectedPeriod.value = period
        _chartTitle.value = when (period) {
            0    -> "Environmental Condition — Today"
            1    -> "Environmental Condition — Last 7 Days"
            else -> "Environmental Condition — Last 30 Days"
        }
        loadPeriod(period)
    }

    private fun loadPeriod(period: Int) {
        // Cancel previous job before starting new one
        dataJob?.cancel()

        dataJob = viewModelScope.launch {
            val range = when (period) {
                0 -> DateRangeUtils.today()
                1 -> DateRangeUtils.last7Days()
                else -> DateRangeUtils.last30Days()
            }
            repository.getReadingsBetween(
                range.startInclusive,
                range.endExclusive,
                DeviceManager.activeDeviceId
            ).collect { list ->
                if (list.isEmpty()) {
                    _isEmpty.value = true
                    resetStats()
                    return@collect
                }
                _isEmpty.value = false
                calculateStats(list, period)
            }
        }
    }

    private fun resetStats() {
        _avgErs.value             = "--"
        _peakTime.value           = "--"
        _safestTime.value         = "--"
        _highRiskDuration.value   = "0 min"
        _chartPoints.value        = emptyList()
        _patternTitle.value       = "Collecting data..."
        _patternRecommendation.value =
            "Use the device for a few days to detect patterns."
    }

    private suspend fun calculateStats(list: List<SensorReadingEntity>, period: Int) {

        val conditions = conditionSeries(list)
        val averageLevel = conditions.map { it.level }.average()
        _avgErs.value = com.enviroguard.app.model.EnvironmentalCondition.fromLevel(averageLevel.toInt()).label

        // Peak and safest values use their actual reading timestamps.
        _peakTime.value = list.zip(conditions).maxByOrNull { it.second.level }?.first
            ?.let { formatTimestamp(it.timestamp, period) } ?: "--"
        _safestTime.value = list.zip(conditions).minByOrNull { it.second.level }?.first
            ?.let { formatTimestamp(it.timestamp, period) } ?: "--"

        // High risk duration — use timestamp differences, not record count
        _highRiskDuration.value = calculateHighRiskDuration(list, conditions)

        // Downsample for chart
        val sampled = downsample(list, 400)
        _chartPoints.value = sampled.map { r -> Pair(r.timestamp, conditions[list.indexOf(r)].level.toFloat()) }

        // Pattern detection
        detectPattern(list)
    }

    // ── HIGH RISK DURATION using timestamp differences ────────
    private fun calculateHighRiskDuration(
        list: List<SensorReadingEntity>, conditions: List<com.enviroguard.app.model.EnvironmentalCondition>
    ): String {
        if (list.size < 2) return "0 min"
        val duration = list.zipWithNext().zip(conditions.zipWithNext()).sumOf { (pair, states) ->
            val gap = pair.second.timestamp - pair.first.timestamp
            if (gap in 1..20_000L && states.first.level >= 2 && states.second.level >= 2) gap else 0L
        }
        val minutes = duration / 60_000L
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    // ── PATTERN DETECTION ─────────────────────────────────────
    private fun detectPattern(list: List<SensorReadingEntity>) {
        val diagnostic = EnvironmentalDiagnosticAnalyzer.analyze(list)
        if (diagnostic.occurrences.isEmpty() || diagnostic.occurrences.all { it.abnormalReadings == 0 }) {
            _patternTitle.value = "No recurring abnormal factor"
            _patternRecommendation.value =
                "No recurring environmental contributor was identified in this period."
            return
        }

        _patternTitle.value = diagnostic.dominantFactor
        _patternRecommendation.value = diagnostic.occurrences.joinToString(" • ") {
            "${it.factor.displayName}: ${it.abnormalReadings} (${"%.1f".format(it.percentage)}%)"
        }
    }

    // ── DOWNSAMPLE ────────────────────────────────────────────
    private fun downsample(
        list: List<SensorReadingEntity>,
        maxPoints: Int
    ): List<SensorReadingEntity> {
        if (list.size <= maxPoints) return list
        val step = (list.size + maxPoints - 1) / maxPoints
        return list.filterIndexed { index, _ -> index % step == 0 }
    }

    private fun formatTimestamp(timestamp: Long, period: Int): String {
        val pattern = if (period == 0) "HH:mm" else "MMM d, HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    }

    private fun conditionSeries(list: List<SensorReadingEntity>) = list.mapIndexed { index, reading ->
        EnvironmentalConditionEngine.assess(
            com.enviroguard.app.model.SensorReading(reading.temperature, reading.humidity, reading.tvoc, reading.eco2, reading.noiseDb, timestamp = reading.timestamp),
            list.subList(0, index + 1).map { it.noiseDb }
        ).overallCondition
    }

    // ── X AXIS LABEL ──────────────────────────────────────────
    fun getXAxisLabel(timestamp: Long, period: Int): String {
        val format = when (period) {
            0    -> SimpleDateFormat("HH:mm", Locale.getDefault())
            1    -> SimpleDateFormat("EEE", Locale.getDefault())
            else -> SimpleDateFormat("MM/dd", Locale.getDefault())
        }
        return format.format(Date(timestamp))
    }

    override fun onCleared() {
        super.onCleared()
        dataJob?.cancel()
    }
}
