package com.enviroguard.app.ui.history

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.utils.DateRangeUtils
import com.enviroguard.app.utils.TemperatureUtils
import com.enviroguard.app.utils.HeatIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryViewModel(private val repository: SensorRepository) : ViewModel() {

    // 0=Temperature 1=Humidity 2=Heat Index 3=TVOC 4=eCO2 5=Estimated Noise
    private val _selectedSensor = MutableLiveData(0)
    val selectedSensor: LiveData<Int> = _selectedSensor

    // 0=Hour 1=Day 2=Week
    private val _selectedRange = MutableLiveData(0)
    val selectedRange: LiveData<Int> = _selectedRange

    private val _chartPoints = MutableLiveData<List<Pair<Long, Float>>>()
    val chartPoints: LiveData<List<Pair<Long, Float>>> = _chartPoints

    private val _minValue = MutableLiveData("--")
    val minValue: LiveData<String> = _minValue

    private val _avgValue = MutableLiveData("--")
    val avgValue: LiveData<String> = _avgValue

    private val _maxValue = MutableLiveData("--")
    val maxValue: LiveData<String> = _maxValue

    private val _chartTitle = MutableLiveData("Temperature — Hour")
    val chartTitle: LiveData<String> = _chartTitle

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    // Job cancellation — prevents multiple simultaneous collectors
    private var dataJob: Job? = null

    init { loadData() }

    fun selectSensor(sensor: Int) {
        _selectedSensor.value = sensor
        updateChartTitle()
        loadData()
    }

    fun selectRange(range: Int) {
        _selectedRange.value = range
        updateChartTitle()
        loadData()
    }

    fun refreshTemperatureUnit() {
        if (_selectedSensor.value == 0) loadData()
    }

    fun refreshForActiveDevice() = loadData()

    private fun loadData() {
        // Cancel previous job before starting new one
        dataJob?.cancel()

        dataJob = viewModelScope.launch {
            val range = getTimeRange(_selectedRange.value ?: 0)
            DeviceManager.activeDeviceId?.let(repository::ensureHistorySynchronization)
            repository.getReadingsBetween(
                range.startInclusive,
                range.endExclusive,
                DeviceManager.activeDeviceId
            ).collect { list ->
                if (list.isEmpty()) {
                    _isEmpty.value = true
                    _chartPoints.value = emptyList()
                    _minValue.value = "--"
                    _avgValue.value = "--"
                    _maxValue.value = "--"
                    return@collect
                }

                _isEmpty.value = false

                // Calculate stats on full data
                val values = list.map { getValueForSensor(it) }.filter { it.isFinite() }
                if (values.isEmpty()) {
                    _isEmpty.value = true
                    _chartPoints.value = emptyList()
                    _minValue.value = "--"
                    _avgValue.value = "--"
                    _maxValue.value = "--"
                    return@collect
                }
                val unit   = getUnit()
                _minValue.value = "%.1f%s".format(values.min(), unit)
                _avgValue.value = "%.1f%s".format(values.average(), unit)
                _maxValue.value = "%.1f%s".format(values.max(), unit)

                // Downsample for chart — max 200 points
                val sampled = downsample(list, 400)
                _chartPoints.value = sampled.mapNotNull { reading ->
                    getValueForSensor(reading).takeIf { it.isFinite() }?.let { Pair(reading.timestamp, it) }
                }
            }
        }
    }

    // ── DOWNSAMPLE TO MAX N POINTS ────────────────────────────
    private fun downsample(
        list: List<SensorReadingEntity>,
        maxPoints: Int
    ): List<SensorReadingEntity> {
        if (list.size <= maxPoints) return list
        val step = (list.size + maxPoints - 1) / maxPoints
        return list.filterIndexed { index, _ -> index % step == 0 }
    }

    fun getValueForSensor(reading: SensorReadingEntity): Float {
        return when (_selectedSensor.value) {
            0    -> if (DeviceManager.useCelsius) reading.temperature
                    else TemperatureUtils.celsiusToFahrenheit(reading.temperature)
            1    -> reading.humidity
            2    -> HeatIndex.calculateCelsius(reading.temperature, reading.humidity)
            3    -> reading.tvoc
            4    -> reading.eco2
            5    -> reading.noiseLevel
            else -> reading.temperature
        }
    }

    fun getUnit(): String {
        return when (_selectedSensor.value) {
            0    -> if (DeviceManager.useCelsius) "°C" else "°F"
            1    -> "%"
            2    -> "°C"
            3    -> " ppb"
            4    -> " ppm"
            5    -> " dB estimated"
            else -> ""
        }
    }

    private fun updateChartTitle() {
        val rangeName = listOf(
            "Hour", "Day", "Week"
        )[_selectedRange.value ?: 0]

        _chartTitle.value = "${sensorName()} — $rangeName"
    }

    private fun sensorName(): String = listOf(
        "Temperature", "Humidity", "Heat Index", "TVOC", "eCO2 equivalent", "Estimated Noise Level"
    )[_selectedSensor.value ?: 0]

    private fun getTimeRange(range: Int) = when (range) {
        0 -> DateRangeUtils.lastHour()
        1 -> DateRangeUtils.today()
        else -> DateRangeUtils.last7Days()
    }

    // ── X AXIS LABEL FORMATTER ───────────────────────────────
    fun getXAxisLabel(timestamp: Long, range: Int): String {
        val format = when (range) {
            0, 1 -> SimpleDateFormat("HH:mm", Locale.getDefault())
            else -> SimpleDateFormat("MM/dd", Locale.getDefault())
        }
        return format.format(Date(timestamp))
    }

    override fun onCleared() {
        super.onCleared()
        dataJob?.cancel()
    }
}
