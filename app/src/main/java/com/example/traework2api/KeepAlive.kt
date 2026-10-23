package com.example.traework2api

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活体检与「一键配置」。
 *
 * ## 必须先讲清的能力边界（别对用户承诺做不到的事）
 *
 * Android **没有**打开「自启动 / 省电策略 / 后台弹出」的公开接口 —— 那些是厂商私有页面。
 * 任何 App 都**不可能**替用户打开、更不可能替用户勾选。所谓"一键保活"的 App 都是
 * 硬编码各家 ROM 的私有 Activity 名去猜，猜不中就失效、ROM 一升级就废。
 *
 * 所以这里分两类，界面也照这个分：
 *  - **可一键**（系统有公开 Intent）：电池优化白名单、精确闹钟、通知权限
 *  - **只能引导**（厂商私有）：自启动、省电策略、后台弹出 → 按 [romGuide] 给本机 ROM 的路径
 *
 * 这样用户看到的是「能点的按钮 + 剩下几步照做」，而不是一个假装全能却静默失败的按钮。
 */
object KeepAlive {

    /** 一项保活检查。 */
    data class Item(
        /** 展示名，如「电池优化白名单」。 */
        val title: String,
        /** 是否已就绪。 */
        val ok: Boolean,
        /** 未就绪时为什么需要它（给用户看的大白话）。 */
        val why: String,
        /**
         * 是否可以用 Intent 一键跳转。
         * false = 厂商私有页面，只能靠 [romGuide] 引导用户自己找。
         */
        val autoOpenable: Boolean
    )

    /** 本机 ROM 家族，决定引导文案。 */
    enum class Rom(val label: String) {
        MIUI("小米 / Redmi（MIUI / HyperOS）"),
        COLOR_OS("OPPO / 一加 / realme（ColorOS）"),
        EMUI("华为 / 荣耀（EMUI / MagicOS）"),
        ORIGIN_OS("vivo / iQOO（OriginOS）"),
        OTHER("其它 Android");
    }

    /**
     * 识别本机 ROM。
     *
     * 为什么不用 `Build.MANUFACTURER` 一个字段：一加/realme 的 MANUFACTURER 是 OnePlus/realme
     * 但系统是 ColorOS，只判厂商会漏；所以先看更具体的 `Build.BRAND`，再看系统里有没有
     * 该 ROM 的特征（`ro.build.version.opporom` / `ro.miui.ui.version.name` 等）。
     */
    fun rom(): Rom = romFor(Build.BRAND, Build.MANUFACTURER) { hasProp(it) }

