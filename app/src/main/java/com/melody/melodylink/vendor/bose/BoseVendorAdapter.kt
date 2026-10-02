package com.melody.melodylink.vendor.bose

import com.melody.melodylink.bose.BoseDeviceConfig
import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.DeviceMatch
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.vendor.VendorAdapter

/** Matches Bose products by advertised Bluetooth name (verified on QC Ultra 2). */
class BoseVendorAdapter : VendorAdapter {
    override val vendor: Vendor = Vendor.BOSE

    override fun match(identity: DeviceIdentity): DeviceMatch? {
        if (!BoseDeviceConfig.matches(identity.bluetoothName, identity.address)) return null
        return DeviceMatch(Vendor.BOSE, BoseDeviceConfig.PROFILE_ID, 90)
    }
}
