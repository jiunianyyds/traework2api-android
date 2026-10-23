package com.example.traework2api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上下文预算：**对客户端声明的长度** 与 **网关侧真实预算** 分开管。
 *
 * ## 为什么要分成两个数
 *
 * `/v1/models` 里的 `context_length` 是给客户端看的**声明值**：客户端拿它决定
 * "什么时候开始压缩历史、什么时候提示超长"。它**不是**上游真的能吃这么多。
 * 把声明改成 1M 而不管网关侧，会出现两种坏情况：
 *
 *  - 客户端以为有 1M，一口气把 60 万字塞进来 → 上游按自己的 `max_input_tokens`
 *    **静默截断**（丢前文）或直接报错，用户看到的是"模型忘了前面说的话"；
 *  - 全程没有日志，问题看起来像"模型变笨了"，极难定位。
 *
 * 所以这里做两件事：
 *  1. [declared] —— 覆盖 `/v1/models` 里的 `context_length`，默认 **1M**（1048576）；
 *  2. [trim] —— 转发前按 [upstream] 的真实预算**从最老的非 system 消息开始丢**，
 *     并打日志。客户端尽管发，网关保证不把超长请求原样顶到上游脸上。
 *
 * 估算用 `字符数 / 2.5`（中文约 1 token ≈ 1~2 字，这里刻意**偏保守**：
 * 宁可多丢一条老消息，也不让上游截断得悄无声息）。
 */
object ContextBudget {

    private const val PREF = "traework2api_prefs"

    /** 声明给客户端的 context_length（0 = 跟随上游原值）。 */
    private const val KEY_DECLARED = "models_declared_context"

    /** 网关侧真实预算（token）。 */
    private const val KEY_UPSTREAM = "gateway_upstream_context"

    /** 界面可循环切换的档位。 */
    val OPTIONS = longArrayOf(131_072L, 262_144L, 524_288L, 1_048_576L)

    /** 默认声明 1M（用户要求）。 */
    const val DEFAULT_DECLARED = 1_048_576L

    /**
     * 网关侧真实预算默认值 = **1M**。
     *
     * 这是实测出来的，不是抄的：往 `/v1/chat/completions` 塞等长填充，
     * 用 `usage.prompt_tokens` 校准字符/token 比（英文约 5.17、中文约 1.77），
     * 首尾埋一个暗号看它记不记得：
     *
     * | 实际 prompt_tokens | 结果 |
     * |---|---|
     * | 1,045,074 | 记得 |
     * | 1,048,075 | 记得 |
     * | 1,048,600 | **返回空正文** |
     *
     * 边界正好卡在 1,048,576（=1M）。所以 `131_072` 不是上游上限，是我们自己
     * 解析不到 `max_input_tokens` 时的回落常量 —— 拿它当预算会白白扔掉 8 倍窗口。
     */
    const val DEFAULT_UPSTREAM = 1_048_576L

    private const val TAG = "ContextBudget"

    @Volatile
    private var app: Context? = null

    /** 由 [Gateway.start] 调一次：本对象要在没有 Context 的转发线程里读 pref。 */
    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun prefs() =
        app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 声明给客户端的长度。0 = 不覆盖，沿用上游 `max_input_tokens`。 */
    fun declared(): Long = prefs()?.getLong(KEY_DECLARED, DEFAULT_DECLARED) ?: DEFAULT_DECLARED

    fun setDeclared(value: Long) {
        prefs()?.edit()?.putLong(KEY_DECLARED, value.coerceIn(0L, 8L * 1_048_576L))?.apply()
    }

    /** 界面按钮：在 [OPTIONS] 里往后循环（末尾回到第一档）。 */
    fun nextDeclared(): Long {
        val cur = declared()
        val i = OPTIONS.indexOfFirst { it == cur }
        return if (i < 0 || i == OPTIONS.lastIndex) OPTIONS[0] else OPTIONS[i + 1]
    }

    /** 网关侧真实预算（token）。 */
    fun upstream(): Long =
        prefs()?.getLong(KEY_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM

    fun setUpstream(value: Long) {
        prefs()?.edit()?.putLong(KEY_UPSTREAM, value.coerceIn(4_096L, 8L * 1_048_576L))?.apply()
    }

    fun nextUpstream(): Long {
        val cur = upstream()
        val i = OPTIONS.indexOfFirst { it == cur }
        return if (i < 0 || i == OPTIONS.lastIndex) OPTIONS[0] else OPTIONS[i + 1]
    }

    /**
     * 粗估一段 `messages` 的 token 数。
     *
     * 只看 `content`（字符串或 text part 数组）与 `tool_calls`/`tool_call_id` 的字面量，
     * 角色与结构开销按每条 +4 计。**不做真实分词**：这里只要一个"够保守的上界"，
     * 精确分词要么带词典（体积），要么调上游（多一次请求）。
     */
    fun estimateTokens(messages: JSONArray?): Long {
        if (messages == null) return 0
        var milli = 0L
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            milli += textMilliTokens(m.opt("content"))
            milli += textMilliTokens(m.opt("tool_calls"))
            milli += textMilliTokens(m.opt("name"))
            milli += textMilliTokens(m.opt("tool_call_id"))
        }
        return milli / 1000L + messages.length() * 4L
    }

