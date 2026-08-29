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
import com.enviroguard.app.utils.TimeRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.LocalDate

class HistoryViewModel(private val repository: SensorRepository) : ViewModel() {

    // 0=Temperature 1=Humidity 2=TVOC 3=eCO2 4=Noise 5=Heat Index
    private val _selectedSensor = MutableLiveData(0)
    val selectedSensor: LiveData<Int> = _selectedSensor

    // 0=Today 1=7Days 2=30Days 3=Custom
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

    private val _chartTitle = MutableLiveData("Temperature — Today")
    val chartTitle: LiveData<String> = _chartTitle

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    // Job cancellation — prevents multiple simultaneous collectors
    private var dataJob: Job? = null
    private var customRange: TimeRange? = null

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

    fun selectCustomRange(startDate: LocalDate, endDateInclusive: LocalDate) {
        customRange = DateRangeUtils.dates(startDate, endDateInclusive)
        _selectedRange.value = 3
        _chartTitle.value = "${sensorName()} — ${startDate} to ${endDateInclusive}"
        loadData()
    }

    fun refreshTemperatureUnit() {
        if (_selectedSensor.value == 0) loadData()
    }

    private fun loadData() {
        // Cancel previous job before starting new one
        dataJob?.cancel()

        dataJob = viewModelScope.launch {
            val range = getTimeRange(_selectedRange.value ?: 0) ?: return@launch
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
            2    -> reading.tvoc
            3    -> reading.eco2
            4    -> reading.noiseLevel
            5    -> HeatIndex.calculateCelsius(reading.temperature, reading.humidity) ?: Float.NaN
            else -> reading.temperature
        }
    }

    fun getUnit(): String {
        return when (_selectedSensor.value) {
            0    -> if (DeviceManager.useCelsius) "°C" else "°F"
            1    -> "%"
            2    -> " ppb"
            3    -> " ppm"
            4    -> " dB"
            5    -> "°C"
            else -> ""
        }
    }

    fun getChartColor(): String {
        return when (_selectedSensor.value) {
            0    -> "#0F6E56"
            1    -> "#BA7517"
            2    -> "#993C1D"
            3    -> "#993C1D"
            4    -> "#1A6B8A"
            else -> "#1A6B8A"
        }
    }

    private fun updateChartTitle() {
        val rangeName = listOf(
            "Today", "Last 7 Days", "Last 30 Days", "Custom"
        )[_selectedRange.value ?: 0]

        _chartTitle.value = "${sensorName()} — $rangeName"
    }

    private fun sensorName(): String = listOf(
        "Temperature", "Humidity", "TVOC", "eCO₂", "Estimated Noise", "Heat Index"
    )[_selectedSensor.value ?: 0]

    private fun getTimeRange(range: Int): TimeRange? = when (range) {
        0 -> DateRangeUtils.today()
        1 -> DateRangeUtils.last7Days()
        2 -> DateRangeUtils.last30Days()
        else -> customRange
    }

    // ── X AXIS LABEL FORMATTER ───────────────────────────────
    fun getXAxisLabel(timestamp: Long, range: Int): String {
        val format = when (range) {
            0    -> SimpleDateFormat("HH:mm", Locale.getDefault())
            1    -> SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            else -> SimpleDateFormat("MM/dd", Locale.getDefault())
        }
        return format.format(Date(timestamp))
    }

    override fun onCleared() {
        super.onCleared()
        dataJob?.cancel()
    }
}
