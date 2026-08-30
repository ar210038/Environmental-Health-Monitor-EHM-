package com.enviroguard.app.provisioning

import java.util.Locale

object ProvisioningConfig {
    const val SERVICE_PREFIX = "EHM_"

    // Prototype Security 1 proof of possession. The firmware phase must use the same value.
    // Replace this provider with a per-device value when device labels/QR codes are introduced.
    internal const val PROTOTYPE_PROOF_OF_POSSESSION = "ehm-provision-v1"

    // The planned Arduino SoftAP is open; Security 1 protects provisioning messages.
    // This remains a single configuration point if a SoftAP service key is introduced later.
    internal const val SOFTAP_SERVICE_KEY: String = ""
}

fun interface ProofOfPossessionProvider {
    fun forDevice(serviceName: String): String
}

class PrototypeProofOfPossessionProvider : ProofOfPossessionProvider {
    override fun forDevice(serviceName: String): String =
        ProvisioningConfig.PROTOTYPE_PROOF_OF_POSSESSION
}

object EhmDeviceIdentity {
    private val serviceNamePattern = Regex("^EHM_[A-Z0-9]{6}$")

    fun normalize(serviceName: String): String? {
        val normalized = serviceName.trim().uppercase(Locale.ROOT)
        return normalized.takeIf(serviceNamePattern::matches)
    }

    fun displayName(serviceName: String): String {
        val normalized = normalize(serviceName) ?: return "Environmental Monitor"
        return "EHM ${normalized.removePrefix(ProvisioningConfig.SERVICE_PREFIX)}"
    }
}
