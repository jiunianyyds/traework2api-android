package com.example.traework2api

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.traework2api.ui.Motion

/**
 * 边缘悬浮球 —— 网关的贴边状态指示器。
 *
 * 形态：
 *  - 折叠态：一颗 18dp 圆点（2dp 白描边），触摸区与圆点同大。
 *    颜色即状态：绿 = 运行空闲   蓝 = 正在转发（呼吸）   红 = 服务未运行
 *  - 展开态：两行卡片（状态 + 打开应用），150dp × 62dp。
 *  - 手势：单击切换形态；拖动只移动，**不改变形态**。
 *
 * 窗口尺寸变化统一走 [updateLayout]：每次传一个**新建的** LayoutParams 实例。
 * 复用同一个实例时 WindowManager 认为"没变化"，窗口尺寸变了但触摸区不重算
 * （卡片看得见、点不动）；而 remove + add 会打断输入通道
 * （`InputManager-JNI: Input channel ... disposed`），之后手势全丢。
 */
@SuppressLint("ClickableViewAccessibility")
object FloatingTag {

    private const val PREFS = "traework2api_service"
    private const val KEY_SIDE = "float_side"          // "L" / "R"
    private const val KEY_Y_RATIO = "float_y_ratio"    // 0f..1f，圆点在可移动范围内的比例

    /** 折叠态触摸区边长 / 圆点直径（dp）。二者相同 —— 触摸区不向外扩一圈。 */
    private const val TOUCH_DP = 18

    /** 展开卡宽高（dp）。 */
    private const val CARD_W_DP = 150
    private const val CARD_H_DP = 62

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var dotView: View? = null
    private var touchView: View? = null
    private var cardView: View? = null
    private var titleText: TextView? = null

    private var side: String = "R"
    private var yRatio: Float = 0.35f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLpX = 0
    private var downLpY = 0
    private var dragged = false
    private var downSeen = false
    private var expanded = false

    private var densityPx = 3f

    private var breatheRunnable: Runnable? = null
    /** 「转发中」呼吸的颜色过渡（620ms 一格，ofArgb 平滑）；hide/stopBreathe 要 cancel 它。 */
    private var breatheAnimator: ValueAnimator? = null
    private var breathePhase = false
    private var pollRunnable: Runnable? = null

    fun isShowing(): Boolean = rootView != null

    fun show(ctx: Context) {
        if (rootView != null) { applyState(); return }
        // 球要活到进程结束，所以视图一律用 Application 上下文建：
        // 用 Activity 当 ctx 的话，Activity 销毁后这棵常驻 overlay 会一直拽着它（内存泄漏）。
        val app = ctx.applicationContext
        if (!canDrawOverlays(app)) return
        // 服务上下文优先（GatewayService 启动成功时走这条），否则退回 Application ——
        // 服务没在跑、App 刚被拉起来时也必须能挂上球（红点 = 已停止，是既有设计）。
        val manager = (ctx as? android.app.Service)
            ?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        side = prefs.getString(KEY_SIDE, "R") ?: "R"
        yRatio = prefs.getFloat(KEY_Y_RATIO, 0.35f).coerceIn(0f, 0.95f)
        densityPx = app.resources.displayMetrics.density

        val tag = buildTag(app)
        wm = manager
        rootView = tag
        runCatching { manager.addView(tag, layoutParams(app)) }.onFailure {
            android.util.Log.w("FloatingTag", "addView 失败: ${it.javaClass.simpleName}: ${it.message}")
            rootView = null
        }
        if (rootView == null) return
        alignTo(side, app)
        applyState()
        startPoll()
    }

    fun hide() {
        // ⚠️ 先把还在排队/在跑的东西从这个**原 rootView** 上摘干净，再 removeView。
        // 只把字段置 null 是不够的：Runnable 已经投递到 View 的消息队列里，
        // removeView 之后它照样会跑一次（对 null 字段做无意义的状态刷新）。
        val v = rootView
        if (v != null) {
            breatheRunnable?.let { v.removeCallbacks(it) }
            pollRunnable?.let { v.removeCallbacks(it) }
            cardView()?.animate()?.cancel()
        }
        breatheRunnable = null
        pollRunnable = null
        stopBreathe()
        stopPoll()
        runCatching { v?.let { wm?.removeView(it) } }
        rootView = null
        wm = null
        dotView = null
        touchView = null
        cardView = null
        titleText = null
        expanded = false
    }

    @Suppress("UNUSED_PARAMETER")
    fun setRunning(running: Boolean) {
        if (rootView != null) applyState()
    }

