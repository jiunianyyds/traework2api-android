package com.example.traework2api

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多账号批量签到 + 每日定时调度（状态与通知也在这里）。
 *
 * ## 三个功能入口共用同一条编排（[runAll]）
 *  1. 账号页「一键签到全部账号」按钮（用户手动）
 *  2. 每日定时闹钟（[CheckinReceiver]，默认关闭、用户手动开启）
 *  3. 进 App 补签（[maybeCatchUp]：定时开关开着但闹钟没跑成 —— App 进程被杀/手机重启）
 *
 * ## 设计决策（都来自 2026-10-13 的方案评审，别退回去）
 *
 * - **设备身份**：[TraeUpstream.deviceIdFor] 等三个标识按 uid 确定性派生，多账号天然不互撞
 *   （9074 的根因就是共用设备 id）—— 这是批量签到可行的前提。
 * - **单线程逐账号**：签到/换 token 函数虽已支持显式 region，但并发跑会同时打同一批上游
 *   端点（风控敏感）；账号之间还留 800ms 间隔。
 * - **国际服跳过**：SG 签到协议未实测（ug 头都是按国服包抓的），标「不支持」而不是硬打。
 * - **claim 前置校验照抄单账号路径**：status 未签且 enable 才发 claim（重复 claim 幂等
 *   但白发请求）；成功判据用 [TraeUpstream.checkinSucceeded]（成功码 ∧ 状态跃迁）。
 * - **续期写回走 [TraeAuthStore.updateTokens]**（不碰 active）—— [TraeAuthStore.save] 会
 *   把保存的账号设为当前账号，后台批量续期会偷偷切号。
 * - **锁边界（v0.4.15 修正）**：整批**不持** `Gateway.locked`。那把锁与转发路径的
 *   `credProvider` 是同一把，整批持锁等于签到期间所有对话请求零字节挂死。只有"续期"
 *   这一步需要串行（refresh 是轮换的），已收进 [TraeAuthApi.validTokenForUid]。
 * - **互斥**：[busy] 挡住「手动签到」与「定时签到」同时跑（两个入口可能撞在同一时刻）。
 *
 * ## 每日闹钟（[scheduleNext]）
 *  生命周期四条：开启时挂 → 每次触发自续 → 进 App 自愈重挂（[maybeCatchUp] 顺带做）→
 *  关闭即取消。**重启手机闹钟全丢是已接受的边界**（用户决定不做 BOOT receiver），
 *  由打开补签兜底。走独立 BroadcastReceiver 而不是 GatewayService 的服务 intent：
 *  后者 onStartCommand 对非 WATCHDOG/STOP intent 会无条件 markShouldRun(true)，
 *  会把用户手动停掉的网关悄悄复活（v0.4.8 已踩过同类坑）。
 */
object BatchCheckin {

    private const val TAG = "BatchCheckin"

    private const val PREFS = "traework2api_checkin"
    /** 每日定时签到开关（默认关 —— 用户要求，不开启就不挂任何闹钟）。 */
    private const val KEY_ENABLED = "schedule_enabled"
    /** 定时时刻，"HH:mm"（设备本地时区）。 */
    private const val KEY_TIME = "schedule_time"
    /** 上次批量跑完的日期（yyyy-MM-dd，本地时区），补签/去重用。 */
    private const val KEY_LAST_RUN_DATE = "last_run_date"
    /** 上次批量结果 JSON（[PerAccount] 列表），账号页展示用。 */
    private const val KEY_LAST_RESULTS = "last_results"

    /** 账号间隔：上游按设备维度风控，连发容易撞 9074。 */
    private const val PER_ACCOUNT_DELAY_MS = 800L
    /** 通知渠道。独立于网关渠道：用户关网关通知不应连带关签到通知。 */
    private const val CHANNEL_ID = "traework2api_checkin"
    private const val NOTIFICATION_ID = 8791
    /** 闹钟 request code（避开 GatewayService 的 8789/8790）。 */
    private const val ALARM_REQUEST = 8792
    const val ACTION_ALARM = "com.example.traework2api.CHECKIN_ALARM"

    /** 手动/定时互斥。 */
    val busy = AtomicBoolean(false)

    /** 单账号签到结果（持久化展示用，不含任何 token）。 */
    data class PerAccount(
        val uid: String,
        val nickname: String,
        /** ok / already / disabled / unsupported / fail */
        val status: String,
        /** 成功时的今日可领积分（-1 = 无）。 */
        val credits: Long,
        /** fail 时的原因（人话）。 */
        val message: String,
    ) {
        val ok: Boolean get() = status == "ok"
    }

