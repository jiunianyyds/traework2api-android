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

    // host 随区域变化，统一从 TraeRegion 取（见该文件；国内版为已实测值）。
    private val region: TraeRegion get() = TraeRegion.current
    private val AGENT_HOST: String get() = region.agentHost
    private val UG_HOST: String get() = region.ugHost

    const val EP_CHAT = "/api/agent/v3/llm_utils_chat"
    const val EP_CHECKIN_STATUS = "/trae/api/v2/ug/checkin_credits/status"
    const val EP_ENT_USAGE = "/trae/api/v2/pay/ide_user_ent_usage"

    const val APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8"
    const val IDE_VERSION = TraeRegion.IDE_VERSION
    const val IDE_VERSION_CODE = "20260716"
    const val DEVICE_BRAND = "83DG"
    const val OS_VERSION = "Windows 11 Pro"
    const val FUNCTION = "solo_work_lite"

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

    /** 签到每日可领积分（checkin_credits/status `checked_in`,`credits`）；失败/区域未配置返回 null。 */
    fun fetchCheckin(tok: String): Pair<Boolean, Long>? {
        if (!region.canQueryQuota) return null
        val root = ug(tok, UG_HOST + EP_CHECKIN_STATUS) ?: return null
        return root.optBoolean("checked_in", false) to root.optLong("credits", 0)
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