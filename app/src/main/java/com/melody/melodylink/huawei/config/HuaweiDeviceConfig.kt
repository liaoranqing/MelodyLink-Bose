package com.melody.melodylink.huawei.config

import com.melody.melodylink.domain.BatteryPart

const val HUAWEI_CONFIG_SCHEMA_VERSION = 1

/** A Huawei model definition loaded from the module's assets. */
data class HuaweiDeviceConfig(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val image: String,
    val aliases: Set<String>,
    val supportsAnc: Boolean,
    val supportsTransparency: Boolean,
    val supportsAncReadback: Boolean,
    val supportsAncLevels: Boolean,
    /** Only enabled for models with a captured 0x2B/0x6C setter frame. */
    val supportsLowLatency: Boolean,
    val batteryParts: Set<BatteryPart>,
)
