package com.enviroguard.app.ui.reports

import androidx.lifecycle.*
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.model.*
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import com.enviroguard.app.utils.DateRangeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ReportsViewModel(private val repository: SensorRepository) : ViewModel() {
    private val _selectedPeriod = MutableLiveData(0); val selectedPeriod: LiveData<Int> = _selectedPeriod
    private val _chartPoints = MutableLiveData<List<Pair<Long, Float>>>(); val chartPoints: LiveData<List<Pair<Long, Float>>> = _chartPoints
    private val _avgErs = MutableLiveData("Not enough historical data yet."); val avgErs: LiveData<String> = _avgErs
    private val _peakTime = MutableLiveData("--"); val peakTime: LiveData<String> = _peakTime
    private val _safestTime = MutableLiveData("--"); val safestTime: LiveData<String> = _safestTime
    private val _highRiskDuration = MutableLiveData("Forecast not available yet."); val highRiskDuration: LiveData<String> = _highRiskDuration
    private val _patternTitle = MutableLiveData("Trends & Forecast"); val patternTitle: LiveData<String> = _patternTitle
    private val _patternRecommendation = MutableLiveData("Not enough historical data yet."); val patternRecommendation: LiveData<String> = _patternRecommendation
    private val _isEmpty = MutableLiveData(false); val isEmpty: LiveData<Boolean> = _isEmpty
    private val _chartTitle = MutableLiveData("Environmental Conditions"); val chartTitle: LiveData<String> = _chartTitle
    private var job: Job? = null
    init { selectPeriod(0) }
    fun selectPeriod(period: Int) { _selectedPeriod.value = period; job?.cancel(); job = viewModelScope.launch {
        val range = when(period) { 0 -> DateRangeUtils.today(); 1 -> DateRangeUtils.last7Days(); else -> DateRangeUtils.last30Days() }
        repository.getReadingsBetween(range.startInclusive, range.endExclusive, DeviceManager.activeDeviceId).collect(::render)
    } }
    private fun render(readings: List<SensorReadingEntity>) {
        _isEmpty.value = readings.isEmpty(); if (readings.isEmpty()) { _chartPoints.value=emptyList(); return }
        val assessments = readings.map { EnvironmentalConditionEngine.assess(it.toReading()) }
        _chartPoints.value = readings.zip(assessments).map { it.first.timestamp to it.second.overallCondition.severity.toFloat() }
        val durations = concernDurations(readings, assessments)
        val max = durations.values.maxOrNull() ?: 0L
        val leaders = durations.filterValues { it == max && max > 0 }.keys
        _patternTitle.value = if (leaders.isEmpty()) "No dominant measured environmental contributor" else "Dominant measured environmental contributor: ${leaders.joinToString { it.displayName }}"
        _patternRecommendation.value = durations.entries.joinToString(" • ") { "${it.key.displayName}: ${it.value / 60_000} min" }
        _avgErs.value = "Forecast not available yet."
    }
    private fun concernDurations(readings: List<SensorReadingEntity>, assessments: List<EnvironmentalAssessment>): Map<EnvironmentalDimension,Long> {
        val result = EnvironmentalDimension.entries.associateWith { 0L }.toMutableMap()
        readings.zipWithNext().forEachIndexed { i, pair -> val gap=pair.second.timestamp-pair.first.timestamp; if (gap in 1..150_000) { val a=assessments[i]; if(a.thermalCondition.severity>0) result[EnvironmentalDimension.THERMAL]=result.getValue(EnvironmentalDimension.THERMAL)+gap; if(a.airCondition.severity>0) result[EnvironmentalDimension.AIR]=result.getValue(EnvironmentalDimension.AIR)+gap; if(a.noiseCondition.severity>0) result[EnvironmentalDimension.NOISE]=result.getValue(EnvironmentalDimension.NOISE)+gap } }
        return result
    }
    fun getXAxisLabel(timestamp: Long, period: Int) = java.text.SimpleDateFormat(if(period==0) "HH:mm" else "MM/dd", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    private fun SensorReadingEntity.toReading() = SensorReading(temperature, humidity, tvoc, eco2, noiseLevel, timestamp)
}
