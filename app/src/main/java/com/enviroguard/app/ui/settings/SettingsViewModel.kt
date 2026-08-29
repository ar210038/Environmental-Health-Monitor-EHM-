package com.enviroguard.app.ui.settings
import android.content.Context
import androidx.lifecycle.*
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.repository.SensorRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class SettingsViewModel(context: Context, private val repository: SensorRepository) : ViewModel() {
    private val prefs=context.getSharedPreferences("enviroguard_prefs",Context.MODE_PRIVATE)
    val notificationsEnabled=MutableLiveData(DeviceManager.notificationsEnabled)
    val demoMode=MutableLiveData(DeviceManager.isDemoMode)
    val useCelsius=MutableLiveData(DeviceManager.useCelsius)
    val collectedSamples=MutableLiveData(0)
    init { viewModelScope.launch { repository.observeDatasetCount().collect { collectedSamples.value=it } } }
    fun setNotifications(value:Boolean){DeviceManager.notificationsEnabled=value;notificationsEnabled.value=value}
    fun setDemoMode(value:Boolean){DeviceManager.isDemoMode=value;demoMode.value=value}
    fun setUseCelsius(value:Boolean){DeviceManager.useCelsius=value;useCelsius.value=value}
    suspend fun getDatasetReadings()=repository.getDatasetReadings()
}
