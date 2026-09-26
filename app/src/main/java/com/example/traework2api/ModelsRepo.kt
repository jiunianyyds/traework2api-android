package com.example.traework2api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 进程内存模型仓库：**只有上游拉回来的表，没有内置兜底清单**。
 *
 * 为什么删掉静态兜底（0.4.3 的改动）：内置 12 个 id 是硬编码的，上游加了新模型这边永远不会变，
 * 于是"打开软件看到 12 个"会被误当成全部模型 —— 用户报的正是这个问题。
 * 现在未刷新时 [current] 返回**空数组**，模型 Tab 显示「尚未加载」空状态，
 * 逼用户主动刷一次，拿到的必然是上游的真实表。
 *
 * 副作用（正向）：上游标了 `is_invisible_to_user` 的模型（如 `glm-5`）以前刷新后消失、
 * **重启又冒出来**（因为回落静态表）；去掉静态表后这个不一致自然消失。
 *
 * LocalFakeServer.staticModels()（`/v1/models` 输出）与"模型"Tab 都读 [current]。
 *
 * **刻意不持久化**：重启就回空状态，必须重新刷。用户要的是"刷最新"，
 * 不是"离线缓存"——存盘会让旧表冒充新表，正是本次要消灭的错觉。
 */
object ModelsRepo {

    /** 未刷新时对外返回的空表（复用同一个实例，避免每次调用都 new）。 */
    private val EMPTY = JSONArray()

    /** 上游拉回来的表；null = 还没刷过。 */
    @Volatile
    private var dynamic: JSONArray? = null

    /**
     * 当前生效的模型表。**未刷新时是空数组**（不是 null，调用方直接 `.length()` 就行）。
     *
     * ⚠️ 空表是**合法状态**：`/v1/models` 会返回空列表，客户端因此看不到可选的模型 ——
     * 这是设计意图（没刷新就没有模型可选），不是故障。
     */
    fun current(): JSONArray = dynamic ?: EMPTY

    /** 是否已经装入上游表（UI 用来区分"尚未加载"与"已加载"）。 */
    val isDynamic: Boolean get() = dynamic != null

    /**
     * 装入上游拉回的表。**空表一律拒绝覆盖**（那道 `length() > 0` 闸）——
     * 已经刷出表之后再刷一次失败，不能把好好的清单清空。
     */
    fun applyFetched(arr: JSONArray) {
        if (arr.length() > 0) dynamic = arr
    }

    /**
     * 自检：初始为空 + 空表不许覆盖 + 非空表能装入。
     *
     * ⚠️ 自检必须**自行还原**（把 dynamic 复位成 null），否则会把测试数据留在真实仓库里，
     * 让 App 一起来就显示一个假模型。
     */
    fun selfCheck() {
        // 初始必须是空的：这是"未刷新 = 空状态"的前提
        check(current().length() == 0) { "自检失败：未刷新时模型表应为空" }
        check(!isDynamic) { "自检失败：未刷新时 isDynamic 应为 false" }
        // 空表不许覆盖（防止"刷一次失败把已有清单清空"）
        applyFetched(JSONArray())
        check(!isDynamic) { "自检失败：空表被装入了" }
        // 非空表要能装进去，且 current() 跟着变
        val probe = JSONArray().put(JSONObject().put("id", "selfcheck-probe"))
        applyFetched(probe)
        check(isDynamic && current().length() == 1) { "自检失败：非空表没被装入" }
        check(current().optJSONObject(0)?.optString("id") == "selfcheck-probe") { "自检失败：装入的表内容不对" }
        // 还原，别让自检污染真实仓库
        dynamic = null
        check(current().length() == 0) { "自检失败：自检没能还原成空状态" }
    }
}
