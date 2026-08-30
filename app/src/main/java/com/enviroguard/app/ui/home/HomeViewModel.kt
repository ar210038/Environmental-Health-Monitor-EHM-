package com.enviroguard.app.ui.home

import androidx.lifecycle.*
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.model.*
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

class HomeViewModel(private val repository: SensorRepository) : ViewModel() {
    private val _sensorReading = MutableLiveData<SensorReading>()
    val sensorReading: LiveData<SensorReading> = _sensorReading
    private val _assessment = MutableLiveData<EnvironmentalAssessment>()
    val assessment: LiveData<EnvironmentalAssessment> = _assessment
    private val _status = MutableLiveData("No Device")
    val status: LiveData<String> = _status
    private var listener: Job? = null
    fun initialise() {
        listener?.cancel()
        if (DeviceManager.isDemoMode) show(repository.getDemoReading(), "Demo Mode")
        else DeviceManager.activeDeviceId?.let { id ->
            _status.value = "Waiting for environmental data"
            listener = viewModelScope.launch {
                repository.listenToCurrentReading(id)
                    .catch { _status.value = "Environmental data unavailable" }
                    .collect { show(it, "Connected") }
            }
        } ?: run {
            _sensorReading.value = null
            _assessment.value = null
            _status.value = "No Device"
        }
    }
    private fun show(reading: SensorReading, status: String) { _sensorReading.value = reading; _assessment.value = EnvironmentalConditionEngine.assess(reading); _status.value = status }
    fun refreshSettings() = initialise()
}
