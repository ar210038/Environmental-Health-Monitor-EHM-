package com.enviroguard.app.utils

object TemperatureUtils {
    fun celsiusToFahrenheit(celsius: Float): Float = celsius * 9f / 5f + 32f
}
