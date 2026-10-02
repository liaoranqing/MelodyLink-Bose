package com.melody.melodylink.huawei

import com.melody.melodylink.huawei.config.HuaweiConfigLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiConfigLoaderTest {
    @Test
    fun loadsEveryBundledProfileAndImage() {
        val assets = mainAssetsDirectory()
        val result = HuaweiConfigLoader.fromDirectory(assets)

        assertTrue(result.issues.joinToString("\n") { "${it.path}: ${it.message}" }, result.issues.isEmpty())
        assertEquals(11, result.registry.profiles.size)
        result.registry.profiles.forEach { profile ->
            assertTrue(profile.image, File(assets, profile.image).isFile)
        }
    }
}

private fun mainAssetsDirectory(): File = sequenceOf(
    File("src/main/assets"),
    File("app/src/main/assets"),
    File("../app/src/main/assets"),
).firstOrNull(File::isDirectory) ?: error("main assets directory was not found")
