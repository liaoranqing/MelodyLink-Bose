package com.melody.melodylink.vendor.huawei

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.DeviceProfile
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.huawei.config.HuaweiDeviceCatalog
import com.melody.melodylink.huawei.config.HuaweiDeviceConfig
import com.melody.melodylink.vendor.VendorAdapter

class HuaweiVendorAdapter : VendorAdapter {
    override val vendor: Vendor = Vendor.HUAWEI

    override fun match(identity: DeviceIdentity): DeviceMatch? = HuaweiDeviceCatalog.find(identity)?.let {
        DeviceMatch(vendor, it.route.id, it.confidence)
    }
}

object HuaweiDeviceCatalogAdapter {
    fun findBest(identity: DeviceIdentity): DeviceProfile? = HuaweiDeviceCatalog.find(identity)?.route?.toProfile()
    fun profile(id: String): DeviceProfile? = HuaweiDeviceCatalog.models.firstOrNull { it.id == id }?.toProfile()

    private fun HuaweiDeviceConfig.toProfile() = DeviceProfile(
        vendor = Vendor.HUAWEI,
        id = id,
        displayName = name,
        capabilities = EarbudsCapabilities(
            ancModes = buildSet {
                add(com.melody.melodylink.domain.AncMode.OFF)
                if (supportsAnc) add(com.melody.melodylink.domain.AncMode.NOISE_CANCELING)
                if (supportsTransparency) add(com.melody.melodylink.domain.AncMode.TRANSPARENCY)
            },
            batteryParts = batteryParts,
        ),
    )
}
