package io.github.recloudstudio.sleepymore

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal client for the Xiaomi cloud ("米家云") account API.
 *
 * This does NOT hook or talk to the 米家 app in any way — it logs into the same account through
 * Xiaomi's public HTTP API, the way the official app itself does, and lets us poll device status
 * on a timer. This makes it independent of 米家's internal (obfuscated, frequently-changing) code.
 *
 * The login + request-signing scheme implemented here follows the widely published community
 * reverse-engineering of this API (the same scheme used by projects such as python-miio and
 * Xiaomi-cloud-tokens-extractor). Xiaomi has changed details of this API before and may again —
 * if login starts failing, cross-check against one of those reference implementations first
 * rather than assuming this code is unfixably broken.
 *
 * Threading: every method here does blocking network I/O. Call only from a background thread
 * (see [MiHomeMonitor]), never from the main thread.
 */
class MiHomeCloudClient(
        private val username: String,
        private val password: String,
        /** cn / de / us / ru / sg / i2 — must match the region the account/devices live in. */
        private val region: String
) {
    private val TAG = "SleepyXposed-MiHome"
    private val client =
            OkHttpClient.Builder()
                    .followRedirects(false) // we need the raw Location header in login step 2
                    .build()

    private var userId: String? = null
    private var serviceToken: String? = null
    private var ssecurity: String? = null
    private val deviceId: String = randomHex(16)

    val isLoggedIn: Boolean
        get() = userId != null && serviceToken != null && ssecurity != null

    /** Blocking. Returns true on success. */
    fun login(): Boolean {
        return try {
            val step1 = loginStep1() ?: return false
            val sign = step1.optString("_sign").takeIf { it.isNotBlank() } ?: return false
            val step2 = loginStep2(sign) ?: return false
            if (step2.optInt("code", -1) != 0) return false
            val location = step2.optString("location").takeIf { it.isNotBlank() } ?: return false
            ssecurity = step2.optString("ssecurity").takeIf { it.isNotBlank() } ?: return false
            userId = step2.optString("userId").takeIf { it.isNotBlank() } ?: return false
            loginStep3(location)
            isLoggedIn
        } catch (e: Exception) {
            android.util.Log.w(TAG, "login failed: ${e.message}")
            false
        }
    }

    private fun loginStep1(): JSONObject? {
        val req =
                Request.Builder()
                        .url("https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true")
                        .header("User-Agent", USER_AGENT)
                        .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            return parseJsonp(body)
        }
    }

    private fun loginStep2(sign: String): JSONObject? {
        val form =
                FormBody.Builder()
                        .add("sid", "xiaomiio")
                        .add("hash", md5(password).uppercase())
                        .add("callback", "https://sts.api.io.mi.com/sts")
                        .add("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue")
                        .add("user", username)
                        .add("_sign", sign)
                        .add("_json", "true")
                        .build()
        val req =
                Request.Builder()
                        .url("https://account.xiaomi.com/pass/serviceLoginAuth2")
                        .header("User-Agent", USER_AGENT)
                        .post(form)
                        .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            return parseJsonp(body)
        }
    }

    private fun loginStep3(location: String) {
        val req = Request.Builder().url(location).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp -> serviceToken = extractCookie(resp, "serviceToken") }
    }

    /** Fetch the account's device list with basic online/offline + any legacy inline status. */
    fun fetchDeviceList(): List<MiHomeDevice> {
        val params = JSONObject().apply {
            put("getVirtualModel", false)
            put("getHuamiDevices", 0)
        }
        val resp = signedRequest("/home/device_list", params) ?: return emptyList()
        val list = resp.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
        val out = mutableListOf<MiHomeDevice>()
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            out.add(
                    MiHomeDevice(
                            did = d.optString("did"),
                            name = d.optString("name"),
                            model = d.optString("model"),
                            isOnline = d.optBoolean("isOnline", false),
                            rawExtra = d.optString("extra")
                    )
            )
        }
        return out
    }

    /** Query one MIoT-spec property (siid/piid) for a device. Returns null on failure. */
    fun getProp(did: String, siid: Int, piid: Int): Any? {
        val params = JSONObject().apply {
            put(
                    "datasource",
                    1
            )
            put(
                    "params",
                    JSONArray().put(JSONObject().apply {
                        put("did", did)
                        put("siid", siid)
                        put("piid", piid)
                    })
            )
        }
        val resp = signedRequest("/miotspec/prop/get", params) ?: return null
        val result = resp.optJSONArray("result") ?: return null
        if (result.length() == 0) return null
        val entry = result.optJSONObject(0) ?: return null
        if (entry.optInt("code", -1) != 0) return null
        return entry.opt("value")
    }

    // ---- signed request plumbing ----

    private fun apiBase(): String =
            if (region.equals("cn", ignoreCase = true)) "https://api.io.mi.com/app"
            else "https://${region}.api.io.mi.com/app"

    private fun signedRequest(path: String, params: JSONObject): JSONObject? {
        val ss = ssecurity ?: return null
        val uid = userId ?: return null
        val token = serviceToken ?: return null

        val nonce = generateNonce()
        val signedNonce = signedNonce(ss, nonce)
        val dataStr = params.toString()

        val signParams = LinkedHashMap<String, String>()
        signParams["data"] = dataStr
        val signature = generateSignature(path, signedNonce, nonce, signParams)

        val form =
                FormBody.Builder()
                        .add("data", dataStr)
                        .add("signature", signature)
                        .add("_nonce", nonce)
                        .build()

        val cookie =
                "userId=$uid; serviceToken=$token; yetAnotherServiceToken=$token; " +
                        "PassportDeviceId=$deviceId; locale=zh_CN"

        val req =
                Request.Builder()
                        .url(apiBase() + path)
                        .header("User-Agent", USER_AGENT)
                        .header("Cookie", cookie)
                        .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
                        .post(form)
                        .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            return try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun generateNonce(): String {
        val random = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val millis = System.currentTimeMillis() / 60000L
        val timePart =
                byteArrayOf(
                        (millis shr 24).toByte(),
                        (millis shr 16).toByte(),
                        (millis shr 8).toByte(),
                        millis.toByte()
                )
        return Base64.encodeToString(random + timePart, Base64.NO_WRAP)
    }

    private fun signedNonce(ssecurity: String, nonce: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(Base64.decode(ssecurity, Base64.NO_WRAP))
        md.update(Base64.decode(nonce, Base64.NO_WRAP))
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    private fun generateSignature(
            path: String,
            signedNonce: String,
            nonce: String,
            params: Map<String, String>
    ): String {
        val parts = mutableListOf(path, signedNonce, nonce)
        for ((k, v) in params) parts.add("$k=$v")
        val toSign = parts.joinToString("&")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(Base64.decode(signedNonce, Base64.NO_WRAP), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(toSign.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ---- helpers ----

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** Xiaomi's account endpoints prefix JSON bodies with "&&&START&&&". */
    private fun parseJsonp(body: String): JSONObject? {
        val cleaned = body.removePrefix("&&&START&&&").trim()
        return try {
            JSONObject(cleaned)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCookie(resp: Response, name: String): String? {
        for (header in resp.headers("Set-Cookie")) {
            val kv = header.substringBefore(";")
            val idx = kv.indexOf('=')
            if (idx > 0 && kv.substring(0, idx) == name) return kv.substring(idx + 1)
        }
        return null
    }

    companion object {
        private const val USER_AGENT =
                "Mozilla/5.0 (Linux; U; Android 14; zh-cn; SleepyXposed) " +
                        "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 " +
                        "MiuiBrowser/23.6.13 Mobile Safari/534.30 XiaoMi/MiuiBrowser/23.6.13"
    }
}

data class MiHomeDevice(
        val did: String,
        val name: String,
        val model: String,
        val isOnline: Boolean,
        val rawExtra: String
)
