package io.github.recloudstudio.sleepymore

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a MIoT property value should be interpreted and rendered as text.
 *
 * Deliberately just 3 generic kinds instead of enumerating sensor categories (temperature,
 * PM2.5, CO2, presence, switch, ...) — new sensor types are handled by configuring a
 * [MiHomePropertySource] instance, never by touching this code.
 */
enum class MiHomeValueType {
    /** on/off, presence, contact, leak, etc. Value is Boolean (or a 0/1 number). */
    BOOLEAN,
    /** Any numeric reading — temperature, humidity, PM2.5, CO2 ppm, lux, battery %, ... */
    NUMBER,
    /** No special handling — stringify whatever the property returns as-is. */
    STRING
}

/**
 * One MIoT property to read. All formatting is data, not code — covering a new sensor kind
 * means adding a source with the right [unit]/[decimals], not adding a case to
 * [MiHomeMonitor.renderValue].
 */
data class MiHomePropertySource(
        val did: String,
        val siid: Int? = null,
        val piid: Int? = null,
        val valueType: MiHomeValueType = MiHomeValueType.NUMBER,
        // --- BOOLEAN formatting ---
        val trueText: String = "开启",
        val falseText: String = "关闭",
        /** Flip the raw boolean before mapping to trueText/falseText — some MIoT props report
         * "no_motion"/"closed" style inverted booleans. */
        val invertBoolean: Boolean = false,
        // --- NUMBER formatting: displayed as (raw * multiplier + offset), rounded to `decimals`,
        // followed by `unit`. Defaults (1.0 / 0.0) mean "show the raw value as-is". ---
        val unit: String = "",
        val decimals: Int = 1,
        val multiplier: Double = 1.0,
        val offset: Double = 0.0,
        val offlineText: String = "离线"
)

/**
 * One reported Sleepy "device". Combines 1..N [MiHomePropertySource]s through [template], where
 * `{#1}`, `{#2}`, ... refer to sources by position (1-based). For the common single-sensor case
 * `template` is just `"{#1}"` — the raw formatted value, no extra text.
 */
data class MiHomeReportItem(
        val deviceId: String,
        val showName: String,
        val template: String = "{#1}",
        val sources: List<MiHomePropertySource>
)

/**
 * Polls Xiaomi cloud for configured device properties and reports each configured
 * [MiHomeReportItem] to the Sleepy server as its own "device", reusing
 * [SleepyApiClient.sendDeviceStatus]. Runs entirely in the SleepyXposed app's own process — no
 * Xposed hook into 米家 involved.
 */
