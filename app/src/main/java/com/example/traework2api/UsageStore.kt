package com.example.traework2api

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 用量记录：**JSONL 追加写**（每行一条），进程内缓存最近 [MAX_MEMORY] 条。
 *
 * **为什么从 SharedPreferences 改过来**：
 * 旧实现每次 `record()` 都做「全量序列化 N 条 + 整个字符串写回 prefs」。N=100 时，
 * 每发一次聊天请求就要重写约 20KB，是纯写放大（高并发对话时尤其明显），而且
 * `SharedPreferences.apply()` 在主线程排队，会拖慢响应。
 * 现在追加一行 = 一次 `appendText`，**O(1)**，与历史条数无关。
 *
 * 磁盘布局（滚动，防止无限增长）：
 * ```
 * filesDir/usage.jsonl      当前写入
 * filesDir/usage.1.jsonl    上一次滚动的
 * filesDir/usage.2.jsonl    再上一次
 * ```
 * 单文件超过 [FILE_MAX_BYTES] 就滚动一次，最多保留 [MAX_FILES] 个文件。
 *
 * 只存 时间/模型/uid/token 数/耗时/状态/错误类型，**绝不存任何凭据**。
 */
object UsageStore {

    /** 旧版存 prefs 的键：只在迁移时读一次，读完全删。 */
    private const val LEGACY_PREFS = "traework2api_prefs"
    private const val LEGACY_KEY = "usage_records"

    /** 内存里保留的条数（记录页只需要最近若干条；磁盘留得更多）。 */
    private const val MAX_MEMORY = 500
    private const val FILE_NAME = "usage.jsonl"
    private const val FILE_MAX_BYTES = 1_048_576L  // 单文件 1 MB
    private const val MAX_FILES = 3

    data class UsageRecord(
        val ts: Long,
        val model: String,
        /**
         * 这次请求实际使用的 Trae 账号 uid。
         *
         * 空串 = 旧记录（升级前写入的）或未登录的假回复，读取时按"归属未知"处理，
         * 不硬塞给任何账号。
         */
        val uid: String = "",
        val prompt: Int,
        val completion: Int,
        val total: Int,
        val durationMs: Long,
        val ok: Boolean,
        val errorType: String,
        /**
         * 请求成功，但**上游没返回 usage**（没有 token 数）。
         *
         * 这类请求会以 `prompt=completion=total=0` 落盘，与"真的用了 0 token"无法区分：
         * 混在一起时 token 均值和成本统计会被系统性拉低，用户看到「0 token」还会以为请求没生效。
         */
        val usageMissing: Boolean = false,
    )

    data class ModelAgg(val model: String, val total: Long, val output: Long)

    private val lock = Any()
    private val cached = mutableListOf<UsageRecord>()
    @Volatile private var loaded = false

    fun record(
        context: Context, model: String, uid: String, prompt: Int, completion: Int, total: Int,
        durationMs: Long, ok: Boolean, errorType: String,
        /** 成功但上游没给 usage。由调用方判断（它才知道 usage 是否为 null）。 */
        usageMissing: Boolean = false,
    ) {
        val r = UsageRecord(
            System.currentTimeMillis(), model, uid, prompt, completion, total,
            durationMs, ok, errorType, usageMissing
        )
        synchronized(lock) {
            ensureLoaded(context)
            cached.add(r)
            while (cached.size > MAX_MEMORY) cached.removeAt(0)
            // 追加写：失败也不能影响请求本身（统计丢了不影响转发）
            runCatching { appendLine(context, r) }
        }
    }

    /**
     * 追加一行。
     *
     * 跑在调用线程（连接线程），所以只做一次 `appendText` —— 不读旧内容、不重写整个文件。
     * 超过 [FILE_MAX_BYTES] 时先滚动一次再写。
     */
    private fun appendLine(context: Context, r: UsageRecord) {
        val f = File(context.filesDir, FILE_NAME)
        if (f.length() > FILE_MAX_BYTES) rotate(context)
        f.appendText(toJson(r).toString() + "\n", Charsets.UTF_8)
    }

    /** usage.jsonl → usage.1.jsonl → usage.2.jsonl，最老的丢掉。 */
    private fun rotate(context: Context) {
        val dir = context.filesDir
        // 从最老的开始往后挪：.2 删掉，.1→.2，当前→.1
        File(dir, "usage.${MAX_FILES - 1}.jsonl").delete()
        for (i in MAX_FILES - 2 downTo 1) {
            val src = File(dir, "usage.$i.jsonl")
            if (src.exists()) src.renameTo(File(dir, "usage.${i + 1}.jsonl"))
        }
        val cur = File(dir, FILE_NAME)
        if (cur.exists()) cur.renameTo(File(dir, "usage.1.jsonl"))
    }

    fun all(context: Context): List<UsageRecord> = synchronized(lock) {
        ensureLoaded(context)
        cached.toList()
    }

