package com.enviroguard.app

import com.enviroguard.app.data.SavedDevice
import com.enviroguard.app.data.SavedDeviceCollection
import com.enviroguard.app.provisioning.EhmDeviceIdentity
import com.enviroguard.app.provisioning.ProvisionedDeviceRegistrar
import com.enviroguard.app.provisioning.ProvisionedDeviceStore
import com.enviroguard.app.provisioning.ProvisioningFailureCode
import com.enviroguard.app.provisioning.ProvisioningState
import com.enviroguard.app.provisioning.SensitiveCredential
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningUnitTest {
    @Test
    fun serviceNameNormalization_acceptsOnlyFinalEhmIdentityContract() {
        assertEquals("EHM_A7F2C1", EhmDeviceIdentity.normalize(" ehm_a7f2c1 "))
        assertNull(EhmDeviceIdentity.normalize("PROV_A7F2C1"))
        assertNull(EhmDeviceIdentity.normalize("EHM_A7F2"))
        assertNull(EhmDeviceIdentity.normalize("EHM_A7F2C1_EXTRA"))
    }

    @Test
    fun savedDeviceUpsert_preventsDuplicateIdsAndUpdatesName() {
        val original = listOf(SavedDevice("EHM_A7F2C1", "Old name"))
        val updated = SavedDeviceCollection.upsert(original, SavedDevice("EHM_A7F2C1", "Updated name"))
        assertEquals(1, updated.size)
        assertEquals("Updated name", updated.single().displayName)
    }

    @Test
    fun successfulProvisioning_addsAndActivatesNormalizedDevice() {
        val store = RecordingStore()
        val registrar = ProvisionedDeviceRegistrar(store)
        val saved = registrar.register(ProvisioningState.Provisioned("ehm_a7f2c1"))
        assertEquals("EHM_A7F2C1", saved?.deviceId)
        assertEquals("EHM_A7F2C1", store.activeDeviceId)
        assertEquals(1, store.devices.size)
    }

    @Test
    fun repeatedSuccess_doesNotRegisterDuplicateDevice() {
        val store = RecordingStore()
        val registrar = ProvisionedDeviceRegistrar(store)
        registrar.register(ProvisioningState.Provisioned("EHM_A7F2C1"))
        registrar.register(ProvisioningState.Provisioned("EHM_A7F2C1"))
        assertEquals(1, store.devices.size)
    }

    @Test
    fun failedOrUnknownProvisioning_doesNotCreateDevice() {
        val store = RecordingStore()
        val registrar = ProvisionedDeviceRegistrar(store)
        registrar.register(ProvisioningState.Failed(ProvisioningFailureCode.AUTHENTICATION_FAILED, "Password rejected"))
        registrar.register(ProvisioningState.StatusUnknown("EHM_A7F2C1", "Not confirmed"))
        assertTrue(store.devices.isEmpty())
        assertNull(store.activeDeviceId)
    }

    @Test
    fun sensitiveCredential_isClearedAfterConsumptionAndFailure() {
        val used = "router-password".toCharArray()
        SensitiveCredential.consume(used) { assertEquals("router-password", it) }
        assertArrayEquals(CharArray(used.size), used)

        val failed = "another-password".toCharArray()
        runCatching { SensitiveCredential.consume<Unit>(failed) { error("protocol failure") } }
        assertArrayEquals(CharArray(failed.size), failed)
    }

    private class RecordingStore : ProvisionedDeviceStore {
        val devices = mutableListOf<SavedDevice>()
        var activeDeviceId: String? = null

        override fun addAndActivate(device: SavedDevice) {
            val index = devices.indexOfFirst { it.deviceId == device.deviceId }
            if (index >= 0) devices[index] = device else devices.add(device)
            activeDeviceId = device.deviceId
        }
    }
}
