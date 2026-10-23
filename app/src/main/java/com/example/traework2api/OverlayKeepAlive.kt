package com.example.traework2api

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs

/**
 * 悬浮窗保活。
 *
 * ## 它到底靠什么起作用（别吹成"防杀神器"）
 *
 * Android 的进程重要性里，**"有可见窗口"会把进程提到 PERCEPTIBLE 档**，
 * 而被判定为 Cached 的进程才是内存回收、App Freezer（冻结）的首选目标。
 * 前台服务把进程提到 FOREGROUND_SERVICE 档，但 OEM 的省电策略照样能杀；
 * 再挂一个 SYSTEM_ALERT_WINDOW 的**真实窗口**，进程就多一层"看得见"的护身符，
 * 同时窗口属于本 App 时，点它就能把被杀的网关**当场复活**（不用去桌面找图标）。
 *
 * 所以定位很清楚：
 *  - 有用：多一层进程优先级 + 一个随时能复活的入口
 *  - 没用：不可能阻止所有强杀；MIUI 还需单独开「后台弹出界面」权限
 *
 * ## 踩过的坑（这些是"悬浮窗保活写着写着就崩"的全部原因）
 *
 *  1. **必须用 applicationContext 取 WindowManager**。用 Activity 的会在页面销毁后
 *     抛 `WindowLeaked` 并让窗口永久残留在屏幕上（点不掉、只能重启手机）。
 *  2. **TYPE_PHONE(2002) 在 Android 8+ 直接抛 BadTokenException**，
 *     API 26 起必须 `TYPE_APPLICATION_OVERLAY(2038)`。
 *  3. **addView 只能在主线程**，且必须在 `canDrawOverlays` 为真之后 —— 否则
 *     权限没授时抛 `BadTokenException: Unable to add window -- permission denied`，
 *     很多人不 catch 就得到一个"打开就闪退"的 App。
 *  4. **FLAG_NOT_FOCUSABLE 一定要有**，否则会抢输入法焦点，用户的输入框打不开。
 *     可拖动 ≠ 可聚焦：拖动只靠 `FLAG_NOT_TOUCH_MODAL` + 自己处理 touch。
 *  5. 服务被停 / 进程被杀前必须 `removeView`，否则窗口成孤儿（进程没了窗口还在，
 *     直到下次开机）。所以 [hide] 要在 [GatewayService.onDestroy] 与 `stopNow` 里都调。
 *  6. 尺寸 1dp 的窗口在某些 ROM 上被当作"无效窗口"直接回收，所以默认给 10dp 的
 *     可点小圆点；想要隐形可调到 [MIN_DP]。
 */
object OverlayKeepAlive {

    private const val TAG = "OverlayKeepAlive"
    private const val PREF = "traework2api_prefs"

    /** 开关（与「保活」页面同步）。 */
    const val KEY_ENABLED = "keepalive_overlay_enabled"
    /** 圆点直径（dp）。 */
    const val KEY_SIZE_DP = "keepalive_overlay_dp"
    const val KEY_X = "keepalive_overlay_x"
    const val KEY_Y = "keepalive_overlay_y"

