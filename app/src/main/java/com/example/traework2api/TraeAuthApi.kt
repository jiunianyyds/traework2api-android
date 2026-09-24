package com.example.traework2api

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 阶段3：Trae 官方认证 API。
 *
 * 对齐 reference/traework2api：登录指引 URL、ExchangeToken（refresh→access, token 轮换）、
 * GetUserInfo。登录是"回调流"——登录成功后上游重定向到 127.0.0.1:18080/authorize，
 * 把 refreshToken / userInfo / userJwt 放进 query；本类只负责构造 URL 与换 token。
 *
 * 回调**由用户在浏览器里复制那条地址栏链接、再粘回 App**（[loginWithCallbackUrl]）——
 * 不依赖 WebView 或页面 JS（内置登录窗已整体移除，见 MainActivity.openLogin）。
 */
object TraeAuthApi {

    /** 脱敏日志：只打 uid / 是否过期 / 状态，绝不打 token 本身。 */
    private const val TAG = "TraeAuth"

    // 随区域变化的 host / ClientID 统一从 TraeRegion 取；CN 为已实测值。
    // 登录入口走 requireLogin()，区域未接入时明确报错而不是拿 CN 的值去连。
    private fun region(): TraeRegion = TraeRegion.requireLogin()
    private val CONSOLE_HOST: String get() = region().consoleHost
    private val OAUTH_HOST: String get() = region().oauthHost
    private val CLIENT_ID: String get() = region().clientId

    const val IDE_VERSION = TraeRegion.IDE_VERSION
    const val CALLBACK_HOST = "127.0.0.1"
    const val CALLBACK_PORT = 18080
    const val CALLBACK_PATH = "/authorize"

    private const val EP_EXCHANGE = "/cloudide/api/v3/trae/oauth/ExchangeToken"
    private const val EP_USER_INFO = "/cloudide/api/v3/trae/GetUserInfo"
    private const val JSON = "application/json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 登录：粘贴浏览器回调链接换取 token。**这是本 App 唯一的登录方式。**
     *
     * 对齐 dsh-trae-solo login.sh 的做法：用户用**系统浏览器**打开登录链接，
     * 登录成功后浏览器会跳到打不开的 `127.0.0.1:18080/authorize?...`（地址栏里带着
     * refreshToken），用户把整条链接粘回来即可。
     *
     * 为什么不内置 WebView 登录窗：页面登录后用 **JS 请求回调**（不是导航），
     * WebViewClient 拦不到；那条 JS 还会被页面第三方 SDK 的超时打断。
     * 粘贴回调绕开了 WebView 与页面 JS 的全部不确定性，所以内置窗已整体移除。
     *
     * @return 登录后的账号信息；失败抛异常（消息可直接展示给用户）。
     */
    fun loginWithCallbackUrl(context: Context, callbackUrl: String): TraeAuthStore.Cred {
        val url = callbackUrl.trim()
        if (url.isBlank()) throw IllegalArgumentException("请粘贴回调链接")
        if (!isCallback(url)) {
            throw IllegalArgumentException(
                "这不是 Trae 回调链接。应形如 http://$CALLBACK_HOST:$CALLBACK_PORT$CALLBACK_PATH?refreshToken=…")
        }
        val refresh = extractRefreshToken(url)
            ?: throw IllegalStateException("链接里没有 refreshToken，请确认复制的是登录后跳转的那条完整链接")
        val ex = exchangeToken(refresh)
        val user = getUserInfo(ex.accessToken)
        val cred = TraeAuthStore.Cred(
            accessToken = ex.accessToken,
            refreshToken = ex.refreshToken ?: refresh,
            expiresAt = ex.expiresAt ?: 0L,
            uid = user.uid,
            nickname = user.nickname,
            regionId = region().id,   // 账号自带区域：切换账号时转发才知道该打哪个 host
        )
        TraeAuthStore.save(context, cred)
        Log.i(TAG, "手动粘贴登录成功 (uid=${cred.uid}, region=${cred.regionId})")
        return cred
    }