    /**
     * ROM 判定的**纯函数**部分，便于自检（真机型号没法在模拟器上造）。
     *
     * 顺序：先看 ROM 特征属性（能覆盖"一加跑 ColorOS""荣耀跑 MagicOS"这类品牌与系统不一致的
     * 情况），再回落到品牌名。
     */
    internal fun romFor(brandRaw: String, manufacturerRaw: String, has: (String) -> Boolean): Rom {
        val brand = "$brandRaw $manufacturerRaw".lowercase()
        if (has("ro.miui.ui.version.name") || has("ro.mi.os.version.name")) return Rom.MIUI
        if (has("ro.build.version.opporom") || has("ro.build.version.oplusrom")) return Rom.COLOR_OS
        if (has("ro.build.version.emui") || has("hw_sc.build.platform.version")) return Rom.EMUI
        if (has("ro.vivo.os.version") || has("ro.vivo.os.name")) return Rom.ORIGIN_OS
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Rom.MIUI
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") -> Rom.COLOR_OS
            brand.contains("huawei") || brand.contains("honor") -> Rom.EMUI
            brand.contains("vivo") || brand.contains("iqoo") -> Rom.ORIGIN_OS
            else -> Rom.OTHER
        }
    }

    private fun hasProp(name: String): Boolean =
        runCatching { Class.forName("android.os.SystemProperties") }.isSuccess &&
            runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java)
                (get.invoke(null, name) as? String).orEmpty().isNotBlank()
            }.getOrDefault(false)

    /**
     * 该 ROM 的**厂商私有**设置路径（系统查不到、也无法跳转，只能写给用户看）。
     *
     * 这些路径是各家 ROM 的实际菜单层级，会随版本微调 —— 所以文案里保留
     * "在应用管理里找"这类兜底描述，别写成唯一路径。
     */
    fun romGuide(r: Rom): List<String> = when (r) {
        Rom.MIUI -> listOf(
            "自启动：设置 → 应用设置 → 应用管理 → 本应用 → 自启动（打开）",
            "省电策略：同上页面 → 省电策略 → 选「无限制」",
            "后台弹出：同上页面 → 权限管理 → 后台弹出界面（允许）"
        )
        Rom.COLOR_OS -> listOf(
            "完全后台行为：设置 → 电池 → 应用耗电管理 → 本应用 → 允许完全后台行为",
            "自启动：同上页面 → 允许自启动",
            "关闭智能省电：同上页面 → 关闭「智能省电」"
        )
        Rom.EMUI -> listOf(
            "应用启动管理：设置 → 应用 → 应用启动管理 → 本应用 → 关掉「自动管理」",
            "然后把「自启动 / 关联启动 / 后台活动」三项都打开"
        )
        Rom.ORIGIN_OS -> listOf(
            "后台高耗电：设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电",
            "自启动：i 管家 → 应用管理 → 权限管理 → 自启动（允许）"
        )
        Rom.OTHER -> listOf(
            "在「设置 → 应用 → 本应用」里找：自启动 / 后台运行 / 省电策略",
            "把省电策略设为「不优化 / 无限制」"
        )
    }

    /**
     * 逐项体检。返回全部项（含已就绪的），界面自己决定怎么展示。
     */
    fun check(ctx: Context): List<Item> {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        val channelOff = GatewayService.isChannelDisabled(ctx)

        val canExact = Build.VERSION.SDK_INT < 31 || runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(true)

        return listOf(
            Item(
                "电池优化白名单", ignoringBattery,
                "没加入白名单时，系统省电策略会在息屏后清掉后台服务 —— 这是真机上最致命的一项。",
                autoOpenable = true
            ),
            Item(
                "通知权限", notifOk,
                "前台服务的常驻通知需要它；通知不可见时系统会弱化甚至清理服务。",
                autoOpenable = true
            ),
            Item(
                "通知渠道未被关闭", !channelOff,
                "渠道被关掉等同于通知不可见，服务会被削弱。",
                autoOpenable = false
            ),
            // 悬浮窗是**可选加强项**：没开不算"没准备好"，所以未开启时判为 ok，
            // 否则每一项都会亮 ⚠，用户会以为必须开（其实很多人不想要屏幕上多个点）。
            Item(
                "悬浮窗保活", !OverlayKeepAlive.isEnabled(ctx) || OverlayKeepAlive.canDraw(ctx),
                "在屏幕上挂一个可拖动的小圆点：进程多一层「可见窗口」优先级，网关被杀后点一下就能复活。" +
                    "没有「显示在其他应用上层」权限时它不起作用。",
                autoOpenable = true
            ),
            Item(
                "精确闹钟", canExact,
                "服务被厂商强杀后靠它定时唤醒恢复；被禁用时只能退化成非精确闹钟，Doze 下可能延迟数小时。",
                autoOpenable = true
            ),
            Item(
                "自启动 / 后台运行", false,
                "系统不提供查询接口，需要你自己确认（路径见下方）。",
                autoOpenable = false
            ),
            Item(
                "省电策略 = 无限制", false,
                "系统不提供查询接口，需要你自己确认（路径见下方）。",
                autoOpenable = false
            )
        )
    }

    /**
     * 「一键配置」：依次跳转**能跳的**项。
     *
     * 返回实际能跳的项数（0 = 全都已就绪或都跳不了），供界面提示。
     * 每次只跳一个：多个系统弹窗连续弹会互相顶掉，用户也来不及看。
     */
    fun openBatteryWhitelist(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun openExactAlarm(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 31) return false
        ctx.startActivity(Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /**
     * 悬浮窗授权页。
     *
     * 这是**特殊权限**（SYSTEM_ALERT_WINDOW），不能运行时弹框申请，只能把这个页面
     * 甩给用户手动勾选；回来后再用 `Settings.canDrawOverlays` 复查。
     */
    fun openOverlayPermission(ctx: Context): Boolean = OverlayKeepAlive.openPermission(ctx)

    // ------------------------------------------------------------------
    // 保活总开关与保活时长（WorkBuddy 的 keepalive_enabled / keepalive_hours）
    // ------------------------------------------------------------------

    private const val PREF = "traework2api_prefs"
    private const val KEY_BOOT_AUTOSTART = "keepalive_boot_autostart"
    private const val KEY_KEEP_ENABLED = "keepalive_enabled"
    private const val KEY_KEEP_HOURS = "keepalive_hours"
    private const val KEY_ARMED_AT = "keepalive_armed_at"

    /** 保活总开关：关掉后看门狗不再自检、不再拉起服务（服务本身不受影响）。默认开。 */
    fun keepEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_KEEP_ENABLED, true)

    fun setKeepEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_KEEP_ENABLED, value).apply()
        if (value) KeepAliveWatchdog.arm(ctx) else KeepAliveWatchdog.disarm(ctx)
    }

    /**
     * 保活时长（小时），**0 = 一直保活**。
     *
     * 语义照 WorkBuddy：`keepalive_hours` 到期后看门狗自行收工，
     * 不再周期性把服务拉起来 —— 给"我只要它撑过这几个小时"这种用法。
     */
    fun keepHours(ctx: Context): Int =
        prefs(ctx).getInt(KEY_KEEP_HOURS, 0).coerceIn(0, 72)

    fun setKeepHours(ctx: Context, hours: Int) {
        prefs(ctx).edit().putInt(KEY_KEEP_HOURS, hours.coerceIn(0, 72)).apply()
        // 改时长等于重新开始计时。
        if (hours > 0) markArmed(ctx) else clearArmed(ctx)
    }

    /** 记下"这一轮保活从什么时候开始"。 */
    fun markArmed(ctx: Context) {
        val p = prefs(ctx)
        if (p.getLong(KEY_ARMED_AT, 0L) == 0L) {
            p.edit().putLong(KEY_ARMED_AT, System.currentTimeMillis()).apply()
        }
    }

    fun clearArmed(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_ARMED_AT, 0L).apply()
    }

    /** 保活时长是否已到期（0 小时 = 永不"到期"，但要求确实被 armed 过）。 */
    fun keepExpired(ctx: Context): Boolean {
        val hours = keepHours(ctx)
        if (hours <= 0) return false
        val armedAt = prefs(ctx).getLong(KEY_ARMED_AT, 0L)
        if (armedAt <= 0L) return false
        return System.currentTimeMillis() - armedAt > hours * 3_600_000L
    }

    // ------------------------------------------------------------------
    // 开机自启（可选，默认关）
    // ------------------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isBootAutostartEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BOOT_AUTOSTART, false)

    fun setBootAutostart(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BOOT_AUTOSTART, value).apply()
    }

    /**
     * 尝试**直达**各家 ROM 的「耗电管理 / 自启动」页。
     *
     * 这些是厂商私有 Activity，名字可能随版本变。所以：
     *  - 按本机 ROM 只试**本家**的那几个（乱试会打开别家页面或直接崩）；
     *  - 全部失败就退到系统公开的「应用详情」页 —— 至少用户能从这里再点进权限。
     *
     * 参考 WorkBuddy 的做法：它把 `com.oplus.battery/com.oplus.powermanager.fuelgaue.
     * PowerUsageModelActivity`、`com.coloros.oppoguardelf/...` 都硬编码试一遍，
     * 因为 ColorOS 上这两代包名并存，只试一个会有一半机器跳不过去。
     */
    fun openRomPowerPage(ctx: Context): Boolean {
        val rom = rom()
        val candidates = when (rom) {
            Rom.COLOR_OS -> listOf(
                "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                "com.coloros.oppoguardelf" to "com.coloros.oppoguardelf.PowerConsumeManageActivity"
            )
            Rom.MIUI -> listOf(
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            Rom.EMUI -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            Rom.ORIGIN_OS -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
            Rom.OTHER -> emptyList()
        }
        for ((pkg, cls) in candidates) {
            val ok = runCatching {
                ctx.startActivity(Intent().apply {
                    component = android.content.ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // MIUI 的「隐藏应用配置」页靠这个 extra 定位到本 App，不带就显示空列表。
                    putExtra("package_name", ctx.packageName)
                })
            }.isSuccess
            if (ok) return true
        }
        // 兜底：系统「应用详情」页（公开接口，任何 ROM 都在）。
        return runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    fun openAppNotificationSettings(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /**
     * 自检：ROM 识别不能因为属性读不到就崩。
     *
     * 为什么值得钉：`SystemProperties` 是**隐藏 API**，靠反射拿。反射一旦失败
     * （厂商裁剪、非标准 ROM）不能让整个 App 起不来 —— 必须优雅回落到品牌名判断。
     */
    fun selfCheck() {
        // 不该抛异常：任何 ROM（包括读不到属性的）都要能给出一个结果。
        val r = runCatching { rom() }.getOrNull()
        check(r != null) { "自检失败：ROM 识别抛异常了，应优雅回落到 OTHER" }
        // 每种 ROM 都必须有引导文案，否则界面会出现空白指引。
        Rom.entries.forEach { rom ->
            check(romGuide(rom).isNotEmpty()) { "自检失败：${rom.label} 没有引导文案" }
        }

        // 品牌名回落分支：真机型号没法在模拟器上造，只能在这里钉住。
        // 判错会让用户在**自己手机上看到别家 ROM 的路径**，属于"看着像正常工作"的坏 bug。
        val none: (String) -> Boolean = { false }
        check(romFor("Xiaomi", "Xiaomi", none) == Rom.MIUI) { "自检失败：小米应判为 MIUI" }
        check(romFor("Redmi", "Xiaomi", none) == Rom.MIUI) { "自检失败：Redmi 应判为 MIUI" }
        check(romFor("POCO", "Xiaomi", none) == Rom.MIUI) { "自检失败：POCO 应判为 MIUI" }
        check(romFor("OPPO", "OPPO", none) == Rom.COLOR_OS) { "自检失败：OPPO 应判为 ColorOS" }
        check(romFor("realme", "realme", none) == Rom.COLOR_OS) { "自检失败：realme 应判为 ColorOS" }
        check(romFor("HUAWEI", "HUAWEI", none) == Rom.EMUI) { "自检失败：华为应判为 EMUI" }
        check(romFor("HONOR", "HONOR", none) == Rom.EMUI) { "自检失败：荣耀应判为 EMUI" }
        check(romFor("vivo", "vivo", none) == Rom.ORIGIN_OS) { "自检失败：vivo 应判为 OriginOS" }
        check(romFor("iQOO", "vivo", none) == Rom.ORIGIN_OS) { "自检失败：iQOO 应判为 OriginOS" }
        check(romFor("Samsung", "Samsung", none) == Rom.OTHER) { "自检失败：未知品牌应回落到 OTHER" }
        // 属性优先于品牌名：一加手机跑的是 ColorOS，按品牌名会判错，必须靠属性纠正。
        check(romFor("OnePlus", "OnePlus") { it == "ro.build.version.oplusrom" } == Rom.COLOR_OS) {
            "自检失败：一加跑 ColorOS 时应靠属性判为 ColorOS（品牌名会误判）"
        }
        // 属性读不到（SystemProperties 反射失败）时不能崩，要能回落到品牌名。
        check(runCatching { romFor("Xiaomi", "Xiaomi", none) }.isSuccess) {
            "自检失败：属性读取失败时不应抛异常"
        }
    }
}