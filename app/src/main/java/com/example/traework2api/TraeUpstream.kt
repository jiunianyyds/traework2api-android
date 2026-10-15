package com.example.traework2api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 阶段2第3步：真实 Trae 上游转发。
 *
 * 职责：把已改写成 Trae 格式的请求体 POST 到 Trae 官方代理，读回 Trae 的 SSE 流，
 * 逐条 `output`/`token_usage`/`done`/`error` 事件转换成 OpenAI chat.completion.chunk SSE，
 * 通过回调 [out] 逐块 (data: {...}\n\n) 交给调用方写出。
 *
 * 所有常量与字段映射均对齐 reference/traework2api（已实测）。
 * 注意：traeToken 绝不落日志/源码/Git。
 */
object TraeUpstream {

    /** 日志 tag（任务书要求用 `TraeUpstream:*`；**只打模型名，绝不打 token**）。 */
    private const val TAG = "TraeUpstream"

    // host 随区域变化，统一从 TraeRegion 取（见该文件；国内版为已实测值）。
    //
    // ⚠️ **不要在长流程里反复读这些 getter**：`TraeRegion.current` 会被 UI 线程改写
    // （切账号 → `syncRegionToActiveAccount`、复制登录链接 → `TraeRegion.set`），
    // 而本文件的方法跑在服务连接线程上、单次请求可长达 180s。中途翻面 = 一次 CN 分支
    // 的请求把 CN token 发往 SG host（区域串号，见 [chatStream] 的快照写法）。
    // 规则：**每个对外方法入口快照一次区域，之后全程用局部值**。
    private val region: TraeRegion get() = TraeRegion.current
    private val AGENT_HOST: String get() = region.agentHost
    private val UG_HOST: String get() = region.ugHost

    const val EP_CHAT = "/api/agent/v3/llm_utils_chat"
    const val EP_MODELS = "/api/ide/v1/get_detail_param"
    const val EP_CHECKIN_STATUS = "/trae/api/v2/ug/checkin_credits/status"
    const val EP_CLAIM = "/trae/api/v2/ug/checkin_credits/claim"
    const val EP_ENT_USAGE = "/trae/api/v2/pay/ide_user_ent_usage"

    const val APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8"
    const val IDE_VERSION = TraeRegion.IDE_VERSION
    const val IDE_VERSION_CODE = "20260716"
    const val DEVICE_BRAND = "83DG"
    const val OS_VERSION = "Windows 11 Pro"
    /**
     * 上游 function 值。**必须 `solo_work_remote`**：
     *
     * 实测（2026-09-25，CN 账号）新模型（`deepseek-v4.1-flash` / `glm-5.3` / `step-5-preview`）
     * **只挂在 `solo_work_remote` 下**；用老的 `solo_work_lite` 打它们一律 `error code=4001
     * param is invalid`。反过来旧 12 个模型在 `solo_work_remote` 下逐个实测全部正常，
     * 工具调用也无回归 —— 所以全局换过来是"白捡"新模型。
     */
    const val FUNCTION = "solo_work_remote"

    /**
     * 请求没带 model 时用的默认模型。
     *
     * 用 DeepSeek-V4-Flash：**积分便宜**，且用户明确要求"别默认用贵的模型"
     * （默认值会被所有没指定模型的客户端共用，选贵的一天就能烧掉大量积分）。
     * 想用更强的模型由客户端在请求里显式指定，不在这里替用户决定。
     */
    const val DEFAULT_MODE = "DeepSeek-V4-Flash"

    /** 合法模型 id 形状（上游 `config_name` 就是这套字符集，含 `o4-mini` 这类名字）。 */
    private val MODEL_ID_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** OpenAI 请求体 → Trae 请求体（原地改写）。 */
    fun prepareBody(raw: JSONObject): JSONObject {
        raw.put("stream", true)
        raw.put("function", FUNCTION)

        if (raw.has("messages")) {
            val msgs = raw.getJSONArray("messages")
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                val role = m.optString("role", "")
                if (role == "assistant" && m.has("tool_calls")) {
                    val kept = JSONArray()
                    val tcs = m.getJSONArray("tool_calls")
                    for (j in 0 until tcs.length()) {
                        val tc = tcs.optJSONObject(j) ?: continue
                        val fn = tc.optJSONObject("function")
                        if (fn != null) { tc.put("function_call", fn); tc.remove("function") }
                        val fc = tc.optJSONObject("function_call")
                        if (fc != null && fc.optString("name", "").trim().isEmpty()) continue
                        kept.put(tc)
                    }
                    if (kept.length() == 0) m.remove("tool_calls") else m.put("tool_calls", kept)
                }
                if (m.has("content") && !m.isNull("content") && m.get("content") is String) {
                    m.put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", m.getString("content"))))
                }
            }
        }

        var model = raw.optString("model", "").trim()
        if (model.isEmpty()) model = DEFAULT_MODE
        // **模型名是信任边界**：它来自客户端请求体，会被原样写进 `config_name`/`model` 后
        // POST 到上游。不校验的话，`"model":"a\" ,\"x\":\"…"` 这类值能直接篡改出站 JSON 结构，
        // 控制字符还能污染日志。上游模型名本来就是 `[A-Za-z0-9._-]` 形状（见 ModelsRepo 的 id），
        // 所以按形状收紧，不合法就直接回落默认模型而不是把垃圾发出去。
        if (!MODEL_ID_RE.matches(model)) {
            android.util.Log.w(TAG, "模型名非法，回落默认: ${model.take(40)}")
            model = DEFAULT_MODE
        }
        raw.put("config_name", model)
        raw.put("model", model)

