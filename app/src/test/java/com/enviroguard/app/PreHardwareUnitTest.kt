package com.enviroguard.app

import android.Manifest
import com.enviroguard.app.data.local.entity.SensorReadingEntity
import com.enviroguard.app.dataset.DatasetCsvExporter
import com.enviroguard.app.model.SensorReading
import com.enviroguard.app.provisioning.ProvisioningPermissions
import org.junit.Assert.*
import org.junit.Test

class PreHardwareUnitTest {
    @Test fun scanningRequiresPreciseLocationAcrossSupportedApis() {
        for (sdk in 26..36) {
            val required = ProvisioningPermissions.required(sdk)
            assertTrue(required.contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertEquals(sdk >= 33, required.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
            assertTrue(ProvisioningPermissions.requested(sdk).contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @Test fun nearbyPermissionAloneDoesNotPermitDiscovery() {
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION,
            ProvisioningPermissions.firstMissing(33) { it == Manifest.permission.NEARBY_WIFI_DEVICES })
        assertEquals(Manifest.permission.NEARBY_WIFI_DEVICES,
            ProvisioningPermissions.firstMissing(33) { it == Manifest.permission.ACCESS_FINE_LOCATION })
        assertNull(ProvisioningPermissions.firstMissing(33) { true })
        assertNull(ProvisioningPermissions.firstMissing(26) { it == Manifest.permission.ACCESS_FINE_LOCATION })
    }

    @Test fun nonFiniteValuesBecomeNullAndRoundTripAsUnavailable() {
        val entity = SensorReadingEntity.fromReading(SensorReading(
            Float.NaN, Float.POSITIVE_INFINITY, 250f, 800f, Float.NEGATIVE_INFINITY, 1000L
        ), "EHM_A7F2C1")
        assertNull(entity.temperature)
        assertNull(entity.humidity)
        assertNull(entity.noiseLevel)
        val restored = entity.toReading()
        assertTrue(restored.temperature.isNaN())
        assertTrue(restored.humidity.isNaN())
        assertTrue(restored.noiseLevel.isNaN())
        assertEquals(250f, restored.tvoc)
        assertEquals(800f, restored.eco2)
        assertEquals(1000L, restored.timestamp)
    }

    @Test fun partialCsvUsesEmptyCellsNotZeroNullTextOrNan() {
        val row = SensorReadingEntity(deviceId = "EHM_A7F2C1", timestamp = 1000,
            tvoc = 250f, eco2 = 800f, noiseLevel = 60f)
        assertEquals("1000,EHM_A7F2C1,,,250.0,800.0,60.0", DatasetCsvExporter.generate(listOf(row)).lineSequence().drop(1).first())
    }
}
