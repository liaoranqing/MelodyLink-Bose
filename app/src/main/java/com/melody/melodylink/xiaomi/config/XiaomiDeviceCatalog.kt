package com.melody.melodylink.xiaomi.config

import com.melody.melodylink.domain.DeviceIdentity

data class XiaomiDeviceMatch(val route: XiaomiDeviceConfig, val confidence: Int)

object XiaomiDeviceCatalog {
    @Volatile private var registry = XiaomiConfigRegistry.empty()

    val models: List<XiaomiDeviceConfig>
        get() = registry.profiles

    fun setRegistry(value: XiaomiConfigRegistry) {
        registry = value
    }

    fun find(identity: DeviceIdentity): XiaomiDeviceMatch? {
        registry.findByVidPid(identity.vendorId, identity.productId)?.let { return XiaomiDeviceMatch(it, 100) }
        return registry.findByName(identity.bluetoothName)?.let { XiaomiDeviceMatch(it, 60) }
    }
}