    fun applyState() {
        refreshTheme()
        val running = runCatching { Gateway.isRunning }.getOrDefault(false)
        val active = try { LocalFakeServer.activeConns } catch (_: Exception) { 0 }
        val busy = running && active > 0

        val c = AppStyle.color(
            when {
                !running -> "error"
                busy -> "accent"
                else -> "success"
            }
        )

        dotView?.background = dotDrawable(c, AppStyle.isNight)
        if (busy) startBreathe() else stopBreathe()

        // 状态色体现在标题文字上（圆点已从卡片里移除，但标题仍要能读出状态）
        titleText?.setTextColor(c)
        titleText?.text = when {
            !running -> "网关已停止"
            busy -> "转发中 · $active"
            else -> "网关运行中"
        }
    }

    fun refresh() {
        if (rootView != null) applyState()
    }

    private fun refreshTheme() {
        val ctx = rootView?.context ?: return
        cardView()?.background = cardBackground(ctx, (16 * densityPx).toInt())
        titleText?.setTextColor(AppStyle.color("on_surface"))
    }

    // ------------------------------------------------------------------
    // 视图构建
    // ------------------------------------------------------------------

    private fun buildTag(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()

        val root = FrameLayout(ctx)

        // ---- 展开卡 ----
        // DragLayout：位移超过 6dp 时由卡片自己接管触摸，否则卡片里的文字先吃掉 DOWN，
        // 父级收不到 MOVE，表现就是"按住卡片拖不动"。
        val card = DragLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = cardBackground(ctx, dp(16))
            visibility = View.GONE
        }

