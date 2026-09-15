package com.enviroguard.app.external.advisory

import com.enviroguard.app.external.model.ExternalAdvisory
import com.enviroguard.app.external.model.ExternalAdvisoryType
import com.enviroguard.app.external.model.ExternalEnvironmentSnapshot

object ExternalAdvisoryEngine {
    private val thunderstormCodes = setOf(95, 96, 99)

    fun evaluate(snapshot: ExternalEnvironmentSnapshot): List<ExternalAdvisory> = buildList {
        if (snapshot.usAqi != null && snapshot.usAqi >= 101) {
            add(
                ExternalAdvisory(
                    type = ExternalAdvisoryType.OUTDOOR_AIR,
                    title = "Outdoor Air Advisory",
                    message = "Outdoor air quality is elevated; consider reducing prolonged outdoor activity."
                )
            )
        }

        val nextSixHours = snapshot.hourlyForecast.take(6)
        if (nextSixHours.any { it.weatherCode in thunderstormCodes }) {
            add(
                ExternalAdvisory(
                    type = ExternalAdvisoryType.FORECAST,
                    title = "Forecast Advisory",
                    message = "Thunderstorm conditions are forecast within the next few hours."
                )
            )
        } else if (nextSixHours.any { (it.precipitationProbabilityPercent ?: -1.0) >= 70.0 }) {
            add(
                ExternalAdvisory(
                    type = ExternalAdvisoryType.FORECAST,
                    title = "Forecast Advisory",
                    message = "Rain is likely within the next few hours."
                )
            )
        }
    }
}
