package com.example.traework2api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 国际服（SG）聊天上游 —— **session 式协议**，与国内服完全不同。
 *
 * 协议来源：**浏览器 Network 实证抓包**（work.trae.ai 发消息时的真实请求），
 * 不是第三方逆向文档（那份文档此处已过时：它写 `query` 是对象、`agent_type=solo_agent_remote`，
 * 实测都会 500）。三段式流程：
 *
 *   ① POST /api/remote/v1/chat_sessions
 *        {mode, environment_id:"default", auto_create_project:false, env:"remote", origin:"web",
 *         initial_message:{
 *           chat_session_id:"", content:[],                      // content 留空数组
 *           query: "[{\"type\":\"text\",\"data\":{\"content\":\"<用户话>\"}}]",  // **JSON 字符串**
 *           model_name:"", agent_id:"solo_work_remote",
 *           agent_type:"solo_work_remote", model_selection_strategy:"auto",
 *           common_params: "{...}"                               // **JSON 字符串**
 *         }}
 *      → 返回 {chat_session_id, message_id}
 *   ② POST /api/remote/v1/chat_sessions/{id}/commit   {"title":"<用户话>"}
 *      ⚠️ **这一步不能省**：少了它事件流只会回一个 status_changed，模型根本不生成。
 *   ③ GET  /api/remote/v1/chat_sessions/{id}/events?reply_to_message_id={mid}   （SSE）
 *
 * SSE 事件（实测共 11 种）：status_changed / platform_timing / metadata / model_config /
 * session_title_message / session_icon_message / timing_events / **plan_item** / token_usage / done。
 * 其中只有 `plan_item` 承载正文：
 *   {"id","task_id","thought":"<正文>","reasoning_content":"...","timing":{...},"tool_call_info":{...}}
 *
 * ⚠️⚠️ `thought` 是**累积快照**（每帧给"到目前为止的全文"），**不是增量**。
 * 实测证据：请求"从1数到10"，相邻两帧分别是 `"1"` 与 `"1,2,3,4,"` ——
 * 后者**包含**前者。所以必须自己 diff 出新增部分再下发，否则客户端拼出来是 `11,2,3,4,`。
 * （对应实现见 [streamEvents] 里的 lastThought/lastReasoning。）
 * 注意同一 `id` 会跨多帧反复出现，不能靠 id 变化判断"新片段"。
 *
 * 安全：token 只进请求头，绝不落日志/源码。
 *
 * **多轮上下文**：国际服不接收 `messages`，只能靠**复用同一个 `chat_session_id`**（见 [sessions]）。
 * ⚠️ 不要试图把历史塞进 `initial_message.content` —— 实测会让上游**完全不生成**（见 [createBody]）。
 */
object TraeIntlUpstream {

    private const val HOST = "https://core-normal.trae.ai"
    private const val EP_SESSIONS = "/api/remote/v1/chat_sessions"

    /** 与账号区域无关；国际服 web 端固定用这组头。 */
    private const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // 事件流是长连接，读超时必须够长
        .build()

    /** 国际服 web 端通用请求头（各步共用）。 */
    private fun headers(url: String, token: String): Request.Builder =
        Request.Builder().url(url)
            .header("Authorization", "Cloud-IDE-JWT $token")
            .header("X-Trae-Client-Type", "web")
            .header("x-user-region", "US")
            .header("Origin", "https://work.trae.ai")
            .header("Referer", "https://work.trae.ai/")
            .header("User-Agent", UA_WEB)

    private fun postJson(url: String, token: String, body: String): Request =
        headers(url, token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .build()

    /**
     * 取本轮要发的 user 消息文本（国际服 `query` 只放这一句）。
     * ⚠️ 历史**不能**走建会话请求体（见 [createBody]），只能靠复用 `chat_session_id`（[sessionKey]）。
     */
    private fun promptOf(src: JSONObject): String {
        val msgs = src.optJSONArray("messages") ?: return ""
        for (i in msgs.length() - 1 downTo 0) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role", "") == "user") return flattenContent(m)
        }
        return ""
    }