    /**
     * 清空全部记录（更多页「维护」入口）。
     *
     * 只删 jsonl 分片与内存缓存：模型缓存、账号凭据都不在 UsageStore，天然不受影响。
     * 失败（IO 异常）时保留原状并返回 false —— 调用方 toast 提示，不假装成功。
     */
    fun clear(context: Context): Boolean = synchronized(lock) {
        ensureLoaded(context)
        val dir = context.filesDir
        var ok = true
        for (name in (MAX_FILES - 1 downTo 1).map { "usage.$it.jsonl" } + FILE_NAME) {
            val f = File(dir, name)
            if (f.exists() && !f.delete()) ok = false
        }
        if (ok) cached.clear()
        ok
    }

    /**
     * 某个账号的最近调用（新→旧）。
     *
     * 只返回 **[uid] 明确匹配**的记录：升级前的老记录没有 uid（空串），
     * 不把它们塞给任何账号 —— 宁可少显示，也不显示错的归属。
     */
    fun recentFor(context: Context, uid: String, limit: Int): List<UsageRecord> = synchronized(lock) {
        ensureLoaded(context)
        if (uid.isBlank()) return@synchronized emptyList()
        cached.asReversed().filter { it.uid == uid }.take(limit)
    }

    /** 库里是否还有"归属未知"的老记录（账号详情页用来解释空态为什么空）。 */
    fun hasLegacyRecords(context: Context): Boolean = synchronized(lock) {
        ensureLoaded(context)
        cached.any { it.uid.isBlank() }
    }

    /** 按模型聚合总/输出 token（按列表中首次出现顺序）。 */
    fun aggregate(context: Context): List<ModelAgg> = synchronized(lock) {
        ensureLoaded(context)
        val map = LinkedHashMap<String, ModelAgg>()
        for (r in cached) {
            val g = map[r.model]
            if (g == null) map[r.model] = ModelAgg(r.model, r.total.toLong(), r.completion.toLong())
            else map[r.model] = g.copy(total = g.total + r.total, output = g.output + r.completion)
        }
        map.values.toList()
    }

    /**
     * 懒加载：迁移旧数据 → 读所有 jsonl 分片。
     *
     * 分片按 **旧→新** 顺序读（usage.2 → usage.1 → usage），保证 `cached` 内是时间正序，
     * 这样 `asReversed()` 拿到的才是真正的"最新在前"。
     */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        runCatching { migrateFromPrefs(context) }
        val dir = context.filesDir
        val order = (MAX_FILES - 1 downTo 1).map { "usage.$it.jsonl" } + FILE_NAME
        for (name in order) {
            val f = File(dir, name)
            if (!f.exists()) continue
            runCatching {
                f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        runCatching { cached.add(fromJson(JSONObject(line))) }
                    }
                }
            }
        }
        // 只保留最近 MAX_MEMORY 条在内存里
        while (cached.size > MAX_MEMORY) cached.removeAt(0)
        loaded = true
    }

    /**
     * 把旧版存在 SharedPreferences 里的一整段 JSON 数组搬到 jsonl，然后删掉那个键。
     *
     * ⚠️ **只有确认写进 jsonl 之后才删旧键**：早先的写法是无论 append 成功与否都 remove，
     * 一次 IO 异常就会把历史用量整个丢掉（而且下次启动已经没得迁移）。现在失败就保留原键，
     * 下次启动自动重试 —— 迁移是幂等的，重复跑只会多写一次（调用方已把旧键当唯一来源）。
     */
    private fun migrateFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val str = prefs.getString(LEGACY_KEY, null) ?: return
        val ok = runCatching {
            val arr = org.json.JSONArray(str)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { sb.append(toJson(fromJson(o)).toString()).append("\n") }
            }
            if (sb.isNotEmpty()) {
                File(context.filesDir, FILE_NAME).appendText(sb.toString(), Charsets.UTF_8)
            }
            true
        }.getOrDefault(false)
        if (ok) prefs.edit().remove(LEGACY_KEY).apply()
    }

    private fun toJson(r: UsageRecord): JSONObject = JSONObject()
        .put("ts", r.ts).put("model", r.model).put("uid", r.uid).put("prompt", r.prompt)
        .put("completion", r.completion).put("total", r.total)
        .put("ms", r.durationMs).put("ok", r.ok).put("err", r.errorType).put("um", r.usageMissing)

    private fun fromJson(base: JSONObject): UsageRecord = UsageRecord(
        ts = base.optLong("ts", System.currentTimeMillis()),
        model = base.optString("model", ""),
        uid = base.optString("uid", ""),
        prompt = base.optInt("prompt", 0),
        completion = base.optInt("completion", 0),
        total = base.optInt("total", 0),
        durationMs = base.optLong("ms", 0L),
        ok = base.optBoolean("ok", false),
        errorType = base.optString("err", ""),
        usageMissing = base.optBoolean("um", false),
    )
}