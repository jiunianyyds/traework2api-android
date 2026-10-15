package com.example.traework2api

import android.content.Context
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
 * **持久化（0.4.8）**：曾经"刻意不持久化"，但外部客户端（Chatbox 等）在 App 重启后
 * 拉 `/v1/models` 只会拿到空列表 —— 用户报的正是"接入其他 APP 无法获取模型"。
 * 现在刷到的表存盘，进程重启后先恢复缓存（客户端立刻有可用清单），进入 App 后
 * 再由 UI 静默刷新一次保证新鲜（见 MainActivity.maybeAutoRefreshModels）。
 * 存盘只发生在**非空**表上：缓存坏了/空了就当没存过，绝不拿空表冒充清单。
 */
object ModelsRepo {

    private const val PREFS = "traework2api_models"
    private const val KEY_MODELS = "models_json"

    /** 未刷新时对外返回的空表（复用同一个实例，避免每次调用都 new）。 */
    private val EMPTY = JSONArray()

    /** 上游拉回来的表；null = 还没刷过（内存里），但可能仍有磁盘缓存。 */
    @Volatile
    private var dynamic: JSONArray? = null

    @Volatile
    private var appContext: Context? = null

    /**
     * 装入磁盘缓存。只在 App 冷启动调一次（MainActivity.onCreate），
     * 必须在任何 `/v1/models` 请求可能到达之前完成。
     */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        if (dynamic != null) return
        val json = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODELS, null)
        }.getOrNull() ?: return
        // 缓存损坏就丢弃：空表/解析失败都当没存过，回空状态，等下次刷新覆盖。
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        if (arr.length() > 0) dynamic = arr
    }

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
     * 已经刷出表之后再刷一次失败，不能把好好的清单清空。非空表同时落盘。
     */
    fun applyFetched(arr: JSONArray) {
        if (arr.length() > 0) {
            dynamic = arr
            val ctx = appContext ?: return
            runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_MODELS, arr.toString()).apply()
            }
        }
    }

    /**
     * 自检：初始为空 + 空表不许覆盖 + 非空表能装入。
     *
     * ⚠️ 自检必须**自行还原**（把 dynamic 复位成 null），否则会把测试数据留在真实仓库里，
     * 让 App 一起来就显示一个假模型。
     * ⚠️ 必须在 [init] **之前**跑：init 会装入磁盘缓存，跑在后面"初始为空"的前提就不成立了。
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
