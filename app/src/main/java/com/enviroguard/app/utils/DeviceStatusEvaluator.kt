package com.enviroguard.app.utils

object DeviceStatusEvaluator {
    const val OFFLINE_AFTER_MS = 25_000L

    fun isRecentlySeen(lastSeen: Long, now: Long): Boolean =
        lastSeen > 0L && now >= lastSeen && now - lastSeen <= OFFLINE_AFTER_MS

    fun delayUntilOffline(lastSeen: Long, now: Long): Long =
        (OFFLINE_AFTER_MS - (now - lastSeen)).coerceAtLeast(0L)
}
