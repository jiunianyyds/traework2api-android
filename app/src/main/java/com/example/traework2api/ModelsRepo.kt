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
     * 装入磁盘缓存。**幂等**，两处调用：MainActivity.onCreate（冷启动）与
     * Gateway.start（服务被 START_STICKY/看门狗在没有 Activity 的新进程里拉起时，
     * 否则 /v1/models 会空表）。必须在任何 `/v1/models` 请求可能到达之前完成。
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

    /** 自检期间为 false：探针表只进内存、**绝不落盘**（见 [selfCheck]）。 */
    @Volatile
    private var persistEnabled = true

    /**
     * 装入上游拉回的表。**空表一律拒绝覆盖**（那道 `length() > 0` 闸）——
     * 已经刷出表之后再刷一次失败，不能把好好的清单清空。非空表同时落盘。
     */
    fun applyFetched(arr: JSONArray) {
        if (arr.length() > 0) {
            dynamic = arr
            if (!persistEnabled) return   // 自检探针：只测内存路径，不碰磁盘
            val ctx = appContext ?: return
            runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_MODELS, arr.toString()).apply()
            }
        }
    }

    /**
     * 自检：空表不许覆盖 + 非空表能装入 + **进出状态不变**。
     *
     * ⚠️ 前提变了（v0.4.15 起 [Gateway.start] 也会调 [init]）：磁盘缓存可能在自检之前
     * 就已经装进来 —— 服务比 Activity 先起（进程被 START_STICKY 重建）时就是这样，
     * 那时网关在跑、`/v1/models` 正在对外服务。旧自检断言"初始必须为空"并顺手把
     * `dynamic` 复位成 null —— 等于把运行中的缓存**砸掉**；而这个自检在每次
     * `recreate()`（切风格/切亮暗/转屏重建）时都会跑，于是切风格必崩
     * （IllegalStateException: 未刷新时模型表应为空）。
     *
     * 现在的前提改为：**先记住进来的状态，结束时原样放回**。"空初态"在受控的
     * 本地变量里测，不拿真实仓库当测试夹具。
     */
    fun selfCheck() {
        val saved = dynamic   // 进来的状态（可能是 null，也可能是网关正在服务的缓存）
        persistEnabled = false   // 探针绝不落盘：applyFetched 的持久化在这期间被关掉
        try {
            // —— 在受控初态下测"空 → 空表不覆盖 → 非空表能装入"——
            dynamic = null
            check(current().length() == 0) { "自检失败：空初态时 current() 应为空表" }
            check(!isDynamic) { "自检失败：空初态时 isDynamic 应为 false" }
            // 空表不许覆盖（防止"刷一次失败把已有清单清空"）
            applyFetched(JSONArray())
            check(!isDynamic) { "自检失败：空表被装入了" }
            // 非空表要能装进去，且 current() 跟着变
            val probe = JSONArray().put(JSONObject().put("id", "selfcheck-probe"))
            applyFetched(probe)
            check(isDynamic && current().length() == 1) { "自检失败：非空表没被装入" }
            check(current().optJSONObject(0)?.optString("id") == "selfcheck-probe") { "自检失败：装入的表内容不对" }
        } finally {
            // 原样放回：自检绝不污染真实仓库（清掉网关正在用的缓存是 v0.4.15 的实际事故）
            dynamic = saved
            persistEnabled = true
        }
    }
}