    /** 构造登录指引 URL。machine/device id 每次生成，兼容上游 cookie/设备绑定。 */
    fun buildLoginUrl(): String {
        val machine = hexStr(16)
        val device = hexStr(16)
        val params = linkedMapOf(
            "login_version" to "1",
            "auth_from" to "solo",
            "login_channel" to "native_ide",
            "plugin_version" to "2.3.62834",
            "auth_type" to "local",
            "client_id" to CLIENT_ID,
            "redirect" to "0",
            "login_trace_id" to hexStr(8),
            "auth_callback_url" to "http://$CALLBACK_HOST:$CALLBACK_PORT$CALLBACK_PATH",
            "machine_id" to machine,
            "device_id" to device,
            "x_device_id" to device,
            "x_machine_id" to machine,
            "x_device_brand" to "PC",
            "x_device_type" to "PC",
            "x_os_version" to "1.0",
            "x_app_version" to IDE_VERSION,
            "x_app_type" to "stable",
        )
        val q = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        // 只打 host 与 client_id（非敏感），便于确认区域没串号（曾出过恒为国服的 bug）
        Log.i(TAG, "生成登录链接: region=${region().id} host=$CONSOLE_HOST client_id=$CLIENT_ID")
        return "$CONSOLE_HOST/authorization?$q"
    }

    /** 回调 query 是否是 Trae 登录回调地址。 */
    fun isCallback(url: String): Boolean {
        return url.regionMatches(0, "http://$CALLBACK_HOST:$CALLBACK_PORT$CALLBACK_PATH", 0,
            "http://$CALLBACK_HOST:$CALLBACK_PORT$CALLBACK_PATH".length, ignoreCase = true)
    }

    /** 从回调 query 提取 refreshToken（一次 URL 解码，parse_qs 语义）。 */
    fun extractRefreshToken(callback: String): String? {
        val query = callback.substringAfter('?', "")
        val map = query.split('&').filter { it.isNotEmpty() }.associate { p ->
            val kv = p.split('=', limit = 2)
            val k = decode(kv.getOrNull(0) ?: "")
            val v = decode(kv.getOrNull(1) ?: "")
            k to v
        }
        var rt = map["refreshToken"]
        if (rt.isNullOrBlank()) rt = map["refresh_token"]
        if (rt.isNullOrBlank()) {
            // 兜底：回调缺 refreshToken 时从 userJwt 里取（同 reference login.sh）
            val jwt = parseJson(map["userJwt"]) ?: parseJson(map["user_jwt"])
            rt = jwt?.optString("RefreshToken")?.takeIf { it.isNotBlank() }
        }
        return rt?.takeIf { it.isNotBlank() }
    }

    /** 解析回调里可能双重 URL 编码的 JSON 参数；解析失败返回 null。 */
    fun parseJsonParam(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        for (cand in listOf(raw, decode(raw))) {
            try { return JSONObject(cand) } catch (_: Exception) {}
        }
        return null
    }

    data class ExchangeResult(
        val accessToken: String,
        val refreshToken: String?, // 轮换后的新 refresh；null=上游未返回（沿用旧值）
        val expiresAt: Long?,       // unix 秒；null=未返回有效期
    )

