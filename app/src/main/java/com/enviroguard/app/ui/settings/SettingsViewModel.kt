package com.enviroguard.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.data.DeviceManager
import kotlinx.coroutines.launch

class SettingsViewModel(
    context: Context,
    private val repository: SensorRepository
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("enviroguard_prefs", Context.MODE_PRIVATE)

    private val _notificationsEnabled = MutableLiveData(
        prefs.getBoolean("notifications_enabled", true)
    )
    val notificationsEnabled: LiveData<Boolean> = _notificationsEnabled

    private val _demoMode = MutableLiveData(
        prefs.getBoolean("demo_mode", true)
    )
    val demoMode: LiveData<Boolean> = _demoMode

    private val _useCelsius = MutableLiveData(
        prefs.getBoolean("use_celsius", true)
    )
    val useCelsius: LiveData<Boolean> = _useCelsius

    private val _ersThreshold = MutableLiveData(
        prefs.getInt("ers_threshold", 50)
    )
    val ersThreshold: LiveData<Int> = _ersThreshold

    private val _tvocThreshold = MutableLiveData(
        prefs.getInt("tvoc_threshold", 220)
    )
    val tvocThreshold: LiveData<Int> = _tvocThreshold

    private val _eco2Threshold = MutableLiveData(
        prefs.getInt("eco2_threshold", 1000)
    )
    val eco2Threshold: LiveData<Int> = _eco2Threshold

    private val _noiseThreshold = MutableLiveData(
        prefs.getInt("noise_threshold", 55)
    )
    val noiseThreshold: LiveData<Int> = _noiseThreshold

    private val _collectedSamples = MutableLiveData(0)
    val collectedSamples: LiveData<Int> = _collectedSamples

    init {
        viewModelScope.launch {
            repository.observeDatasetCount().collect { _collectedSamples.value = it }
        }
    }

    private val _collectionSessionId = MutableLiveData(DeviceManager.activeCollectionSessionId)
    val collectionSessionId: LiveData<String?> = _collectionSessionId

    fun toggleCollectionSession() {
        _collectionSessionId.value = if (_collectionSessionId.value == null) {
            DeviceManager.startCollectionSession()
        } else {
            DeviceManager.stopCollectionSession()
            null
        }
    }

    suspend fun getDatasetReadings() = repository.getDatasetReadings()

    fun setNotifications(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun setDemoMode(enabled: Boolean) {
        _demoMode.value = enabled
        prefs.edit().putBoolean("demo_mode", enabled).apply()
    }

    fun setUseCelsius(celsius: Boolean) {
        _useCelsius.value = celsius
        prefs.edit().putBoolean("use_celsius", celsius).apply()
    }

    fun setErsThreshold(value: Int) {
        _ersThreshold.value = value
        prefs.edit().putInt("ers_threshold", value).apply()
    }

    fun setTvocThreshold(value: Int) {
        _tvocThreshold.value = value
        prefs.edit().putInt("tvoc_threshold", value).apply()
    }

    fun setEco2Threshold(value: Int) {
        _eco2Threshold.value = value
        prefs.edit().putInt("eco2_threshold", value).apply()
    }

    fun setNoiseThreshold(value: Int) {
        _noiseThreshold.value = value
        prefs.edit().putInt("noise_threshold", value).apply()
    }
}
