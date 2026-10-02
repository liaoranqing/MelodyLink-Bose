package com.melody.melodylink.xiaomi.config

import android.content.res.AssetManager
import com.melody.melodylink.domain.BatteryPart
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

interface XiaomiAssetSource {
    fun readText(path: String): String
}

class AssetManagerXiaomiAssetSource(private val assets: AssetManager) : XiaomiAssetSource {
    override fun readText(path: String): String = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

class DirectoryXiaomiAssetSource(private val root: File) : XiaomiAssetSource {
    override fun readText(path: String): String = File(root, path).readText(Charsets.UTF_8)
}

data class XiaomiConfigIssue(val path: String, val message: String)
data class XiaomiConfigLoadResult(val registry: XiaomiConfigRegistry, val issues: List<XiaomiConfigIssue>)

object XiaomiConfigLoader {
    private const val REGISTRY_PATH = "xiaomi/registry.json"

    fun fromAssets(assets: AssetManager) = load(AssetManagerXiaomiAssetSource(assets))
    fun fromDirectory(root: File) = load(DirectoryXiaomiAssetSource(root))

    fun load(source: XiaomiAssetSource): XiaomiConfigLoadResult {
        val registryJson = try {
            JSONObject(source.readText(REGISTRY_PATH))
        } catch (error: Throwable) {
            return XiaomiConfigLoadResult(XiaomiConfigRegistry.empty(), listOf(XiaomiConfigIssue(REGISTRY_PATH, error.message ?: "registry unavailable")))
        }
        if (registryJson.optInt("schemaVersion", -1) != XIAOMI_CONFIG_SCHEMA_VERSION) {
            return XiaomiConfigLoadResult(XiaomiConfigRegistry.empty(), listOf(XiaomiConfigIssue(REGISTRY_PATH, "unsupported registry schema")))
        }
        val path = registryJson.optString("devices")
        if (path.isBlank()) return XiaomiConfigLoadResult(XiaomiConfigRegistry.empty(), listOf(XiaomiConfigIssue(REGISTRY_PATH, "devices must be a path")))
        val devices = try {
            JSONObject(source.readText(path)).getJSONArray("devices")
        } catch (error: Throwable) {
            return XiaomiConfigLoadResult(XiaomiConfigRegistry.empty(), listOf(XiaomiConfigIssue(path, error.message ?: "device list unavailable")))
        }
        val issues = mutableListOf<XiaomiConfigIssue>()
        val profiles = mutableListOf<XiaomiDeviceConfig>()
        for (index in 0 until devices.length()) {
            val profile = try {
                parse(devices.getJSONObject(index))
            } catch (error: Throwable) {
                issues += XiaomiConfigIssue("$path[$index]", error.message ?: error.javaClass.simpleName)
                continue
            }
            val validation = XiaomiConfigValidator.validate(profile)
            validation.forEach { issues += XiaomiConfigIssue("$path[$index]", it) }
            if (validation.isNotEmpty()) continue
            if (profiles.any { it.id == profile.id }) {
                issues += XiaomiConfigIssue("$path[$index]", "duplicate profile id ${profile.id}")
                continue
            }
            if (profiles.flatMap { it.vidPids }.any { it in profile.vidPids }) {
                issues += XiaomiConfigIssue("$path[$index]", "duplicate VID:PID")
                continue
            }
            profiles += profile
        }
        return XiaomiConfigLoadResult(XiaomiConfigRegistry.of(profiles), issues)
    }

    private fun parse(json: JSONObject): XiaomiDeviceConfig = XiaomiDeviceConfig(
        schemaVersion = json.requiredInt("schemaVersion"),
        id = json.requiredString("id"),
        name = json.requiredString("name"),
        aliases = json.requiredStrings("aliases").map(::normalizeXiaomi).toSet(),
        vidPids = json.requiredStrings("vidPids").map(::parseVidPid).toSet(),
        featureIds = json.requiredStrings("featureIds").toSet(),
        image = json.requiredString("image"),
        batteryParts = json.requiredStrings("batteryParts").map(BatteryPart::valueOf).toSet(),
    )
}

class XiaomiConfigRegistry private constructor(val profiles: List<XiaomiDeviceConfig>) {
    fun findByVidPid(vendorId: Int?, productId: Int?): XiaomiDeviceConfig? {
        if (vendorId == null || productId == null) return null
        return profiles.firstOrNull { XiaomiVidPid(vendorId, productId) in it.vidPids }
    }

    fun findByName(name: String?): XiaomiDeviceConfig? {
        val normalized = normalizeXiaomi(name.orEmpty())
        return profiles.sortedByDescending { profile -> profile.aliases.maxOf(String::length) }
            .firstOrNull { profile -> normalized in profile.aliases }
    }

    companion object {
        fun of(profiles: List<XiaomiDeviceConfig>) = XiaomiConfigRegistry(profiles)
        fun empty() = XiaomiConfigRegistry(emptyList())
    }
}

object XiaomiConfigValidator {
    fun validate(profile: XiaomiDeviceConfig): List<String> = buildList {
        if (profile.schemaVersion != XIAOMI_CONFIG_SCHEMA_VERSION) add("unsupported profile schema")
        if (!profile.id.matches(Regex("xiaomi\\.[a-z0-9_]+"))) add("invalid profile id")
        if (profile.name.isBlank()) add("name must not be blank")
        if (profile.aliases.isEmpty() || profile.aliases.any(String::isBlank)) add("at least one alias is required")
        if (profile.vidPids.isEmpty()) add("at least one VID:PID is required")
        if (profile.featureIds.isEmpty()) add("at least one feature id is required")
        if (!profile.image.startsWith("xiaomi/images/")) add("image must be a Xiaomi image asset path")
        if (profile.batteryParts != setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE)) add("battery layout must be LEFT, RIGHT, CASE")
    }
}

private fun parseVidPid(value: String): XiaomiVidPid {
    val pieces = value.split(':')
    require(pieces.size == 2 && pieces.all { it.matches(Regex("[0-9A-Fa-f]{4}")) }) { "invalid VID:PID $value" }
    return XiaomiVidPid(pieces[0].toInt(16), pieces[1].toInt(16))
}

private fun JSONObject.requiredString(name: String): String = if (has(name) && !isNull(name)) getString(name) else error("missing string $name")
private fun JSONObject.requiredInt(name: String): Int = if (has(name) && !isNull(name)) getInt(name) else error("missing integer $name")
private fun JSONObject.requiredStrings(name: String): List<String> {
    if (!has(name) || isNull(name)) error("missing array $name")
    return getJSONArray(name).strings(name)
}
private fun JSONArray.strings(name: String): List<String> = buildList {
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isBlank()) error("$name contains an empty value")
        add(value)
    }
}

internal fun normalizeXiaomi(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
