package com.melody.melodylink.huawei.config

import com.melody.melodylink.domain.DeviceIdentity
import java.util.UUID

object HuaweiUuids {
    val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}

data class HuaweiDeviceMatch(val route: HuaweiDeviceConfig, val confidence: Int)

/** Runtime catalog. It remains empty until the module assets have been validated. */
object HuaweiDeviceCatalog {
    @Volatile private var registry = HuaweiConfigRegistry.empty()

    val models: List<HuaweiDeviceConfig>
        get() = registry.profiles

    fun setRegistry(value: HuaweiConfigRegistry) {
        registry = value
    }

    fun find(identity: DeviceIdentity): HuaweiDeviceMatch? {
        val route = registry.find(identity.bluetoothName) ?: return null
        val hasSpp = HuaweiUuids.spp.toString() in identity.serviceUuids.map(String::lowercase)
        return HuaweiDeviceMatch(route, if (hasSpp) 100 else 70)
    }
}
