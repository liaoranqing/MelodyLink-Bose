package com.melody.melodylink.xiaomi.config

import com.melody.melodylink.domain.BatteryPart

const val XIAOMI_CONFIG_SCHEMA_VERSION = 1

data class XiaomiDeviceConfig(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val aliases: Set<String>,
    val vidPids: Set<XiaomiVidPid>,
    val featureIds: Set<String>,
    val image: String,
    val batteryParts: Set<BatteryPart>,
)

data class XiaomiVidPid(val vendorId: Int, val productId: Int) {
    override fun toString(): String = "%04X:%04X".format(vendorId, productId)
}
