package com.melody.melodylink.huawei.config

import android.content.res.AssetManager
import com.melody.melodylink.domain.BatteryPart
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

interface HuaweiAssetSource {
    fun readText(path: String): String
}

class AssetManagerHuaweiAssetSource(private val assetManager: AssetManager) : HuaweiAssetSource {
    override fun readText(path: String): String =
        assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

class DirectoryHuaweiAssetSource(private val root: File) : HuaweiAssetSource {
    override fun readText(path: String): String = File(root, path).readText(Charsets.UTF_8)
}

data class HuaweiConfigIssue(val path: String, val message: String)

data class HuaweiConfigLoadResult(
    val registry: HuaweiConfigRegistry,
    val issues: List<HuaweiConfigIssue>,
)

object HuaweiConfigLoader {
    private const val REGISTRY_PATH = "huawei/registry.json"

    fun fromAssets(assetManager: AssetManager): HuaweiConfigLoadResult = load(AssetManagerHuaweiAssetSource(assetManager))

    fun fromDirectory(root: File): HuaweiConfigLoadResult = load(DirectoryHuaweiAssetSource(root))

    fun load(source: HuaweiAssetSource): HuaweiConfigLoadResult {
        val registry = try {
            JSONObject(source.readText(REGISTRY_PATH))
        } catch (error: Throwable) {
            return HuaweiConfigLoadResult(HuaweiConfigRegistry.empty(), listOf(HuaweiConfigIssue(REGISTRY_PATH, error.message ?: "registry unavailable")))
        }
        if (registry.optInt("schemaVersion", -1) != HUAWEI_CONFIG_SCHEMA_VERSION) {
            return HuaweiConfigLoadResult(HuaweiConfigRegistry.empty(), listOf(HuaweiConfigIssue(REGISTRY_PATH, "unsupported registry schema")))
        }
        val files = registry.optJSONArray("devices")
            ?: return HuaweiConfigLoadResult(HuaweiConfigRegistry.empty(), listOf(HuaweiConfigIssue(REGISTRY_PATH, "devices must be an array")))
        val issues = mutableListOf<HuaweiConfigIssue>()
        val profiles = mutableListOf<HuaweiDeviceConfig>()
        for (index in 0 until files.length()) {
            val path = files.optString(index)
            if (path.isBlank()) {
                issues += HuaweiConfigIssue(REGISTRY_PATH, "devices[$index] is not a path")
                continue
            }
            val profile = try {
                parseDevice(JSONObject(source.readText(path)))
            } catch (error: Throwable) {
                issues += HuaweiConfigIssue(path, error.message ?: error.javaClass.simpleName)
                continue
            }
            val validationIssues = HuaweiConfigValidator.validate(profile)
            validationIssues.forEach { issues += HuaweiConfigIssue(path, it) }
            if (validationIssues.isNotEmpty()) continue
            if (profiles.any { it.id == profile.id }) {
                issues += HuaweiConfigIssue(path, "duplicate profile id ${profile.id}")
                continue
            }
            profiles += profile
        }
        return HuaweiConfigLoadResult(HuaweiConfigRegistry.of(profiles), issues)
    }

    private fun parseDevice(json: JSONObject): HuaweiDeviceConfig {
        val capabilities = json.requiredObject("capabilities")
        return HuaweiDeviceConfig(
            schemaVersion = json.requiredInt("schemaVersion"),
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            image = json.requiredString("image"),
            aliases = json.requiredStringList("aliases").map(::normalize).toSet(),
            supportsAnc = capabilities.requiredBoolean("supportsAnc"),
            supportsTransparency = capabilities.requiredBoolean("supportsTransparency"),
            supportsAncReadback = capabilities.requiredBoolean("supportsAncReadback"),
            supportsAncLevels = capabilities.requiredBoolean("supportsAncLevels"),
            supportsLowLatency = capabilities.requiredBoolean("supportsLowLatency"),
            batteryParts = json.requiredStringList("batteryParts").map(BatteryPart::valueOf).toSet(),
        )
    }
}

class HuaweiConfigRegistry private constructor(val profiles: List<HuaweiDeviceConfig>) {
    fun find(name: String?): HuaweiDeviceConfig? {
        val normalizedName = normalize(name.orEmpty())
        return profiles
            .sortedByDescending { profile -> profile.aliases.maxOf(String::length) }
            .firstOrNull { profile -> profile.aliases.any { alias -> normalizedName == alias } }
    }

    companion object {
        fun of(profiles: List<HuaweiDeviceConfig>) = HuaweiConfigRegistry(profiles)
        fun empty() = HuaweiConfigRegistry(emptyList())
    }
}

object HuaweiConfigValidator {
    fun validate(profile: HuaweiDeviceConfig): List<String> = buildList {
        if (profile.schemaVersion != HUAWEI_CONFIG_SCHEMA_VERSION) add("unsupported profile schema")
        if (!profile.id.matches(Regex("huawei\\.[a-z0-9_]+"))) add("invalid profile id")
        if (profile.name.isBlank()) add("name must not be blank")
        if (!profile.image.startsWith("huawei/images/")) add("image must be a Huawei image asset path")
        if (profile.aliases.isEmpty() || profile.aliases.any(String::isBlank)) add("at least one alias is required")
        if (!profile.supportsAnc && (profile.supportsTransparency || profile.supportsAncReadback || profile.supportsAncLevels)) {
            add("ANC sub-capabilities require ANC support")
        }
    }
}

private fun JSONObject.requiredObject(name: String): JSONObject =
    if (has(name) && !isNull(name)) getJSONObject(name) else error("missing object $name")

private fun JSONObject.requiredString(name: String): String =
    if (has(name) && !isNull(name)) getString(name) else error("missing string $name")

private fun JSONObject.requiredInt(name: String): Int =
    if (has(name) && !isNull(name)) getInt(name) else error("missing integer $name")

private fun JSONObject.requiredBoolean(name: String): Boolean =
    if (has(name) && !isNull(name)) getBoolean(name) else error("missing boolean $name")

private fun JSONObject.requiredStringList(name: String): List<String> {
    if (!has(name) || isNull(name)) error("missing array $name")
    return getJSONArray(name).stringList(name)
}

private fun JSONArray.stringList(name: String): List<String> = buildList {
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isBlank()) error("$name contains an empty value")
        add(value)
    }
}

internal fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
