package com.enviroguard.app

import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.dataset.DatasetCsvExporter
import com.enviroguard.app.model.*
import com.enviroguard.app.utils.EnvironmentalConditionEngine
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
 @Test fun finalConditions_haveFixedSeverityOrder(){assertEquals(0,EnvironmentalCondition.GOOD.severity);assertEquals(3,EnvironmentalCondition.CRITICAL.severity)}
 @Test fun factorBoundaries_followFinalBands(){assertEquals(EnvironmentalCondition.GOOD,EnvironmentalConditionEngine.classifyTvoc(222f));assertEquals(EnvironmentalCondition.MODERATE,EnvironmentalConditionEngine.classifyTvoc(223f));assertEquals(EnvironmentalCondition.POOR,EnvironmentalConditionEngine.classifyEco2(2001f));assertEquals(EnvironmentalCondition.POOR,EnvironmentalConditionEngine.classifyNoise(85f))}
 @Test fun eco2AloneNeverCreatesCriticalAir(){val a=EnvironmentalConditionEngine.assess(SensorReading(temperature=20f,humidity=40f,tvoc=100f,eco2=5000f,noiseLevel=40f));assertEquals(EnvironmentalCondition.POOR,a.airCondition);assertNotEquals(EnvironmentalCondition.CRITICAL,a.overallCondition)}
 @Test fun overallUsesMaximumAndRetainsTies(){val a=EnvironmentalConditionEngine.assess(SensorReading(temperature=35f,humidity=70f,tvoc=800f,eco2=500f,noiseLevel=40f));assertEquals(EnvironmentalCondition.POOR,a.overallCondition);assertTrue(a.primaryConcerns.contains(EnvironmentalDimension.THERMAL));assertTrue(a.primaryConcerns.contains(EnvironmentalDimension.AIR))}
 @Test fun rawCsvHasNoLegacyOrDerivedFields(){val csv=DatasetCsvExporter.generate(listOf(SensorReadingEntity(deviceId="d",timestamp=1,temperature=1f,humidity=2f,tvoc=3f,eco2=4f,noiseLevel=5f)));assertEquals("timestamp,deviceId,temperature,humidity,tvoc,eco2,noise_level",csv.lineSequence().first());assertFalse(csv.contains("ers"));assertFalse(csv.contains("session"))}
}
