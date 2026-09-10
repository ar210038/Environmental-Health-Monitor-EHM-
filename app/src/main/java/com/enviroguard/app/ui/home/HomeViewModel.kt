package com.enviroguard.app.ui.home

import androidx.lifecycle.*
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.demo.ScenarioTestManager
import com.enviroguard.app.model.*
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import com.enviroguard.app.alerts.EnvironmentalAlertManager
import com.enviroguard.app.ai.AiExplanationState
import com.enviroguard.app.ai.GeminiExplanationContextFactory
import com.enviroguard.app.ai.GeminiExplanationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

class HomeViewModel(
    private val repository: SensorRepository,
    private val alertManager: EnvironmentalAlertManager? = null,
    private val geminiExplanationService: GeminiExplanationService? = null
) : ViewModel() {
    private val _sensorReading = MutableLiveData<SensorReading?>()
    val sensorReading: LiveData<SensorReading?> = _sensorReading
    private val _assessment = MutableLiveData<EnvironmentalAssessment?>()
    val assessment: LiveData<EnvironmentalAssessment?> = _assessment
    private val _status = MutableLiveData("No Device")
    val status: LiveData<String> = _status
    private val _aiExplanationState = MutableLiveData<AiExplanationState>(AiExplanationState.Idle)
    val aiExplanationState: LiveData<AiExplanationState> = _aiExplanationState
    private var listener: Job? = null
    private var aiRequest: Job? = null
    fun initialise() {
        listener?.cancel()
        if (DeviceManager.isDemoMode) {
            val scenarioReading = ScenarioTestManager.currentReading(demoModeEnabled = true)
            show(scenarioReading ?: repository.getDemoReading(), if (scenarioReading == null) "Demo Mode" else "Scenario Test")
        }
        else DeviceManager.activeDeviceId?.let { id ->
            repository.ensureHistorySynchronization(id)
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
    private fun show(reading: SensorReading, status: String) {
        val currentAssessment = EnvironmentalConditionEngine.assess(reading)
        _sensorReading.value = reading
        _assessment.value = currentAssessment
        _status.value = status
        alertManager?.evaluate(currentAssessment, reading, DeviceManager.isDemoMode)
    }

    fun requestAiExplanation() {
        if (_aiExplanationState.value == AiExplanationState.Loading) return
        val reading = _sensorReading.value
        val currentAssessment = _assessment.value
        if (reading == null || currentAssessment?.overallCondition == null) {
            _aiExplanationState.value = AiExplanationState.Error("A current environmental assessment is needed before requesting an AI explanation.")
            return
        }
        val service = geminiExplanationService
        if (service == null) {
            _aiExplanationState.value = AiExplanationState.Error("The optional AI explanation service is unavailable.")
            return
        }
        val context = GeminiExplanationContextFactory.create(
            reading = reading,
            assessment = currentAssessment,
            isDemo = DeviceManager.isDemoMode
        )
        aiRequest?.cancel()
        aiRequest = viewModelScope.launch {
            service.explain(context).collect(_aiExplanationState::setValue)
        }
    }

    fun refreshSettings() = initialise()
}
