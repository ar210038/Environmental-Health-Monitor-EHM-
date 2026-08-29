package com.enviroguard.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.data.repository.SensorRepository

class SettingsViewModelFactory(
    private val context: Context,
    private val repository: SensorRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(context, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel")
    }
}
