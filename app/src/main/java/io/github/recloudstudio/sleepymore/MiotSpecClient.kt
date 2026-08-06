package io.github.recloudstudio.sleepymore

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One readable MIoT property, resolved from the public miot-spec.org registry. */
data class MiotSpecProperty(
        val siid: Int,
        val piid: Int,
        /** Human-readable label, e.g. "环境状态 · 温度" — service + property description. */
        val label: String,
        /** Raw MIoT format: bool / int8 / int16 / int32 / int64 / uint8 / uint16 / uint32 /
         * uint64 / float / string. */
        val format: String,
        /** Normalized display unit (°C, %, ppm, lux, ...), empty if the spec has none. */
        val unit: String
) {
    fun guessValueType(): MiHomeValueType =
            when (format) {
                "bool" -> MiHomeValueType.BOOLEAN
                "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64", "float" ->
                        MiHomeValueType.NUMBER
                else -> MiHomeValueType.STRING
            }
}

/**
 * Reads Xiaomi's public MIoT spec registry (https://miot-spec.org) — the same open catalog the
 * official 米家 app and Home Assistant's integration use to know what properties a device model
 * exposes. This has nothing to do with the account/cloud client: no login needed, purely a
 * lookup by device model string (e.g. "cgllc.airm.cgdn1").
 */
object MiotSpecClient {
    private val client = OkHttpClient()
    private var instancesCache: JSONObject? = null
    private val specCache = HashMap<String, List<MiotSpecProperty>>()

    private fun fetchInstances(): JSONObject? {
        instancesCache?.let { return it }
        val req =
                Request.Builder()
                        .url("https://miot-spec.org/miot-spec-v2/instances?status=all")
                        .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                JSONObject(body).also { instancesCache = it }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Latest published spec `type` (urn) registered for a given device model string. */
    private fun findUrnForModel(model: String): String? {
        val instances = fetchInstances()?.optJSONArray("instances") ?: return null
        var best: String? = null
        var bestVersion = -1
        for (i in 0 until instances.length()) {
            val o = instances.optJSONObject(i) ?: continue
            if (o.optString("model") != model) continue
            val version = o.optInt("version", 0)
            if (version > bestVersion) {
                bestVersion = version
                best = o.optString("type")
            }
        }
        return best
    }

    /** Blocking. Fetches (and caches) the list of readable properties for a device model.
     * Returns an empty list if the model isn't in the public registry or the property list has
     * no readable properties — callers should fall back to manual siid/piid entry in that case. */
    fun fetchProperties(model: String): List<MiotSpecProperty> {
        if (model.isBlank()) return emptyList()
        specCache[model]?.let { return it }

        val urn = findUrnForModel(model) ?: return emptyList()
        val req = Request.Builder().url("https://miot-spec.org/miot-spec-v2/instance?type=$urn").build()
        val spec =
                try {
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: return emptyList()
                        JSONObject(body)
                    }
                } catch (e: Exception) {
                    return emptyList()
                }

        val services = spec.optJSONArray("services") ?: return emptyList()
        val out = mutableListOf<MiotSpecProperty>()
        for (si in 0 until services.length()) {
            val service = services.optJSONObject(si) ?: continue
            val siid = service.optInt("iid", -1)
            val serviceLabel = service.optString("description", service.optString("type", ""))
            val props = service.optJSONArray("properties") ?: continue
            for (pi in 0 until props.length()) {
                val prop = props.optJSONObject(pi) ?: continue
                val access = prop.optJSONArray("access")
                val readable =
                        access == null ||
                                (0 until access.length()).any { access.optString(it) == "read" }
                if (!readable) continue
                val piid = prop.optInt("iid", -1)
                if (siid < 0 || piid < 0) continue
                val propLabel = prop.optString("description", prop.optString("type", "属性"))
                out.add(
                        MiotSpecProperty(
                                siid = siid,
                                piid = piid,
                                label = "$serviceLabel · $propLabel",
                                format = prop.optString("format", "string"),
                                unit = normalizeUnit(prop.optString("unit", ""))
                        )
                )
            }
        }
        specCache[model] = out
        return out
    }

    private fun normalizeUnit(raw: String): String =
            when (raw) {
                "celsius" -> "°C"
                "fahrenheit" -> "°F"
                "percentage" -> "%"
                "none", "" -> ""
                else -> raw
            }
}
