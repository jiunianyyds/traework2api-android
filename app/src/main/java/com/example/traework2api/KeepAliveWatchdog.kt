package com.example.traework2api

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 看门狗（WorkBuddy 式：**独立广播接收器 + 精确闹钟**，不再塞进服务的 intent）。
 *
 * ## 为什么从"服务 intent"改成"广播接收器"
 *
 * 原版是把 `ACTION_WATCHDOG` 直接发给 [GatewayService]（`PendingIntent.getService`）。
 * 这条路上有两个结构性毛病：
 *
 *  1. **闹钟唤醒 = 后台启动服务**。Android 12+ 对后台 `startForegroundService` 有限制，
 *     服务可能起不来，而 `onStartCommand` 根本没被执行 —— 于是"看门狗跑了"和
 *     "服务回来了"看起来一样，日志里全是成功，实际早已失联。
 *  2. **服务被杀后，指向服务的 PendingIntent 也无法把服务"叫醒"**（它只是投递一个 intent），
 *     判断逻辑写在服务里，服务不在就没人判断。
 *
 * 拆成 receiver 之后，判断"该不该拉起"这件事发生在**进程外部的广播**里：
 * 闹钟唤醒 receiver → receiver 决定是否 `startForegroundService` → 失败就只记日志、
 * 下次再来。这与 WorkBuddy 的 `WATCHDOG` 广播（`watchdog fired, ensuring foreground`、
 * `direct restart blocked, falling back to alarm`）是同一套结构。
 *
 * ## 间隔别调小
 *
 * 15 分钟。更密会被 ColorOS/MIUI 判定为异常唤醒而**拦截后续闹钟**，
 * 结果比 15 分钟还不可靠（WorkBuddy 同样是 15 min）。
 */
class KeepAliveWatchdog : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveWatchdog"

        /** 周期自检广播。 */
        const val ACTION = "com.example.traework2api.WATCHDOG"

        /** 从最近任务划掉后的 5 秒一次性兜底。 */
        const val ACTION_TASK_REMOVED = "com.example.traework2api.WATCHDOG.TASK_REMOVED"

        /**
         * 自检间隔。**别调小**：更频繁会被厂商 ROM 判为异常唤醒而拦截，
         * 15 分钟的可靠性反而最高。
         */
        const val INTERVAL_MS = 15 * 60_000L

        private const val REQ_WATCHDOG = 8801
        private const val REQ_TASK_REMOVED = 8802

        /** 排上周期自检（幂等：同一 requestCode 会被替换，不会堆积闹钟）。 */
        fun arm(ctx: Context) {
            val app = ctx.applicationContext
            KeepAlive.markArmed(app)
            schedule(app, REQ_WATCHDOG, ACTION, INTERVAL_MS, "看门狗")
        }

        /**
         * 划掉任务后的一次性兜底闹钟（5 秒）。
         *
         * 为什么需要：`onTaskRemoved` 里直接 `startForegroundService` 很可能被 Android 12+
         * 的后台启动限制挡住；闹钟唤醒属于系统回调，能拿到一次宝贵的启动配额。
         */
        fun armTaskRemovedFallback(ctx: Context) {
            schedule(ctx.applicationContext, REQ_TASK_REMOVED, ACTION_TASK_REMOVED, 5_000L, "划掉后兜底")
        }

        /** 取消全部自检闹钟（用户停止网关、关闭保活总开关时调）。 */
        fun disarm(ctx: Context) {
            val app = ctx.applicationContext
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { am.cancel(pending(app, REQ_WATCHDOG, ACTION)) }
            runCatching { am.cancel(pending(app, REQ_TASK_REMOVED, ACTION_TASK_REMOVED)) }
            KeepAlive.clearArmed(app)
            Log.i(TAG, "看门狗已取消")
        }

        private fun pending(ctx: Context, request: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, request,
                Intent(ctx, KeepAliveWatchdog::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun schedule(ctx: Context, request: Int, action: String, delayMs: Long, label: String) {
            runCatching {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val pi = pending(ctx, request, action)
                val at = SystemClock.elapsedRealtime() + delayMs
                // Android 12+ 用户可能事后关掉"闹钟与提醒"，此时 setExactAndAllowWhileIdle
                // 会抛 SecurityException。先预检再选，并把降级写进日志（Doze 下可能延迟数小时）。
                val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (canExact) {
                    runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
                        .onFailure {
                            Log.w(TAG, "精确闹钟被拒，$label 降级为非精确: ${it.javaClass.simpleName}")
                            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                        }
                } else {
                    Log.w(TAG, "精确闹钟未授权，$label 降级为非精确（Doze 下可能延迟）")
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
                Log.i(TAG, "$label 已排 ${delayMs / 1000}s 后（exact=$canExact）")
            }.onFailure { Log.w(TAG, "$label 调度失败", it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION && action != ACTION_TASK_REMOVED) return

        val app = context.applicationContext

        // ① 用户主动停过 → 尊重，别再拉起（否则就是"关了又自己开"）。
        if (!GatewayService.shouldRun(app)) {
            Log.i(TAG, "唤醒($action)：用户已停止服务 → 取消自检")
            disarm(app)
            OverlayKeepAlive.hide(app)
            return
        }
        // ② 保活总开关关掉 → 只清闹钟，不动服务。
        if (!KeepAlive.keepEnabled(app)) {
            Log.i(TAG, "唤醒($action)：保活总开关已关闭 → 取消自检")
            disarm(app)
            return
        }
        // ③ 保活时长到期（WorkBuddy 的 keepalive_hours）→ 收工，不再唤醒。
        if (KeepAlive.keepExpired(app)) {
            Log.i(TAG, "唤醒($action)：保活时长已到期 → 取消自检与悬浮窗")
            disarm(app)
            OverlayKeepAlive.hide(app)
            return
        }

        // ④ 确保前台服务还在。失败不算异常：下一次闹钟还会再来。
        Log.i(TAG, "watchdog fired($action), ensuring foreground")
        runCatching { GatewayService.start(app) }
            .onFailure { Log.w(TAG, "拉起前台服务被拦（正常现象）: ${it.javaClass.simpleName}") }
        OverlayKeepAlive.sync(app)

        // ⑤ 续上下一轮（划掉兜底那次也顺手把周期自检补回来）。
        arm(app)
    }
}