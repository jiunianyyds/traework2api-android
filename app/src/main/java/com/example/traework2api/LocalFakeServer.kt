package com.example.traework2api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
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

            // chunked 的体上限：这条曾整条漏掉（只给 Content-Length 加了闸），
            // 于是持本地 key 的客户端发一个畸形 chunk 头就能让 `ByteArray(size)` 分配 2GB、
            // 抛 OutOfMemoryError 把进程打死。两种超限都必须**在分配之前**被拒。
            // 用小 maxBytes 造样本，避免自检自己吃掉十几 MB 内存。
            val oneChunkHuge = (200).toString(16) + "\r\n" + "a".repeat(200) + "\r\n0\r\n\r\n"
            check(runCatching {
                readChunkedBody(java.io.ByteArrayInputStream(oneChunkHuge.toByteArray()), maxBytes = 100)
            }.isFailure) { "自检失败：单块超过上限必须被拒（否则 2GB 分配会 OOM 打死进程）" }
            val cumulativeHuge = (60).toString(16) + "\r\n" + "a".repeat(60) + "\r\n" +
                (60).toString(16) + "\r\n" + "a".repeat(60) + "\r\n0\r\n\r\n"
            check(runCatching {
                readChunkedBody(java.io.ByteArrayInputStream(cumulativeHuge.toByteArray()), maxBytes = 100)
            }.isFailure) { "自检失败：累计超过上限必须被拒" }
            // 上限之内必须照常工作（别把闸设成把正常请求也挡了）
            check(readChunkedBody(
                java.io.ByteArrayInputStream(cumulativeHuge.toByteArray()), maxBytes = 120
            ) == "a".repeat(120)) { "自检失败：上限内的 chunked 请求应正常解析" }
            // 长度行溢出/非法必须报错，不能被当成 EOF 悄悄返回半截 body
            check(runCatching {
                readChunkedBody(java.io.ByteArrayInputStream("FFFFFFFF\r\nabc\r\n0\r\n\r\n".toByteArray()))
            }.isFailure) { "自检失败：非法 chunk 长度行必须报错（静默 break 会返回残缺 body）" }
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

            // 上游失败时只有 `data: {"error":…}`，聚合器**必然**给不出内容 —— 这钉住了
            // "为什么非流式失败路径必须回 502 而不能照常 aggregate 成 200"：
            // 那样客户端会收到 content:"" + finish_reason:"stop" 的"成功"响应，静默吞错。
            val errOnly = listOf(
                "data: {\"error\":{\"message\":\"upstream http 401\",\"type\":\"api_error\"}}\n\n",
                "data: [DONE]\n\n"
            )
            val errAgg = aggregateChunks(errOnly, "m3")
            val errMsg = errAgg.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            check(errMsg.optString("content").isEmpty()) {
                "自检失败：错误帧不该被聚合成有内容的回答（否则 200 空气泡就是这个 bug 的形态）"
            }

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

        /**
         * 单行请求头（含 chunk 长度行）上限。同样是安全边界，不是可调项：
         * 请求行/头是**逐字节**读进 `StringBuilder` 的，无上限时一个不换行的客户端就能
         * 让内存一直涨（`soTimeout` 只是 15s 的兜底，局域网内足够塞进几十 MB）。
         * 8KB 远超任何真实客户端（最长的 Cookie/JWT 也就几 KB）。
         */
        internal const val MAX_HEADER_LINE = 8 * 1024

        /** 并发连接上限（见实例字段 connSlots）。 */
        private const val MAX_CONNS = 32

        /** 拿不到空位时最多等多久再丢弃：给瞬时并发一点缓冲，又不会把 accept 线程堵死。 */
        private const val CONN_QUEUE_WAIT_MS = 5_000L

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
        internal fun readChunkedBody(input: java.io.InputStream, maxBytes: Int = MAX_BODY_BYTES): String {
            val buf = java.io.ByteArrayOutputStream()
            var total = 0
            // 逐字节读到 CRLF：不能用 BufferedReader，它会预读进自己的缓冲、把后续 chunk 吃掉。
            fun readLine(): String? {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                    // 长度行本身也必须设上限：逐字节读的 sb 是**无界**的，
                    // 一个不换行的客户端能让它一直涨（15s soTimeout 只是兜底，局域网内足够塞进几十 MB）。
                    if (sb.length > MAX_HEADER_LINE) return null
                    sb.append(c.toChar())
                }
            }
            while (true) {
                val sizeLine = readLine() ?: break
                // 长度行可能带 chunk 扩展（如 `1a;ext=1`），取分号前那一段。
                // 解析失败（含 `FFFFFFFF` 这种溢出、或长度行超长被 readLine 截成 null）
                // **必须报错而不是当 EOF**：静默 break 会返回半截 body → 上游收到残缺请求，
                // 客户端看到的是"空气泡"，而服务端日志一片太平。
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw IllegalArgumentException("chunk 长度行非法: ${sizeLine.take(40)}")
                if (size == 0) break   // 末块；后面的 trailer 用不上，不读
                // ⚠️ **与 Content-Length 路径同一个安全边界，不能只给一边**：
                // `size` 直接来自报文，下面 `ByteArray(size)` 会照着它分配 —— 一个 `7FFFFFFF`
                // 就是 2GB → `OutOfMemoryError`。而它是 **Error 不是 Exception**，
                // 连接线程的 `catch (e: Exception)` 抓不到，整个进程直接被干掉。
                // 单块与累计都要卡，且在**分配之前**卡。
                require(size in 1..maxBytes && total + size <= maxBytes) {
                    "请求体过大: 块 $size 字节、累计 ${total + size} 字节 > $maxBytes"
                }
                total += size
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

    /**
     * 并发连接上限。服务监听 0.0.0.0，同网段任何设备都能建连；
     * 无上限时几百条连接就能把线程栈 + 堆打满（每条连接一个线程）。
     */
    private val connSlots = java.util.concurrent.Semaphore(MAX_CONNS)

    val isRunning: Boolean get() = running.get()

    /**
     * 开始监听，**同步完成 bind**。绑定失败直接抛（调用方 [Gateway.start] 负责兜住并报人话原因）。
     *
     * ⚠️ 顺序是硬约束：**先 bind 成功，再把 `running` 置 true**。
     * 以前是 `compareAndSet(false,true)` 之后才 `ServerSocket(...)`，于是端口被占时：
     *  1) `IOException` 抛出去，`running` 却已经是 true；
     *  2) 若异常被别处捕获，`Gateway.isRunning` 会读成 true、`Gateway.start` 直接早退
     *     —— 端口永远起不来，只能杀进程。
     * 现在失败时 `running` 保持 false，下一次调用还能正常重试。
     */
    @Synchronized
    fun start() {
        if (running.get()) return
        // bind 失败会在这里抛，此时还没有任何状态被改动
        val ss = ServerSocket(PORT, 50, java.net.InetAddress.getByName("0.0.0.0"))
        server = ss
        running.set(true)
        acceptThread = thread(name = "LocalFakeServer-accept", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    // 单个连接必须**受管辖**：无上限时局域网内几百条连接就能把线程栈 + 堆打满
                    // （服务监听 0.0.0.0，任何同网段设备都到得了）。32 远超正常客户端用量
                    // （Chatbox 并行几条），超了就排队等前面释放。
                    // ponytail: 用信号量而非线程池 —— 少了拒绝策略/队列语义的复杂度，
                    // 反正是本地服务，排队比拒绝更友好。真要限速再换 ThreadPoolExecutor。
                    if (!connSlots.tryAcquire(CONN_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        android.util.Log.w(TAG, "并发连接已满（${MAX_CONNS}），丢弃一条")
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }
                    // 单个连接的异常不得杀掉进程：一个坏请求只应影响它自己。
                    thread(name = "LocalFakeServer-conn", isDaemon = true) {
                        try { handle(client) } catch (e: Exception) {
                            android.util.Log.w(TAG, "连接处理失败: ${e.javaClass.simpleName}: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        } finally {
                            connSlots.release()
                        }
                    }
                } catch (e: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    @Synchronized
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
                    // 超长行直接当连接结束处理（不返回半截行：截断的头会解析出错误的
                    // Content-Length/Auth，比"断开"更难诊断）。见 MAX_HEADER_LINE。
                    if (sb.length > MAX_HEADER_LINE) return null
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
            // ⚠️ 只记**去掉查询串**的路径：部分客户端把 API Key 放在 `?key=` 里，
            // 打整条 path 等于把凭据写进 logcat。
            android.util.Log.i(TAG, "→ $method $query auth=${authHeader != null} len=$contentLength chunked=$chunked")
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
                    // 两种传体方式的**上限检查都必须在同一个 try 里**：chunked 路径曾经在
                    // try 之外裸读（只给 Content-Length 加了闸），超限会抛到连接线程 → 连接被重置，
                    // 客户端完全不知道发生了什么。现在两边都收敛成 413。
                    val body = try {
                        if (chunked) readChunkedBody(input) else readBody(input, contentLength)
                    } catch (e: Exception) {
                        // 体过大 / 报文畸形：都必须**明确回状态码**，不能冒泡成"连接被重置"。
                        // 分开报是因为两者对用户的意义不同：413 = 你发的东西太大，
                        // 400 = 你发的报文本身坏了（客户端 bug 或中间设备改写）。
                        val tooLarge = e.message?.startsWith("请求体过大") == true
                        android.util.Log.w(TAG, "请求体被拒（${if (tooLarge) 413 else 400}）: ${e.message}")
                        writeJson(
                            out,
                            if (tooLarge) 413 else 400,
                            openAIError(
                                if (tooLarge) "request_too_large" else "invalid_request_error",
                                if (tooLarge) "请求体过大（上限 ${MAX_BODY_BYTES / 1024 / 1024} MB）"
                                else "请求体报文畸形：${e.message}",
                            )
                        )
                        return
                    }
                    // 每次请求都问一次「当下有效」的凭证：服务长驻，token 会过期，
                    // 靠启动时快照的那一个迟早变 401（见 credProvider 注释）。
                    val fresh = try { credProvider?.invoke() } catch (e: Exception) {
                        android.util.Log.w("LocalFakeServer", "取凭证失败: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                    if (fresh == null || fresh.accessToken.isBlank()) {
                        // 假回复也记一笔用量（0 token、ok）：否则"发了请求但记录页空空如也"，
                        // 用户没法区分"请求没到达"和"没登录走了假回复"。model 取请求里的
                        // 原始 model（还没进 prepareBody，非法名也不经这里）。
                        val fakeModel = try { org.json.JSONObject(body).optString("model", "") } catch (_: Exception) { "" }
                        usageRecorder?.invoke(fakeModel.ifBlank { "fake" }, null, 0, true, null)
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
                        } catch (e: Exception) {                            android.util.Log.w(TAG, "转发失败: ${e.javaClass.simpleName}: ${e.message}")
                            // 只在还没写过任何字节时补错误响应（流式头一旦发了就不能再补）
                            try { writeJson(out, 502, openAIError("upstream_error", "上游转发失败")) } catch (_: Exception) {}
                        }
                    }
                }
                else -> {
                    // 未知路径必须留痕：客户端"连不上/用不了"时，这条日志直接指出是路径没对上，
                    // 而不是让人去猜端口、鉴权、防火墙。同样不记查询串（可能含 key）。
                    android.util.Log.w(TAG, "← 404 未知端点: $method $query")
                    writeJson(out, 404, openAIError("not_found", "unknown endpoint: $query"))
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
            writeJson(out, 400, openAIError("invalid_request_error", "请求体不是合法 JSON"))
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
            // 区域取**这份凭证自己的**（不是全局 current）：换号后 cur 已更新，
            // 传 cur.region 才能保证"token 与 host 同属一个区域"。
            TraeUpstream.chatStream(prepared, cur.accessToken, cur.uid, cur.region, { chunk ->
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
                if (collected != null) {
                    // 非流式收尾。**上游在吐出任何正文之前就失败时必须回 502**：
                    // 以前这里无条件 `aggregateChunks` 再写 200，而错误帧是 `event: error` +
                    // 字符串 data，聚合器只认 `data:{…}`，会把错误全部丢掉 ——
                    // 客户端于是收到一个 HTTP 200、`content:""`、`finish_reason:"stop"` 的
                    // "成功"响应，既不报错也不会重试，用户只能看到空气泡。
                    if (!streaming && err != null) {
                        writeJson(out, 502, openAIError("upstream_error", "上游请求失败：$err"))
                    } else {
                        // ponytail: 上游"已经吐了半截正文再中断"时仍回 200（内容保留给用户），
                        // finish_reason 由 done 帧决定；要精确区分截断，得在聚合结果里加自定义字段，
                        // 那会破坏 OpenAI 兼容性，暂不做。
                        writeJson(out, 200, aggregateChunks(collected, model))
                    }
                }
                return
            }
            android.util.Log.i(TAG, "账号 ${cur.uid} 失败(${err})，换 ${next.uid} 重试")
            cur = next
            attempt++
        }
    }

    private fun authorized(auth: String?): Boolean {
        // **fail-closed**：key 异常为空时一律拒绝。以前这里是 `return true`（fail-open），
        // 现状走不到（Gateway.apiKey 恒生成非空值），但那是"将来某次改动一漏就整个服务裸奔"的默认值。
        if (apiKey.isEmpty()) return false
        val prefix = "Bearer "
        if (auth == null || !auth.startsWith(prefix, ignoreCase = true)) return false
        // 常量时间比较：`==` 会在首个不同字节处提前返回，理论上可被逐字节试探。
        // MessageDigest.isEqual 是 JDK 自带的、对长度差异也按常量时间处理的实现（不引依赖）。
        val given = auth.substring(prefix.length).trim().toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(given, apiKey.toByteArray(Charsets.UTF_8))
    }

    private fun writeUnauthorized(out: OutputStream) {
        writeJson(out, 401, openAIError("invalid_api_key", "missing or invalid local API key"))
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

    /** OpenAI 形状的错误体。**status 不进 body**：它由 HTTP 状态行承载（调用方传）。 */
    private fun openAIError(code: String, msg: String): JSONObject =
        JSONObject().put("error",
            JSONObject().put("message", msg).put("type", "api_error").put("code", code))

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