    /** refresh token 换 access token（refreshToken 轮换）。网络/结构错误抛异常。 */
    fun exchangeToken(refreshToken: String): ExchangeResult {
        val body = JSONObject()
            .put("ClientID", CLIENT_ID)
            .put("RefreshToken", refreshToken)
            .put("ClientSecret", "-")
            .put("UserID", "")
            .toString()
        val req = Request.Builder()
            .url(OAUTH_HOST + EP_EXCHANGE)
            .post(body.toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .header("User-Agent", "Trae/$IDE_VERSION")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                throw java.io.IOException("ExchangeToken http ${resp.code}: ${(resp.body?.string() ?: "").take(200)}")
            }
            val obj = JSONObject(resp.body!!.string())
            val result = obj.optJSONObject("Result") ?: throw java.io.IOException("ExchangeToken 缺 Result")
            val token = result.optString("Token", "")
            if (token.isBlank()) throw java.io.IOException("ExchangeToken 未返回 Token")
            val newRefresh = result.optString("RefreshToken", "").takeIf { it.isNotBlank() }
            val expireAtRaw = result.optString("TokenExpireAt", "")
            // 到期时间：优先 TokenExpireAt（上游可能是毫秒）；缺失时用 TokenExpireDuration 兜底
            // （reference client.go:214-219 同款）。缺了兜底会回落成"旧的可能已过期的值"，
            // 导致每次启动都重复轮换 ExchangeToken。注意上游可能返回数字或字符串。
            val expiresAt = expireAtRaw.toLongOrNull()?.takeIf { it > 0 }?.let { normalizeMs(it) }
                ?: result.optLong("TokenExpireDuration", 0L).takeIf { it > 0 }
                    ?.let { System.currentTimeMillis() / 1000 + it }
            return ExchangeResult(token, newRefresh, expiresAt)
        }
    }

    data class UserInfo(val uid: String, val nickname: String, val enterpriseId: String)

    /** 用 access token 拉取账号信息。 */
    fun getUserInfo(accessToken: String): UserInfo {
        val body = JSONObject().put("ReqSource", "IDE").put("IDEVersion", IDE_VERSION).toString()
        val req = Request.Builder()
            .url(OAUTH_HOST + EP_USER_INFO)
            .post(body.toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .header("User-Agent", "Trae/$IDE_VERSION")
            .header("X-Cloudide-Token", accessToken)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                throw java.io.IOException("GetUserInfo http ${resp.code}: ${(resp.body?.string() ?: "").take(200)}")
            }
            val result = JSONObject(resp.body!!.string()).optJSONObject("Result")
                ?: throw java.io.IOException("GetUserInfo 缺 Result")
            return UserInfo(
                uid = result.optString("UserID", ""),
                nickname = result.optString("ScreenName", ""),
                enterpriseId = result.optString("EnterpriseID", ""),
            )
        }
    }

    /**
     * 启动即用的续期编排：读**当前账号**的加密凭证 → 若 token 在 [skewSec] 内将过期则换新 → 写回。
     * 返回当前有效 access token（无可用 token 时 null）。旧 refresh 失败时不破坏已存数据。
     *
     * 关键：账号自带 region，续期必须打到**该账号自己的** OAuth host。
     * 这里临时把 [TraeRegion.current] 切到账号的区域再换 token，
     * 否则国际服账号会被拿去国内服换（或反过来）——那是实打实踩过的坑。
     */
    fun restartWithValidToken(context: Context, skewSec: Long): String? {
        val cred = TraeAuthStore.load(context) ?: return null
        // 让后续 exchangeToken/getUserInfo 走该账号自己的区域
        TraeRegion.current = cred.region
        if (cred.accessToken.isNotBlank() && !expiredWithin(cred.expiresAt, skewSec)) {
            Log.i(TAG, "token 有效，无需续期 (uid=${cred.uid}, region=${cred.regionId}, exp=${cred.expiresAt})")
            return cred.accessToken
        }
        // 临近/已过期 → 用 refresh 换新的
        Log.i(TAG, "token 临近/已过期，触发续期 (uid=${cred.uid}, region=${cred.regionId}, exp=${cred.expiresAt})")
        if (cred.refreshToken.isBlank()) {
            Log.w(TAG, "无 refreshToken，无法续期")
            return cred.accessToken.takeIf { it.isNotBlank() }
        }
        val ex = exchangeToken(cred.refreshToken)
        TraeAuthStore.save(context, TraeAuthStore.Cred(
            accessToken = ex.accessToken,
            refreshToken = ex.refreshToken ?: cred.refreshToken,
            expiresAt = ex.expiresAt ?: cred.expiresAt,
            uid = cred.uid,
            nickname = cred.nickname,
            regionId = cred.regionId,
        ))
        Log.i(TAG, "续期成功 (新exp=${ex.expiresAt ?: cred.expiresAt}, refresh轮换=${ex.refreshToken != null})")
        return ex.accessToken
    }

    private fun expiredWithin(expiresAt: Long?, skewSec: Long): Boolean {
        if (expiresAt == null || expiresAt <= 0) return true
        return System.currentTimeMillis() / 1000 + skewSec >= expiresAt
    }

    /**
     * 自检 [expiredWithin] 的边界。
     *
     * 为什么要它：这是"要不要续期"的唯一判据，而服务现在**每个转发请求**都靠它决定
     * （见 LocalFakeServer.credProvider）。判错的方向后果不对称：
     *   - 该续期却判"有效" → 拿过期 token 打上游，持续 401（用户看到请求全废）
     *   - 不该续期却判"过期" → 反复续期，且 refresh 是轮换的，白白多消耗
     * 所以两边都钉住，并覆盖"0/负数=未知"这个必须当成已过期的约定。
     * 纯计算，不碰 KeyStore/网络，可安全在 onCreate 调用。
     */
    fun selfCheck() {
        val now = System.currentTimeMillis() / 1000
        val skew = 3600L
        // 未知到期时间（0 / null / 负数）必须判为过期 → 才会走续期，而不是盲用一个未知的 token
        check(expiredWithin(0L, skew)) { "自检失败：exp=0（未知）应判为已过期" }
        check(expiredWithin(null, skew)) { "自检失败：exp=null 应判为已过期" }
        check(expiredWithin(-5L, skew)) { "自检失败：负数 exp 应判为已过期" }
        // 已经过去 → 过期
        check(expiredWithin(now - 10, skew)) { "自检失败：已过去的 exp 应判为已过期" }
        // 落在 skew 窗口内（即将过期）→ 也必须续期，不能等真的 401
        check(expiredWithin(now + 60, skew)) { "自检失败：skew 内的 exp 应判为需续期" }
        // 远离 skew → 不该续期（否则每请求都轮换 refresh）
        check(!expiredWithin(now + skew + 60, skew)) { "自检失败：skew 外的 exp 不应判为续期" }

        // 登录链接是**现在唯一的登录方式**（内置 WebView 窗已移除），
        // 它坏了 = 用户彻底进不去，所以钉住关键字段。
        val base = "http://$CALLBACK_HOST:$CALLBACK_PORT$CALLBACK_PATH"
        check(isCallback(base)) { "自检失败：回调地址本身必须被 isCallback 认作合法" }
        // 缺 refreshToken 的裸回调不该被当成有效链接（否则会拿一个空 refresh 去换 token）
        check(extractRefreshToken(base) == null) { "自检失败：无 refreshToken 的回调不应解析出 token" }
        check(isCallback("$base?refreshToken=abc123")) { "自检失败：带 refreshToken 的回调应合法" }
        check(extractRefreshToken("$base?refreshToken=abc123") == "abc123") {
            "自检失败：refreshToken 解析错误"
        }
        // 别把别的站点的链接误判成回调（用户可能粘错东西）
        check(!isCallback("http://evil.example.com/authorize?refreshToken=abc")) {
            "自检失败：非本机回调地址不该被认作合法"
        }
    }

    /** TokenExpireAt 毫秒 → Unix 秒（>1e12 判定毫秒）。 */
    private fun normalizeMs(v: Long): Long = if (v > 1_000_000_000_000L) v / 1000 else v

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun parseJson(raw: String?): JSONObject? = try { JSONObject(raw ?: "") } catch (_: Exception) { null }

    private fun hexStr(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder(bytes * 2)
        for (x in b) { sb.append("%02x".format(x)) }
        return sb.toString()
    }
}
