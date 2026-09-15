package com.enviroguard.app

import android.app.Application
import com.enviroguard.app.data.AuthManager
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.local.EnviroGuardDatabase
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.alerts.EnvironmentalAlertManager
import com.enviroguard.app.ai.GeminiExplanationService
import com.enviroguard.app.forecast.EnvironmentalForecastModel
import com.enviroguard.app.forecast.EnvironmentalForecastService
import com.enviroguard.app.external.location.AndroidExternalLocationProvider
import com.enviroguard.app.external.network.OpenMeteoClient
import com.enviroguard.app.external.repository.DailyRequestQuota
import com.enviroguard.app.external.repository.ExternalEnvironmentRepository
import com.enviroguard.app.external.repository.SharedPreferencesQuotaControlStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EnviroGuardApp : Application() {

    val database by lazy { EnviroGuardDatabase.getInstance(this) }
    val repository by lazy { SensorRepository(database) }
    val alertManager by lazy { EnvironmentalAlertManager(this) }
    val geminiExplanationService by lazy { GeminiExplanationService() }
    private val forecastModelDelegate = lazy { EnvironmentalForecastModel(this) }
    val forecastModel by forecastModelDelegate
    val forecastService by lazy { EnvironmentalForecastService(forecastModel) }
    internal val externalEnvironmentRepository by lazy {
        val preferences = getSharedPreferences("external_request_quota", MODE_PRIVATE)
        ExternalEnvironmentRepository(
            service = OpenMeteoClient(),
            quota = DailyRequestQuota(SharedPreferencesQuotaControlStore(preferences))
        )
    }
    internal val externalLocationProvider by lazy { AndroidExternalLocationProvider(this) }

    override fun onCreate() {
        super.onCreate()

        AppCheckProviderInstaller.install()

        // Init device manager
        DeviceManager.init(this)
        EnvironmentalAlertManager.createNotificationChannel(this)

        // Sign in anonymously on startup
        CoroutineScope(Dispatchers.IO).launch {
            val result = AuthManager.signInAnonymously()
            result.onSuccess { uid ->
                android.util.Log.d("EnviroGuard", "Signed in with UID: $uid")
            }
            result.onFailure { e ->
                android.util.Log.e("EnviroGuard", "Auth failed: ${e.message}")
            }
        }
    }

    override fun onTerminate() {
        if (forecastModelDelegate.isInitialized()) forecastModel.close()
        super.onTerminate()
    }
}
