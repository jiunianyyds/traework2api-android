package com.example.traework2api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 进程内存模型仓库：固定默认清单（buildStatic）。
 *
 * LocalFakeServer.staticModels()（/v1/models 输出）与"模型"Tab 都读 current()；
 * 此清单固定不变，不做真模型拉取替换。
 */
object ModelsRepo {

    private val defaultIds = listOf(
        "glm-5.2", "glm-5-turbo", "glm-5",
        "Doubao-Seed-2.1-Pro", "Doubao-Seed-2.1-Turbo",
        "DeepSeek-V4-Pro", "DeepSeek-V4-Flash",
        "kimi-k3", "kimi-k2.7-code", "kimi-k2.6",
        "qwen-3.7-plus", "minimax-m3"
    )

    private val current: JSONArray = buildStatic()

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

    fun current(): JSONArray = current
}