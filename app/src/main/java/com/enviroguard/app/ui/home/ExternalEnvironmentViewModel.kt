package com.enviroguard.app.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.enviroguard.app.external.advisory.ExternalAdvisoryEngine
import com.enviroguard.app.external.location.ExternalLocationProvider
import com.enviroguard.app.external.location.ExternalLocationResult
import com.enviroguard.app.external.model.ExternalEnvironmentSnapshot
import com.enviroguard.app.external.model.ExternalEnvironmentState
import com.enviroguard.app.external.repository.ExternalEnvironmentRepository
import com.enviroguard.app.external.repository.ExternalRefreshPreflight
import com.enviroguard.app.external.repository.ExternalRefreshResult
import kotlinx.coroutines.launch

internal class ExternalEnvironmentViewModel(
    private val repository: ExternalEnvironmentRepository,
    private val locationProvider: ExternalLocationProvider
) : ViewModel() {
    private val _state = MutableLiveData<ExternalEnvironmentState>(ExternalEnvironmentState.NotLoaded)
    val state: LiveData<ExternalEnvironmentState> = _state

    private var lastSnapshot: ExternalEnvironmentSnapshot? = null

    fun loadIfNeeded() {
        if (_state.value == ExternalEnvironmentState.Loading) return
        beginRefresh(manual = false)
    }

    fun manualRefresh() {
        if (_state.value == ExternalEnvironmentState.Loading) return
        beginRefresh(manual = true)
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) beginRefresh(manual = false)
        else _state.value = ExternalEnvironmentState.PermissionRequired
    }

    fun retryAfterLocationSettings() = beginRefresh(manual = false)

    private fun beginRefresh(manual: Boolean) {
        when (val preflight = repository.preflight(manual)) {
            is ExternalRefreshPreflight.Fresh -> showSuccess(preflight.snapshot)
            is ExternalRefreshPreflight.Cooldown -> showCooldown(preflight.retryAfterMillis)
            ExternalRefreshPreflight.DailyLimitReached ->
                _state.value = ExternalEnvironmentState.RateLimited(dailyLimitReached = true)
            ExternalRefreshPreflight.Ready -> obtainLocationAndRefresh(manual)
        }
    }

    private fun obtainLocationAndRefresh(manual: Boolean) {
        _state.value = ExternalEnvironmentState.Loading
        viewModelScope.launch {
            when (val location = locationProvider.currentLocation()) {
                ExternalLocationResult.PermissionRequired ->
                    _state.value = ExternalEnvironmentState.PermissionRequired
                ExternalLocationResult.Disabled ->
                    _state.value = ExternalEnvironmentState.LocationDisabled
                ExternalLocationResult.Unavailable ->
                    _state.value = ExternalEnvironmentState.LocationUnavailable
                is ExternalLocationResult.Available ->
                    handleRefreshResult(repository.refresh(location.coordinates, manual))
            }
        }
    }

    private fun handleRefreshResult(result: ExternalRefreshResult) {
        when (result) {
            is ExternalRefreshResult.Success -> showSuccess(result.snapshot)
            is ExternalRefreshResult.Cooldown -> showCooldown(result.retryAfterMillis)
            ExternalRefreshResult.DailyLimitReached ->
                _state.value = ExternalEnvironmentState.RateLimited(dailyLimitReached = true)
            ExternalRefreshResult.ServiceRateLimited ->
                _state.value = ExternalEnvironmentState.RateLimited(dailyLimitReached = false)
            ExternalRefreshResult.Offline -> _state.value = ExternalEnvironmentState.Offline
            ExternalRefreshResult.Error -> _state.value = ExternalEnvironmentState.Error
        }
    }

    private fun showSuccess(snapshot: ExternalEnvironmentSnapshot, notice: String? = null) {
        lastSnapshot = snapshot
        _state.value = ExternalEnvironmentState.Success(
            snapshot = snapshot,
            advisories = ExternalAdvisoryEngine.evaluate(snapshot),
            notice = notice
        )
    }

    private fun showCooldown(retryAfterMillis: Long) {
        val minutes = ((retryAfterMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
        val notice = "Recently refreshed. Try again in $minutes min."
        lastSnapshot?.let { showSuccess(it, notice) }
            ?: run { _state.value = ExternalEnvironmentState.Cooldown(retryAfterMillis) }
    }
}

internal class ExternalEnvironmentViewModelFactory(
    private val repository: ExternalEnvironmentRepository,
    private val locationProvider: ExternalLocationProvider
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExternalEnvironmentViewModel::class.java)) {
            return ExternalEnvironmentViewModel(repository, locationProvider) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
