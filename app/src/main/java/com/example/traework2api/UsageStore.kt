package com.example.traework2api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用量记录：SharedPreferences + JSON，进程内缓存，上限 [MAX] 条（超了丢最旧）。
 * 只存 时间/模型/token 数/耗时/状态/错误类型，绝不存任何凭据。
 */
object UsageStore {

    private const val PREFS = "traework2api_prefs"
    private const val KEY = "usage_records"
    private const val MAX = 100

    data class UsageRecord(
        val ts: Long,
        val model: String,
        val prompt: Int,
        val completion: Int,
        val total: Int,
        val durationMs: Long,
        val ok: Boolean,
        val errorType: String,
    )

    data class ModelAgg(val model: String, val total: Long, val output: Long)

    private val lock = Any()
    private val cached = mutableListOf<UsageRecord>()
    @Volatile private var loaded = false

    fun record(context: Context, model: String, prompt: Int, completion: Int, total: Int,
               durationMs: Long, ok: Boolean, errorType: String) {
        synchronized(lock) {
            ensureLoaded(context)
            cached.add(UsageRecord(System.currentTimeMillis(), model, prompt, completion, total,
                durationMs, ok, errorType))
            while (cached.size > MAX) cached.removeAt(0)
            persist(context)
        }
    }

    fun all(context: Context): List<UsageRecord> = synchronized(lock) {
        ensureLoaded(context)
        cached.toList()
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

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        runCatching {
            val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            if (!str.isNullOrBlank()) {
                val arr = JSONArray(str)
                for (i in 0 until arr.length()) cached.add(fromJson(arr.optJSONObject(i)))
            }
        }
        loaded = true
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (r in cached) arr.put(
            JSONObject().put("ts", r.ts).put("model", r.model).put("prompt", r.prompt)
                .put("completion", r.completion).put("total", r.total)
                .put("ms", r.durationMs).put("ok", r.ok).put("err", r.errorType)
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun fromJson(o: JSONObject?): UsageRecord {
        val base = o ?: JSONObject()
        return UsageRecord(
            ts = base.optLong("ts", System.currentTimeMillis()),
            model = base.optString("model", ""),
            prompt = base.optInt("prompt", 0),
            completion = base.optInt("completion", 0),
            total = base.optInt("total", 0),
            durationMs = base.optLong("ms", 0L),
            ok = base.optBoolean("ok", false),
            errorType = base.optString("err", ""),
        )
    }
}