    /** 取一条消息的纯文本：content 是字符串直接用；是多段数组则只拼 text 段。 */
    private fun flattenContent(m: JSONObject): String = when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> {
            val sb = StringBuilder()
            for (j in 0 until c.length()) {
                val p = c.optJSONObject(j) ?: continue
                if (p.optString("type", "") == "text") sb.append(p.optString("text", ""))
            }
            sb.toString()
        }
        else -> ""   // tool 结果若只带 tool_call_id 而无 content，这里得空串，属已知上限
    }

    /**
     * 会话记忆：按「系统提示 + 首条 user」指纹指向一个 `chat_session_id`，**意图**用于多轮续聊。
     *
     * ⚠️ **实测（2026-09-23）：这条路目前走不通，等同于未生效。**
     * 把上一轮的 `chat_session_id` 回填进 `initial_message.chat_session_id` 后，
     * 上游**仍然返回一个全新的 session id**（`message_index` 恒为 3，不是追加），
     * 即 `POST /chat_sessions` 是**只建不续**的端点 —— 上下文接不上（实测第二轮问"我叫什么"仍答"不知道"）。
     * 要真正支持多轮，需要抓包确定 web 端**发第二条消息时的真实请求**（很可能不是这个端点，
     * 或需要额外的 `reply_to_message_id` / `last_message_id` 之类字段）。
     * 代码先留着：一旦抓到正确形状，改 [createBody] 即可生效。
     *
     * 刻意简化（`ponytail:` 上限）：
     * - 只在内存里，进程重启即失效，不落盘、不加密、不含任何凭据；
     * - key 取前 512 字符，改系统提示就会新建会话（宁可丢上下文，不可串台）；
     * - 上限 [SESSIONS_MAX] 条，超出按插入序删最早的（防长跑内存涨，非 LRU 也够用）。
     */
    private val sessions = ConcurrentHashMap<String, String>()
    private const val SESSIONS_MAX = 32

    private fun sessionKey(prepared: JSONObject): String {
        val msgs = prepared.optJSONArray("messages") ?: return ""
        val sys = StringBuilder()
        var firstUser = ""
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role", "")
            if (role == "system" && sys.length < 512) sys.append(flattenContent(m))
            if (role == "user" && firstUser.isEmpty()) { firstUser = flattenContent(m); break }
        }
        if (firstUser.isBlank()) return ""
        return ("$sys\u0000$firstUser").take(512)
    }

    private fun rememberSession(key: String, sid: String) {
        if (key.isEmpty()) return
        if (sessions.size >= SESSIONS_MAX && !sessions.containsKey(key)) {
            sessions.keys.firstOrNull()?.let { sessions.remove(it) }
        }
        sessions[key] = sid
    }

    /**
     * OpenAI `tools` → 国际服 `tool_plus_setting`。
     * 形状未实测（国际服 `tool_call_info.name` 一直是空），**失败不影响聊天**：
     * 建会话失败时 [chatStream] 会去掉它重试一次。
     */
    private fun toolSetting(prepared: JSONObject): String? {
        val arr = prepared.optJSONArray("tools") ?: return null
        val list = JSONArray()
        for (i in 0 until arr.length()) {
            val fn = arr.optJSONObject(i)?.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            list.put(JSONObject()
                .put("name", name)
                .put("description", fn.optString("description", ""))
                .put("parameters", fn.opt("parameters") ?: JSONObject()))
        }
        if (list.length() == 0) return null
        return JSONObject().put("tools", list).toString()
    }

    /**
     * 转发国际服聊天。契约与 [TraeUpstream.chatStream] 一致：
     * [out] 收到的是完整 `data: ...\n\n`；结束时回调 [onResult]（usage, 错误码）。
     */
    fun chatStream(
        prepared: JSONObject,
        traeToken: String,
        uid: String,
        out: (String) -> Unit,
        onResult: (JSONObject?, String?) -> Unit,
    ) {
        val userText = promptOf(prepared)
        if (userText.isBlank()) {
            out("event: error\ndata: ${jsonQuote("请求里没有 user 消息内容")}\n\n")
            out("data: [DONE]\n\n")
            onResult(null, "intl:empty_user_message")
            return
        }
        val model = prepared.optString("model", "").trim()
        val key = sessionKey(prepared)
        val reuse = sessions[key]      // 命中的话：续聊同一个会话，历史才接得上

        // ---------- ① 建会话（带 tools 失败就摘掉重试一次，工具字段是尽力而为） ----------
        val body = createBody(userText, model, uid, reuse ?: "")
        val ts = toolSetting(prepared)
        val created: Pair<String, String>
        try {
            created = createSession(body, ts, traeToken)
        } catch (e: Exception) {
            out("event: error\ndata: ${jsonQuote("国际服建会话异常: ${e.javaClass.simpleName}: ${e.message}")}\n\n")
            out("data: [DONE]\n\n")
            onResult(null, "intl_create:exception")
            return
        }
        if (created.second == NO_SESSION) {
            out("event: error\ndata: ${jsonQuote("国际服建会话失败 http ${created.first}")}\n\n")
            out("data: [DONE]\n\n")
            onResult(null, "intl_create:${created.first}")
            return
        }
        val sid = created.first
        val mid = created.second
        if (sid.isBlank()) {
            out("event: error\ndata: ${jsonQuote("国际服建会话未返回 chat_session_id")}\n\n")
            out("data: [DONE]\n\n")
            onResult(null, "intl_create:no_session")
            return
        }
        rememberSession(key, sid)

        // ---------- ② commit（少了它模型不会生成） ----------
        try {
            val cb = JSONObject().put("title", userText.take(80)).toString()
            client.newCall(postJson("$HOST$EP_SESSIONS/$sid/commit", traeToken, cb)).execute().use { r ->
                if (!r.isSuccessful) {
                    val t = r.body?.string().orEmpty()
                    out("event: error\ndata: ${jsonQuote("国际服 commit 失败 http ${r.code}: ${t.take(200)}")}\n\n")
                    out("data: [DONE]\n\n")
                    onResult(null, "intl_commit:${r.code}")
                    return
                }
            }
        } catch (e: Exception) {
            out("event: error\ndata: ${jsonQuote("国际服 commit 异常: ${e.javaClass.simpleName}")}\n\n")
            out("data: [DONE]\n\n")
            onResult(null, "intl_commit:exception")
            return
        }

        // ---------- ③ 订阅事件流 ----------
        val evReq = headers("$HOST$EP_SESSIONS/$sid/events?reply_to_message_id=$mid", traeToken)
            .get()
            .header("Accept", "text/event-stream")
            .build()
        try {
            client.newCall(evReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val t = resp.body?.string().orEmpty()
                    out("event: error\ndata: ${jsonQuote("国际服事件流失败 http ${resp.code}: ${t.take(200)}")}\n\n")
                    out("data: [DONE]\n\n")
                    onResult(null, "intl_events:${resp.code}")
                    return
                }
                streamEvents(resp.body!!.source(), out, onResult)
            }
        } catch (e: Exception) {
            out("event: error\ndata: ${jsonQuote("国际服事件流异常: ${e.javaClass.simpleName}: ${e.message}")}\n\n")
            out("data: [DONE]\n\n")
            onResult(null, "intl_events:exception")
        }
    }

    /**
     * 建会话。返回 `(sid, mid)`；建会话**失败**时返回 `(HTTP码字符串, NO_SESSION)`。
     * `toolSetting` 是未实测的尽力字段：带了它被拒 → 摘掉重试一次（保证聊天本身不受影响）。
     */
    private fun createSession(body: String, toolSetting: String?, token: String): Pair<String, String> {
        val first = tryCreate("$HOST$EP_SESSIONS", token, body, toolSetting)
        val r = if (first.second != NO_SESSION || toolSetting == null) first
                else tryCreate("$HOST$EP_SESSIONS", token, body, null)
        return r
    }

    private fun tryCreate(url: String, token: String, body: String, toolSetting: String?): Pair<String, String> {
        val payload = if (toolSetting == null) body
                      else JSONObject(body).put("tool_plus_setting", toolSetting).toString()
        client.newCall(postJson(url, token, payload)).execute().use { it ->
            val t = it.body?.string().orEmpty()
            if (!it.isSuccessful) return it.code.toString() to NO_SESSION
            val data = JSONObject(t).optJSONObject("data")
            return data?.optString("chat_session_id", "").orEmpty() to
                   data?.optString("message_id", "").orEmpty()
        }
    }

    /** [createSession] 失败的哨兵值（正常 mid 是一个非空 id，不会是它）。 */
    private const val NO_SESSION = "\u0000NO_SESSION"

    /**
     * 构造建会话请求体（字段形状严格照浏览器抓包，改动前先回看类注释）。
     *
     * ⚠️⚠️ **`content` 必须留空数组**（实测，2026-09-23，别再试）：
     * 把历史消息塞进 `content`（`[{type:text,data:{content}}]`）后，建会话仍返回 200/status 2，
     * 但事件流只回一个 `status_changed new_status=6` 就结束，**模型完全不生成**（无 plan_item）。
     * 改回空数组立刻恢复正常。所以**上下文只能靠复用 `chat_session_id`**，不能靠回传 messages。
     */
    private fun createBody(userText: String, model: String, uid: String, sessionId: String): String {
        // query 是 **JSON 字符串**（不是对象）：[{"type":"text","data":{"content":"..."}}]
        val query = JSONArray().put(
            JSONObject().put("type", "text")
                .put("data", JSONObject().put("content", userText))
        ).toString()
        val common = JSONObject()
            .put("language", "zh-cn")
            .put("app_language", "en")
            .put("quality", "stable")
            .put("app_version", "1.0.0.63")
            .put("biz_user_id", uid)
            .put("user_unique_id", uid)
            .put("scope", "marscode-us")
            .put("tenant", "marscode")
            .put("solo_chat_mode", "work")
            .put("user_identity", "Free")
            .put("is_freshman", "0")
            .put("is_privacy_mode", 0)
            .put("privacy_mode", "off")
            .toString()
        val im = JSONObject()
            .put("chat_session_id", sessionId)    // 续聊时复用，空串=新建
            .put("content", JSONArray())          // ⚠️ 必须留空，见本函数注释
            .put("query", query)
            .put("model_name", model)             // 空串=服务端按 model_selection_strategy 自动选
            .put("agent_id", "solo_work_remote")
            .put("agent_type", "solo_work_remote")
            .put("model_selection_strategy", "auto")
            .put("common_params", common)
        return JSONObject()
            .put("mode", "work")
            .put("environment_id", "default")
            .put("initial_message", im)
            .put("env", "remote")
            .put("auto_create_project", false)
            .put("origin", "web")
            .toString()
    }

    // ------------------------------------------------------------------
    // 事件流 → OpenAI SSE
    // ------------------------------------------------------------------

    private fun streamEvents(
        src: okio.BufferedSource,
        out: (String) -> Unit,
        onResult: (JSONObject?, String?) -> Unit,
    ) {
        val id = "chatcmpl-${System.currentTimeMillis()}"
        var settled = false
        var sawDone = false
        var lastUsage: JSONObject? = null
        /** 本次流出现过 tool_calls → done 的 finish_reason 要归一为 "tool_calls"（同国内服，见 TraeUpstream）。 */
        var sawToolCall = false

        fun settle(usage: JSONObject?, err: String?) {
            if (!settled) { settled = true; onResult(usage, err) }
        }
        fun chunk(delta: JSONObject, finish: String?) {
            val choice = JSONObject().put("index", 0).put("delta", delta)
            if (finish != null) choice.put("finish_reason", finish)
            val c = JSONObject()
                .put("id", id).put("object", "chat.completion.chunk")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", "")
                .put("choices", JSONArray().put(choice))
            out("data: ${c.toString()}\n\n")
        }

        var eventName = ""
        var dataBuf = StringBuilder()
        // plan_item 的 thought / reasoning_content 是**累积快照**：每帧给"到目前为止的全文"。
        // 这里记住上一次的全文，只把**新增后缀**下发给客户端（OpenAI 是增量语义）。
        // 用「新值以上次值开头」作为判定，是的就取差值，否则整段当新增（内容被重写时兜底）。
        var lastThought = ""
        var lastReasoning = ""
        while (true) {
            val line = src.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {          // 事件边界
                    if (eventName.isNotEmpty()) {
                        val r = handleEvent(eventName, dataBuf.toString())
                        if (r != null) {
                            when (r.kind) {
                                Kind.OUTPUT -> {
                                    val dc = deltaOf(lastThought, r.content)
                                    val dr = deltaOf(lastReasoning, r.reasoning)
                                    lastThought = r.content
                                    lastReasoning = r.reasoning
                                    val d = JSONObject()
                                    if (dc.isNotEmpty()) d.put("content", dc)
                                    if (dr.isNotEmpty()) d.put("reasoning_content", dr)
                                    // 工具调用：上游**不给参数增量**（实测 id 每帧变、params 恒 null），
                                    // 所以只在**见过之后**整段下发一次完整 tool_calls —— 客户端拿到就能执行。
                                    // ⚠️ 这是猜的形状（未实测到真实工具调用），失败也不影响纯聊天。
                                    if (r.toolName.isNotEmpty() && !sawToolCall) {
                                        sawToolCall = true
                                        d.put("tool_calls", JSONArray().put(toolCall(r.toolName, r.toolArgs)))
                                        d.put("content", "")   // OpenAI 要求带 tool_calls 的帧 content 为空串
                                    }
                                    if (d.length() > 0) chunk(d, null)
                                }
                                Kind.USAGE -> lastUsage = r.usage
                                Kind.DONE -> {
                                    chunk(JSONObject(), if (sawToolCall) "tool_calls" else "stop")
                                    out("data: [DONE]\n\n")
                                    sawDone = true
                                    settle(lastUsage ?: r.usage, null)
                                }
                                Kind.ERROR -> {
                                    out("event: error\ndata: ${jsonQuote(r.errorText)}\n\n")
                                    out("data: [DONE]\n\n")
                                    sawDone = true
                                    settle(null, "intl:${r.errorText.take(60)}")
                                }
                                Kind.IGNORE -> {}
                            }
                        }
                    }
                    eventName = ""; dataBuf = StringBuilder()
                }
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataBuf.append(line.removePrefix("data:"))
                // 其余行（id: / 注释 / 重试）忽略
            }
        }
        if (!sawDone) { chunk(JSONObject(), "stop"); out("data: [DONE]\n\n"); settle(lastUsage, null) }
    }

    private enum class Kind { OUTPUT, USAGE, DONE, ERROR, IGNORE }

    private class Parsed(
        val kind: Kind,
        val content: String = "",
        val reasoning: String = "",
        val usage: JSONObject? = null,
        val errorText: String = "",
        val toolName: String = "",
        val toolArgs: String = "",
    )

    /**
     * 解析单个事件。只有 plan_item 承载正文；其余（status_changed/metadata/timing 等）忽略。
     *
     * plan_item 形状（实测）：
     *   {"id","task_id","thought":"<累积全文>","reasoning_content":"<累积全文>","timing":{...},
     *    "tool_call_info":{"id":"...","name":"","params":null,"result":{}}}
     * ⚠️ 取出的 `thought`/`reasoning_content` 是**累积快照**，diff 成增量的工作在 [streamEvents] 里做。
     * ⚠️ 实测 `tool_call_info.name` 一直是空串、`params` 恒为 null —— 真实工具调用没被观察到，
     * 所以 [toolCall] 只是**尽力而为**的映射（见那里的注释）。
     */
    private fun handleEvent(name: String, data: String): Parsed? {
        if (data.isBlank()) return null
        val raw = try { JSONObject(data) } catch (_: Exception) { return null }
        return when (name) {
            "plan_item" -> {
                // 与国内服同理：optString 会把 JSON null 变成字面量 "null"，必须用类型安全取法
                val thought = (raw.opt("thought") as? String).orEmpty()
                val reasoning = (raw.opt("reasoning_content") as? String).orEmpty()
                val info = raw.optJSONObject("tool_call_info")
                val toolName = (info?.opt("name") as? String).orEmpty().trim()
                val toolArgs = (info?.opt("params") as? String)
                    ?: info?.opt("params")?.takeIf { it != JSONObject.NULL }?.toString()
                    ?: "{}"
                // 有工具名就一定要下发，哪怕 thought/reasoning 都是空
                if (thought.isEmpty() && reasoning.isEmpty() && toolName.isEmpty()) Parsed(Kind.IGNORE)
                else Parsed(Kind.OUTPUT, content = thought, reasoning = reasoning,
                            toolName = toolName, toolArgs = toolArgs)
            }
            "token_usage" -> Parsed(Kind.USAGE, usage = raw)
            "done" -> Parsed(Kind.DONE, usage = raw.optJSONObject("usage"))
            "error" -> {
                val msg = (raw.opt("message") as? String)
                    ?: (raw.opt("error") as? String)
                    ?: raw.toString().take(200)
                Parsed(Kind.ERROR, errorText = msg)
            }
            else -> Parsed(Kind.IGNORE)
        }
    }

    /** 拼一个 OpenAI 形状的 tool_call 分片（完整下发，不做增量）。 */
    private fun toolCall(name: String, args: String): JSONObject = JSONObject()
        .put("index", 0)
        .put("id", "call_${System.currentTimeMillis()}")
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args))

    private fun jsonQuote(s: String): String = JSONObject.quote(s)

    /**
     * 累积快照 → 增量差值。这是国际服转换里**最容易错**的一处：
     * 上游 `plan_item.thought` 每帧给全文，OpenAI 客户端按增量拼接，
     * 直接透传会导致内容重复（实测：`"1"` 之后是 `"1,2,3,4,"`，拼出来成 `11,2,3,4,`）。
     *
     * 自检 [selfCheck] 把它钉住。
     */
    internal fun deltaOf(prev: String, now: String): String = when {
        now.isEmpty() -> ""
        now == prev -> ""
        now.startsWith(prev) -> now.substring(prev.length)
        else -> now   // 非累积增长（重写/回退/换了一轮）：整段下发，宁可多也不丢
    }

    /**
     * 自检 [deltaOf] 的关键边界（`MainActivity.onCreate` 调用，失败即崩溃，属预期行为）。
     * 用实测到的真实序列做样本。
     */
    fun selfCheck() {
        // 实测序列："1" → "1,2,3,4,"，第二帧相对第一帧只应下发 ",2,3,4,"
        check(deltaOf("1", "1,2,3,4,") == ",2,3,4,") { "自检失败：累积快照未正确 diff" }
        // 首帧（上次为空）应整段下发
        check(deltaOf("", "你好") == "你好") { "自检失败：首帧应整段下发" }
        // 完全相同 → 不下发（上游会重发同一帧）
        check(deltaOf("abc", "abc") == "") { "自检失败：重复帧不应下发" }
        // 空值 → 不下发（reasoning_content 常为空串）
        check(deltaOf("abc", "") == "") { "自检失败：空值不应下发" }
        // 非累积增长（内容被重写）→ 整段下发，不能丢
        check(deltaOf("abc", "xyz") == "xyz") { "自检失败：非累积增长应整段下发" }
        // 中文多字节：必须按字符而不是字节 diff
        check(deltaOf("你好", "你好世界") == "世界") { "自检失败：中文应按字符 diff" }

        // ---- promptOf / flattenContent ----
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是助手"))
            .put(JSONObject().put("role", "user").put("content", "我叫小明"))
            .put(JSONObject().put("role", "assistant").put("content", "你好小明"))
            .put(JSONObject().put("role", "user").put("content", "我叫什么"))
        check(promptOf(JSONObject().put("messages", msgs)) == "我叫什么") {
            "自检失败：query 只应是最後一条 user 消息"
        }
        // 只有 assistant/tool（没有 user）→ 空串，[chatStream] 会直接报错而不去建会话
        check(promptOf(JSONObject().put("messages", JSONArray()
            .put(JSONObject().put("role", "assistant").put("content", "x")))).isEmpty()) {
            "自检失败：没有 user 消息时应返回空串"
        }
        // 多段 content：只拼 text 段
        val multi = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "看图"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "x"))))
        check(flattenContent(multi) == "看图") { "自检失败：多段 content 只应取 text 段" }

        // ---- ⚠️ content 必须留空：历史进 content 会让上游完全不生成（实测踩过） ----
        val bodyObj = JSONObject(createBody("你好", "", "u1", ""))
        check(bodyObj.getJSONObject("initial_message").getJSONArray("content").length() == 0) {
            "自检失败：initial_message.content 必须留空数组（非空会让上游不生成回复）"
        }

        // ---- 会话复用 key：同对话稳定，换对话/换系统提示必须变 ----
        val k1 = sessionKey(JSONObject().put("messages", msgs))
        check(k1.isNotEmpty()) { "自检失败：会话 key 不应为空" }
        val msgs2 = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是助手"))
            .put(JSONObject().put("role", "user").put("content", "换个话题"))
        check(k1 != sessionKey(JSONObject().put("messages", msgs2))) {
            "自检失败：不同对话必须映射到不同会话 key（否则会串台）"
        }
        val msgs3 = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是翻译"))
            .put(JSONObject().put("role", "user").put("content", "我叫小明"))
        check(k1 != sessionKey(JSONObject().put("messages", msgs3))) {
            "自检失败：换系统提示必须映射到不同会话 key"
        }
        // 同一对话的下一轮（追加 assistant + 新 user）key 必须**不变**，否则无法续聊
        val nextTurn = JSONArray()
        for (i in 0 until msgs.length()) nextTurn.put(JSONObject(msgs.get(i).toString()))
        nextTurn.put(JSONObject().put("role", "assistant").put("content", "你叫小明"))
        nextTurn.put(JSONObject().put("role", "user").put("content", "再说一次"))
        check(k1 == sessionKey(JSONObject().put("messages", nextTurn))) {
            "自检失败：同一对话的下一轮必须复用同一个会话 key（否则多轮上下文失效）"
        }

        // 无 user 消息 → 空 key（[chatStream] 会直接报错，不会去建会话）
        check(sessionKey(JSONObject().put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "x")))).isEmpty()) {
            "自检失败：没有 user 消息时应返回空 key"
        }

        // ---- 工具调用形状：OpenAI 客户端靠 name 判断做什么 ----
        val tc = toolCall("finish", "{\"summary\":\"好的\"}")
        check(tc.optString("type") == "function") { "自检失败：tool_call.type 必须是 function" }
        check(tc.getJSONObject("function").optString("name") == "finish") { "自检失败：tool_call 名字错" }
        check(tc.has("index") && tc.has("id")) { "自检失败：tool_call 必须带 index 与 id" }
    }
}