    const val MIN_DP = 1
    const val MAX_DP = 48
    const val DEFAULT_DP = 10

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var dragMoved = false

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) sync(ctx) else hide(ctx)
    }

    fun sizeDp(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SIZE_DP, DEFAULT_DP).coerceIn(MIN_DP, MAX_DP)

    fun setSizeDp(ctx: Context, dp: Int) {
        prefs(ctx).edit().putInt(KEY_SIZE_DP, dp.coerceIn(MIN_DP, MAX_DP)).apply()
        if (isShowing()) {
            hide(ctx)
            sync(ctx)
        }
    }

    /** 是否已获得悬浮窗权限（API 23+ 是运行时特殊权限，只能跳系统页让用户手动给）。 */
    fun canDraw(ctx: Context): Boolean = runCatching {
        Settings.canDrawOverlays(ctx)
    }.getOrDefault(false)

    fun isShowing(): Boolean = view != null

    /** 打开系统的「显示在其他应用上层」授权页。 */
    fun openPermission(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /**
     * 让窗口状态与「开关 + 权限」一致：
     * 开了且有权 → 挂上；否则摘掉。**幂等**，可以在服务每次 onStartCommand 里无脑调。
     */
    fun sync(ctx: Context) {
        if (!isEnabled(ctx) || !canDraw(ctx)) {
            hide(ctx)
            return
        }
        if (isShowing()) return
        show(ctx)
    }

    /** 挂上悬浮窗。返回是否真的挂上了（失败原因会写 Log + 给用户 toast）。 */
    fun show(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (isShowing()) return true
        if (!canDraw(app)) {
            Log.w(TAG, "没有悬浮窗权限，挂不了")
            toast(app, "还没有「显示在其他应用上层」权限")
            return false
        }
        val host = Handler(Looper.getMainLooper())
        if (Looper.myLooper() != Looper.getMainLooper()) {
            host.post { show(app) }
            return true
        }
        return runCatching {
            val w = (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            val dp = sizeDp(app)
            val px = (app.resources.displayMetrics.density * dp).toInt().coerceAtLeast(1)
            val p = WindowManager.LayoutParams(
                px, px,
                if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs(app).getInt(KEY_X, 0)
                y = prefs(app).getInt(KEY_Y, 240)
            }

            val v = FrameLayout(app)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC2F6BFF.toInt())
                setStroke((1.5f * app.resources.displayMetrics.density).toInt(), 0x66FFFFFF)
            }
            v.alpha = 0.85f
            v.setOnTouchListener { _, ev -> onTouch(app, ev) }
            v.setOnClickListener { revive(app) }

            w.addView(v, p)
            wm = w
            view = v
            params = p
            Log.i(TAG, "悬浮窗已挂上 ${px}px @(${p.x},${p.y})")
            true
        }.onFailure {
            // 没权限 / ROM 拦截 / 窗口 token 失效，都会走到这里。**不能让它冒到主线程**。
            Log.w(TAG, "挂悬浮窗失败: ${it.javaClass.simpleName}: ${it.message}")
            toast(app, "悬浮窗挂不上，请检查系统权限（部分机型需「后台弹出界面」）")
        }.getOrDefault(false)
    }

    /** 摘掉悬浮窗。幂等；synchronized 防两个线程同时 remove（会抛 IllegalArgument）。 */
    @Synchronized
    fun hide(ctx: Context) {
        val v = view ?: return
        val w = wm
        view = null
        params = null
        wm = null
        runCatching { w?.removeViewImmediate(v) }
            .onFailure { Log.w(TAG, "removeView 失败(忽略): ${it.message}") }
    }

    // ------------------------------------------------------------------
    // 交互：单击复活 / 拖动移位
    // ------------------------------------------------------------------

    /**
     * 触摸分发。
     *
     * ⚠️ **必须每一个 action 都返回 true**。只对 ACTION_UP 返回 true 是悬浮窗"点一下没反应、
     * 也拖不动"的头号原因：DOWN 返回 false 时后续 MOVE/UP 根本不会再送到这个 View，
     * 手势被上层（或谁的没有）吞掉，表现就是"这个点是个死点"。
     */
    private fun onTouch(app: Context, ev: MotionEvent): Boolean {
        val p = params ?: return true
        val w = wm ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMoved = false
                startX = ev.rawX.toInt()
                startY = ev.rawY.toInt()
                downX = p.x
                downY = p.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX.toInt() - startX
                val dy = ev.rawY.toInt() - startY
                if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) dragMoved = true
                if (dragMoved) {
                    p.x = downX + dx
                    p.y = downY + dy
                    runCatching { w.updateViewLayout(view, p) }
                }
            }
            MotionEvent.ACTION_UP -> if (dragMoved) {
                prefs(app).edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
            } else {
                revive(app)
            }
            MotionEvent.ACTION_CANCEL -> if (dragMoved) {
                // 拖动中被取消（比如系统手势接管）：位置已经变了，也要持久化，否则下次回到老位置。
                prefs(app).edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
            }
        }
        return true
    }

    private var startX = 0
    private var startY = 0
    private var downX = 0
    private var downY = 0
    private const val TOUCH_SLOP = 12

    /**
     * 点一下悬浮窗：网关没在跑就拉起来。
     *
     * 这是悬浮窗保活里**真正救急**的那一下 —— 服务被 OEM 强杀后，
     * 用户不用回桌面翻图标，点一下这个点就复活。
     */
    private var lastReviveAt = 0L
    private const val REVIVE_DEBOUNCE_MS = 1_500L

    private fun revive(app: Context) {
        if (Gateway.isRunning) {
            toast(app, "网关运行中")
            return
        }
        // 去抖：连点只会拉起一次，避免并发 startForegroundService 撞端口。
        val now = System.currentTimeMillis()
        if (now - lastReviveAt < REVIVE_DEBOUNCE_MS) return
        lastReviveAt = now
        Log.i(TAG, "点悬浮窗：网关未运行，拉起服务")
        runCatching { GatewayService.start(app) }
            .onFailure { toast(app, "拉起失败：${it.message}") }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun toast(ctx: Context, msg: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 自检：纯逻辑部分（真机窗口挂不了，只能钉住持久化与边界）。 */
    fun selfCheck() {
        check(MIN_DP >= 1) { "自检失败：最小尺寸不能小于 1dp" }
        check(DEFAULT_DP in MIN_DP..MAX_DP) { "自检失败：默认尺寸越界" }
        check(MAX_DP <= 96) { "自检失败：最大尺寸过大，会挡住别的 App" }
        // 颜色不能是全透明，否则用户找不到入口，还会被某些 ROM 当无效窗口回收。
        check(Color.alpha(0xCC2F6BFF.toInt()) > 0) { "自检失败：悬浮窗颜色不应全透明" }
    }
}