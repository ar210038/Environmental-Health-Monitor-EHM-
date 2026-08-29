package com.enviroguard.app.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.ui.home.HomeViewModel
import com.enviroguard.app.ui.reports.ReportsViewModel
import com.enviroguard.app.ui.history.HistoryViewModel
import com.enviroguard.app.alerts.EnvironmentalAlertManager

class ViewModelFactory(
    private val repository: SensorRepository,
    private val alertManager: EnvironmentalAlertManager? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(repository, alertManager) as T
            modelClass.isAssignableFrom(ReportsViewModel::class.java) ->
                ReportsViewModel(repository) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
