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

    /** SOLO 请求头（chat / fetchModels 共用；token 只进内存，不落盘日志）。 */
    private fun Builder.solo(token: String, stream: Boolean, uid: String = ""): Builder {
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

    /** ug 接口 headers + 出参 JSON；token 只进内存。非 2xx/解析失败/区域未配置返回 null。 */
    private fun ug(token: String, url: String): JSONObject? {
        val b = Request.Builder().url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", "Trae/$IDE_VERSION")
            .header("Authorization", "Cloud-IDE-JWT $token")
        // X-User-Region 随区域变化；SG 未实测时为空 → 不发该头
        if (region.userRegionHeader.isNotBlank()) b.header("X-User-Region", region.userRegionHeader)
        val req = b.build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful || r.body == null) null else JSONObject(r.body!!.string())
            }
        } catch (_: Exception) { null }
    }

    /** 权益额度上限 + 当前可用积分。来源于 ide_user_ent_usage.usage_summary：
     * 额度(上限)=total_amount；可用=total_amount-consumed_amount。失败/区域未配置返回 null。 */
    fun fetchUsage(tok: String): Pair<Long, Long>? {
        if (!region.canQueryQuota) return null
        val sum = ug(tok, UG_HOST + EP_ENT_USAGE)?.optJSONObject("usage_summary") ?: return null
        val total = sum.optDouble("total_amount", -1.0)
        if (total < 0) return null
        val consumed = sum.optDouble("consumed_amount", 0.0)
        return (total.toLong()) to (total - consumed).toLong()
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

    fun fetchCheckin(tok: String): CheckinStatus? {
        if (!region.canQueryQuota) return null
        val root = ug(tok, UG_HOST + EP_CHECKIN_STATUS) ?: return null
        return parseCheckin(root)
    }

    /**
     * 执行签到（`checkin_credits/claim`）。返回业务 code，网络失败返回 null。
     *
     * ⚠️ **实测（2026-09-25，真账号，今天已签状态下试发）**：返回
     * `{"code":9004,"message":"The submitted order parameters are incorrect..."}` ——
     * 注意**不是** GitHub `L0NE-6/Trae-AutoCheckin` 里写的 `9095`，那份参考在这里对不上。
     * 上游 message 是英文且很含糊，**不要直接展示给用户**，由调用方映射成人话。
     *
     * ⚠️ 同样实测：body 用 `{}` 还是 `{"req_source":1}`、带不带设备头，**返回一字不差** ——
     * 参考实现说的"空体会被拒成 9074"在本账号上不成立，所以**不额外加设备头/设备号**。
     *
     * ⚠️ 幂等性：重复领取**不会**扣积分或改坏状态（已复测 `checked_in`/`credits` 不变），
     * 但会产生一次无谓请求 —— UI 仍应在 `checkedIn=true` 时禁止点击。
     */
    fun claimCheckin(tok: String): Int? {
        if (!region.canQueryQuota) return null
        return ug(tok, UG_HOST + EP_CLAIM)?.optInt("code", -1)
    }

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
        if (!region.canQueryQuota) return null
        val body = JSONObject()
            .put("function", FUNCTION)
            .put("config_names", JSONObject.NULL)
            .put("need_prompt", false)
            .put("current_config_info", JSONObject.NULL)
            .put("poly_prompt", true)
            .put("mode_type", JSONObject.NULL)
            .put("agent_type", JSONObject.NULL)
        val req = Request.Builder()
            .url(AGENT_HOST + EP_MODELS)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .solo(tok, stream = false, uid = uid)
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
    }

    /**
     * 转发到 Trae 并流式转换。每产生一块要写给客户端的内容就调用 [out]（已是完整 `data: ...\n\n`）。
     * 流转结束后（成功/出错）调用 [onResult]：参数为 最终 usage（可能 null）与 错误类型（null=成功）。
     * 上游 HTTP 出错时也通过 [out] 输出 error 事件 + [DONE]。
     */
    fun chatStream(prepared: JSONObject, traeToken: String, uid: String = "", out: (String) -> Unit, onResult: (JSONObject?, String?) -> Unit) {
        // 区域分派：两个区域都已支持聊天，但协议完全不同 ——
        //   CN：单次 POST llm_utils_chat
        //   SG：建会话 → commit → 订阅 events 三段式（见 TraeIntlUpstream）
        if (TraeRegion.current != TraeRegion.CN) {
            TraeIntlUpstream.chatStream(prepared, traeToken, uid, out, onResult)
            return
        }
        val req = Request.Builder()
            .url(AGENT_HOST + EP_CHAT)
            .post(prepared.toString().toRequestBody("application/json".toMediaType()))
            .solo(traeToken, stream = true, uid = uid)
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