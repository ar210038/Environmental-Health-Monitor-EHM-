package com.enviroguard.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.enviroguard.app.MainActivity
import com.enviroguard.app.R
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.model.EnvironmentalAssessment
import com.enviroguard.app.model.SensorReading

class EnvironmentalAlertManager(context: Context) {
    private val appContext = context.applicationContext
    private val coordinator = EnvironmentalAlertCoordinator(AlertDecisionEngine(), ::post)

    fun evaluate(assessment: EnvironmentalAssessment, reading: SensorReading, isDemo: Boolean) {
        coordinator.evaluate(
            assessment = assessment,
            reading = reading,
            now = System.currentTimeMillis(),
            enabled = DeviceManager.notificationsEnabled && canPostNotifications(),
            isDemo = isDemo
        )
    }

    private fun post(decision: AlertDecision) {
        if (!canPostNotifications()) return
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_device)
            .setContentTitle(decision.title)
            .setContentText(decision.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(decision.body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        @Suppress("MissingPermission")
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    companion object {
        private const val CHANNEL_ID = "environmental_condition_alerts"
        private const val NOTIFICATION_ID = 1001

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Environmental condition alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts after unfavorable environmental conditions persist"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