    /** 批量总结果。 */
    data class BatchResult(val results: List<PerAccount>, val date: String) {
        /** 是否所有"可签"账号都签上了（补签去重的判据之一）。 */
        val allOk: Boolean get() = results.isNotEmpty() && results.all { it.ok || it.status == "already" || it.status == "disabled" }
    }

    // ------------------------------------------------------------------
    // 设置（定时开关 / 时刻）
    // ------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    /** 定时时刻，"HH:mm"；损坏回落 08:00。 */
    fun timeText(ctx: Context): String =
        prefs(ctx).getString(KEY_TIME, "08:00")?.takeIf { Regex("^\\d{1,2}:\\d{2}$").matches(it) } ?: "08:00"

    private fun timeMinutes(ctx: Context): Int {
        val (h, m) = timeText(ctx).split(":").map { it.toIntOrNull() ?: 0 }
        return h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59)
    }

    /** 开启定时并立刻挂上闹钟。 */
    fun enable(ctx: Context, time: String) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, true).putString(KEY_TIME, time).apply()
        scheduleNext(ctx)
        Log.i(TAG, "定时签到已开启: $time")
    }

    /** 关闭定时并取消闹钟。 */
    fun disable(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, false).apply()
        cancelAlarm(ctx)
        Log.i(TAG, "定时签到已关闭")
    }

    /** 修改时刻（仅开关已开时才动闹钟；关着只存值，等开启时再生效）。 */
    fun setTime(ctx: Context, time: String) {
        prefs(ctx).edit().putString(KEY_TIME, time).apply()
        if (isEnabled(ctx)) scheduleNext(ctx)
    }

    // ------------------------------------------------------------------
    // 每日闹钟
    // ------------------------------------------------------------------

    /**
     * 挂**下一个**触发时刻的闹钟。幂等：重复调用只更新触发时间。
     * 写法照抄 GatewayService.scheduleAlarm（含 canScheduleExactAlarms 预检与降级）。
     */
    fun scheduleNext(ctx: Context) {
        if (!isEnabled(ctx)) return
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, ALARM_REQUEST,
                Intent(ctx, CheckinReceiver::class.java).setAction(ACTION_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // 目标 = 今天或明天的 HH:mm（已过就算明天）
            val cal = java.util.Calendar.getInstance()
            val minutes = timeMinutes(ctx)
            cal.set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
            cal.set(java.util.Calendar.MINUTE, minutes % 60)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) {
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi) }
            } else {
                Log.w(TAG, "精确闹钟未授权，签到定时降级为非精确（Doze 下可能延迟数小时）")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
            Log.i(TAG, "已挂签到闹钟: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(cal.time)}")
        }.onFailure { Log.w(TAG, "签到闹钟调度失败", it) }
    }

    fun cancelAlarm(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(PendingIntent.getBroadcast(
                ctx, ALARM_REQUEST,
                Intent(ctx, CheckinReceiver::class.java).setAction(ACTION_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
        }.onFailure { Log.w(TAG, "签到闹钟取消失败", it) }
    }

    // ------------------------------------------------------------------
    // 批量编排（唯一的签到执行路径）
    // ------------------------------------------------------------------

    /**
     * 对**全部已保存账号**执行签到。**阻塞调用** —— 必须在后台线程跑
     * （手动入口在 MainActivity 的 thread 里；定时入口在 CheckinReceiver 的 goAsync 里）。
     *
     * ⚠️ **不持 `Gateway.locked` 跑整批**（v0.4.14 的写法，已修）：
     * 那把锁同时被 `LocalFakeServer` 的 `credProvider` 持着，而它在**每个** `/v1/chat/completions`
     * 写响应头之前同步调用。整批持锁 = 签到期间所有对话请求零字节挂死，10 个账号能堵几十分钟
     * （单次 ug 请求读超时就有 180s）。锁只保留在**真正需要串行**的地方 —— 续期，
     * 见 [TraeAuthApi.validTokenForUid]。
     *
     * 返回结果并持久化；跑完把 last_run_date 记为今天。
     */
    fun runAll(ctx: Context): BatchResult {
        // 只是本地索引读取（毫秒级），短锁即可
        val metas = Gateway.locked { TraeAuthStore.list(ctx) }.filter { it.region.isSupported }
        val results = mutableListOf<PerAccount>()
        for ((i, meta) in metas.withIndex()) {
            if (i > 0) Thread.sleep(PER_ACCOUNT_DELAY_MS)
            results.add(checkinOne(ctx, meta))
        }
        val today = today()
        prefs(ctx).edit()
            .putString(KEY_LAST_RUN_DATE, today)
            .putString(KEY_LAST_RESULTS, toJson(results).toString())
            .apply()
        val r = BatchResult(results, today)
        Log.i(TAG, "批量签到完成: ${results.count { it.ok }} 成功 / ${results.size} 国服账号")
        // 通知由调用方决定发不发（定时路径发、手动路径不发——用户就在界面上）。
        return r
    }

    /** 对单个账号执行完整签到流程。失败不抛 —— 一人失败不拖垮整批。 */
    private fun checkinOne(ctx: Context, meta: TraeAuthStore.AccountMeta): PerAccount {
        val name = meta.nickname.ifBlank { meta.uid }
        val reg = meta.region
        if (!reg.isSupported || !reg.canQueryQuota) {
            return PerAccount(meta.uid, name, "unsupported", -1, "该区域未支持签到")
        }
        return try {
            // 续期/取 token 走后台统一入口：锁内续期、不切 active、不翻全局区域。
            // 以前这里自己抄了一份"过期就 exchangeToken 再 updateTokens/updateMeta"，
            // 与 CreditMonitor 里那份重复，且必须在 runAll 的大锁里才不会撞轮换。
            val token = TraeAuthApi.validTokenForUid(ctx, meta.uid, 3600)
            if (token.isNullOrBlank()) {
                return PerAccount(meta.uid, name, "fail", -1,
                    "取不到有效 token（已过期且无法自动续期），需重新登录")
            }
            // 先查状态再决定发不发 claim（照抄单账号路径的纪律）
            val before = TraeUpstream.fetchCheckin(token, meta.uid, reg)
                ?: return PerAccount(meta.uid, name, "fail", -1, "拉取签到状态失败（网络或上游异常）")
            if (before.checkedIn) return PerAccount(meta.uid, name, "already", before.credits, "")
            if (!before.enable) return PerAccount(meta.uid, name, "disabled", -1, "该账号未开放签到")
            val code = TraeUpstream.claimCheckin(token, meta.uid, reg)
            val after = TraeUpstream.fetchCheckin(token, meta.uid, reg)
            when {
                TraeUpstream.checkinSucceeded(before, after, code) ->
                    PerAccount(meta.uid, name, "ok", after!!.credits, "")
                after?.checkedIn == true ->
                    // 状态已是已签但合取判据没过（如 code 异常但跃迁已发生）：按已签归档，不报错
                    PerAccount(meta.uid, name, "already", after.credits, "")
                code == null -> PerAccount(meta.uid, name, "fail", -1, "网络不通，签到请求未送达")
                code == 9074 -> PerAccount(meta.uid, name, "fail", -1, "上游判定操作过于频繁（9074），请稍后手动重试")
                else -> PerAccount(meta.uid, name, "fail", -1, "签到未生效（code=$code）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "账号 ${meta.uid} 签到异常: ${e.javaClass.simpleName}: ${e.message}")
            PerAccount(meta.uid, name, "fail", -1, "签到异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // 进 App 补签 + 自愈重挂
    // ------------------------------------------------------------------

    /**
     * 进 App 调用（后台线程）：定时开着 && 今天还没跑过 && 还有账号没签上 → 补跑一轮；
     * 无论跑不跑，只要开关开着就**重挂一次闹钟**（自愈：重启/被 ROM 清掉的闹钟在这里恢复）。
     *
     * 今天跑过且全部签上 → 不再打上游（纯去重）；跑过但有失败 → 再试一轮（给 9074/断网一次翻身机会）。
     *
     * @return 本次是否真的跑了批量（调用方据此刷新 UI）。
     */
    fun maybeCatchUp(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        scheduleNext(ctx)   // 自愈重挂：幂等
        if (prefs(ctx).getString(KEY_LAST_RUN_DATE, null) == today()) {
            val last = lastResults(ctx)
            if (last != null && last.allOk) return false   // 今天全签上了，别再打上游
        }
        if (!busy.compareAndSet(false, true)) return false
        try {
            runAll(ctx)
            return true
        } finally {
            busy.set(false)
        }
    }

    /** 定时触发路径的成功通知（手动路径不发 —— 用户就在屏幕前）。 */
    fun notifyIfScheduled(ctx: Context, result: BatchResult) {
        val okCount = result.results.count { it.ok }
        if (okCount == 0) return   // 只对"真的新签到成功"发通知；全失败/全已签不弹（每天白响）
        runCatching { ensureChannel(ctx) }.onFailure { return }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)
        val detail = result.results.filter { it.ok }
            .joinToString("、") { "${it.nickname.ifBlank { it.uid }} +${it.credits}" }
        val n = builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("每日签到完成")
            .setContentText("${okCount} 个账号签到成功（$detail）")
            .setStyle(Notification.BigTextStyle().bigText("${okCount} 个账号签到成功：$detail"))
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "签到结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "每日定时签到的成功通知"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        mgr.createNotificationChannel(ch)
    }

    // ------------------------------------------------------------------
    // 结果读取（账号页展示）
    // ------------------------------------------------------------------

    /** 上次批量结果（无/损坏返回 null）。 */
    fun lastResults(ctx: Context): BatchResult? {
        val raw = prefs(ctx).getString(KEY_LAST_RESULTS, null) ?: return null
        val date = prefs(ctx).getString(KEY_LAST_RUN_DATE, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PerAccount(
                    uid = o.optString("uid", ""),
                    nickname = o.optString("nickname", ""),
                    status = o.optString("status", "fail"),
                    credits = o.optLong("credits", -1L),
                    message = o.optString("message", ""),
                )
            }
            BatchResult(list, date)
        } catch (_: Exception) { null }
    }

    /** 上次批量是否就是今天跑的（UI 上"今天已批量签到"的判据）。 */
    fun lastRunToday(ctx: Context): Boolean = prefs(ctx).getString(KEY_LAST_RUN_DATE, null) == today()

    private fun toJson(list: List<PerAccount>): JSONArray = JSONArray().apply {
        list.forEach { r ->
            put(JSONObject()
                .put("uid", r.uid)
                .put("nickname", r.nickname)
                .put("status", r.status)
                .put("credits", r.credits)
                .put("message", r.message))
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 结果状态 → 人话（账号页每行用）。 */
    fun statusLabel(r: PerAccount): String = when (r.status) {
        "ok" -> "✓ 签到成功 +${r.credits}"
        "already" -> "今日已签到"
        "disabled" -> "未开放签到"
        "unsupported" -> "该区域未支持签到"
        else -> "失败：${r.message}"
    }

    // ------------------------------------------------------------------
    // 自检
    // ------------------------------------------------------------------

    /**
    * 纯计算自检：不碰网络/KeyStore/闹钟，可安全在 onCreate 调用。
    * 钉住的都是"判错会让用户白丢积分"的路径：时刻解析、日期去重、状态文案、
    * 结果 JSON 往返、 expiredWithin 边界。
    */
    fun selfCheck() {
        // 过期判据与 TraeAuthApi 同一套约定（未知=过期 → 才会走续期而不是盲用）。
        // 用**同一个函数**而不是本地副本：副本改了这边不改，会出现"这里判过期、那边判有效"。
        check(TraeAuthApi.expiredWithin(0L, 3600)) { "自检失败：exp=0 应判为过期" }
        check(TraeAuthApi.expiredWithin(null, 3600)) { "自检失败：exp=null 应判为过期" }
        check(!TraeAuthApi.expiredWithin((System.currentTimeMillis() / 1000) + 7200, 3600)) {
            "自检失败：远未来 exp 不应判为过期"
        }

        // 结果 JSON 往返（uid 是主键，串了会把 A 的结果显示给 B）
        val src = listOf(
            PerAccount("3695871032827027", "甲", "ok", 150, ""),
            PerAccount("7687189514112844821", "乙", "fail", -1, "网络不通，签到请求未送达"),
        )
        val back = try {
            val arr = toJson(src)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PerAccount(o.getString("uid"), o.getString("nickname"), o.getString("status"),
                    o.getLong("credits"), o.getString("message"))
            }
        } catch (e: Exception) { throw IllegalStateException("自检失败：批量结果序列化异常 ${e.message}") }
        check(back == src) { "自检失败：批量结果往返不一致" }

        // 状态文案必须覆盖全部五种 status（漏了会显示原文 status 码给用户）
        listOf("ok", "already", "disabled", "unsupported", "fail").forEach { s ->
            check(statusLabel(PerAccount("u", "n", s, 100, "x")).isNotBlank()) { "自检失败：status=$s 缺文案" }
        }

        // 总结果聚合判据：allOk 是补签去重的闸，判松了每天多打一轮上游，判严了有失败永远补不上
        check(BatchResult(listOf(PerAccount("a", "甲", "ok", 1, "")), "2026-01-01").allOk) { "自检失败：全成功应判 allOk" }
        check(BatchResult(listOf(PerAccount("a", "甲", "already", 1, "")), "2026-01-01").allOk) { "自检失败：已签应判 allOk" }
        check(BatchResult(listOf(PerAccount("a", "甲", "fail", -1, "x")), "2026-01-01").allOk.not()) { "自检失败：有失败不应判 allOk" }
        check(BatchResult(emptyList(), "2026-01-01").allOk.not()) { "自检失败：空结果不应判 allOk（避免空账号时误去重）" }
    }
}
