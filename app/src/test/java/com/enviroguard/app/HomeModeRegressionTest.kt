package com.enviroguard.app

import android.app.Application
import com.enviroguard.app.data.DeviceManager
import com.enviroguard.app.data.repository.SensorRepository
import com.enviroguard.app.demo.ScenarioTestInput
import com.enviroguard.app.demo.ScenarioTestManager
import com.enviroguard.app.ui.home.HomeViewModel
import com.enviroguard.app.model.SensorReading
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.mockito.Mockito.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeModeRegressionTest {
    @Test fun demoAndScenarioClearSynchronouslyWhenReturningToRealMode() {
        val context = RuntimeEnvironment.getApplication()
        DeviceManager.init(context)
        val repository = mock(SensorRepository::class.java)
        `when`(repository.getDemoReading()).thenReturn(SensorReading(29f, 65f, 250f, 800f, 60f))
        `when`(repository.listenToCurrentReading("EHM_A7F2C1")).thenReturn(emptyFlow())
        try {
            val vm = HomeViewModel(repository)
            for (deviceId in listOf(null, "EHM_A7F2C1")) {
                DeviceManager.activeDeviceId = deviceId
                for (scenario in listOf(false, true)) {
                    DeviceManager.isDemoMode = true
                    if (scenario) ScenarioTestManager.apply(ScenarioTestInput(32f, 70f, 250f, 800f, 60f), true)
                    vm.initialise()
                    assertNotNull(vm.sensorReading.value)
                    assertNotNull(vm.assessment.value)
                    DeviceManager.isDemoMode = false
                    vm.refreshSettings()
                    assertNull(vm.sensorReading.value)
                    assertNull(vm.assessment.value)
                    assertEquals(if (deviceId == null) "No Device" else "Waiting for environmental data", vm.status.value)
                    assertFalse(ScenarioTestManager.isActive)
                }
            }
            // A failed real listener must not resurrect the previous demo assessment either.
            DeviceManager.isDemoMode = true
            vm.initialise()
            `when`(repository.listenToCurrentReading("EHM_A7F2C1")).thenReturn(flow { throw IllegalStateException("Test unavailable") })
            DeviceManager.isDemoMode = false
            vm.refreshSettings()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(vm.sensorReading.value)
            assertNull(vm.assessment.value)
            assertEquals("Environmental data unavailable", vm.status.value)
        } finally {
            ScenarioTestManager.clear()
        }
    }
}
