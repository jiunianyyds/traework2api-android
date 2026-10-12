package com.example.traework2api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 阶段2第3步：本地 OpenAI 兼容服务。
 *
 * 在 0.0.0.0:PORT 起一个 HTTPServer：
 *   - 鉴权：Authorization: Bearer <apiKey>（本地 key，与上游 access_token 是两套）
 *   - POST /v1/chat/completions → 由 [credProvider] 取当下有效凭证转发（流式）；
 *     取不到（未登录/无可用账号）则返回固定假回复
 *   - GET  /v1/models → 返回静态模型表
 *   - GET  /healthz   → ok
 */
class LocalFakeServer(private val apiKey: String) {

    /**
     * 凭证提供者：**每次转发前**调用，而不是只在启动时快照一次。
     *
     * 为什么必须有它：服务是长驻的（App 一起就一直在跑），而 token 会过期。
     * 原来只在「启动 / 登录 / 切账号」时把 token 推进来，服务跑着跑着 token 过期就
     * **没有任何人续期**，接下来所有请求都是 401 —— 用户不可能记得每 14 天重启一次 App。
     * 交给提供者后，每次转发都拿"当下有效"的凭证（必要时由它触发续期）。
     *
     * 返回 null = 当前没有可用账号 → 走"无 token"分支（固定假回复），
     * **不**回退到任何旧快照：那只会拿一个可能已过期的 token 去撞 401。
     */
    @Volatile
    var credProvider: (() -> TraeAuthStore.Cred?)? = null

    /** 聊天用量回调：每次转发结束调用（后台线程）。参数=model,usage(可为null),耗时ms,成功,错误类型。 */
    @Volatile
    var usageRecorder: ((String, org.json.JSONObject?, Long, Boolean, String?) -> Unit)? = null

    /**
     * 故障转移：上一个账号**在还没吐出任何正文之前**就失败时，问一次"换哪个账号"。
     *
     * 入参 = 失败的 uid + 错误类型；返回 null = 没有可换的，就把原错误报给客户端。
     * 只在**未输出任何字节**时才会被调用 —— 已经在流里的半截回答无法撤回，换号也补不回来。
     */
    @Volatile
    var failoverProvider: ((failedUid: String, err: String) -> TraeAuthStore.Cred?)? = null

    /**
     * 换号重试**也没救活**时回调，参数是**原来那个** uid：把当前账号切回去。
     *
     * 为什么需要：换号是"乐观切换"（先切过去才能拿到该账号的有效 token，见 failoverProvider）。
     * 若换过去同样失败（典型：两个账号都不可用），不还原的话每次请求都会再换一次并持久化，
     * 于是账号在请求之间来回跳 —— 白打两次上游，账号页还闪烁。
     * 换号**成功**时不需要这个回调：那时新账号正是我们要的效果。
     */
    @Volatile
    var failoverRollback: ((originalUid: String) -> Unit)? = null

