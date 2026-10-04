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

    /** Field-verified: BMAP answers on RFCOMM channel 2 (insecure socket only). */
    const val RFCOMM_CHANNEL = 2

    /**
     * MACs of known Bose units whose Bluetooth name was changed by the owner
     * (name-prefix matching alone would miss them). Ported from the verified
     * v1.x module.
     */
    val KNOWN_MACS: Set<String> = setOf("68:F2:1F:3D:41:D7")

    /**
     * 0.5.74. Field-verified on the user's Find X8 Ultra (dumpsys bluetooth_manager):
     * 40:72:18:C7:75:70 is a REAL OPPO Enco X3 the user also owns — not the Bose.
     * The host's whitelist lookup answers for it with the genuine
     * {@code id=067410 name=OPPO Enco X3} DTO, and stripping the Enco four-level ANC
     * sub-levels there would break that earphone's own page. Everything we do to a
     * lookup result must therefore EXCLUDE this address.
     */
    val EXCLUDED_MACS: Set<String> = setOf("40:72:18:C7:75:70")

    /** True when [address] is a device we must never rewrite. */
    fun isExcluded(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return EXCLUDED_MACS.any { it.equals(address, ignoreCase = true) }
    }

    /** ColorOS-facing mode constants verified on the device. */
    const val MODE_QUIET = 0
    const val MODE_AWARE = 1

    /** [31.10] AudioModesSettingsConfig layout indices. */
    const val SETTING_CNC = 0
    const val SETTING_AUTO_CNC = 1
    const val SETTING_SPATIAL = 2
    /**
     * Wind Block (0=off, 1=on). Bose hides this in the official app but the
     * firmware accepts it on the same unauthenticated [31.10] register —
     * verified by bosectl against QC Ultra 2 / `edith` hardware.
     * Audibility note: wind masks the CNC DSP path, so the 0-10 level only
     * sounds different while wind is off.
     */
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

    /** True when the name looks like a factory Bose product name. */
    fun matchesName(bluetoothName: String?): Boolean {
        if (bluetoothName.isNullOrBlank()) return false
        return bluetoothName.lowercase().startsWith("bose")
    }

    fun matchesAddress(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return KNOWN_MACS.any { it.equals(address, ignoreCase = true) }
    }

    fun matches(bluetoothName: String?): Boolean = matchesName(bluetoothName)

    fun matches(bluetoothName: String?, address: String?): Boolean =
        matchesName(bluetoothName) || matchesAddress(address)

    fun profile(): DeviceProfile = DeviceProfile(
        vendor = com.melody.melodylink.domain.Vendor.BOSE,
        id = PROFILE_ID,
        displayName = PRODUCT_NAME,
        capabilities = capabilities,
    )
}

class BoseDeviceCatalog : DeviceCatalog {
    override fun findBest(identity: DeviceIdentity): DeviceProfile? =
        if (BoseDeviceConfig.matches(identity.bluetoothName, identity.address)) {
            BoseDeviceConfig.profile()
        } else {
            null
        }
}
