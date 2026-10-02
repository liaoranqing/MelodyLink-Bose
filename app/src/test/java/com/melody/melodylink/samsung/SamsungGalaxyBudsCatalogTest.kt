package com.melody.melodylink.samsung

import com.melody.melodylink.domain.DeviceIdentity
import com.melody.melodylink.domain.Vendor
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsCatalog
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsModel
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsUuids
import com.melody.melodylink.vendor.samsung.SamsungVendorAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungGalaxyBudsCatalogTest {
    @Test
    fun mapsEveryReferenceDeviceId() {
        SamsungGalaxyBudsCatalog.models.forEach { model ->
            model.deviceIds.forEach { deviceId ->
                val identity = modernIdentity(SamsungGalaxyBudsUuids.deviceIdUuid(deviceId).toString())
                assertEquals(model, SamsungGalaxyBudsCatalog.find(identity)?.model)
            }
        }
    }

    @Test
    fun mapsOriginalGalaxyBudsUsingLegacyQualifiedEndpoint() {
        val identity = DeviceIdentity(
            bluetoothName = "Galaxy Buds",
            serviceUuids = setOf(
                SamsungGalaxyBudsUuids.legacySpp.toString(),
                SamsungGalaxyBudsUuids.mepSpp.toString(),
            ),
        )

        assertEquals(SamsungGalaxyBudsModel.GALAXY_BUDS, SamsungGalaxyBudsCatalog.find(identity)?.model)
    }

    @Test
    fun requiresBothModernSppAndSamsungMepUuid() {
        val deviceId = SamsungGalaxyBudsUuids.deviceIdUuid(0x0155).toString()
        assertNull(SamsungGalaxyBudsCatalog.find(DeviceIdentity(serviceUuids = setOf(deviceId))))
        assertNull(SamsungGalaxyBudsCatalog.find(DeviceIdentity(serviceUuids = setOf(
            SamsungGalaxyBudsUuids.modernSpp.toString(), deviceId,
        ))))
    }

    @Test
    fun fallsBackToSpecificNameOnlyAfterEndpointQualification() {
        val identity = modernIdentity(name = "Galaxy Buds4 Pro")
        val result = SamsungGalaxyBudsCatalog.find(identity)

        assertEquals(SamsungGalaxyBudsModel.GALAXY_BUDS4_PRO, result?.model)
        assertEquals(60, result?.confidence)
        assertNull(SamsungGalaxyBudsCatalog.find(DeviceIdentity(bluetoothName = "Galaxy Buds4 Pro")))
    }

    @Test
    fun reportsSamsungVendorMatch() {
        val match = SamsungVendorAdapter().match(modernIdentity(
            SamsungGalaxyBudsUuids.deviceIdUuid(0x0145).toString(),
        ))

        assertEquals(Vendor.SAMSUNG, match?.vendor)
        assertEquals("samsung.galaxy_buds2_pro", match?.profileId)
        assertTrue(SamsungGalaxyBudsCatalog.models.size == 13)
    }

    private fun modernIdentity(
        deviceId: String? = null,
        name: String? = null,
    ) = DeviceIdentity(
        bluetoothName = name,
        serviceUuids = buildSet {
            add(SamsungGalaxyBudsUuids.modernSpp.toString())
            add(SamsungGalaxyBudsUuids.mepSpp.toString())
            deviceId?.let(::add)
        },
    )
}
