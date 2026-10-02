package com.melody.melodylink.vendor.samsung

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.DeviceProfile
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsCatalog
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsModel
import com.melody.melodylink.vendor.VendorAdapter

class SamsungVendorAdapter : VendorAdapter {
    override val vendor: Vendor = Vendor.SAMSUNG

    override fun match(identity: DeviceIdentity): DeviceMatch? {
        val result = SamsungGalaxyBudsCatalog.find(identity) ?: return null
        return DeviceMatch(vendor, result.model.id, result.confidence)
    }
}

object SamsungDeviceCatalogAdapter {
    fun findBest(identity: DeviceIdentity): DeviceProfile? {
        val result = SamsungGalaxyBudsCatalog.find(identity) ?: return null
        return result.model.toDeviceProfile()
    }

    fun profile(id: String): DeviceProfile? = SamsungGalaxyBudsCatalog.models
        .firstOrNull { it.id == id }
        ?.toDeviceProfile()

    private fun SamsungGalaxyBudsModel.toDeviceProfile() = DeviceProfile(
        vendor = Vendor.SAMSUNG,
        id = id,
        displayName = displayName,
        capabilities = EarbudsCapabilities(
            ancModes = ancModes,
            batteryParts = batteryParts,
        ),
    )
}
