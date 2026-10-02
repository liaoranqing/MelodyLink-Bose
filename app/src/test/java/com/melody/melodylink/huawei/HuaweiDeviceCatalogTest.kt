package com.melody.melodylink.huawei

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.huawei.config.HuaweiConfigLoader
import com.melody.melodylink.huawei.config.HuaweiDeviceCatalog
import java.io.File
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuaweiDeviceCatalogTest {
    @Before
    fun loadBundledCatalog() {
        HuaweiDeviceCatalog.setRegistry(HuaweiConfigLoader.fromDirectory(mainAssetsDirectory()).registry)
    }

    @Test fun matchesNormalizedFreeBudsName() {
        val match = HuaweiDeviceCatalog.find(DeviceIdentity(bluetoothName = "HUAWEI FreeBuds 7i"))
        assertEquals("huawei.freebuds7i", match?.route?.id)
        assertEquals(70, match?.confidence)
    }

    @Test fun rejectsUnknownName() {
        assertNull(HuaweiDeviceCatalog.find(DeviceIdentity(bluetoothName = "Random Headset")))
    }

    @Test fun rejectsNearMatchWithUnverifiedSuffix() {
        assertNull(HuaweiDeviceCatalog.find(DeviceIdentity(bluetoothName = "HUAWEI FreeBuds Pro 5i")))
    }
}

private fun mainAssetsDirectory(): File = sequenceOf(
    File("src/main/assets"),
    File("app/src/main/assets"),
    File("../app/src/main/assets"),
).firstOrNull(File::isDirectory) ?: error("main assets directory was not found")
