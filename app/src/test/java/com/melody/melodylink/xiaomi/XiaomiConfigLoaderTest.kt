package com.melody.melodylink.xiaomi

import com.melody.melodylink.xiaomi.config.XiaomiConfigLoader
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiConfigLoaderTest {
    @Test
    fun loadsEveryDocumentedProfileAndReferencedImage() {
        val assets = mainAssetsDirectory()
        val result = XiaomiConfigLoader.fromDirectory(assets)

        assertTrue(result.issues.joinToString("\n") { "${it.path}: ${it.message}" }, result.issues.isEmpty())
        assertEquals(37, result.registry.profiles.size)
        result.registry.profiles.forEach { profile -> assertTrue(profile.image, File(assets, profile.image).isFile) }

        val manifest = JSONObject(File(assets, "xiaomi/image-manifest.json").readText())
        val referenced = manifest.getJSONArray("assets")
        val files = (0 until referenced.length()).map { referenced.getJSONObject(it).getString("file") }.toSet()
        assertEquals(92, files.size)
        files.forEach { file -> assertTrue(file, File(assets, "xiaomi/images/$file").isFile) }
    }

    @Test
    fun prefersVidPidOverANameAlias() {
        val registry = XiaomiConfigLoader.fromDirectory(mainAssetsDirectory()).registry
        val result = registry.findByVidPid(0x2717, 0x50AD)
        assertEquals("xiaomi.buds_5_pro", result?.id)
    }
}

private fun mainAssetsDirectory(): File = sequenceOf(
    File("src/main/assets"), File("app/src/main/assets"), File("../app/src/main/assets"),
).firstOrNull(File::isDirectory) ?: error("main assets directory was not found")