class MiHomeMonitor(private val context: Context) {
    private val TAG = "SleepyXposed-MiHome"
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var client: MiHomeCloudClient? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val ht = HandlerThread("SleepyXposed-MiHome").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h
        h.post(::pollLoop)
    }

    fun stop() {
        running = false
        thread?.quitSafely()
        thread = null
        handler = null
        client = null
    }

    private fun pollLoop() {
        if (!running) return
        val config = ConfigManager.loadConfig(context)
        if (config == null || !config.miHomeEnabled) {
            scheduleNext(120_000L)
            return
        }

        try {
            ensureLoggedIn(config)?.let { cloud -> pollOnce(cloud, config) }
        } catch (e: Exception) {
            Log.w(TAG, "poll failed: ${e.message}")
        }

        val intervalMs = (config.miHomePollIntervalSec.coerceAtLeast(30)) * 1000L
        scheduleNext(intervalMs)
    }

    private fun scheduleNext(delayMs: Long) {
        if (!running) return
        handler?.postDelayed(::pollLoop, delayMs)
    }

    private fun ensureLoggedIn(config: SleepyConfig): MiHomeCloudClient? {
        var c = client
        if (c == null || !c.isLoggedIn) {
            if (config.miHomeUsername.isBlank() || config.miHomePassword.isBlank()) return null
            c = MiHomeCloudClient(config.miHomeUsername, config.miHomePassword, config.miHomeRegion)
            if (!c.login()) {
                Log.w(TAG, "Mi Home cloud login failed — check account/password/region")
                return null
            }
            client = c
        }
        return c
    }

    private fun pollOnce(cloud: MiHomeCloudClient, config: SleepyConfig) {
        val items = parseReportItems(config.miHomeDevicesJson)
        if (items.isEmpty()) return

        val devices = cloud.fetchDeviceList().associateBy { it.did }

        for (item in items) {
            if (item.sources.isEmpty()) continue

            val rendered =
                    item.sources.map { src ->
                        val online = devices[src.did]?.isOnline ?: false
                        if (!online) {
                            false to src.offlineText
                        } else if (src.siid != null && src.piid != null) {
                            renderValue(src, cloud.getProp(src.did, src.siid, src.piid))
                        } else {
                            true to "在线"
                        }
                    }

            var text = item.template
            rendered.forEachIndexed { idx, (_, str) -> text = text.replace("{#${idx + 1}}", str) }
            val using = rendered.any { it.first }

            SleepyApiClient.sendDeviceStatus(
                    baseUrl = config.serverUrl,
                    secret = config.secret,
                    id = item.deviceId,
                    showName = item.showName,
                    using = using,
                    status = text,
                    callback =
                            object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    Log.w(TAG, "report ${item.deviceId} failed: ${e.message}")
                                }
                                override fun onResponse(call: Call, response: Response) {
                                    response.close()
                                }
                            }
            )
        }
    }

    /** Returns (using, text) for a raw MIoT property value, per the source's [MiHomeValueType]. */
    private fun renderValue(src: MiHomePropertySource, value: Any?): Pair<Boolean, String> {
        if (value == null) return false to src.offlineText
        return when (src.valueType) {
            MiHomeValueType.BOOLEAN -> {
                val raw = value as? Boolean ?: (((value as? Number)?.toDouble() ?: 0.0) != 0.0)
                val b = if (src.invertBoolean) !raw else raw
                b to (if (b) src.trueText else src.falseText)
            }
            MiHomeValueType.NUMBER -> {
                val n = (value as? Number)?.toDouble() ?: return true to value.toString()
                val adjusted = n * src.multiplier + src.offset
                true to ("%.${src.decimals.coerceIn(0, 6)}f%s".format(adjusted, src.unit))
            }
            MiHomeValueType.STRING -> true to value.toString()
        }
    }

    companion object {
        fun parseReportItems(json: String): List<MiHomeReportItem> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val deviceId =
                            o.optString("deviceId").takeIf { it.isNotBlank() }
                                    ?: return@mapNotNull null
                    val sourcesArr = o.optJSONArray("sources") ?: JSONArray()
                    val sources =
                            (0 until sourcesArr.length()).mapNotNull { si ->
                                parseSource(sourcesArr.optJSONObject(si))
                            }
                    if (sources.isEmpty()) return@mapNotNull null
                    MiHomeReportItem(
                            deviceId = deviceId,
                            showName = o.optString("showName", deviceId),
                            template = o.optString("template", "{#1}"),
                            sources = sources
                    )
                }
            } catch (e: Exception) {
                Log.w("SleepyXposed-MiHome", "invalid mihome_devices JSON: ${e.message}")
                emptyList()
            }
        }

        private fun parseSource(o: JSONObject?): MiHomePropertySource? {
            if (o == null) return null
            val did = o.optString("did").takeIf { it.isNotBlank() } ?: return null
            val valueType =
                    try {
                        MiHomeValueType.valueOf(o.optString("valueType", "NUMBER").uppercase())
                    } catch (_: Exception) {
                        MiHomeValueType.NUMBER
                    }
            return MiHomePropertySource(
                    did = did,
                    siid = if (o.has("siid")) o.optInt("siid") else null,
                    piid = if (o.has("piid")) o.optInt("piid") else null,
                    valueType = valueType,
                    trueText = o.optString("trueText", "开启"),
                    falseText = o.optString("falseText", "关闭"),
                    invertBoolean = o.optBoolean("invertBoolean", false),
                    unit = o.optString("unit", ""),
                    decimals = o.optInt("decimals", 1),
                    multiplier = o.optDouble("multiplier", 1.0),
                    offset = o.optDouble("offset", 0.0),
                    offlineText = o.optString("offlineText", "离线")
            )
        }
    }
}
