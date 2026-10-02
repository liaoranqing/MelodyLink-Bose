package com.melody.melodylink.bose

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.DeviceCatalog
import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceProfile
import com.melody.melodylink.domain.EarbudsCapabilities

/** Static profile for the Bose QuietComfort Ultra Earbuds (2nd Gen, "edith"). */
object BoseDeviceConfig {
    const val PRODUCT_NAME = "Bose QC Earbuds Ultra 2"
    const val PROFILE_ID = "qc-earbuds-ultra-2"
    /** BMAP RFCOMM service UUID advertised by Bose head products. */
    const val BMAP_UUID = "00000000-deca-fade-deca-deafdecacaff"

    /** ColorOS-facing mode constants verified on the device. */
    const val MODE_QUIET = 0
    const val MODE_AWARE = 1

    /** [31.10] AudioModesSettingsConfig layout indices. */
    const val SETTING_CNC = 0
    const val SETTING_AUTO_CNC = 1
    const val SETTING_SPATIAL = 2
    const val SETTING_RESERVED = 3
    const val SETTING_ANC = 4

    val capabilities = EarbudsCapabilities(
        ancModes = setOf(
            AncMode.OFF,
            AncMode.NOISE_CANCELING,
            AncMode.TRANSPARENCY,
        ),
        batteryParts = setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE),
        supportsAmbientLevel = true,
    )

    fun matches(bluetoothName: String?): Boolean {
        if (bluetoothName.isNullOrBlank()) return false
        val name = bluetoothName.lowercase()
        return name.startsWith("bose")
    }

    fun profile(): DeviceProfile = DeviceProfile(
        vendor = com.melody.melodylink.domain.Vendor.BOSE,
        id = PROFILE_ID,
        displayName = PRODUCT_NAME,
        capabilities = capabilities,
    )
}

class BoseDeviceCatalog : DeviceCatalog {
    override fun findBest(identity: DeviceIdentity): DeviceProfile? =
        if (BoseDeviceConfig.matches(identity.bluetoothName)) BoseDeviceConfig.profile() else null
}
