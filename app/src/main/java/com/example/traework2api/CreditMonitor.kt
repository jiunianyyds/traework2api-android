package com.example.traework2api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 积分监测：网关服务运行中，每 [TICK_MS]（30 秒）查一次**当前账号**的剩余积分；
 * 低于用户设定的阈值时，自动切到余额最高的另一个国服账号。
 *
 * ## 为什么是 30 秒而不是更短
 * [TraeUpstream.fetchUsage] 每次都是一次真实的上游往返（`/pay/ide_user_ent_usage`）。
 * 2 秒一次等于每小时 1800 次，既费电也容易触发上游的设备维度风控（该项目在
 * `X-Device-Id` 派生那段踩过 9074）。30 秒 = 每小时 120 次，单账号。
 *
 * ## 锁边界（这个类最容易写错的地方）
 * `LocalFakeServer` 在**写出任何响应字节之前**同步调用 `Gateway.credProvider`，
 * 而 `credProvider` 整个包在 `Gateway.tokenLock` 里。所以：
 *
 *  - `Gateway.locked {}` 内**绝不发网络请求**。`fetchUsage` 最坏 200 秒
 *    （client readTimeout 180s + ug() 吞异常），`exchangeToken` 最坏 50 秒；
 *    持锁做这个 = 所有 `/v1/chat/completions` 零字节挂死。
 *  - 唯一允许进锁的网络是**token 真的过期时的续期**：refresh 是轮换的，
 *    并发续期会互相作废，必须串行。它约 14 天触发一次/账号。
 *
 * ## 不做的事
 *  - 不判断上游"额度耗尽"的业务码（真实码未实测，不猜）。
 *  - 不常驻轮询所有账号：其它账号的余额只在"当前账号低于阈值、需要挑人"时查一次。
 */
object CreditMonitor {

    private const val TAG = "CreditMonitor"

    private const val PREFS = "traework2api_prefs"
    private const val KEY_AUTO = "auto_switch_enabled"
    private const val KEY_THRESHOLD = "auto_switch_threshold"

    /** 轮询间隔。改这里就等于改监控灵敏度，别在别处散落写 30000。 */
    const val TICK_MS = 30_000L

    /** 续期判据的前置窗口：距到期不到 1 小时就换，避免请求途中过期。 */
    private const val SKEW_SEC = 3600L

    /** 余额快照保鲜期：超过它就当"未知"，不参与换号决策（防一次超时导致误切）。 */
    private const val FRESH_MS = 2 * TICK_MS

    /** 扫描一轮后无论成败都静默这么久，避免"全部账号都没钱"时每 30 秒把全表打一遍。 */
    private const val SCAN_COOLDOWN_MS = 5 * 60_000L

    /** 被判低余额的账号，这段时间内不参与自动换号候选（与 failover 共用）。 */
    private const val DEMOTE_MS = 10 * 60_000L

    private const val DEFAULT_THRESHOLD = 5L

    /** 一次余额读数。只存**成功**读到的值，失败（null）不写，见 [balanceOf]。 */
    private data class Reading(val value: Long, val atMs: Long)

    /** 一次自动换号的记录，供 UI 显示"已从 X 切到 Y"。 */
    data class Switch(
        val fromUid: String,
        val fromNick: String,
        val fromBalance: Long,
        val toUid: String,
        val toNick: String,
        val toBalance: Long,
        val atMs: Long,
    )

    /** 一次 tick 的结果，回调给 UI。 */
    data class Snapshot(
        val activeUid: String?,
        val balance: Long?,
        val atMs: Long,
        val switch: Switch?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val readings = ConcurrentHashMap<String, Reading>()
    private val demotedUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var latest: Snapshot? = null
    @Volatile private var lastSwitch: Switch? = null
    @Volatile private var lastScanAt = 0L
    @Volatile private var running = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var worker: Thread? = null

    private val inFlight = AtomicBoolean(false)

    /** UI 监听：**主线程**回调。Activity 销毁时必须置 null（见 MainActivity.onDestroy）。 */
    @Volatile var listener: ((Snapshot) -> Unit)? = null

    // ------------------------------------------------------------------
    // 开关与阈值（prefs 持久化，跟着用户走）
    // ------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 自动换号默认**开**：用户装这个 App 就是为了多账号轮着用。 */
    fun isAutoSwitchEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, true)