    /**
     * 中文 1 token ≈ 1.77 字，英文 1 token ≈ 5.17 字（实测，见 [DEFAULT_UPSTREAM] 注释）。
     * 折算成千分之一 token 存整数：CJK 取 570，其余取 190。
     *
     * 为什么不能再用 `字符数 / 2.5`：那个系数对英文高估一倍（2.5 vs 5.17），
     * 会把还能装下的对话提前丢掉；对中文又低估（2.5 vs 1.77），该丢的时候没丢，
     * 于是照样撞上游上限、静默返回空正文。
     */
    private fun milliTokensOf(s: String): Long {
        var t = 0L
        for (ch in s) t += if (ch.code >= 0x2E80) 570L else 190L
        return t
    }

    private fun textMilliTokens(v: Any?): Long = when (v) {
        null, JSONObject.NULL -> 0L
        is String -> milliTokensOf(v)
        is JSONArray -> {
            var n = 0L
            for (i in 0 until v.length()) {
                val it = v.opt(i)
                n += if (it is JSONObject) {
                    // content: [{"type":"text","text":"..."}]
                    milliTokensOf(it.optString("text", ""))
                } else textMilliTokens(it)
            }
            n
        }
        is JSONObject -> milliTokensOf(v.toString())
        else -> milliTokensOf(v.toString())
    }

    /**
     * 转发前裁剪：超出 [limit] 就从**最老的非 system 消息**开始丢，直到装得下或只剩一条。
     *
     * 返回丢掉的条数（0 = 没裁）。裁剪结果原地写回 `src` 的 `messages`。
     *
     * 为什么保留 system：那是用户/客户端设的人设与工具约束，丢了行为会突变。
     * 为什么从最老丢：这等价于"滑动窗口"，比截断最新消息更能维持对话连贯性。
     */
    fun trim(src: JSONObject, limit: Long = upstream()): Int {
        if (limit <= 0) return 0
        val msgs = src.optJSONArray("messages") ?: return 0
        val before = estimateTokens(msgs)
        if (before <= limit) return 0

        var dropped = 0
        while (msgs.length() > 1 && estimateTokens(msgs) > limit) {
            val idx = oldestDroppable(msgs)
            if (idx < 0) break
            removeAt(msgs, idx)
            dropped++
        }
        android.util.Log.i(
            TAG,
            "上下文裁剪：丢 $dropped 条老消息，估算 ${before} → ${estimateTokens(msgs)} tokens（预算 $limit）"
        )
        return dropped
    }

    /** 第一条不是 system 的消息下标；全是 system 时返回 -1（不裁，交给上游报错）。 */
    private fun oldestDroppable(msgs: JSONArray): Int {
        for (i in 0 until msgs.length()) {
            val role = msgs.optJSONObject(i)?.optString("role", "") ?: ""
            if (role != "system") return i
        }
        return -1
    }

    /** JSONArray 没有 remove，重建一份。 */
    private fun removeAt(msgs: JSONArray, index: Int) {
        val kept = JSONArray()
        for (i in 0 until msgs.length()) {
            if (i == index) continue
            kept.put(msgs.opt(i))
        }
        // 原地替换：调用方持有的是 src.getJSONArray("messages")，所以要把内容写回同一对象。
        while (msgs.length() > 0) msgs.remove(0)
        for (i in 0 until kept.length()) msgs.put(kept.opt(i))
    }

    /** 自检：不依赖手机，纯逻辑可跑。 */
    fun selfCheck() {
        // 声明值档位必须单调递增、包含 1M
        check(OPTIONS.contains(DEFAULT_UPSTREAM)) { "自检失败：默认预算必须是一个可切换档位" }
        check(OPTIONS.last() == DEFAULT_DECLARED) { "自检失败：最后一档应为 1M" }
        for (i in 1 until OPTIONS.size) {
            check(OPTIONS[i] > OPTIONS[i - 1]) { "自检失败：档位必须递增" }
        }

        // 估算：26 个 ASCII → 26*190/1000 = 4，再 +4 = 8
        val one = JSONArray().put(JSONObject().put("role", "user").put("content", "a".repeat(26)))
        check(estimateTokens(one) == 8L) { "自检失败：26 个 ASCII 应估 8，得到 ${estimateTokens(one)}" }
        // 估算：26 个汉字 → 26*570/1000 = 14，再 +4 = 18（中文必须比英文贵，否则漏裁）
        val cjk = JSONArray().put(JSONObject().put("role", "user").put("content", "字".repeat(26)))
        check(estimateTokens(cjk) == 18L) { "自检失败：26 个汉字应估 18，得到 ${estimateTokens(cjk)}" }

        // 裁剪：system 必须留下，老 user 先被丢
        val sys = JSONObject().put("role", "system").put("content", "s".repeat(100))
        val old1 = JSONObject().put("role", "user").put("content", "x".repeat(2000))
        val old2 = JSONObject().put("role", "assistant").put("content", "y".repeat(2000))
        val last = JSONObject().put("role", "user").put("content", "z".repeat(100))
        val body = JSONObject().put("messages", JSONArray().put(sys).put(old1).put(old2).put(last))
        val dropped = trim(body, limit = 500L)
        check(dropped >= 1) { "自检失败：超预算时必须裁掉消息" }
        val after = body.getJSONArray("messages")
        check(after.length() < 4) { "自检失败：裁剪后条数应减少" }
        check(after.getJSONObject(0).getString("role") == "system") {
            "自检失败：system 必须保留在首位（丢了人设会突变）"
        }
        check(after.getJSONObject(after.length() - 1).optString("content").startsWith("z")) {
            "自检失败：最后一条用户消息不能丢"
        }

        // 不超预算时不该动
        val small = JSONObject().put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", "hi")))
        check(trim(small, limit = 100_000L) == 0) { "自检失败：未超预算不应裁剪" }
    }
}