        normalizeToolChoice(raw)
        normalizeTools(raw)
        return raw
    }

    private fun normalizeToolChoice(obj: JSONObject) {
        val suppress = { obj.remove("tools"); obj.remove("functions") }
        if (!obj.has("tool_choice")) return
        val tc = obj.get("tool_choice")
        when (tc) {
            is String -> {
                if (tc.trim().equals("none", true)) { obj.remove("tool_choice"); suppress() }
            }
            is JSONObject -> {
                when (tc.optString("type", "").lowercase().trim()) {
                    "none" -> { obj.remove("tool_choice"); suppress() }
                    "auto", "required" -> obj.put("tool_choice", tc.optString("type").lowercase().trim())
                    "function" -> {
                        var name = tc.optJSONObject("function")?.optString("name", "") ?: ""
                        if (name.isEmpty()) name = tc.optString("name", "")
                        name = name.trim()
                        obj.put("tool_choice", if (name.isNotEmpty()) name else "auto")
                    }
                    else -> obj.remove("tool_choice")
                }
            }
            else -> obj.remove("tool_choice")
        }
    }

    private fun normalizeTools(obj: JSONObject) {
        if (!obj.has("tools")) return
        val list = obj.optJSONArray("tools") ?: return
        if (list.length() == 0) { obj.remove("tools"); return }
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val params = fn.opt("parameters")
            if (params is JSONObject) fn.put("parameters", params.toString())
            out.put(t)
        }
        if (out.length() == 0) obj.remove("tools") else obj.put("tools", out)
    }

    /**
     * SOLO 请求头（chat / fetchModels 共用；token 只进内存，不落盘日志）。
     *
     * [region] 必须由调用方**在入口快照好再传进来**，不能在这里读全局 —— 见文件头
     * 关于 `region` getter 的说明（长流程中途翻面会把 token 发到错误区域）。
     */
    private fun Builder.solo(token: String, stream: Boolean, uid: String = "", region: TraeRegion = TraeRegion.current): Builder {
        if (stream) header("Accept", "text/event-stream") else header("Accept", "application/json")
        val b = header("Content-Type", "application/json")
            .header("User-Agent", "Trae/$IDE_VERSION")
            .header("Authorization", "Cloud-IDE-JWT $token")
            .header("X-Cloudide-Token", token)
            .header("X-Ide-Token", token)
        // reference headers.go:26-28 对 SOLO 请求必带 X-Uid（空则不带）。
        // 缺这个头时上游会接受连接却不返回任何数据（聊天请求挂死）。
        if (uid.isNotBlank()) b.header("X-Uid", uid)
        return b
            .header("X-App-Id", APP_ID)
            .header("X-App-Version", "default")
            .header("X-Ide-Version", IDE_VERSION)
            .header("X-Ide-Version-Code", IDE_VERSION_CODE)
            .header("X-App-Version-Code", IDE_VERSION_CODE)
            .header("X-Ide-Version-Type", "stable")
            .header("X-Device-Type", "windows")
            .header("X-OS-Version", OS_VERSION)
            .header("X-Device-Brand", DEVICE_BRAND)
            .header("Request-Traffic-Type", "prod")
    }

    /** ug 接口（usage / checkin status / claim）headers + 出参 JSON；token 只进内存。
     *
     * 头集合逐条对齐参考实现 `luckymiaow/trae-mate`（`checkin.rs::build_headers`，
     * 源头 `inlayin/trae-check` + `device_proxy`）—— 那是**实测能签到成功**的一套。
     * 关键：三个配套设备标识都必须带，缺任一个上游仍判为"共用设备"→ 9074。
     */
    private fun ug(token: String, url: String, uid: String, region: TraeRegion = TraeRegion.current): JSONObject? {
        val b = Request.Builder().url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Cloud-IDE-JWT $token")
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("User-Agent", "VSCode 1.107.1 (TRAE SOLO CN)")
            .header("X-Market-Client-Id", "VSCode 1.107.1")
            .header("X-Market-User-Id", marketUserIdFor(uid))
            .header("X-Device-Id", deviceIdFor(uid))
            .header("Vscode-Sessionid", sessionIdFor(uid))
            .header("X-Lgw-Req-Sdk-Type", "3")
            .header("Package-Type", "stable_cn")
            .header("X-Lscbd-Aid", "787976")
            .header("X-Lscbd-Platform", "windows")
            .header("App-Version", "0.1.45")
            .header("X-Tt-Trace-Id", "00-" + uuidNoDash().substring(0, 16) + "-01")
            .header("X-Request-Id", java.util.UUID.randomUUID().toString())
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "no-cors")
            .header("Sec-Fetch-Site", "none")
            .header("X-User-Region", region.userRegionHeader.ifBlank { "CN" })
        val req = b.build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful || r.body == null) null else JSONObject(r.body!!.string())
            }
        } catch (_: Exception) { null }
    }

    private fun uuidNoDash(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    // ===== 设备身份：从 uid 稳定派生（对齐 device_proxy / device_map.rs 算法）=====
    //
    // 上游按**设备维度**限制签到（同一设备每天只能领一次）。多个账号共用同一设备 id
    // 会被判「操作太过频繁」(code 9074) —— 参考项目 trae_instance.rs:111 原话。
    // 所以每个账号必须有**自己的、稳定的**设备身份：以 uid 为种子确定性派生，
    // 同一账号永远得到同一套（不漂移），不同账号天然隔离（不互撞）。

    /** 确定性字节流：反复取 SHA-256(salt:seed || 计数器大端4字节)，直到够长。 */
    private fun seededStream(seed: String, salt: String, nbytes: Int): ByteArray {
        val data = "$salt:$seed".toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (out.size() < nbytes) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update(data)
            md.update(byteArrayOf(
                (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()
            ))
            out.write(md.digest())
            i++
        }
        return out.toByteArray().copyOf(nbytes)
    }

    /** `x-device-id`：15 位纯数字（**不是** hex —— 格式错了上游不认这套设备身份）。 */
    internal fun deviceIdFor(uid: String): String {
        val bs = seededStream(uid, "devid", 16)
        val sb = StringBuilder(15)
        for (i in 0 until 15) sb.append(((bs[i].toInt() and 0xFF) % 10).toString())
        return sb.toString()
    }

    /** `vscode-sessionid`：64 位 hex。 */
    internal fun sessionIdFor(uid: String): String {
        val bs = seededStream(uid, "sess", 32)
        val sb = StringBuilder(64)
        for (b in bs) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString().substring(0, 64)
    }

    /** `x-market-user-id`：标准 UUID v4（确定性派生，修正 version/variant 位）。 */
    internal fun marketUserIdFor(uid: String): String {
        val bs = seededStream(uid, "market", 16)
        bs[6] = ((bs[6].toInt() and 0x0F) or 0x40).toByte()
        bs[8] = ((bs[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = StringBuilder(32)
        for (b in bs) hex.append(String.format("%02x", b.toInt() and 0xFF))
        val h = hex.toString()
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" +
            h.substring(16, 20) + "-" + h.substring(20, 32)
    }

    /** 当前可用积分。来源于 ide_user_ent_usage.usage_summary。失败/区域未配置返回 null。
     *
     * 可用 = `total_amount − consumed_amount`（实测 5100 − 1931.46 = 3168.54，UI 取整 3168）。
     *
     * 实测（2026-10-10 dump 过原始返回）：`total_amount` 是**当前所有未过期积分包的
     * `credits_limit` 之和**，即"额度池总量"——**不是固定上限**，会随积分包过期/新增而变
     * （见过 8350 → 5100），所以它不适合当"当前可用"以外的对外指标，已不再展示。
     * 校验：`consumed_amount / total_amount` 与上游给的 `consumption_ratio` 逐位吻合
     * （1931.46 / 5100 = 0.37871764705882355），确认这两个字段就是"池内已用 / 池总量"。
     */
    fun fetchUsage(tok: String, uid: String, region: TraeRegion = TraeRegion.current): Long? {
        val reg = region   // 批量签到按账号传区域；单账号调用不传=沿用全局（旧路径不变）
        if (!reg.canQueryQuota) return null
        val sum = ug(tok, reg.ugHost + EP_ENT_USAGE, uid, reg)?.optJSONObject("usage_summary") ?: return null
        val total = sum.optDouble("total_amount", -1.0)
        if (total < 0) return null
        return (total - sum.optDouble("consumed_amount", 0.0)).toLong()
    }

    /**
     * 签到状态（`checkin_credits/status`）。失败/区域未配置返回 null。
     *
     * 上游真实返回（2026-09-25 用真账号 dump 过，三种请求体返回一致）：
     * `{"checked_in":true,"code":0,"credits":150,"did_checked_in":false,"enable":true,`
     * `"extra_credits":50,"message":"success"}`
     *
     * - [CheckinStatus.checkedIn] 今日是否已签（true 时 UI 必须置灰按钮，见 MainActivity）
     * - [CheckinStatus.credits] 签到每日可领积分
     * - [CheckinStatus.enable] 该账号是否开放签到；上游 `false` 时**不该发 claim**
     */
    data class CheckinStatus(val checkedIn: Boolean, val credits: Long, val enable: Boolean)

    fun fetchCheckin(tok: String, uid: String, region: TraeRegion = TraeRegion.current): CheckinStatus? {
        val reg = region   // 同 fetchUsage：批量按账号传，缺省沿用全局
        if (!reg.canQueryQuota) return null
        val root = ug(tok, reg.ugHost + EP_CHECKIN_STATUS, uid, reg) ?: return null
        return parseCheckin(root)
    }

    /**
     * 执行签到（`checkin_credits/claim`）。返回业务 code，网络失败返回 null。
     *
     * ⚠️ **这里曾连着错两次，两次都是"把一个业务码读成了状态"**，别再退回旧写法：
     *
     * 1. 最初 `9004 -> "今日已签到"`。实测（2026-09-26）证明 `9004` 是
     *    **缺设备头 → 参数校验失败**（`The submitted order parameters are incorrect`），
     *    不是"已签"——那正是"没签到却提示签到成功"的源头。
     * 2. 上一版补了 `X-Device-Id` 后仍回 `9074`，便下结论"上游限流、客户端无解"。
     *    **也错了**。`9074`（"操作太过频繁"）的真正成因是**设备身份不独立**：
     *    上游按**设备维度**限制领取，只发一个设备 id（尤其格式不对时）会被判成
     *    "多个账号共用同一设备" → 9074。参考实现 `luckymiaow/trae-mate` 的
     *    `trae_instance.rs:111` 原话："多个账号共用同一设备 id 领取会被判
     *    操作太过频繁(code 9074)"。修法是补齐 [deviceIdFor] / [sessionIdFor] /
     *    [marketUserIdFor] 三个**配套**标识（缺一不可）。
     *
     * ⚠️ 幂等性：重复领取**不会**扣积分或改坏状态（已复测 `checked_in`/`credits` 不变），
     * 但会产生无谓请求 —— UI 仍应在 `checkedIn=true` 时禁止点击。
     *
     * ⚠️ `code` **不能单独当成功判据**：`ug()` 只在 HTTP 非 2xx / 解析失败时返回 null，
     * 而上游业务失败时也回 **HTTP 200 + 业务体**，所以被拒也可能带 `code` 回来。
     * 成功与否由 [checkinSucceeded]（"成功码 AND 状态跃迁"的合取）判定，`code` 只用于文案。
     */
    fun claimCheckin(tok: String, uid: String, region: TraeRegion = TraeRegion.current): Int? {
        val reg = region   // 同 fetchUsage：批量按账号传，缺省沿用全局
        if (!reg.canQueryQuota) return null
        return ug(tok, reg.ugHost + EP_CLAIM, uid, reg)?.optInt("code", -1)
    }

    /**
     * 本次签到是否**真的**刚签上。判据是**合取**：成功码 + 状态跃迁，两者缺一不可。
     *
     * - 只看 `code`：会被"HTTP 200 + 业务拒绝"骗 —— `ug()` 只在非 2xx 返回 null。
     * - 只看状态跃迁：本地状态陈旧时（卡片显示"未签"、其实今天早已签过）会误报
     *   —— `code=9004` 是"今天已签过"，不是"这次签上了"，而积分也不会变。
     *
     * 合取之后 `code=9004` 不再被算成本次成功，由调用方走 `after.checkedIn` 分支
     * 正确归到「今日已签到」。这才是"没签到就不许说成功"的完整实现。
     *
     * - 查不到状态（[after] 为 null）一律判**未成功**：宁可不报喜，也不能重演误报。
     * - 计划前就已签（[before] 已 true）不算本次成功。
     * - [code] 为 null（网络失败）或 `-1`（字段缺失）都不算成功。
     */
    internal fun checkinSucceeded(before: CheckinStatus?, after: CheckinStatus?, code: Int?): Boolean =
        code == 0 && after != null && after.checkedIn && before?.checkedIn != true

    /**
     * 把上游 status 的 JSON 解析成 [CheckinStatus]。
     *
     * 抽出来单独一个函数是为了**能自检**（`fetchCheckin` 要发网络请求，自检里不能打上游）。
     * 回落规则：字段缺失按 `false`/`0` 处理，**不抛异常** —— 上游多给/少给字段不该让页面崩。
     */
    internal fun parseCheckin(root: JSONObject): CheckinStatus = CheckinStatus(
        checkedIn = root.optBoolean("checked_in", false),
        credits = root.optLong("credits", 0),
        enable = root.optBoolean("enable", false),
    )

    /**
     * 拉上游真实模型表（`get_detail_param`，照抄 reference/traework2api 的 FetchModels）。
     *
     * 返回 OpenAI `/v1/models` 形状的数组（id/name/config_name/context_length/object/owned_by/created）；
     * 失败/解析出空表返回 null —— **调用方必须保留静态兜底**，空表覆盖会让所有客户端看不到模型。
     *
     * token 只进内存与请求头，绝不落日志。
     */
    fun fetchModels(tok: String, uid: String = ""): JSONArray? {
        // 区域分派交给调用方判断？不 —— 表也走同一个 SOLO 端点，只有 CN 实测过。
        val reg = TraeRegion.current   // 快照：见文件头 region 说明
        if (!reg.canQueryQuota) return null
        val body = JSONObject()
            .put("function", FUNCTION)
            .put("config_names", JSONObject.NULL)
            .put("need_prompt", false)
            .put("current_config_info", JSONObject.NULL)
            .put("poly_prompt", true)
            .put("mode_type", JSONObject.NULL)
            .put("agent_type", JSONObject.NULL)
        val req = Request.Builder()
            .url(reg.agentHost + EP_MODELS)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .solo(tok, stream = false, uid = uid, region = reg)
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    android.util.Log.w(TAG, "拉模型表失败: http ${r.code} ${text.take(200)}")
                    return null
                }
                parseModels(JSONObject(text))
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "拉模型表异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 解析 `config_info_list` → `/v1/models` 数组。空 `config_name` 跳过（reference 同款闸）。
     *
     * `name` 取 `display_config.display_name`（空则回落 id）；
     * `context_length` 取 `model_detail_list[0].max_input_tokens`（取不到回落 131072，与静态表一致）。
     *
     * **过滤**：上游表混着大量非聊天项（`custom_model_*` 占位、`summary` / `file_search_agent` /
     * `browser_use_subagent` 等内部 agent、以及各类子能力项）。它们当 config_name 发过去
     * 聊天会 4001，摆在「模型」Tab 里只会让用户选错。判据见 [isChatModel]。
     */
    internal fun parseModels(root: JSONObject): JSONArray? {
        val list = root.optJSONArray("config_info_list") ?: return null
        val arr = JSONArray()
        for (i in 0 until list.length()) {
            val cfg = list.optJSONObject(i) ?: continue
            val id = cfg.optString("config_name", "").trim()
            if (id.isEmpty()) continue
            if (!isChatModel(cfg)) continue
            val name = cfg.optJSONObject("display_config")?.optString("display_name", "")?.trim().orEmpty()
            var ctx = 0L
            cfg.optJSONArray("model_detail_list")?.optJSONObject(0)
                ?.let { ctx = it.optLong("max_input_tokens", 0L) }
            if (ctx <= 0L) ctx = 131072L
            arr.put(
                JSONObject()
                    .put("id", id).put("name", name.ifEmpty { id })
                    .put("config_name", id).put("context_length", ctx)
                    .put("object", "model").put("owned_by", "trae-solo")
                    .put("created", 1753600000)
            )
        }
        return if (arr.length() == 0) null else arr
    }

    /**
     * 这个条目是不是**Trae 官方内置、且能直接聊天**的模型。
     *
     * 判据全部来自上游字段（2026-09-25 把 49 项逐项 dump 出来核对过），**不再猜名字**：
     *
     *  1. **`config_source == 1`** —— 只留官方内置。实测你的表里 `config_source=3` 的那批
     *     （`mistral-small-4` / `gemini-3.1-pro`(肖恩ai-…) / `glm-4.5-air` / `oc/muse-spark-…` /
     *     `deepseek-v4-flash-0731`(ZZ …) / 两个 `TRAE-DeepSeek-V4-Flash`）正是**用户自己接入的第三方 API**。
     *     它们不该出现在"Trae 内置模型"页里 —— 名字里的「肖恩ai」「ZZ」就是用户配置时的前缀。
     *  2. **`display_config.is_custom_model == false`** —— 与上一条互为印证（第三方项均为 true）。
     *  3. **`is_invisible_to_user == false`** —— 上游自己标"不对用户展示"的内部项
     *     （实测：`glm-5` / `glm-5-turbo` / `DeepSeek-V4-Flash` / `DeepSeek-V4-Pro` /
     *     `summary` / `file_search_agent` / `sagitta` / `aquila`…）不该露出来。
     *     ⚠️ 注意：**同名两条**（一条 invisible、一条可见）正是靠这条区分开的，
     *     之前只按名字过滤会把两条都留下，UI 里出现重复的 `DeepSeek-V4-Flash`。
     *  4. **名字里没有内部 agent 词形** —— 兜底：`custom_model_*` 占位、`*_agent`、`summary`、
     *     `sagitta`、`aquila`、`file_search_*`、`explore_*`、`computer_use_*`、`browser_use_*`。
     *     这些即使上游标了可见也不是给人聊天的。
     *
     * 任一条不满足即排除。前三条是上游的**权威字段**，第四条只是补充，所以不再依赖名字猜测。
     *
     * ⚠️ **已知局限**：本判据无法排除「官方内置但当前账号用不了」的模型。
     * 实测 `Doubao-Seed-Evolving`（会员专属，见任务书 §1.3）确实出现在表里且
     * `config_source=1`/可见，但真实聊天返回 `4001`（当前账号不是会员）。
     * 上游表里没有一个"当前账号是否有权用"的字段可判，所以**只能留着**——
     * 它确实是一个 Trae 内置模型，只是该账号无权调用。真要按权限过滤得逐个试聊天（太贵，不做）。
     */
    internal fun isChatModel(cfg: JSONObject): Boolean {
        if (cfg.optInt("config_source", 0) != 1) return false
        val dc = cfg.optJSONObject("display_config")
        if (dc?.optBoolean("is_custom_model", false) == true) return false
        if (cfg.optBoolean("is_invisible_to_user", false)) return false
        if (dc == null) return false          // 没有 display_config 的条目不是正常模型
        return isBuiltinChatName(cfg.optString("config_name", "").trim())
    }

    /** 名字词形兜底（见 [isChatModel] 第 4 条）：挡住内部 agent / 占位项。 */
    private fun isBuiltinChatName(id: String): Boolean {
        val s = id.lowercase()
        if (s.startsWith("custom_model")) return false
        val blocked = listOf(
            "agent", "subagent", "summary", "sagitta", "aquila",
            "file_search", "explore_", "computer_use", "browser_use",
        )
        return blocked.none { s.contains(it) }
    }

    /**
     * 解析自检。**fixture 照抄上游真实字段形状**（2026-09-25 dump 过 49 项），
     * 每个 check 对应一个真实踩过/差点踩到的坑。
     */
    fun selfCheck() {
        // 设备身份派生必须与参考实现（trae-mate `device_map.rs` 的 Python 对标向量）**逐位一致**：
        // 派生错了设备身份就不独立，领取会被判"共用设备"→ 9074。改派生逻辑必先过这里。
        check(deviceIdFor("1234567890123456") == "413174708280782") { "自检失败：device_id 派生不符" }
        check(sessionIdFor("1234567890123456") ==
            "fbabf7aa1e173b90385c623e1ec49157860cc07a4b01f53f7ca141b17d876eae") { "自检失败：sessionid 派生不符" }
        check(marketUserIdFor("1234567890123456") == "746608f9-7f37-4960-b4c7-8553cec6d366") {
            "自检失败：market uuid 派生不符"
        }
        check(deviceIdFor("9876543210") == "630512735296035") { "自检失败：device_id 派生不符(第二组)" }
        // 同 uid 必须稳定（不漂移）、不同 uid 必须隔离（不互撞）—— 这是"设备身份独立"的全部要求
        check(deviceIdFor("a") == deviceIdFor("a")) { "自检失败：同 uid 派生必须稳定" }
        check(deviceIdFor("a") != deviceIdFor("b")) { "自检失败：不同 uid 必须派生不同设备" }

        // **格式契约**：上游是按格式认这三个标识的，格式错了就等于没带（会被判"共用设备"→9074）。
        // 上面钉的是"值"，这里钉"形状"，两者都要 —— 换实现方式时值可能不变而形状会变。
        // 用一个真实 uid 形状的种子（16 位 CN 号）验证，别用 "a"/"b" 这类短种子蒙混过去。
        run {
            val real = "3695871032827027"
            val dev = deviceIdFor(real)
            val sess = sessionIdFor(real)
            val mkt = marketUserIdFor(real)
            check(dev.length == 15 && dev.all { it.isDigit() }) { "自检失败：device_id 必须是 15 位纯数字" }
            check(sess.length == 64 && sess.all { it in "0123456789abcdef" }) { "自检失败：sessionid 必须是 64 位小写 hex" }
            check(mkt.length == 36 && mkt[14] == '4' && mkt[19] in "89ab" &&
                mkt.count { it == '-' } == 4) { "自检失败：market id 必须是合法 UUID v4" }
            // 三个标识之间也必须互不相同（同一 uid 派生出三份一样的就算串了）
            check(dev != sess && sess != mkt && dev != mkt) { "自检失败：三个设备标识不许互相相同" }
            // 换一个 uid 后三个都要变（防"某个 salt 忘了拼进种子"这类错误）
            val other = "7687189514112844821"
            check(deviceIdFor(other) != dev && sessionIdFor(other) != sess &&
                marketUserIdFor(other) != mkt) { "自检失败：换 uid 后三个标识都必须变" }
        }

        /** 造一条上游条目：[src]=config_source、[invisible]/[custom] 是上游自己的标记。 */
        fun entry(id: String, name: String = "", src: Int = 1, invisible: Boolean = false,
                  custom: Boolean = false, ctx: Long = 0L, withDc: Boolean = true): JSONObject {
            val o = JSONObject().put("config_name", id)
                .put("config_source", src).put("is_invisible_to_user", invisible)
            if (withDc) {
                o.put("display_config", JSONObject()
                    .put("display_name", name).put("is_custom_model", custom))
            }
            if (ctx > 0) o.put("model_detail_list", JSONArray().put(JSONObject().put("max_input_tokens", ctx)))
            return o
        }

        val root = JSONObject().put("config_info_list", JSONArray()
            .put(entry("DeepSeek-V4-Flash", "DeepSeek V4 Flash", ctx = 65536))   // 官方内置可见 → 留下
            .put(entry("  "))                                                    // 空 config_name → 跳过
            .put(entry("no-name-no-detail", withDc = false))                     // 无 display_config → 过滤
            .put(entry("custom_model_gpt-6", "x"))                               // 占位项 → 过滤
            .put(entry("summary", "x"))                                          // 内部 agent → 过滤
            .put(entry("file_search_agent", "search_agent_qwen_fast"))           // 内部 agent → 过滤
            .put(entry("glm-5", "GLM-5", invisible = true))                      // 上游标"不对用户展示" → 过滤
            // 🔴 第三方接入：config_source=3 + is_custom_model=true（"肖恩ai-…"/"ZZ …" 就是这类）→ 过滤
            .put(entry("gemini-3.1-pro", "肖恩ai-gemini-3.1-pro", src = 3, custom = true))
            .put(entry("DeepSeek-V4-Flash", "TRAE-DeepSeek-V4-Flash", src = 3, custom = true)))
        val out = parseModels(root) ?: throw IllegalStateException("自检失败：模型表解析返回 null")

        // 只有官方内置那条该留下：空名、无 display_config、占位、agent、不可见、第三方全部挡掉
        check(out.length() == 1) { "自检失败：应只剩 1 条官方内置模型，得到 ${out.length()} 条" }
        val a = out.getJSONObject(0)
        check(a.getString("id") == "DeepSeek-V4-Flash") { "自检失败：留下的不是官方内置模型" }
        check(a.getString("name") == "DeepSeek V4 Flash") { "自检失败：name 应取 display_name" }
        check(a.getLong("context_length") == 65536L) { "自检失败：context_length 应取 max_input_tokens" }

        // 回落规则：name 缺省回落 id、context 缺省回落 131072
        val fb = parseModels(JSONObject().put("config_info_list",
            JSONArray().put(entry("no-name-no-detail"))))!!
        check(fb.getJSONObject(0).getString("name") == "no-name-no-detail") { "自检失败：name 缺省应回落 id" }
        check(fb.getJSONObject(0).getLong("context_length") == 131072L) { "自检失败：context 缺省应回落 131072" }

        // 空表 / 无表必须返回 null（否则会把静态表覆盖成空 = 所有客户端看不到模型）
        check(parseModels(JSONObject()) == null) { "自检失败：没有 config_info_list 时应返回 null" }
        check(parseModels(JSONObject().put("config_info_list", JSONArray())) == null) {
            "自检失败：空表应返回 null（空表覆盖会让所有客户端看不到模型）"
        }

        // 逐条边界（用真实 id 钉住，防止以后改判据把真模型误伤）
        val builtin = { id: String -> entry(id, "x") }
        check(isChatModel(builtin("deepseek-v4.1-flash"))) { "自检失败：新模型 deepseek-v4.1-flash 被误过滤" }
        check(isChatModel(builtin("glm-5.3"))) { "自检失败：新模型 glm-5.3 被误过滤" }
        check(isChatModel(builtin("step-5-preview"))) { "自检失败：新模型 step-5-preview 被误过滤" }
        check(isChatModel(builtin("DeepSeek-V4-Flash"))) { "自检失败：DeepSeek-V4-Flash 被误过滤" }
        check(!isChatModel(entry("mistral-small-4", src = 3, custom = true))) { "自检失败：第三方模型应被过滤" }
        check(!isChatModel(entry("gemini-3.1-pro", src = 3, custom = true))) { "自检失败：第三方模型应被过滤" }
        check(!isChatModel(entry("glm-4.5-air", src = 3, custom = true))) { "自检失败：第三方模型应被过滤" }
        check(!isChatModel(builtin("custom_model_gpt-6"))) { "自检失败：custom_model_* 占位项应被过滤" }
        check(!isChatModel(builtin("summary"))) { "自检失败：summary 应被过滤" }
        check(!isChatModel(builtin("file_search_agent"))) { "自检失败：内部 agent 应被过滤" }
        check(!isChatModel(builtin("browser_use_subagent"))) { "自检失败：内部 subagent 应被过滤" }
        check(!isChatModel(builtin("explore_sub_agent_v2"))) { "自检失败：内部 agent 应被过滤" }
        check(!isChatModel(entry("glm-5", invisible = true))) { "自检失败：上游标不可见的项应被过滤" }

        // 签到状态解析：用**上游真实返回**当 fixture（2026-09-25 真账号 dump 的原文）
        val real = parseCheckin(JSONObject(
            """{"checked_in":true,"code":0,"credits":150,"did_checked_in":false,""" +
                """"enable":true,"extra_credits":50,"message":"success"}"""
        ))
        check(real.checkedIn) { "自检失败：checked_in=true 应解析为已签" }
        check(real.credits == 150L) { "自检失败：credits 应解析为 150" }
        check(real.enable) { "自检失败：enable=true 应解析为开放签到" }
        // 未签 + 未开放：两个布尔必须各自独立解析（混了会让按钮该灰的时候可点）
        val fresh = parseCheckin(JSONObject("""{"checked_in":false,"enable":false,"credits":100}"""))
        check(!fresh.checkedIn && !fresh.enable) { "自检失败：未签/未开放应都为 false" }
        check(fresh.credits == 100L) { "自检失败：未签时 credits 仍应解析" }
        // 字段缺失不许崩（上游加字段/少字段都要活）
        val empty = parseCheckin(JSONObject("{}"))
        check(!empty.checkedIn && !empty.enable && empty.credits == 0L) { "自检失败：字段缺失应回落而非抛异常" }

        // 签到成功的判据是「成功码 AND 状态跃迁」的**合取**，两个方向都要钉住：
        // 只会 code → 被"HTTP 200 + 业务拒绝"骗；只看跃迁 → 把 code=9004 的
        // "今天早已签过"误报成"这次签上了"（积分并不会变，这就是误报的残留形态）
        check(checkinSucceeded(fresh, real, 0)) { "自检失败：成功码+未签→已签应判成功" }
        check(!checkinSucceeded(fresh, fresh, 0)) { "自检失败：状态没变不许报成功（误报根因）" }
        check(!checkinSucceeded(fresh, real, 9004)) { "自检失败：9004 是已签过，不算本次刚签上" }
        check(!checkinSucceeded(fresh, real, null)) { "自检失败：网络失败不许报成功" }
        check(!checkinSucceeded(fresh, real, -1)) { "自检失败：码缺失（-1）不许报成功" }
        check(!checkinSucceeded(real, real, 0)) { "自检失败：本来就已签不算本次成功" }
        check(!checkinSucceeded(null, null, 0)) { "自检失败：查不到状态不许报成功" }
        check(!checkinSucceeded(fresh, null, 0)) { "自检失败：重查失败不许报成功" }

        // 模型名信任边界：合法名原样透传，非法名（JSON 注入/控制字符/超长/空）回落默认。
        // 这钉的是"客户端不能靠 model 字段篡改出站 JSON"。
        check(MODEL_ID_RE.matches("DeepSeek-V4-Flash")) { "自检失败：正常模型名被判非法" }
        check(MODEL_ID_RE.matches("glm-5.3")) { "自检失败：带点的模型名被判非法" }
        check(MODEL_ID_RE.matches("o4-mini")) { "自检失败：带连字符的模型名被判非法" }
        check(!MODEL_ID_RE.matches("a\",\"x\":\"1")) { "自检失败：注入型 model 必须被拒" }
        check(!MODEL_ID_RE.matches("a\nb")) { "自检失败：含换行的 model 必须被拒" }
        check(!MODEL_ID_RE.matches("")) { "自检失败：空 model 必须被拒（走默认回落）" }
        check(!MODEL_ID_RE.matches("a".repeat(65))) { "自检失败：超长 model 必须被拒" }
        // 真跑一遍查表路径：非法名必须真的变成默认模型，而不只是正则判负。
        val ghost = prepareBody(JSONObject().put("model", "bad\"name").put("messages", JSONArray()))
        check(ghost.getString("config_name") == DEFAULT_MODE) { "自检失败：非法 model 未回落到默认" }
        check(ghost.getString("model") == DEFAULT_MODE) { "自检失败：非法 model 未同步回落到默认" }
    }

    /**
     * 转发到 Trae 并流式转换。每产生一块要写给客户端的内容就调用 [out]（已是完整 `data: ...\n\n`）。
     * 流转结束后（成功/出错）调用 [onResult]：参数为 最终 usage（可能 null）与 错误类型（null=成功）。
     * 上游 HTTP 出错时也通过 [out] 输出 error 事件 + [DONE]。
     */
    fun chatStream(prepared: JSONObject, traeToken: String, uid: String = "", out: (String) -> Unit, onResult: (JSONObject?, String?) -> Unit) {
        // **区域只在此处快照一次**，后续 host/header/协议分支全部用局部值。
        // 不这么做的话：:614 判定与 :619 取 host 是两次独立读取，中间夹着可达 180s 的
        // 流式窗口；用户在此期间切账号就会让 `current` 翻面 —— 一次已按 CN 协议发出的请求
        // 会把 CN 的 token 发到 SG host，属**账号区域串号**，不只是显示问题。
        val reg = TraeRegion.current
        // 区域分派：两个区域都已支持聊天，但协议完全不同 ——
        //   CN：单次 POST llm_utils_chat
        //   SG：建会话 → commit → 订阅 events 三段式（见 TraeIntlUpstream）
        if (reg != TraeRegion.CN) {
            TraeIntlUpstream.chatStream(prepared, traeToken, uid, out, onResult)
            return
        }
        val req = Request.Builder()
            .url(reg.agentHost + EP_CHAT)
            .post(prepared.toString().toRequestBody("application/json".toMediaType()))
            .solo(traeToken, stream = true, uid = uid, region = reg)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = (resp.body?.string() ?: "").take(200)
                out("event: error\ndata: ${jsonEscape("upstream http ${resp.code}: $err")}\n\n")
                out("data: [DONE]\n\n")
                onResult(null, "http:${resp.code}")
                return
            }
            val br = BufferedReader(InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8))
            streamSse(br, out, onResult)
        }
    }

    // ------------------------------------------------------------------
    // Trae SSE → OpenAI SSE 流式转换（复刻 solosse.go::streamOpts）
    // ------------------------------------------------------------------

    private fun streamSse(br: BufferedReader, out: (String) -> Unit, onResult: (JSONObject?, String?) -> Unit) {
        val id = "chatcmpl-${System.currentTimeMillis()}"
        var pendingUsage: JSONObject? = null
        var lastUsage: JSONObject? = null
        var sawDone = false
        var settled = false
        /** 本次流里出现过 tool_calls → done 的 finish_reason 要归一为 "tool_calls"（见下）。 */
        var sawToolCall = false
        val ev = SseState()

        fun settle(usage: JSONObject?, err: String?) {
            if (!settled) { settled = true; onResult(usage, err) }
        }

        fun writeChunk(delta: JSONObject, finish: String?) {
            val choice = JSONObject().put("index", 0)
            if (finish != null) choice.put("finish_reason", finish)
            val chunk = JSONObject()
                .put("id", id)
                .put("object", "chat.completion.chunk")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", "")
                .put("choices", JSONArray().put(choice))
            choice.put("delta", delta)
            if (pendingUsage != null) { chunk.put("usage", pendingUsage); pendingUsage = null }
            out("data: ${chunk.toString()}\n\n")
        }
        fun writeDONE() = out("data: [DONE]\n\n")

        while (true) {
            val line = br.readLine() ?: break
            val v = ev.scan(line) ?: continue
            when (v.event) {
                "output" -> {
                    val delta = JSONObject()
                    if (v.response.isNotEmpty()) delta.put("content", v.response)
                    if (v.reasoning.isNotEmpty()) delta.put("reasoning_content", v.reasoning)
                    if (v.toolCallsRaw != null) {
                        val tc = stripSoloTools(v.toolCallsRaw!!)
                        if (tc != null) { delta.put("tool_calls", tc); sawToolCall = true }
                    }
                    if (delta.length() > 0) writeChunk(delta, null)
                }
                "token_usage" -> { pendingUsage = v.usage; lastUsage = v.usage }
                "done" -> {
                    // 上游在带工具调用时也发 finish_reason:"stop"（实测），但 OpenAI 规范要求
                    // 这里是 "tool_calls" —— 客户端（Claude Code/Cursor）靠它决定「该执行工具了」，
                    // 收到 "stop" 会直接结束对话、工具永不执行。有 tool_calls 就归一为 "tool_calls"。
                    val fin = if (sawToolCall) "tool_calls" else v.finishReason
                    writeChunk(JSONObject(), fin); writeDONE(); sawDone = true; settle(lastUsage, null)
                }
                "error" -> {
                    out("event: error\ndata: ${jsonEscape("solo error code=${v.errorCode} msg=${v.errorMessage}")}\n\n")
                    writeDONE(); sawDone = true; settle(null, "solo:${v.errorCode}")
                }
            }
        }
        if (!sawDone) { writeDONE(); settle(null, "interrupted") } // 上游中断幂等兜底
    }

    private fun stripSoloTools(raw: Any): JSONArray? {
        val arr = when (raw) {
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            else -> return null
        }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val call = arr.optJSONObject(i) ?: continue
            if (!call.isNull("function_call")) {
                call.put("function", call.get("function_call"))
                call.remove("function_call")
            }
            val fn = call.optJSONObject("function")
            if (fn != null) { fn.remove("namespace"); fn.remove("partial_arguments") }
            // 上游按 OpenAI 那样**分片**下发 tool_calls：首帧带 id/type/name，
            // 后续帧只带 index + arguments 片段（实测）。后续帧里上游会给空的 id/type/name，
            // 空字符串不是合法 OpenAI 分片内容（客户端可能当成"另一个工具"或覆盖首帧），去掉。
            if (call.optString("id", "").isEmpty()) call.remove("id")
            if (call.optString("type", "").isEmpty()) call.remove("type")
            if (fn != null && fn.optString("name", "").isEmpty()) fn.remove("name")
            out.put(call)
        }
        return out
    }

    private fun jsonEscape(s: String): String = JSONObject.quote(s)

    // ------------------------------------------------------------------
    // SSE 行状态机（复刻 solosse.go::sseState + scanLine + ParseSOLOLine）
    // ------------------------------------------------------------------

    private class SseResult {
        var event = ""
        var response = ""
        var reasoning = ""
        var toolCallsRaw: Any? = null
        var usage: JSONObject? = null
        var finishReason = ""
        var errorCode = 0L
        var errorMessage = ""
    }

    private class SseState {
        var event = ""
        var data = StringBuilder()

        fun reset() { event = ""; data.setLength(0) }

        /** 逐行喂入；仅当一行事件闭合（空行）时返回结果，否则 null。 */
        fun scan(line: String): SseResult? {
            when {
                line.isEmpty() -> {
                    if (event.isEmpty()) { reset(); return null }
                    val r = parse(event, data.toString())
                    reset()
                    return r
                }
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:"))
                line.startsWith(":") -> {} // 注释行
            }
            return null
        }

        private fun parse(eventName: String, dataLine: String): SseResult {
            val r = SseResult()
            r.event = eventName.trim()
            if (dataLine.isEmpty()) return r
            val raw = try { JSONObject(dataLine) } catch (_: Exception) { return r }
            when (r.event) {
                "output" -> {
                    r.response = raw.optString("response", "")
                    // 必须是「真字符串」才取：optString 会把 JSON null 变成字面量 "null"
                    // 发给客户端（实测踩过，每帧都挂着 "reasoning_content":"null"）。
                    // reference solosse.go:82 用的是 raw["reasoning_content"].(string) 类型断言，
                    // 非字符串一律落空 → 这里等价地判 isString。
                    r.reasoning = (raw.opt("reasoning_content") as? String).orEmpty()
                    if (raw.has("tool_calls") && !raw.isNull("tool_calls")) r.toolCallsRaw = raw.get("tool_calls")
                }
                "token_usage" -> r.usage = raw
                "done" -> r.finishReason = raw.optString("finish_reason", "")
                "error" -> {
                    r.errorCode = raw.optLong("code", 0)
                    r.errorMessage = raw.optString("message", "")
                }
            }
            return r
        }
    }
}