    fun setAutoSwitchEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    /** 当前账号余额 ≤ 这个值就换号。0 = 用完才换。 */
    fun threshold(ctx: Context): Long =
        prefs(ctx).getLong(KEY_THRESHOLD, DEFAULT_THRESHOLD).coerceAtLeast(0L)

    fun setThreshold(ctx: Context, value: Long) {
        prefs(ctx).edit().putLong(KEY_THRESHOLD, value.coerceAtLeast(0L)).apply()
    }

    // ------------------------------------------------------------------
    // 对外查询
    // ------------------------------------------------------------------

    fun currentSnapshot(): Snapshot? = latest

    fun lastSwitchOrNull(): Switch? = lastSwitch

    /** 指定账号最近一次**成功**读到的余额；超过保鲜期或从未读到返回 null。 */
    fun balanceOf(uid: String): Long? {
        val r = readings[uid] ?: return null
        return r.value.takeIf { System.currentTimeMillis() - r.atMs <= FRESH_MS }
    }

    /**
     * 该账号是否在冷却期（被判低余额/刚被自动切走）。
     *
     * `Gateway.failoverProvider` 与 `failoverRollback` 都要看它 —— 否则会出现
     * "monitor 切走 A → 请求失败 → rollback 切回 A → 30 秒后 monitor 又切走" 的死循环。
     */
    fun isDemoted(uid: String): Boolean {
        val until = demotedUntil[uid] ?: return false
        if (System.currentTimeMillis() >= until) {
            demotedUntil.remove(uid)
            return false
        }
        return true
    }

    private fun demote(uid: String) {
        demotedUntil[uid] = System.currentTimeMillis() + DEMOTE_MS
    }

    // ------------------------------------------------------------------
    // 生命周期：由 Gateway.start / Gateway.stop 调（只在那两处，别的地方别起线程）
    // ------------------------------------------------------------------