    companion object {
        /**
         * 8788 太常见（各类本地服务 demo 都爱用它），容易与其他 App 的测试服务撞端口。
         * 首选 84772 超出 TCP 端口上限（最大 65535，实测 `IllegalArgumentException: Port
         * value out of range`），备选 4772 是特权端口（<1024）绑定受限 —— 落到 8787。
         */
        const val PORT = 8787

        private const val TAG = "LocalFakeServer"

        /** `/v1/models` 输出的模型表。**未刷新时是空数组**（模型 Tab 还没加载），属设计意图。 */
        fun staticModels(): JSONArray = ModelsRepo.current()

        /**
         * 自检：中文 body 必须按**字节**长度读满。
         * 曾经按字符读（CharArray(contentLength)），中文请求字节数 > 字符数，
         * 于是永远等不到"剩余字节"、连接挂死到客户端超时。这里用一次断言把它钉住。
         */
        fun selfCheck() {
            val json = """{"model":"glm-5.2","messages":[{"role":"user","content":"你好，续期"}]}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            check(bytes.size > json.length) { "自检前提失败：中文 body 的字节数应大于字符数" }
            val got = readBody(java.io.ByteArrayInputStream(bytes), bytes.size)
            check(got == json) { "自检失败：按字节读回的 body 与原文不一致" }

            // 换号判定的边界：换错了会白耗另一个账号的额度、并把真错误藏起来。
            check(retryableUpstreamError("http:401")) { "自检失败：401 应换号" }
            check(retryableUpstreamError("intl_create:429")) { "自检失败：429 应换号" }
            check(retryableUpstreamError("http:503")) { "自检失败：5xx 应换号" }
            check(retryableUpstreamError("intl_events:exception")) { "自检失败：非 http 类错误应保守换号" }
            check(!retryableUpstreamError("http:400")) { "自检失败：400 是请求本身的问题，换谁都一样，不该换号" }
            check(!retryableUpstreamError("intl_commit:404")) { "自检失败：404 不该换号" }
            check(!retryableUpstreamError("http:422")) { "自检失败：422 不该换号" }
            check(retryableUpstreamError("http:500")) { "自检失败：500 应换号" }

            // chunked 请求体解析：Chatbox 就发 chunked，解析错了 body 会变成空串
            // → 上游收到空请求 → 客户端看到**空气泡**（且 curl 测不出来，因为它发 Content-Length）。
            // 注意分块长度是**字节**数：`"你好"}` = 1 + 3 + 3 + 1 + 1 = 9 字节（不是 8）。
            val chunked = "5\r\n{\"a\":\r\n9\r\n\"你好\"}\r\n0\r\n\r\n"
            val expect = "{\"a\":\"你好\"}"
            val gotChunked = readChunkedBody(java.io.ByteArrayInputStream(chunked.toByteArray(Charsets.UTF_8)))
            check(gotChunked == expect) { "自检失败：chunked 解析结果不对，得到 [$gotChunked]" }
            // 中文必须按字节切块：按字符切会把多字节字符劈开 → 解码出乱码。
            check(readChunkedBody(java.io.ByteArrayInputStream("3\r\n你\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8))) == "你") {
                "自检失败：chunked 里的中文（多字节）被切坏了"
            }

            // 非流式聚合：客户端发 stream:false 时要拿到一个完整 JSON。
            // 只看最后一帧拼内容会丢字（OpenAI 是逐帧分片的），所以必须逐帧拼接。
            val frames = listOf(
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":100," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"想\"}}]}\n\n",
                "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"}}]}\n\n",
                "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n\n",
                "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"delta\":{}}]," +
                    "\"usage\":{\"total_tokens\":7}}\n\n",
                "data: [DONE]\n\n"
            )
            val agg = aggregateChunks(frames, "m1")
            val msg = agg.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            check(msg.getString("content") == "你好") { "自检失败：非流式聚合应逐帧拼接 content，得到 [${msg.optString("content")}]" }
            check(msg.optString("reasoning_content") == "想") { "自检失败：reasoning_content 未被聚合" }
            check(agg.getJSONArray("choices").getJSONObject(0).getString("finish_reason") == "stop") {
                "自检失败：finish_reason 应取最后一个非空值"
            }
            check(agg.getJSONObject("usage").optInt("total_tokens") == 7) { "自检失败：usage 未被带出" }
            check(agg.optString("id") == "c1") { "自检失败：聚合后的 id 不对" }

            // 工具调用：name 在首帧、arguments 分片在后帧，必须合并成一条而不是互相覆盖。
            val toolFrames = listOf(
                "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}\n\n",
                "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n",
                "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"function\":{\"arguments\":\"\\\"北京\\\"}\"}}]}}]}\n\n",
                "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"delta\":{}}]}\n\n"
            )
            val toolAgg = aggregateChunks(toolFrames, "m2")
            val tMsg = toolAgg.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val tcs = tMsg.optJSONArray("tool_calls")
            check(tcs != null && tcs.length() == 1) { "自检失败：工具调用应合并成 1 条，得到 ${tcs?.length()}" }
            val tfn = tcs!!.getJSONObject(0).getJSONObject("function")
            check(tfn.getString("name") == "get_weather") { "自检失败：工具名丢了" }
            check(tfn.getString("arguments") == "{\"city\":\"北京\"}") {
                "自检失败：工具参数分片未拼全，得到 [${tfn.optString("arguments")}]"
            }
            check(tMsg.has("content")) { "自检失败：有工具调用时 content 仍需存在（可为空串）" }
        }

        /**
         * 该错误是否值得换个账号重试。
         *
         * 认证/限流/服务端问题 → 换号有意义（换个账号可能就好）；
         * 请求本身的问题（400/404/422 参数错）→ 换谁都一样，换号只会白耗另一个账号的额度并把真错误藏起来。
         * 解析不出状态码的（如网络异常、上游非 http 类错误）→ 保守换一次。
         */
        internal fun retryableUpstreamError(err: String): Boolean {
            val code = err.substringAfterLast(':').trim().toIntOrNull() ?: return true
            return code == 401 || code == 403 || code == 429 || code >= 500
        }

        /**
         * 单请求体上限。**不是可调项，是安全边界**：
         * `contentLength` 直接来自客户端写的 `Content-Length` 头，`ByteArray(contentLength)`
         * 会照着它分配 —— 一个 `Content-Length: 2147483647` 就能触发 2GB 分配，
         * 抛的是 `OutOfMemoryError`（不是 `Exception`，`catch (e: Exception)` **抓不到**），
         * 直接把进程干掉。对话请求体远超 8MB 也不现实（那是整本书的量）。
         */
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024

        /** 与实例方法同逻辑的静态版，供 [selfCheck] 直接调用。 */
        private fun readBody(input: java.io.InputStream, contentLength: Int): String {
            if (contentLength <= 0) return ""
            require(contentLength <= MAX_BODY_BYTES) { "请求体过大: $contentLength 字节 > $MAX_BODY_BYTES" }
            val bytes = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = input.read(bytes, off, contentLength - off)
                if (n < 0) break
                off += n
            }
            return String(bytes, 0, off, Charsets.UTF_8)
        }

        /**
         * 读 `Transfer-Encoding: chunked` 的请求体并拼回原始字符串（静态版，供 selfCheck 调用）。
         *
         * 为什么需要：**Chatbox 就是发 chunked 的**（实测 `TE: chunked` + 无 Content-Length）。
         * 只按 Content-Length 读会把 body 读成空串 → 上游收到空请求后不返回内容 →
         * 客户端表现为**空气泡**。且 `curl` 默认发 Content-Length，
         * 所以**只用 curl 测永远发现不了这个 bug**。
         *
         * 格式：`<十六进制长度>\r\n<该长度的字节>\r\n` 反复，以 `0\r\n\r\n` 结束。
         * 长度按**字节**算（中文一字多字节），故逐块按字节读、最后整体 UTF-8 解码 ——
         * 不能按字符拼（与 [readBody] 是同一个坑）。
         */
        internal fun readChunkedBody(input: java.io.InputStream): String {
            val buf = java.io.ByteArrayOutputStream()
            // 逐字节读到 CRLF：不能用 BufferedReader，它会预读进自己的缓冲、把后续 chunk 吃掉。
            fun readLine(): String? {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                    sb.append(c.toChar())
                }
            }
            while (true) {
                val sizeLine = readLine() ?: break
                // 长度行可能带 chunk 扩展（如 `1a;ext=1`），取分号前那一段。
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) break   // 末块；后面的 trailer 用不上，不读
                val chunk = ByteArray(size)
                var off = 0
                while (off < size) {
                    val n = input.read(chunk, off, size - off)
                    if (n < 0) break
                    off += n
                }
                buf.write(chunk, 0, off)
                readLine()              // 吃掉本块末尾的 CRLF
                if (off < size) break   // 连接提前断开：拿到的部分就是全部
            }
            return String(buf.toByteArray(), Charsets.UTF_8)
        }

        /**
         * 把内部产生的 SSE 帧聚合成一个**非流式** `chat.completion` 响应。
         *
         * 为什么需要：上游（Trae）只认流式，我们内部也只能流式拿数据；但 OpenAI 客户端
         * 可能发 `stream:false`（要求一次性 JSON）。以前那种请求会原样收到 SSE，
         * 严格按规范解析的客户端会直接报错，所以在这里拼回标准结构。
         *
         * 要拼的字段（对齐 OpenAI 流式 → 非流式的语义）：
         *  - `delta.content` 逐帧**拼接**成 `message.content`
         *  - `delta.reasoning_content` 拼接成 `message.reasoning_content`（推理模型的思考）
         *  - `delta.tool_calls` 按 `index` 累积：`function.name` 取首个非空，`arguments` 逐帧拼接
         *    （OpenAI 就是这么分片发的，不能只取最后一帧）
         *  - `finish_reason` 取**最后一个非空**值（中途帧是空的）
         *  - `usage` 取最后一帧带的那个
         */
        internal fun aggregateChunks(chunks: List<String>, model: String): JSONObject {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var finish = "stop"
            var usage: JSONObject? = null
            var id: String? = null
            var created = 0L
            // index → 累积中的 tool_call（name 取首个非空，arguments 逐帧拼）
            val toolCalls = LinkedHashMap<Int, JSONObject>()

            for (frame in chunks) {
                // 一帧可能含多行（`data: {...}` 后跟空行）；只认 data 行。
                for (line in frame.split('\n')) {
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val payload = t.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
                    if (id == null) obj.optString("id").takeIf { it.isNotBlank() }?.let { id = it }
                    if (created == 0L) created = obj.optLong("created", 0L)
                    obj.optJSONObject("usage")?.let { usage = it }
                    val choices = obj.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val c = choices.optJSONObject(i) ?: continue
                        // 中途帧的 finish_reason 是空串/字面 "null"，只在有真值时覆盖。
                        val fr = c.optString("finish_reason", "")
                        if (fr.isNotBlank() && fr != "null") finish = fr
                        val d = c.optJSONObject("delta") ?: continue
                        d.optString("content", "").takeIf { it.isNotEmpty() }?.let { content.append(it) }
                        d.optString("reasoning_content", "").takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
                        val tcs = d.optJSONArray("tool_calls") ?: continue
                        for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            val idx = tc.optInt("index", j)
                            val acc = toolCalls.getOrPut(idx) {
                                JSONObject().put("index", idx).put("type", "function")
                                    .put("function", JSONObject().put("name", "").put("arguments", ""))
                            }
                            tc.optString("id", "").takeIf { it.isNotBlank() }?.let { acc.put("id", it) }
                            val fn = tc.optJSONObject("function") ?: continue
                            val accFn = acc.getJSONObject("function")
                            if (accFn.optString("name").isEmpty()) {
                                fn.optString("name", "").takeIf { it.isNotBlank() }?.let { accFn.put("name", it) }
                            }
                            fn.optString("arguments", "").takeIf { it.isNotEmpty() }?.let {
                                accFn.put("arguments", accFn.optString("arguments") + it)
                            }
                        }
                    }
                }
            }

            val message = JSONObject().put("role", "assistant").put("content", content.toString())
            if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
            if (toolCalls.isNotEmpty()) {
                message.put("tool_calls", JSONArray().apply { toolCalls.values.forEach { put(it) } })
                // 有工具调用时 content 必须是字符串（可为空），OpenAI 客户端据此走工具分支。
                if (finish == "stop") finish = "tool_calls"
            }
            val choice = JSONObject().put("index", 0).put("message", message).put("finish_reason", finish)
            return JSONObject()
                .put("id", id ?: "chatcmpl-local")
                .put("object", "chat.completion")
                .put("created", if (created > 0) created else System.currentTimeMillis() / 1000)
                .put("model", model)
                .put("choices", JSONArray().put(choice))
                .apply { usage?.let { put("usage", it) } }
        }
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(PORT, 50, java.net.InetAddress.getByName("0.0.0.0"))
        server = ss
        acceptThread = thread(name = "LocalFakeServer-accept", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    // 单个连接的异常不得杀掉进程：一个坏请求只应影响它自己。
                    thread(name = "LocalFakeServer-conn", isDaemon = true) {
                        try { handle(client) } catch (e: Exception) {
                            android.util.Log.w("LocalFakeServer", "连接处理失败: ${e.javaClass.simpleName}: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ------------------------------------------------------------------
    // HTTP 处理
    // ------------------------------------------------------------------

    private fun handle(client: java.net.Socket) {
        client.use { sock ->
            // 读超时：请求行/头是**逐字节**读的（[readLineRaw]），客户端建连后只要慢慢发包、
            // 或干脆不发，就能永久占住一个连接线程。15s 足够任何正常客户端把请求发完。
            try { sock.soTimeout = 15_000 } catch (_: Exception) {}
            val input = sock.getInputStream()
            // 请求行/头逐字节读到 CRLF 为止：不用 BufferedReader，因为它会预读一批字节
            // 进自己的缓冲区，body 再从底层读就会缺一段（body 必须按 Content-Length 字节精确读）。
            fun readLineRaw(): String? {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                    sb.append(c.toChar())
                }
            }
            // 读取请求行（过滤空行）
            var line0: String?
            do { line0 = readLineRaw() } while (line0 != null && line0.isEmpty())
            if (line0 == null) return
            val parts = line0.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // 读取剩余请求头
            var line: String?
            var authHeader: String? = null
            var contentLength = 0
            var chunked = false
            while (true) {
                line = readLineRaw() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", true)) {
                    authHeader = line.substringAfter(":").trim()
                } else if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (line.startsWith("Transfer-Encoding:", true) &&
                    line.substringAfter(":").contains("chunked", true)
                ) {
                    chunked = true
                }
            }

            val out = sock.getOutputStream()
            val query = path.substringBefore('?')
            // 访问日志：客户端的路径五花八门（实测 Chatbox 走的就不是官方 SDK 那套），
            // 出问题时靠它一眼看出"对方到底请求了哪个路径、我们答了什么" ——
            // 没有这行日志时只能靠猜（这正是"获取模型失败"排查卡住的原因）。
            android.util.Log.i(TAG, "→ $method $path auth=${authHeader != null} len=$contentLength chunked=$chunked")
            when {
                // CORS 预检**必须最先答、且任何路径都答**：浏览器/WebView 系的客户端
                // （实测 Chatbox 就是）在真正发请求前先问一句 `OPTIONS`，
                // 它只认状态码与 Access-Control-* 头，不看 body。之前这个请求落到了
                // `/v1/models` 分支、回了 200+模型表但**没有 CORS 头** → 预检失败
                // → 客户端表现为"获取模型失败"。204 是预检的标准应答。
                method == "OPTIONS" -> writeRaw(out, 204, "text/plain", "")
                query == "/healthz" && method == "GET" -> writeRaw(out, 200, "text/plain", "ok")
                // 路径别名：**部分客户端请求不带 `/v1` 的 `/models`**（Chatbox 就是），
                // 只认 `/v1/models` 会让它们在"获取模型"这一步直接失败。两个都收。
                query == "/v1/models" || query == "/models" -> {
                    // /v1/models 仅返回固定模型清单（无敏感数据），免鉴权便于各类客户端拉取；ponytail: 聊天接口仍必须鉴权
                    val data = staticModels()
                    android.util.Log.i(TAG, "← 200 $query （${data.length()} 个模型）")
                    writeJson(out, 200, JSONObject().put("object", "list").put("data", data))
                }
                (query == "/v1/chat/completions" || query == "/chat/completions") && method == "POST" -> {
                    if (!authorized(authHeader)) { writeUnauthorized(out); return }
                    // 两种传体方式都要认：多数客户端发 Content-Length，但 **Chatbox 发 chunked**
                    // （实测 `Transfer-Encoding: chunked`，此时 Content-Length 缺失、contentLength=0）。
                    // 只认 Content-Length 的话 chunked 请求的 body 会读成空串 → 上游收到空请求
                    // → 什么都不返回 → 客户端显示**空气泡**。curl 默认发 Content-Length，
                    // 所以这个 bug 只用 curl 测是发现不了的。
                    val body = if (chunked) readChunkedBody(input)
                    else try {
                        readBody(input, contentLength)
                    } catch (e: Exception) {
                        // 体过大/非法：必须**明确回 413**，不能让它冒泡成"连接被重置"。
                        android.util.Log.w(TAG, "请求体被拒: ${e.message}")
                        writeJson(out, 413, openAIError("invalid_request_error",
                            "请求体过大（上限 ${MAX_BODY_BYTES / 1024 / 1024} MB）", 413))
                        return
                    }
                    // 每次请求都问一次「当下有效」的凭证：服务长驻，token 会过期，
                    // 靠启动时快照的那一个迟早变 401（见 credProvider 注释）。
                    val fresh = try { credProvider?.invoke() } catch (e: Exception) {
                        android.util.Log.w("LocalFakeServer", "取凭证失败: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                    if (fresh == null || fresh.accessToken.isBlank()) {
                        writeJson(out, 200, fakeCompletion())
                    } else {
                        // X-Uid 必须与本次 token 同属一个账号，否则上游接受连接却不返回数据。
                        //
                        // ⚠️ 必须兜住：`prepareBody` 会抛 JSONException（`messages` 存在但不是数组）、
                        // OkHttp 会抛 IOException。未兜的话异常穿到连接线程，而**流式响应头是在
                        // prepareBody 之后才写的** —— 客户端已发出请求却收到零字节，
                        // 表现成"连接被重置"，既没有状态行也无法诊断。
                        try {
                            streamingChat(out, body, fresh)
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "转发失败: ${e.javaClass.simpleName}: ${e.message}")
                            // 只在还没写过任何字节时补错误响应（流式头一旦发了就不能再补）
                            try { writeJson(out, 502, openAIError("upstream_error", "上游转发失败", 502)) } catch (_: Exception) {}
                        }
                    }
                }
                else -> {
                    // 未知路径必须留痕：客户端"连不上/用不了"时，这条日志直接指出是路径没对上，
                    // 而不是让人去猜端口、鉴权、防火墙。
                    android.util.Log.w(TAG, "← 404 未知端点: $method $path")
                    writeJson(out, 404, openAIError("not_found", "unknown endpoint: $path", 404))
                }
            }
        }
    }

    /**
     * 按 Content-Length（**字节**数）精确读满 body（实现见 companion.readBody，自检同源）。
     * 必须按字节读：含中文的请求字节数 > 字符数，按字符读会挂死到客户端超时。
     * 且不能走 BufferedReader——它会预读字节进自己的缓冲，导致 body 缺一段。
     */
    private fun readBody(input: java.io.InputStream, contentLength: Int): String =
        Companion.readBody(input, contentLength)

    /** 见 [Companion.readChunkedBody]：Chatbox 发 chunked，只认 Content-Length 会读成空 body。 */
    private fun readChunkedBody(input: java.io.InputStream): String =
        Companion.readChunkedBody(input)

    /**
     * 走真实 Trae 转发：先写 SSE 响应头，再把转换后的 chunk 逐块写回客户端。
     *
     * **故障转移**：上游"还没吐出任何正文就失败"时（典型：账号 token 死了的 401、
     * 限流的 429、上游 5xx），换一个账号重试一次（见 [failoverProvider]）。
     * 判定依据是「有没有出现过带 `choices` 的帧」—— 上游只有在成功路径上才会发它，
     * 失败路径只发 `event: error` + `[DONE]`。所以在看到第一个 `choices` 帧之前，
     * 输出先攒着不发给客户端；一旦确认这次成了，就把攒下的刷出去并转为直写。
     * 这样换号成功时客户端**看不到**上一个账号的报错（否则它会先收到 `[DONE]` 又来数据）。
     * 已经在流里的半截回答无法撤回，所以**只允许在未输出时换**，且只换一次。
     */
    private fun streamingChat(out: OutputStream, body: String, cred: TraeAuthStore.Cred) {
        // 请求体是信任边界：空体/非法 JSON 必须回 400，绝不能让 JSONException 冒泡
        // 到连接线程（那样会杀掉整个进程）。
        val src = try {
            org.json.JSONObject(body)
        } catch (_: org.json.JSONException) {
            writeJson(out, 400, openAIError("invalid_request_error", "请求体不是合法 JSON", 400))
            return
        }
        // ⚠️ 必须在 prepareBody **之前**读：prepareBody 会把 `stream` 原地改成 true
        //（上游只认流式），改完就看不到客户端原本要的是不是非流式了。
        val wantsStream = src.optBoolean("stream", false)
        val prepared = TraeUpstream.prepareBody(src)
        val model = prepared.optString("model", "")
        if (wantsStream) {
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: text/event-stream\r\n".toByteArray())
            out.write("Cache-Control: no-cache\r\n".toByteArray())
            // 流式响应头是**手写**的，不走 writeRaw —— CORS 头必须在这里补一份，
            // 否则 WebView 系客户端能拉到模型、却仍然发不出对话（流式响应被判跨站拦截）。
            out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            out.write("Access-Control-Allow-Headers: *\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.flush()
        }
        // 非流式时先攒着，最后一次性聚合成 JSON 再写（见 aggregateChunks）。
        val collected = if (wantsStream) null else ArrayList<String>()

        // 客户端可能提前断开（Broken pipe），属正常边界：忽略写失败，流转仍继续并记录用量
        fun writeChunk(s: String) {
            if (collected != null) { collected.add(s); return }
            try { out.write(s.toByteArray(Charsets.UTF_8)); out.flush() } catch (_: java.io.IOException) {}
        }

        var cur = cred
        var attempt = 0
        val originalUid = cred.uid   // 换号失败时要还原的对象
        while (true) {
            val t0 = System.currentTimeMillis()
            val pending = ArrayList<String>()
            var streaming = false          // 已确认这次上游成功（出现过 choices 帧）
            var usage: org.json.JSONObject? = null
            var err: String? = null
            TraeUpstream.chatStream(prepared, cur.accessToken, cur.uid, { chunk ->
                if (streaming) writeChunk(chunk)
                else {
                    pending.add(chunk)
                    if (chunk.contains("\"choices\"")) {
                        streaming = true
                        for (p in pending) writeChunk(p)
                        pending.clear()
                    }
                }
            }) { u, e -> usage = u; err = e }

            // 换号条件：这次没成功 + 错误值得换 + 还没换过 + 确实有别的账号
            val next = if (!streaming && err != null && attempt == 0 && retryableUpstreamError(err!!)) {
                try { failoverProvider?.invoke(cur.uid, err!!) } catch (e: Exception) {
                    android.util.Log.w(TAG, "换号失败: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }?.takeIf { it.accessToken.isNotBlank() && it.uid != cur.uid }
            } else null

            if (next == null) {
                // 收尾：把攒下的（正常情况下是错误事件）交给客户端
                for (p in pending) writeChunk(p)
                usageRecorder?.invoke(model, usage, System.currentTimeMillis() - t0, err == null, err)
                // 换了号却没救活 → 还原成用户原来的账号，免得每次请求都换一次、来回跳
                if (attempt > 0 && err != null) {
                    try { failoverRollback?.invoke(originalUid) } catch (e: Exception) {
                        android.util.Log.w(TAG, "还原账号失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                // 非流式：到这里所有帧都攒完了，聚合成一个 chat.completion 一次性返回。
                if (collected != null) writeJson(out, 200, aggregateChunks(collected, model))
                return
            }
            android.util.Log.i(TAG, "账号 ${cur.uid} 失败(${err})，换 ${next.uid} 重试")
            cur = next
            attempt++
        }
    }

    private fun authorized(auth: String?): Boolean {
        if (apiKey.isEmpty()) return true
        val prefix = "Bearer "
        if (auth == null || !auth.startsWith(prefix, ignoreCase = true)) return false
        return auth.substring(prefix.length).trim() == apiKey
    }

    private fun writeUnauthorized(out: OutputStream) {
        writeJson(out, 401, openAIError("invalid_api_key", "missing or invalid local API key", 401))
    }

    /** 固定的测试回复：与真实 Trae 结构一致，但内容是假的。 */
    private fun fakeCompletion(): JSONObject {
        val content = "这是本地假服务返回的测试响应。阶段2第2步已跑通：本地 OpenAI 兼容接口可用，" +
            "下一步再接真实 TraeWork 上游转发。"
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", content)
        val choice = JSONObject()
            .put("index", 0)
            .put("message", message)
            .put("finish_reason", "stop")
        return JSONObject()
            .put("id", "chatcmpl-fake-local")
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", "glm-5.2")
            .put("choices", JSONArray().put(choice))
            .put(
                "usage", JSONObject()
                    .put("prompt_tokens", 0)
                    .put("completion_tokens", 0)
                    .put("total_tokens", 0)
            )
    }

    private fun openAIError(code: String, msg: String, status: Int): JSONObject {
        val body = JSONObject().put("error",
            JSONObject().put("message", msg).put("type", "api_error").put("code", code))
        // 参考工程把 status 放返回值；此处仅用于 name/url 兼容，故不放 body。
        return body
    }

    private fun writeJson(out: OutputStream, status: Int, obj: JSONObject) {
        writeRaw(out, status, "application/json", obj.toString())
    }

    private fun writeRaw(out: OutputStream, status: Int, contentType: String, body: String) {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"; 404 -> "Not Found"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
        out.write("Content-Type: $contentType\r\n".toByteArray())
        // CORS：**每一个响应都要带**。实测 Chatbox 拉模型前会先发 `OPTIONS /v1/models` 预检，
        // 缺这几个头预检就失败（现象正是"获取模型失败"，且它会每 500ms 重试）。
        // 本地服务只在本机/局域网内、且聊天接口另有 API Key 鉴权，放开跨站头无实际风险。
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n".toByteArray())
        out.write("Access-Control-Allow-Headers: *\r\n".toByteArray())
        out.write("Access-Control-Max-Age: 86400\r\n".toByteArray())
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }
}