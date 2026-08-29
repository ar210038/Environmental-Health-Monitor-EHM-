package com.enviroguard.app

import android.app.Application
import com.enviroguard.app.data.AuthManager
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.local.EnviroGuardDatabase
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.alerts.EnvironmentalAlertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EnviroGuardApp : Application() {

    val database by lazy { EnviroGuardDatabase.getInstance(this) }
    val repository by lazy { SensorRepository(database) }
    val alertManager by lazy { EnvironmentalAlertManager(this) }

    override fun onCreate() {
        super.onCreate()

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
}
