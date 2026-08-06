package io.github.recloudstudio.sleepymore

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // Per-host cookie store. This matters a lot here: the 3-step login flow is a session — step1
    // sets cookies that step2/step3 must send back, and the serviceToken step3 needs often only
    // shows up after following a redirect chain. Without a CookieJar, OkHttp keeps NO cookies at
    // all between requests, which silently breaks the whole flow even with a correct password.
    private val cookieStore = HashMap<String, MutableMap<String, String>>()
    private val cookieJar =
            object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val host = cookieStore.getOrPut(url.host) { mutableMapOf() }
                    for (c in cookies) host[c.name] = c.value
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val host = cookieStore[url.host] ?: return emptyList()
                    return host.map { (k, v) -> Cookie.Builder().name(k).value(v).domain(url.host).build() }
                }
            }
    private val client = OkHttpClient.Builder().cookieJar(cookieJar).build()

    private var userId: String? = null
    private var serviceToken: String? = null
    private var ssecurity: String? = null
    private val deviceId: String = randomHex(16)

    /** Human-readable reason the last [login] call failed, or null if it succeeded / hasn't run
     * yet. Check this after `login()` returns false — logcat tag "SleepyXposed-MiHome" also has
     * it, but this is meant to be shown directly in the UI. */
    var lastError: String? = null
        private set

    val isLoggedIn: Boolean
        get() = userId != null && serviceToken != null && ssecurity != null

    /** Blocking. Returns true on success; see [lastError] for why it failed otherwise. */
    fun login(): Boolean {
        lastError = null
        return try {
            val step1 = loginStep1()
            if (step1 == null) {
                lastError = "无法连接小米账号服务器（step1 请求失败或返回内容无法解析）"
                return false
            }
            val sign = step1.optString("_sign").takeIf { it.isNotBlank() }
            if (sign == null) {
                lastError = "小米账号服务器返回的登录响应缺少 _sign 字段，接口可能已变化"
                return false
            }

            val step2 = loginStep2(sign)
            if (step2 == null) {
                lastError = "无法连接小米账号服务器（step2 请求失败或返回内容无法解析）"
                return false
            }
            val code = step2.optInt("code", -1)
            if (code != 0) {
                val desc = step2.optString("desc").ifBlank { "未知原因" }
                val notificationUrl = step2.optString("notificationUrl")
                lastError =
                        if (notificationUrl.isNotBlank()) {
                            "登录被小米风控拦截（需要短信验证码/滑块等二次验证，账密登录无法完成），" +
                                    "需要先在浏览器里用同一账号正常登录一次再重试：$notificationUrl"
                        } else {
                            "登录被拒绝（code=$code，$desc）——多半是账号或密码不对，也可能是区域填错了"
                        }
                return false
            }

            val location = step2.optString("location").takeIf { it.isNotBlank() }
            val ss = step2.optString("ssecurity").takeIf { it.isNotBlank() }
            val uid = step2.optString("userId").takeIf { it.isNotBlank() }
            if (location == null || ss == null || uid == null) {
                lastError = "登录响应缺少 location/ssecurity/userId 字段，接口可能已变化"
                return false
            }
            ssecurity = ss
            userId = uid

            loginStep3(location)
            if (serviceToken == null) {
                lastError = "未能拿到 serviceToken（常见于二次验证没有走完，或 Cookie 会话丢失）"
                return false
            }
            true
        } catch (e: Exception) {
            lastError = "登录过程中发生异常：${e.message}"
            android.util.Log.w(TAG, "login failed", e)
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
        // Let OkHttp follow the redirect chain (default) — the CookieJar captures Set-Cookie
        // headers at every hop automatically, wherever serviceToken actually gets set.
        val req = Request.Builder().url(location).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { /* body unused, we only care about cookies */ }
        serviceToken = findCookie("serviceToken")
    }

    private fun findCookie(name: String): String? {
        for ((_, cookies) in cookieStore) {
            cookies[name]?.let { return it }
        }
        return null
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
