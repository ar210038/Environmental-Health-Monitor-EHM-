package com.enviroguard.app.external.repository

import android.content.SharedPreferences
import java.time.LocalDate

internal interface QuotaControlStore {
    var quotaDate: String?
    var requestCount: Int
}

internal class SharedPreferencesQuotaControlStore(
    private val preferences: SharedPreferences
) : QuotaControlStore {
    override var quotaDate: String?
        get() = preferences.getString(KEY_DATE, null)
        set(value) {
            preferences.edit().putString(KEY_DATE, value).apply()
        }

    override var requestCount: Int
        get() = preferences.getInt(KEY_COUNT, 0).coerceAtLeast(0)
        set(value) {
            preferences.edit().putInt(KEY_COUNT, value.coerceAtLeast(0)).apply()
        }

    private companion object {
        const val KEY_DATE = "quota_date"
        const val KEY_COUNT = "http_call_count"
    }
}

internal class DailyRequestQuota(
    private val store: QuotaControlStore,
    private val dateProvider: () -> String = { LocalDate.now().toString() },
    private val maximumRequests: Int = MAX_REQUESTS_PER_DAY
) {
    @Synchronized
    fun remaining(): Int {
        resetForNewDayIfNeeded()
        return (maximumRequests - store.requestCount).coerceAtLeast(0)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        resetForNewDayIfNeeded()
        if (store.requestCount >= maximumRequests) return false
        store.requestCount += 1
        return true
    }

    private fun resetForNewDayIfNeeded() {
        val today = dateProvider()
        if (store.quotaDate != today) {
            store.quotaDate = today
            store.requestCount = 0
        }
    }

    companion object {
        const val MAX_REQUESTS_PER_DAY = 120
    }
}