        // 标题行：不再放圆点，纯文字。状态色由标题文字本身体现（[applyState] 里给 titleText 上色）
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleText = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(AppStyle.color("on_surface"))
            maxLines = 1
        }
        titleRow.addView(titleText, LinearLayout.LayoutParams(-2, -2))
        card.addView(titleRow)

        // 「打开应用」：与标题同左对齐（不再居中），不带箭头
        val openRow = TextView(ctx).apply {
            text = "打开应用"
            textSize = 12f
            setTextColor(AppStyle.color("primary"))
            setPadding(0, dp(7), 0, 0)
            setOnClickListener { collapse(); openApp(ctx) }
        }
        card.addView(openRow, LinearLayout.LayoutParams(-2, -2))

        card.setOnTouchListener { _, ev ->
            handleTouch(ctx, ev, fromCard = true)
            true
        }
        root.addView(card, FrameLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP)).apply {
            gravity = Gravity.TOP
        })

        // ---- 折叠态：一颗圆点，触摸区就是圆点本身 ----
        val touch = FrameLayout(ctx)
        val dotCore = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)).apply {
                gravity = Gravity.CENTER
            }
            contentDescription = "网关状态"
        }
        dotView = dotCore
        touch.addView(dotCore)
        touch.setOnTouchListener { _, ev ->
            handleTouch(ctx, ev, fromCard = false)
            true
        }
        touchView = touch
        root.addView(touch, FrameLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)).apply {
            gravity = Gravity.TOP
        })

        return root
    }

    private fun handleTouch(ctx: Context, ev: MotionEvent, fromCard: Boolean) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                downLpX = lpOrNull()?.x ?: 0
                downLpY = lpOrNull()?.y ?: 0
                dragged = false
                downSeen = true
            }
            MotionEvent.ACTION_MOVE -> {
                val lp = lpOrNull() ?: return
                if (!downSeen) {
                    downSeen = true
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downLpX = lp.x
                    downLpY = lp.y
                }
                val d = ctx.resources.displayMetrics.density
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (Math.abs(dx) > d * 6 || Math.abs(dy) > d * 6) dragged = true
                if (dragged) {
                    // 只移动，不改形态
                    updateLayout(
                        ctx,
                        (downLpX + dx).toInt().coerceIn(0, maxX(ctx)),
                        (downLpY + dy).toInt().coerceIn(0, maxY(ctx))
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                if (downSeen) {
                    if (dragged) snapAndSave(ctx)
                    else if (fromCard) collapse() else toggleExpand(ctx)
                }
                downSeen = false
                dragged = false
            }
            MotionEvent.ACTION_CANCEL -> { downSeen = false; dragged = false }
        }
    }

    private fun toggleExpand(ctx: Context) {
        if (expanded) collapse() else expand(ctx)
    }

    private fun expand(ctx: Context) {
        if (expanded) return
        expanded = true
        cardView()?.visibility = View.VISIBLE
        touchView?.visibility = View.GONE
        applyState()
        alignTo(side, ctx)
        // 展开：0.96 → 1 + 淡入，160ms，**无回弹**。
        // 旧版是 0.82 → 1 配 OvershootInterpolator(1.6)：贴边小卡片弹一下就成了噪音，
        // 而且它是个常驻系统悬浮窗，弹跳会被重复看到。
        cardView()?.let { c ->
            c.animate().cancel()
            if (Motion.animationsEnabled(ctx)) {
                c.scaleX = 0.96f
                c.scaleY = 0.96f
                c.alpha = 0f
                c.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(160)
                    .setInterpolator(Motion.ease())
                    .start()
            } else {
                c.scaleX = 1f
                c.scaleY = 1f
                c.alpha = 1f
            }
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        val c = cardView()
        val t = touchView
        val ctx = rootView?.context ?: return
        if (c != null && Motion.animationsEnabled(ctx)) {
            // 收起也走 120ms 淡出，再切回圆点：直接 GONE 会让卡片"啪"一下消失
            c.animate().cancel()
            c.animate().alpha(0f).setDuration(120).setInterpolator(Motion.ease())
                .withEndAction {
                    c.visibility = View.GONE
                    c.alpha = 1f
                    c.scaleX = 1f
                    c.scaleY = 1f
                    // 取消后又被展开时不要再切圆点（以 expanded 的当前值为准）
                    if (!expanded) {
                        t?.visibility = View.VISIBLE
                        alignTo(side, ctx)
                    }
                }
                .start()
            return
        }
        c?.animate()?.cancel()
        c?.visibility = View.GONE
        c?.alpha = 1f
        c?.scaleX = 1f
        c?.scaleY = 1f
        t?.visibility = View.VISIBLE
        alignTo(side, ctx)
    }

    private fun cardView(): View? = (rootView as? ViewGroup)?.getChildAt(0)

    private fun lpOrNull(): WindowManager.LayoutParams? =
        rootView?.layoutParams as? WindowManager.LayoutParams

    // ------------------------------------------------------------------
    // 窗口尺寸与位置
    // ------------------------------------------------------------------

    private fun winW(ctx: Context): Int {
        val d = ctx.resources.displayMetrics.density
        return ((if (expanded) CARD_W_DP else TOUCH_DP) * d).toInt()
    }

    private fun winH(ctx: Context): Int {
        val d = ctx.resources.displayMetrics.density
        if (!expanded) return (TOUCH_DP * d).toInt()
        val scale = runCatching { ctx.resources.configuration.fontScale }.getOrDefault(1f)
            .coerceIn(1f, 1.8f)
        return (CARD_H_DP * d * scale).toInt()
    }

    private fun layoutParams(ctx: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            winW(ctx), winH(ctx), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (ratioBaseH(ctx) * yRatio).toInt()
        }
    }

    /** 换**新建的** LayoutParams 实例再 updateViewLayout，强制 WindowManager 重算触摸区。 */
    private fun updateLayout(ctx: Context, x: Int, y: Int) {
        val v = rootView ?: return
        val w = wm ?: return
        val old = lpOrNull() ?: return
        val lp = WindowManager.LayoutParams(
            winW(ctx), winH(ctx), old.type, old.flags, old.format
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = old.layoutInDisplayCutoutMode
            }
        }
        runCatching { w.updateViewLayout(v, lp) }
    }

    /** 贴到 [s] 那一侧，并把圆点的对齐方向刷一遍。 */
    private fun alignTo(s: String, ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val w = winW(ctx)
        val x = if (s == "L") 0 else (dm.widthPixels - w).coerceAtLeast(0)
        val y = (lpOrNull()?.y ?: (ratioBaseH(ctx) * yRatio).toInt()).coerceIn(0, maxY(ctx))
        (dotView?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = (if (s == "L") Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        }
        dotView?.requestLayout()
        updateLayout(ctx, x, y)
    }

    private fun maxX(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return (dm.widthPixels - winW(ctx)).coerceAtLeast(0)
    }

    private fun maxY(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val nav = (56 * dm.density).toInt()
        return (dm.heightPixels - winH(ctx) - nav).coerceAtLeast(0)
    }

    /** 记忆位置用的基准高度：固定值，不随当前形态变化（否则冷启动会漂）。 */
    private fun ratioBaseH(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val touch = (TOUCH_DP * dm.density).toInt()
        val nav = (56 * dm.density).toInt()
        return (dm.heightPixels - touch - nav).coerceAtLeast(1)
    }

    /** 松手吸附：按窗口中心判左右，y 按比例记忆。 */
    private fun snapAndSave(ctx: Context) {
        val lp = lpOrNull() ?: return
        val dm = ctx.resources.displayMetrics
        val centerX = lp.x + winW(ctx) / 2
        side = if (centerX < dm.widthPixels / 2) "L" else "R"
        yRatio = (lp.y.toFloat() / ratioBaseH(ctx)).coerceIn(0f, 0.95f)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SIDE, side)
            .putFloat(KEY_Y_RATIO, yRatio)
            .apply()
        alignTo(side, ctx)
    }

    private fun openApp(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        runCatching {
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
        }
    }

    // ------------------------------------------------------------------
    // 外观
    // ------------------------------------------------------------------

    private fun dotDrawable(color: Int, night: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                (2f * densityPx).toInt().coerceAtLeast(1),
                if (night) 0xCCFFFFFF.toInt() else 0xF2FFFFFF.toInt()
            )
        }

    private fun cardBackground(ctx: Context, radiusPx: Int): LayerDrawable {
        val night = AppStyle.isNight
        val bg = GradientDrawable().apply {
            setColor(if (night) 0xF21C2026.toInt() else 0xF2FFFFFF.toInt())
            cornerRadius = radiusPx.toFloat()
        }
        val stroke = GradientDrawable().apply {
            setColor(0x00000000)
            cornerRadius = radiusPx.toFloat()
            setStroke(1, if (night) 0x33FFFFFF else 0x1F000000)
        }
        return LayerDrawable(arrayOf(bg, stroke))
    }

    // ------------------------------------------------------------------
    // 轮询 / 呼吸
    // ------------------------------------------------------------------

    private fun startPoll() {
        if (pollRunnable != null) return
        val v = rootView ?: return
        val r = object : Runnable {
            override fun run() {
                if (rootView == null || pollRunnable !== this) return
                applyState()
                v.postDelayed(this, 1500)
            }
        }
        pollRunnable = r
        v.postDelayed(r, 1500)
    }

    private fun stopPoll() {
        rootView?.let { v -> pollRunnable?.let { v.removeCallbacks(it) } }
        pollRunnable = null
    }

    private fun startBreathe() {
        if (breatheRunnable != null) return
        val v = rootView ?: return
        val base = AppStyle.color("accent")
        val dim = android.graphics.Color.rgb(
            (android.graphics.Color.red(base) * 0.65f).toInt(),
            (android.graphics.Color.green(base) * 0.65f).toInt(),
            (android.graphics.Color.blue(base) * 0.65f).toInt()
        )
        breathePhase = false
        val r = object : Runnable {
            override fun run() {
                if (rootView == null || breatheRunnable !== this) return
                breathePhase = !breathePhase
                // 双档换色改 ofArgb 平滑过渡（周期仍 620ms）：旧版是每 620ms 硬切一次颜色，
                // 常驻悬浮窗上看着像接触不良。关掉动画时退回硬切（呼吸本身是"状态指示"，不是装饰）。
                val from = if (breathePhase) base else dim
                val to = if (breathePhase) dim else base
                breatheAnimator?.cancel()
                breatheAnimator = null
                if (Motion.animationsEnabled(v.context)) {
                    breatheAnimator = ValueAnimator.ofArgb(from, to).apply {
                        duration = 620L
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener {
                            dotView?.background = dotDrawable(it.animatedValue as Int, AppStyle.isNight)
                        }
                        start()
                    }
                } else {
                    dotView?.background = dotDrawable(to, AppStyle.isNight)
                }
                v.postDelayed(this, 620)
            }
        }
        breatheRunnable = r
        v.postDelayed(r, 620)
    }

    private fun stopBreathe() {
        rootView?.let { v -> breatheRunnable?.let { v.removeCallbacks(it) } }
        breatheRunnable = null
        breatheAnimator?.cancel()
        breatheAnimator = null
    }

    private fun canDrawOverlays(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(ctx)
        } else true

    /** 位移超过 6dp 时接管触摸，让拖动不被卡片里的文字控件吞掉。 */
    private class DragLayout(ctx: Context) : LinearLayout(ctx) {
        private var downX = 0f
        private var downY = 0f

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = resources.displayMetrics.density * 6
                    if (Math.abs(ev.rawX - downX) > d || Math.abs(ev.rawY - downY) > d) return true
                }
            }
            return false
        }
    }
}
