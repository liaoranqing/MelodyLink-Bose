package com.melody.melodylink.vendor.xiaomi

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.DeviceProfile
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.xiaomi.config.XiaomiDeviceCatalog
import com.melody.melodylink.xiaomi.config.XiaomiDeviceConfig
import com.melody.melodylink.vendor.VendorAdapter

class XiaomiVendorAdapter : VendorAdapter {
    override val vendor = Vendor.XIAOMI
    override fun match(identity: DeviceIdentity): DeviceMatch? = XiaomiDeviceCatalog.find(identity)?.let { DeviceMatch(vendor, it.route.id, it.confidence) }
}

object XiaomiDeviceCatalogAdapter {
    fun findBest(identity: DeviceIdentity): DeviceProfile? = XiaomiDeviceCatalog.find(identity)?.route?.toProfile()
    private fun XiaomiDeviceConfig.toProfile() = DeviceProfile(
        vendor = Vendor.XIAOMI,
        id = id,
        displayName = name,
        capabilities = EarbudsCapabilities(batteryParts = batteryParts),
    )
}
