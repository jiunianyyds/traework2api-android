package com.example.traework2api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * 每日定时签到的闹钟接收器。
 *
 * ## 为什么是独立 receiver，而不是复用 GatewayService 的服务 intent（红线，别"顺手"合并）
 *
 * 1. `GatewayService.onStartCommand` 对**任何**非 STOP/WATCHDOG intent 都会无条件
 *    `markShouldRun(true)` —— 用户手动停掉的网关会被签到闹钟悄悄复活（v0.4.8 已踩过
 *    "停止后又自己开"这类坑）。
 * 2. 反过来，给 GatewayService 加 CHECKIN 分支做 shouldRun 前置校验，签到就继承了
 *    网关开关 —— 用户没开网关时签到永远不跑，违背"定时签到独立于网关"的需求。
 * 3. Android 12+ 从闹钟拉起前台服务受后台启动限制（FGS start not allowed）；
 *    receiver 里直接干活不碰这条限制。
 *
 * ## 执行模型
 *
 * `goAsync()` 把 receiver 的存活窗口延到 ~10s；批量签到可能超时，所以自持一个
 * **带 2 分钟超时**的 PARTIAL_WAKE_LOCK（权限已声明；持锁进程还有冻结豁免），
 * 干活放后台线程，跑完 finish() + 释放锁 + 发通知 + 重挂次日闹钟。
 */
class CheckinReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CheckinReceiver"
        /** 覆盖 N 账号 × (status+claim+status) × 网络超时的上界；到期兜底释放，防漏放。 */
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BatchCheckin.ACTION_ALARM) return
        if (!BatchCheckin.isEnabled(context)) {
            // 在投递路上被用户关掉：什么都别做（cancelAlarm 拦不住已发出的闹钟）
            android.util.Log.i(TAG, "闹钟到达但定时签到已被关闭 → 忽略")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traework2api:checkin").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        thread(name = "checkin-alarm") {
            try {
                if (!BatchCheckin.busy.compareAndSet(false, true)) {
                    android.util.Log.i(TAG, "手动签到正在进行 → 本轮定时跳过（结果仍会由手动路径产出）")
                    return@thread
                }
                try {
                    val result = BatchCheckin.runAll(app)
                    BatchCheckin.notifyIfScheduled(app, result)
                    android.util.Log.i(TAG, "定时签到完成: ${result.results.count { it.ok }} 成功")
                } finally {
                    BatchCheckin.busy.set(false)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "定时签到异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                BatchCheckin.scheduleNext(app)   // 触发后自续：挂上明天的闹钟
                runCatching { if (wl.isHeld) wl.release() }
                pending.finish()
            }
        }
    }
}
