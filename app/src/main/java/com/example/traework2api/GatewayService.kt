package com.example.traework2api

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * 保活：把本地网关放进**前台服务**，让 App 退到后台 / Activity 被销毁后它继续跑。
 *
 * 为什么必须有它：网关原来由 MainActivity 持有，`onDestroy` 一调就停 ——
 * 而用户要"网关开着、切到别的客户端去用"，本 App 一旦被回收或划掉最近任务，端口就没了。
 *
 * ## 保活分两层，缺一层都会在真机上失效
 *
 * **第一层：标准 Android 保活**（原生系统/模拟器上够用）
 *  1. 前台服务 + 常驻通知 —— Android 8+ 唯一不会被随手回收的服务形态
 *  2. `PARTIAL_WAKE_LOCK` —— Cached App Freezer 会**跳过持锁进程**（系统豁免），
 *     没有它，冻结器会让"端口在监听但进程不被调度、请求全挂"
 *  3. `START_STICKY` —— 进程被系统回收后自动重建
 *  4. `stopWithTask=false`（见 manifest）—— 划掉最近任务不随任务栈销毁
 *
 * **第二层：国产 ROM（ColorOS / MIUI / EMUI）适配** —— 这层才是真机上的命门。
 * 厂商的省电策略会**无视 START_STICKY 直接强杀**前台服务，所以必须有补偿：
 *  5. `onTaskRemoved` —— 从最近任务划掉时立刻拉回；被后台启动限制拦住就挂精确闹钟兜底
 *  6. **看门狗** —— 已按 WorkBuddy 的结构**外置**为独立广播接收器 [KeepAliveWatchdog]：
 *     15 分钟一次精确闹钟唤醒它，由它判断"该不该拉起前台服务"。判断不再写在服务里，
 *     服务被杀也照样有人判断（原先 `PendingIntent.getService` 把判断塞进服务，
 *     服务不在就等于没人判断）。间隔 15 分钟：更密会被 ColorOS 判异常唤醒而拦截。
 *     8. 悬浮窗（[OverlayKeepAlive]）—— 有可见窗口的进程优先级更高，点一下即可复活
 *     9. 开机自启（[BootReceiver]，可选、默认关）
 *  7. `should_run` **持久化用户意图** —— 区分"用户主动关了"和"被系统杀了"，
 *     否则会出现"我都关了它怎么又自己开了"
 *
 * 另有**界面侧**的两项（不在本文件）：电池优化白名单引导、保活状态自检面板。
 * 电池优化白名单是厂商白名单机制的正门，比前面所有技术手段都管用。
 *
 * 开机自启是**可选开关**（默认关），实现在 [BootReceiver]：默认保持"重启后自己开一次"
 * 的原行为，用户在保活页显式打开才会在开机时拉起。
 *
 * 0.4.8 更新：用户意图语义变了 —— "进入 App 就默认开启，除非手动停止过"。
 * shouldRun 默认值仍为 false，但 MainActivity 的 autoStartServiceIfWanted 用
 * hasUserIntent 区分"从没碰过"（默认开）与"主动停了"（尊重关闭）。
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "traework2api_gateway"
        private const val NOTIFICATION_ID = 8788
        private const val WAKELOCK_TAG = "traework2api:gateway-wakelock"

        /** 通知栏「停止」按钮走这条 —— 不必为了停服务而打开 App。 */
        const val ACTION_STOP = "com.example.traework2api.STOP"
        /**
         * 唤醒锁超时 10 分钟，短于看门狗续期间隔。
         *
         * 为什么不无限期持有：死锁不还会被厂商省电策略判为"异常耗电目标"**优先清理**，
         * 与保活初衷相反。带超时 + 定期续期 = 始终持锁，又不留无人回收的永久锁。
         */
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L

        /** 用户意图持久化：true = 用户希望服务处于运行状态。 */
        private const val PREFS = "traework2api_service"
        private const val KEY_SHOULD_RUN = "should_run"

        /**
         * 给界面读的"网关在跑吗"（内存态，进程重建后由 [shouldRun] 之外的事实决定）。
         * 界面显示服务状态用这个；**判断该不该自动拉起**要用 [shouldRun]。
         */
        @Volatile
        var running = false
            private set

        /** 记录"用户是否希望服务运行"：启动置 true，用户主动停止置 false。 */
        fun markShouldRun(ctx: Context, value: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOULD_RUN, value).apply()
        }
        /** 用户意图。默认 false —— 装上就是关的（用户要求服务默认不开启）。 */
        fun shouldRun(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

        /**
         * 用户是否**表达过**意图（点过启动或停止）。没表达过 = 全新安装：
         * 进 App 默认启动的判据用它 + [shouldRun] 组合 —— shouldRun=false 有两种含义
         * （"从没碰过"与"主动停了"），只有后者才该拦住自动启动。
         */
        fun hasUserIntent(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SHOULD_RUN)

        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            // API 26+ 必须用 startForegroundService，否则后台启动服务会被直接拒绝。
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GatewayService::class.java).setAction(ACTION_STOP))
        }

        /**
         * 前台服务通知渠道是否被用户关掉。
         *
         * 前台服务通知不可见时，系统可能据此弱化甚至清理服务 —— 这是
         * "通知权限明明开着却还是被杀"的常见盲区，界面侧据此给重新开启的引导。
         */
        fun isChannelDisabled(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return false
            return mgr.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNow()
            // 用户主动停止 → 不允许系统再拉起来（否则会"关了又自己开"）。
            return START_NOT_STICKY
        }
        // 只要服务被启动（用户点按 / 看门狗兜底 / 划掉后拉回），就记为"用户希望它运行"。
        // 拦"用户已手动停止"的活儿现在在**拉起方**做：KeepAliveWatchdog 先查 shouldRun，
        // 再决定是否 startForegroundService —— 闹钟已不再直接投递 intent 给本服务，
        // 那些"在途 intent 拦不住"的边角情况随之消失。
        markShouldRun(applicationContext, true)
        // 必须在数秒内 startForeground，否则系统按 ANR 处理并杀掉服务。
        startForeground(NOTIFICATION_ID, notification())
        acquireWakeLock()
        Gateway.start(applicationContext)
        running = true
        // 悬浮窗保活：与「开关 + 权限」对齐（幂等，每次进服务都对齐一次，
        // 这样用户在设置页授权返回、或服务被重启后窗口都会自己回来）。
        OverlayKeepAlive.sync(applicationContext)
        // 每次进入都续期看门狗。看门狗本体是**独立广播接收器**（KeepAliveWatchdog），
        // 不再是由本服务自己接 intent —— 服务没了还能有人判断"该不该拉起"。
        if (KeepAlive.keepEnabled(applicationContext)) KeepAliveWatchdog.arm(applicationContext)
        return START_STICKY
    }

    /**
     * 用户从最近任务里划掉 App。
     *
     * 厂商 ROM（典型 ColorOS）会在这一刻**连带杀掉前台服务** —— `stopWithTask="false"`
     * 只能挡住"随任务栈销毁"，挡不住系统主动清理。
     *
     * Android 12+ 禁止后台启动前台服务（`ForegroundServiceStartNotAllowedException`），
     * 所以这里直接调 `startForegroundService` 很可能被拦；"只打日志"等于划掉后永不恢复。
     * 失败时立刻挂一个 5 秒后的**精确闹钟** —— 闹钟唤醒属于系统回调，不受该限制，
     * 能拿到一次宝贵的"前台服务启动配额"把服务拉回来。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户主动停止过就不要再拉起，否则会出现"关了又自己开"。
        if (!shouldRun(applicationContext)) {
            Log.i(TAG, "任务被划掉，但用户已停止服务 → 不拉起")
            super.onTaskRemoved(rootIntent)
            return
        }
        Log.i(TAG, "任务被划掉，正在拉回服务")
        val restart = Intent(applicationContext, GatewayService::class.java)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart) else startService(restart)
        }.isSuccess
        if (!started) {
            Log.w(TAG, "后台拉起被拦，退化为精确闹钟")
            KeepAliveWatchdog.armTaskRemovedFallback(applicationContext)
        }
        if (KeepAlive.keepEnabled(applicationContext)) KeepAliveWatchdog.arm(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Gateway.stop()
        // 服务先于进程结束时必须摘窗口：否则进程没了、窗口还挂在屏幕上，
        // 只能靠重启手机清掉（典型的"幽灵悬浮窗"）。
        OverlayKeepAlive.hide(applicationContext)
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    private fun stopNow() {
        running = false
        markShouldRun(applicationContext, false)
        // 必须撤掉看门狗，否则 15 分钟后会被闹钟莫名拉起来（"关了又自己开"）。
        KeepAliveWatchdog.disarm(applicationContext)
        Gateway.stop()
        OverlayKeepAlive.hide(applicationContext)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "网关已按用户要求停止")
    }

    // ------------------------------------------------------------------
    // 唤醒锁
    // ------------------------------------------------------------------

    /**
     * 部分唤醒锁（不计数模式，服务停止时统一释放）。
     *
     * 带 10 分钟超时而不是无限期持有：长时间死锁会被 ROM 的省电策略判为异常耗电目标而
     * **优先清理**，与保活初衷相反；超时后由 15 分钟的看门狗自动续期，既保持"始终持锁"，
     * 又不会留下一个无人回收的永久锁。
     */
    private fun acquireWakeLock() {
        // 已持有且未过期就跳过；带超时的锁过期后 isHeld 会变回 false，下次唤醒自动续上。
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "唤醒锁已持有（冻结豁免生效）")
        }.onFailure { Log.w(TAG, "唤醒锁获取失败，保活能力下降", it) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            // 复用应用图标的前景层（白色双向箭头）：通知栏小图标必须**纯白剪影**，
            // 系统只取 alpha 通道自己染色 —— 直接用 ic_launcher 那种靛蓝底会糊成一个方块。
            // 前景层正好是「白箭头 + 透明底」，拿来即用，不新增 drawable。
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TraeWork2API 网关")
            .setContentText("本地服务运行中 · 端口 ${LocalFakeServer.PORT}")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止", stop)
            .setOngoing(true)
            // CATEGORY_SERVICE：告诉系统这是长期运行的服务类通知，降低被清理概率。
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_DEFAULT)
            // Android 12+ 前台服务通知默认可延迟 10 秒显示；IMMEDIATE 让它立刻出现，
            // 避免"服务已在跑但通知栏迟迟看不到"而被部分 ROM 判定为隐形后台服务。
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // 已存在就不覆盖：用户手动调过的渠道设置（重要性/声音）必须尊重。
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 用 DEFAULT 而不是 LOW：部分 ROM 会把低重要性通知当作可降级，
        // 进而弱化对应前台服务的优先级；开发者文档也建议不低于 DEFAULT。
        val channel = NotificationChannel(
            CHANNEL_ID, "本地网关", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "常驻运行本地 OpenAI 兼容网关，供其它应用调用"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}