package com.example.traework2api

import android.content.Context
import java.util.UUID

/**
 * 本地网关的**业务持有者**：跑不跑、凭证从哪来、失败怎么换号。
 *
 * 为什么单独抽出来、而不是留在 MainActivity 或 [GatewayService] 里：
 *  - Android 的保活机制（前台服务 / 通知 / 唤醒锁）属于 [GatewayService]，
 *    与"网关跑不跑"无关 —— 分开后两边各自只有一个理由变更。
 *  - ⚠️ 这里**一律只用 applicationContext**。旧代码把这些 provider 建在 MainActivity 里、
 *    捕获了 Activity；保活之后服务会比 Activity 活得更久，那就是长驻内存泄漏。
 */
object Gateway {
    private const val SIXTY_MINUTES_SEC = 3600L
    private const val TAG = "Gateway"

    /**
     * 串行化「取凭证 + 续期」。refresh token 是**轮换**的：并发续期会让先换到的那个失效。
     * MainActivity 的"登录后主动续期"也必须走 [locked]，否则会和这里的 credProvider 撞车。
     */
    private val tokenLock = Any()

    @Volatile
    private var server: LocalFakeServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    /** 供 MainActivity 的主动续期复用同一把锁（别再加第二把：轮换只允许一个续期在跑）。 */
    fun <T> locked(block: () -> T): T = synchronized(tokenLock) { block() }

    /** 本地 API Key（与上游 access_token 是两套）。首次访问生成并持久化。 */
    fun apiKey(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("traework2api_prefs", Context.MODE_PRIVATE)
        prefs.getString("local_api_key", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val key = "sk-" + UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("local_api_key", key).apply()
        return key
    }

    /**
     * 幂等启动：已在跑就直接返回。
     * 重复 start 会去抢同一个端口 → `EADDRINUSE` 崩掉，所以这道闸必须留着。
     */
    @Synchronized
    fun start(ctx: Context) {
        if (isRunning) return
        val app = ctx.applicationContext
        // 上下文预算要能在没有 Context 的转发线程里读 pref，所以在这里注入一次。
        ContextBudget.init(app)
        val s = LocalFakeServer(apiKey(app))
        s.usageRecorder = { model, usage, ms, ok, err ->
            UsageStore.record(app, model,
                usage?.optInt("prompt_tokens", 0) ?: 0,
                usage?.optInt("completion_tokens", 0) ?: 0,
                usage?.optInt("total_tokens", 0) ?: 0,
                ms, ok, err ?: "")
        }
        // 每次转发前取「当下有效」的凭证（必要时续期）。服务是长驻的、token 会过期，
        // 不能只在启动/登录/切账号时推一次快照（那样跑够 14 天后全是 401）。
        s.credProvider = {
            locked {
                TraeAuthApi.restartWithValidToken(app, skewSec = SIXTY_MINUTES_SEC)
                    ?.let { t -> TraeAuthStore.load(app)?.let { c -> c.copy(accessToken = t) } }
            }
        }
        // 故障转移：上游**还没吐正文**就失败（token 死了的 401 / 限流 429 / 5xx）时换另一个账号重试一次。
        // 换号会**真的切过去并持久化** —— 否则下一个请求还拿同一个死账号去撞墙。
        // 过滤 CreditMonitor 标了冷却的账号：它刚因低余额被切走，换回它只会让账号来回跳。
        s.failoverProvider = failover@{ failedUid, err ->
            val next = TraeAuthStore.pickFailoverCandidate(
                TraeAuthStore.list(app).filterNot { CreditMonitor.isDemoted(it.uid) }, failedUid,
            ) ?: return@failover null
            android.util.Log.i(TAG, "转发失败($err)，自动换到账号 ${next.uid}(${next.regionId})")
            locked {
                TraeAuthStore.setActive(app, next.uid)
                // 必须同步区域：否则会拿新账号的 token 打旧区域的 host
                TraeAuthStore.syncRegionToActiveAccount(app)
                TraeAuthApi.restartWithValidToken(app, skewSec = SIXTY_MINUTES_SEC)
                    ?.let { t -> TraeAuthStore.load(app)?.let { c -> c.copy(accessToken = t) } }
            }
        }
        // 换号也没救活 → 切回用户原来选的账号（否则账号会在请求间来回跳）
        // 例外：原账号刚被判低余额（CreditMonitor 冷却中）。回滚它 = 下一轮 monitor 又把它切走，
        // 两个机制会每 30 秒对着掰一次。宁可停在当前号上，让错误如实报给客户端。
        s.failoverRollback = { originalUid ->
            if (CreditMonitor.isDemoted(originalUid)) {
                android.util.Log.i(TAG, "原账号 $originalUid 处于低余额冷却期，不回滚")
            } else {
                android.util.Log.i(TAG, "换号后仍失败，切回原账号 $originalUid")
                locked {
                    TraeAuthStore.setActive(app, originalUid)
                    TraeAuthStore.syncRegionToActiveAccount(app)
                }
            }
        }
        server = s
        s.start()
        // 积分监测线程随网关起停：只有网关在跑（才可能消耗积分）时它才有意义。
        // 注意它内部只做「锁内读本地 → 锁外发网络」，绝不在 tokenLock 内做网络请求。
        CreditMonitor.start(app)
        android.util.Log.i(TAG, "网关已启动，端口 ${LocalFakeServer.PORT}")
    }

    /** 幂等停止：没启动时调用也安全（用户可能没开就退出）。 */
    @Synchronized
    fun stop() {
        CreditMonitor.stop()
        server?.stop()
        server = null
        android.util.Log.i(TAG, "网关已停止")
    }

    /**
     * 自检：本地 API Key 必须**多次取值一致且非空**。
     *
     * 为什么值得钉：key 一旦变成"每次访问都重新生成"，所有已经填了 key 的客户端
     * 全部 401，而且现象是"昨天能用今天不能"，极难定位。
     */
    fun selfCheck(ctx: Context) {
        val a = apiKey(ctx)
        check(a.isNotBlank() && a.startsWith("sk-")) { "自检失败：本地 API Key 格式不对" }
        check(a == apiKey(ctx)) { "自检失败：本地 API Key 不稳定（每次取值都变会让所有客户端 401）" }
    }
}