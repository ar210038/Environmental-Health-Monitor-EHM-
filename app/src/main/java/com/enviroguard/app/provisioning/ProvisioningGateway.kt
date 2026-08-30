package com.enviroguard.app.provisioning

import kotlinx.coroutines.flow.StateFlow

interface ProvisioningGateway {
    val state: StateFlow<ProvisioningState>

    fun findDevices()
    fun permissionDenied(permission: String)
    fun connect(device: ProvisioningDevice)
    fun scanNetworks()
    fun provision(network: ProvisioningNetwork, password: CharArray)
    fun cancel()
    fun close()
}

object SensitiveCredential {
    inline fun <T> consume(value: CharArray, block: (String) -> T): T {
        return try {
            block(String(value))
        } finally {
            value.fill('\u0000')
        }
    }
}
