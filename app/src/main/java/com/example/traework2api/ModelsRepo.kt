package com.example.traework2api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 进程内存模型仓库：静态默认清单（buildStatic）+ 可选的上游动态表。
 *
 * LocalFakeServer.staticModels()（/v1/models 输出）与"模型"Tab 都读 current()。
 * 用户在模型 Tab 点「刷新模型」时由 [applyFetched] 装入上游真实表；**静态表始终兜底**。
 *
 * 第一版**不持久化**：重启回静态表。用户要的是"刷最新"，不是"离线缓存"。
 */
object ModelsRepo {

    private val defaultIds = listOf(
        "glm-5.2", "glm-5-turbo", "glm-5",
        "Doubao-Seed-2.1-Pro", "Doubao-Seed-2.1-Turbo",
        "DeepSeek-V4-Pro", "DeepSeek-V4-Flash",
        "kimi-k3", "kimi-k2.7-code", "kimi-k2.6",
        "qwen-3.7-plus", "minimax-m3"
    )

    private val static: JSONArray = buildStatic()

    /** 上游拉回来的表；null = 还没刷过 / 刷新失败（回落到 [static]）。 */
    @Volatile
    private var dynamic: JSONArray? = null

    private fun buildStatic(): JSONArray {
        val arr = JSONArray()
        for (id in defaultIds) {
            arr.put(
                JSONObject()
                    .put("id", id).put("name", id)
                    .put("config_name", id).put("context_length", 131072)
                    .put("object", "model").put("owned_by", "trae-solo")
                    .put("created", 1753600000)
            )
        }
        return arr
    }

    /**
     * 当前生效的模型表。**永远非空**：没刷过 / 刷失败 / 未登录都回静态表。
     *
     * ⚠️ 空表绝不允许生效 —— `/v1/models` 与模型 Tab 都读它，
     * 空数组会让**所有客户端看不到任何模型**（比少一个新模型严重得多）。
     */
    fun current(): JSONArray = dynamic ?: static

    /** 是否已经装入上游表（UI 用来区分"内置清单"与"已刷新"）。 */
    val isDynamic: Boolean get() = dynamic != null

    /**
     * 装入上游拉回的表。**空表一律拒绝覆盖**（那道 `length() > 0` 闸）——
     * 否则"刷一次失败"就把好好的清单清空了。
     */
    fun applyFetched(arr: JSONArray) {
        if (arr.length() > 0) dynamic = arr
    }

    /**
     * 自检：表非空 + 空表不许覆盖。空表会让所有客户端看不到模型，是本任务最贵的失败面。
     */
    fun selfCheck() {
        check(current().length() > 0) { "自检失败：模型表为空（空表会让所有客户端看不到模型）" }
        // 空表不许覆盖：这是"刷一次失败把清单清空"的护栏
        applyFetched(JSONArray())
        check(dynamic == null) { "自检失败：空表覆盖了静态表" }
        // 非空表要能装进去，且 current() 跟着变
        val probe = JSONArray().put(JSONObject().put("id", "selfcheck-probe"))
        applyFetched(probe)
        check(dynamic != null && current().length() == 1) { "自检失败：非空表没被装入" }
        // 还原，别让自检污染真实清单
        dynamic = null
        check(current().length() == defaultIds.size) { "自检失败：自检没能还原静态表" }
    }
}
