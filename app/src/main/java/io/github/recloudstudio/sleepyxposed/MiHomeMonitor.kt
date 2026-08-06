package io.github.recloudstudio.sleepyxposed

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
 * How a MIoT property value should be interpreted and rendered as Sleepy status text.
 *
 * Deliberately just 3 generic kinds instead of enumerating sensor categories (temperature,
 * PM2.5, CO2, presence, switch, ...) — new sensor types are handled by configuring a
 * [MiHomeDeviceMapping] instance, never by touching this code.
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
 * A single device mapping: which cloud device (did[/siid/piid]) maps to which Sleepy status
 * entry (deviceId/showName), and how to render the value. All formatting is data, not code —
 * covering a new sensor kind means adding a mapping with the right [unit]/[decimals], not adding
 * a case to [MiHomeMonitor.renderValue].
 */
data class MiHomeDeviceMapping(
        val did: String,
        val siid: Int? = null,
        val piid: Int? = null,
        val valueType: MiHomeValueType = MiHomeValueType.STRING,
        val deviceId: String,
        val showName: String,
        // --- BOOLEAN formatting ---
        val trueText: String = "开启",
        val falseText: String = "关闭",
        /** Flip the raw boolean before mapping to trueText/falseText — some MIoT props report
         * "no_motion"/"closed" style inverted booleans. */
        val invertBoolean: Boolean = false,
        // --- NUMBER formatting: displayed as (raw * multiplier + offset), rounded to `decimals`,
        // followed by `unit`. Defaults (1.0 / 0.0) mean "show the raw value as-is". Some MIoT
        // props report values pre-scaled (e.g. humidity*10) — multiplier/offset cover that
        // without needing a special case per sensor kind. ---
        val unit: String = "",
        val decimals: Int = 1,
        val multiplier: Double = 1.0,
        val offset: Double = 0.0,
        val offlineText: String = "离线"
)

/**
 * Polls Xiaomi cloud for configured device states and reports each one to the Sleepy server as
 * its own "device", reusing [SleepyApiClient.sendDeviceStatus]. Runs entirely in the SleepyXposed
 * app's own process — no Xposed hook into 米家 involved.
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
        val mappings = parseMappings(config.miHomeDevicesJson)
        if (mappings.isEmpty()) return

        val devices = cloud.fetchDeviceList().associateBy { it.did }

        for (m in mappings) {
            val device = devices[m.did]
            val online = device?.isOnline ?: false

            val statusText: String
            val using: Boolean

            if (!online) {
                statusText = m.offlineText
                using = false
            } else if (m.siid != null && m.piid != null) {
                val value = cloud.getProp(m.did, m.siid, m.piid)
                val rendered = renderValue(m, value)
                using = rendered.first
                statusText = rendered.second
            } else {
                using = true
                statusText = "在线"
            }

            SleepyApiClient.sendDeviceStatus(
                    baseUrl = config.serverUrl,
                    secret = config.secret,
                    id = m.deviceId,
                    showName = m.showName,
                    using = using,
                    status = statusText,
                    callback =
                            object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    Log.w(TAG, "report ${m.deviceId} failed: ${e.message}")
                                }
                                override fun onResponse(call: Call, response: Response) {
                                    response.close()
                                }
                            }
            )
        }
    }

    /** Returns (using, statusText) for a raw MIoT property value, per the mapping's [MiHomeValueType]. */
    private fun renderValue(m: MiHomeDeviceMapping, value: Any?): Pair<Boolean, String> {
        if (value == null) return false to m.offlineText
        return when (m.valueType) {
            MiHomeValueType.BOOLEAN -> {
                val raw = value as? Boolean ?: (((value as? Number)?.toDouble() ?: 0.0) != 0.0)
                val b = if (m.invertBoolean) !raw else raw
                b to (if (b) m.trueText else m.falseText)
            }
            MiHomeValueType.NUMBER -> {
                val n = (value as? Number)?.toDouble() ?: return true to value.toString()
                val adjusted = n * m.multiplier + m.offset
                true to ("%.${m.decimals.coerceIn(0, 6)}f%s".format(adjusted, m.unit))
            }
            MiHomeValueType.STRING -> true to value.toString()
        }
    }

    private fun parseMappings(json: String): List<MiHomeDeviceMapping> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o: JSONObject = arr.optJSONObject(i) ?: return@mapNotNull null
                val did = o.optString("did").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val deviceId =
                        o.optString("deviceId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val valueType =
                        try {
                            MiHomeValueType.valueOf(o.optString("valueType", "STRING").uppercase())
                        } catch (_: Exception) {
                            MiHomeValueType.STRING
                        }
                MiHomeDeviceMapping(
                        did = did,
                        siid = if (o.has("siid")) o.optInt("siid") else null,
                        piid = if (o.has("piid")) o.optInt("piid") else null,
                        valueType = valueType,
                        deviceId = deviceId,
                        showName = o.optString("showName", deviceId),
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
        } catch (e: Exception) {
            Log.w(TAG, "invalid mihome_devices JSON: ${e.message}")
            emptyList()
        }
    }
}