    fun start(context: Context) {
        appCtx = context.applicationContext
        if (running) return
        running = true
        worker = Thread({ loop() }, "credit-monitor").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "积分监测已启动（每 ${TICK_MS / 1000} 秒）")
    }

    /**
     * 停止。**不 join**：调用方是主线程（GatewayService.onDestroy / stopNow），
     * 而一次 tick 可能正卡在 200 秒的上游超时里，join 就是 ANR。
     * daemon 线程留在那里最多多跑完一轮，不影响进程退出。
     */
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        while (running) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                if (inFlight.compareAndSet(false, true)) {
                    try {
                        tick()
                    } finally {
                        inFlight.set(false)
                    }
                }
            } catch (e: Throwable) {
                // 任何异常都必须吞在循环内：否则线程死掉而 Gateway.isRunning 仍是 true，
                // 用户看到的是"监控开着但永远不动"，没有任何提示。
                Log.w(TAG, "tick 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
            // 500ms 粒度睡眠：stop() 的 interrupt 能及时生效，也不用 wait/notify
            while (running && SystemClock.elapsedRealtime() - startedAt < TICK_MS) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 一次 tick
    // ------------------------------------------------------------------

    private fun tick() {
        val ctx = appCtx ?: return
        if (!Gateway.isRunning) return
        // 批量签到全程持锁且串行跑好几十秒，这时候插进来只会互相等；跳过本轮即可
        if (BatchCheckin.busy.get()) return

        // ① 锁内只做本地读取（元数据 + KeyStore 解密），毫秒级
        val metas = Gateway.locked { TraeAuthStore.list(ctx) }
        val activeUid = Gateway.locked { TraeAuthStore.activeUid(ctx) }
        if (activeUid == null) {
            publish(Snapshot(null, null, System.currentTimeMillis(), null))
            return
        }
        val activeMeta = metas.firstOrNull { it.uid == activeUid }

        // ② 取当前账号 token（必要时在锁内续期），③ 锁外查余额
        val token = tokenFor(ctx, activeUid)
        val balance = if (token.isNullOrBlank()) null else
            TraeUpstream.fetchUsage(token, activeUid, activeMeta?.region ?: TraeRegion.current)
        if (balance != null) readings[activeUid] = Reading(balance, System.currentTimeMillis())

        val now = System.currentTimeMillis()
        val threshold = threshold(ctx)
        val autoEnabled = isAutoSwitchEnabled(ctx)

        var switched: Switch? = null
        if (shouldSwitch(balance, threshold, autoEnabled) &&
            !isDemoted(activeUid) &&
            now - lastScanAt >= SCAN_COOLDOWN_MS
        ) {
            switched = trySwitchToRichest(ctx, metas, activeUid, activeMeta, balance!!, threshold)
        }

        publish(Snapshot(activeUid, balance, now, switched))
    }

    /**
     * 换号判据。抽成纯函数是为了能在 [selfCheck] 里钉边界 ——
     * 这段逻辑判错的方向后果不对称：余额未知（null）被判成 0 会因一次网络抖动换号。
     */
    internal fun shouldSwitch(balance: Long?, threshold: Long, autoEnabled: Boolean): Boolean =
        autoEnabled && balance != null && balance <= threshold

    /**
     * 在其它国服账号里挑余额最高的，切过去。返回换号记录；没换成返回 null。
     * 网络全在锁外；落笔在锁内且**复核 active 没被用户改动过**。
     */
    private fun trySwitchToRichest(
        ctx: Context,
        metas: List<TraeAuthStore.AccountMeta>,
        fromUid: String,
        fromMeta: TraeAuthStore.AccountMeta?,
        fromBalance: Long,
        threshold: Long,
    ): Switch? {
        val candidates = metas.filter {
            it.uid != fromUid && it.region.isSupported && !isDemoted(it.uid)
        }
        if (candidates.isEmpty()) {
            lastScanAt = System.currentTimeMillis()
            Log.i(TAG, "当前账号余 $fromBalance ≤ $threshold，但没有可切换的其它国服账号")
            return null
        }

        var bestMeta: TraeAuthStore.AccountMeta? = null
        var bestBalance = Long.MIN_VALUE
        var skippedExpired = 0
        for (m in candidates) {
            val c = TraeAuthStore.loadByUid(ctx, m.uid) ?: continue
            // 只查 token 还剩效的候选，**不在这里续期**。
            // 续期必须在 tokenLock 里做网络请求（refresh 是轮换的，不能并发），扫 N 个过期账号
            // 就是 N 次持锁 —— 那段时间所有 /v1/chat/completions 都卡在 credProvider 上。
            // 切过去之后 credProvider 会自己续期，这里没必要抢着做。
            if (c.accessToken.isBlank() || TraeAuthApi.expiredWithin(c.expiresAt, SKEW_SEC)) {
                skippedExpired++
                continue
            }
            val b = TraeUpstream.fetchUsage(c.accessToken, m.uid, m.region) ?: continue
            readings[m.uid] = Reading(b, System.currentTimeMillis())
            if (b > threshold && b > bestBalance) {
                bestBalance = b
                bestMeta = m
            }
        }
        lastScanAt = System.currentTimeMillis()

        val target = bestMeta
        if (target == null) {
            val note = if (skippedExpired > 0) "（其中 $skippedExpired 个 token 已过期，留给下次请求时自动续期）" else ""
            Log.i(TAG, "没有余额 > $threshold 的可切账号$note，保持当前账号（${SCAN_COOLDOWN_MS / 1000}s 内不再扫描）")
            return null
        }

        // 落笔：复核 active 还是我们决定时的那个 —— 扫描要几秒，这期间用户可能手动切过号
        val applied = Gateway.locked {
            if (TraeAuthStore.activeUid(ctx) != fromUid) {
                Log.i(TAG, "扫描期间当前账号已被改动，放弃本次自动换号")
                false
            } else {
                TraeAuthStore.setActive(ctx, target.uid)
                // 必须同步区域：否则下一个请求拿新账号的 token 打旧区域的 host
                TraeAuthStore.syncRegionToActiveAccount(ctx)
                true
            }
        }
        if (!applied) return null

        demote(fromUid)
        val record = Switch(
            fromUid = fromUid,
            fromNick = fromMeta?.nickname?.ifBlank { null } ?: fromUid,
            fromBalance = fromBalance,
            toUid = target.uid,
            toNick = target.nickname.ifBlank { target.uid },
            toBalance = bestBalance,
            atMs = System.currentTimeMillis(),
        )
        lastSwitch = record
        Log.i(TAG, "积分低于阈值($fromBalance ≤ $threshold)，自动切到 ${target.uid}（余 $bestBalance）")
        return record
    }

    /**
     * 取指定账号的可用 access token；必要时续期。
     *
     * **不复用 [TraeAuthApi.restartWithValidToken]**：那个函数只认"当前账号"，
     * 且会 `TraeRegion.current = cred.region` 翻全局区域（用户正复制的国际服登录链接会被串区），
     * 续期成功还走 `save()` 把 active 抢回去。这里按账号自己的区域换、写回不动 active。
     */
    private fun tokenFor(ctx: Context, uid: String): String? {
        val c = TraeAuthStore.loadByUid(ctx, uid) ?: return null
        val usable = c.accessToken.isNotBlank() && !TraeAuthApi.expiredWithin(c.expiresAt, SKEW_SEC)
        if (usable) return c.accessToken
        if (c.refreshToken.isBlank()) return c.accessToken.takeIf { it.isNotBlank() }
        return try {
            renew(ctx, uid)
        } catch (e: Exception) {
            Log.w(TAG, "续期失败 $uid: ${e.javaClass.simpleName}: ${e.message}")
            c.accessToken.takeIf { it.isNotBlank() }
        }
    }

    /** 锁内续期：进锁后**再判一次**是否还需要换（这几十毫秒里别人可能已经换过）。 */
    private fun renew(ctx: Context, uid: String): String? = Gateway.locked {
        val c = TraeAuthStore.loadByUid(ctx, uid) ?: return@locked null
        if (c.accessToken.isNotBlank() && !TraeAuthApi.expiredWithin(c.expiresAt, SKEW_SEC)) {
            return@locked c.accessToken
        }
        if (c.refreshToken.isBlank()) return@locked null
        val ex = TraeAuthApi.exchangeToken(c.refreshToken, c.region) // 显式传区域，不读全局
        val nc = c.copy(
            accessToken = ex.accessToken,
            refreshToken = ex.refreshToken ?: c.refreshToken,
            expiresAt = ex.expiresAt ?: c.expiresAt,
        )
        TraeAuthStore.updateTokens(ctx, nc)   // 不切 active
        // expiresAt 存在索引里，必须一起回写：不然下一轮还会再"续期"一次，白耗一个 refresh
        TraeAuthStore.updateMeta(ctx, nc.toMeta())
        nc.accessToken
    }

    private fun publish(snapshot: Snapshot) {
        latest = snapshot
        listener?.let { l -> main.post { l(snapshot) } }
    }

    // ------------------------------------------------------------------
    // 自检
    // ------------------------------------------------------------------

    /**
     * 纯计算自检，App 启动时调用。
     *
     * 钉住的是"余额未知不换号"：`fetchUsage` 在任何失败路径都返回 null
     * （区域不支持 / 非 2xx / 字段缺失 / 异常），把它当 0 会让一次超时把好好的账号切走。
     */
    fun selfCheck() {
        check(!shouldSwitch(null, 100, true)) { "自检失败：余额未知（null）不得触发换号" }
        check(shouldSwitch(100, 100, true)) { "自检失败：余额等于阈值应触发换号" }
        check(!shouldSwitch(101, 100, true)) { "自检失败：余额高于阈值不得触发换号" }
        check(shouldSwitch(0, 0, true)) { "自检失败：阈值 0 时余 0 应触发换号" }
        check(!shouldSwitch(1, 0, true)) { "自检失败：阈值 0 时余 1 不得触发换号" }
        check(!shouldSwitch(0, 0, false)) { "自检失败：总开关关闭时不得换号" }
    }
}
