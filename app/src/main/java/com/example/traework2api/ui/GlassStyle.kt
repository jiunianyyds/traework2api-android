package com.example.traework2api.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.traework2api.AppStyle

/**
 * 设计 token 的唯一来源（对应设计稿 `tokens.css`）。
 *
 * **规矩**：新写任何界面都不许再写字面量（`dp(13)` / `"#CC4520"` / `radius 12f`），
 * 一律从这里取 —— 否则改一次风格要满项目找数字（这个坑项目里踩过多次）。
 *
 * 与设计稿的对应关系写在每个常量后面，改设计稿时照着这里同步。
 */
object Tokens {

    // ---- 圆角（tokens.css: --radius-*） ----
    /** 卡片：15dp（tokens.css --radius-lg） */
    const val RADIUS_CARD = 15f
    /** 控件（按钮/输入框）：12dp（tokens.css --radius-md） */
    const val RADIUS_CONTROL = 12f
    /** 内嵌小块（分段选中块）：9dp（tokens.css --radius-sm） */
    const val RADIUS_SMALL = 9f
    /** 胶囊：给一个大值即全圆角 */
    const val RADIUS_PILL = 999f

    // ---- 间距（4/8 的倍数） ----
    const val SPACE_1 = 4
    const val SPACE_2 = 8
    const val SPACE_3 = 12
    const val SPACE_4 = 16
    const val SPACE_5 = 20
    const val SPACE_6 = 24
    const val SPACE_7 = 32

    /** 页面左右内边距：20dp（tokens.css main padding） */
    const val PAGE_PAD_H = 20
    /** 卡片内边距：14×16dp（tokens.css .card padding 14px 16px） */
    const val CARD_PAD = 16
    /** 卡片之间的间距：10dp（.card + .card margin-top） */
    const val CARD_GAP = 10
    /** 页面顶部内边距（内容区起点）：6dp */
    const val PAGE_PAD_TOP = 6

    // ---- 玻璃透明度（tokens.css --glass / --glass-strong / --glass-line 的 alpha 部分） ----
    /** 常规玻璃卡：白 .66（深色 .065） */
    const val GLASS_ALPHA = 0.66f
    const val GLASS_ALPHA_DARK = 0.065f
    /** 玻璃加强（ghost 按钮/搜索框）：白 .82（深色 .10） */
    const val GLASS_STRONG_ALPHA = 0.82f
    const val GLASS_STRONG_ALPHA_DARK = 0.10f
    /** 玻璃卡描边：白 .78（深色 .10） */
    const val GLASS_LINE_ALPHA = 0.78f
    const val GLASS_LINE_ALPHA_DARK = 0.10f

    // ---- 字号阶梯（tokens.css：11 / 14 / 20 / 26，紧凑档） ----
    /** 11sp：说明、副标题、chip */
    const val TEXT_XS = 11f
    /** 14sp：正文、卡片标题 */
    const val TEXT_MD = 14f
    /** 20sp：页面大标题 */
    const val TEXT_XL = 20f
    /** 26sp：主指标数字 */
    const val TEXT_2XL = 26f

    /** 触控目标最小值（Material 规范 48dp；这里统一按 44dp，与设计稿一致） */
    const val MIN_TOUCH = 44

    /** 底部漂浮胶囊：高 56、距底 24（tokens.css .tabbar bottom:34px 含安全区 → 24dp） */
    const val NAV_HEIGHT = 56
    const val NAV_BOTTOM_MARGIN = 24
    const val NAV_INSET_H = 16
    const val NAV_MAX_WIDTH = 560

    // ---- 语义色（全部走 AppStyle，不在这里写色值） ----

    fun color(name: String): Int = AppStyle.color(name)

    fun dimen(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dimen(context: Context, dp: Float): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 叠透明度。半透明**必须**用这个而不是 `Color.argb`：
     * 后者会把已有 alpha 覆盖掉，深色模式下叠两次就会看穿。
     */
    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

/**
 * 玻璃卡（对应设计稿 `.card`）。
 *
 * 四种语气（tokens.css）：
 * - [Tone.NORMAL] `.card`：半透明玻璃底 + 白描边 + 大圆角；
 * - [Tone.SOFT] `.card.plain`：实底 surface_2，无描边无玻璃（说明/空态）；
 * - [Tone.TINT] `.card.tint`：primary_soft 实底（强调卡，本页最该看的一块）；
 * - [Tone.WARN]：warning_soft 实底（保活提醒这类告示）。
 *
 * 原生没有 backdrop-filter，玻璃感靠「半透明底 + 白描边」近似；
 * 底栏（CapsuleNavBar）透明度压到接近不透明，背后的文字不能透出来干扰阅读。
 */
class GlassCard(context: Context, tone: Tone = Tone.NORMAL) : LinearLayout(context) {

    enum class Tone(val fill: String, val stroke: String?, val alpha: Float) {
        /** 常规玻璃卡：半透明底 + 一圈白描边。 */
        NORMAL("glass", "glass_line", Tokens.GLASS_ALPHA),
        /** 深色下的常规卡透明度不同（.065），构造时按当前亮暗取。 */
        NORMAL_DARK("glass", "glass_line", Tokens.GLASS_ALPHA_DARK),
        /** 说明卡 / 空态：实底，无描边。 */
        SOFT("surface_container", null, 1f),
        /** 强调卡：primary_soft 实底。 */
        TINT("primary_container", null, 1f),
        /** 告示卡：warning_soft 实底。 */
        WARN("warning_container", null, 1f),
    }

    init {
        orientation = VERTICAL
        val pad = Tokens.dimen(context, Tokens.CARD_PAD)
        setPadding(pad, (pad * 0.875f).toInt(), pad, (pad * 0.875f).toInt())
        background = gradient(tone)
        elevation = 0f
    }

    private fun gradient(tone: Tone): android.graphics.drawable.GradientDrawable {
        // NORMAL 与 NORMAL_DARK 共用 NORMAL 构造参数，深色时换透明度
        val t = if (tone == Tone.NORMAL_DARK && !AppStyle.isNight) Tone.NORMAL else tone
        val alpha = if (AppStyle.isNight && t == Tone.NORMAL) Tokens.GLASS_ALPHA_DARK
        else if (!AppStyle.isNight && t == Tone.NORMAL_DARK) Tokens.GLASS_ALPHA
        else t.alpha
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Tokens.alpha(Tokens.color(t.fill), alpha))
            cornerRadius = Tokens.dimen(context, Tokens.RADIUS_CARD).toFloat()
            t.stroke?.let {
                val lineAlpha = if (AppStyle.isNight) Tokens.GLASS_LINE_ALPHA_DARK else Tokens.GLASS_LINE_ALPHA
                setStroke(Tokens.dimen(context, 1), Tokens.alpha(Tokens.color(it), lineAlpha))
            }
        }
    }
}

/** 卡片标题（14sp 600，tokens.css .card-title）。 */
fun glassTitle(context: Context, value: String, colorName: String = "on_surface"): TextView =
    TextView(context).apply {
        text = value
        textSize = Tokens.TEXT_MD
        setTextColor(Tokens.color(colorName))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.35f)
    }

/** 说明文字（11sp，tokens.css --text-sm）。 */
fun glassCaption(context: Context, value: String, colorName: String = "on_surface_variant"): TextView =
    TextView(context).apply {
        text = value
        textSize = Tokens.TEXT_XS
        setTextColor(Tokens.color(colorName))
        setLineSpacing(0f, 1.65f)
    }

/** 正文（14sp）。 */
fun glassBody(context: Context, value: String, colorName: String = "on_surface"): TextView =
    TextView(context).apply {
        text = value
        textSize = Tokens.TEXT_MD
        setTextColor(Tokens.color(colorName))
        setLineSpacing(0f, 1.6f)
    }

/** 大数字（26sp 600，等宽数字对齐；tokens.css --text-2xl）。 */
fun glassMetric(context: Context, value: String, colorName: String = "primary"): TextView =
    TextView(context).apply {
        text = value
        textSize = Tokens.TEXT_2XL
        setTextColor(Tokens.color(colorName))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(0f, 1.1f)
    }

/** 区块小标题（11sp 600；tokens.css .section-label。中文不加宽字距——层级靠颜色与字号）。 */
fun glassSection(context: Context, value: String): TextView =
    TextView(context).apply {
        text = value
        textSize = Tokens.TEXT_XS
        setTextColor(Tokens.color("on_surface_variant"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0f
        setPadding(0, Tokens.dimen(context, 20), 0, Tokens.dimen(context, 8))
    }

/** 1dp 分隔线。 */
fun glassDivider(context: Context): View = View(context).apply {
    setBackgroundColor(Tokens.color("outline_variant"))
    layoutParams = LinearLayout.LayoutParams(-1, Tokens.dimen(context, 1))
}

/** 横向行，垂直居中。 */
fun glassRow(context: Context, build: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
    }

/** dp → 布局边距用的 LayoutParams（宽撑满、高自适应）。 */
fun glassLp(context: Context, top: Int = 0, start: Int = 0, end: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = Tokens.dimen(context, top)
        marginStart = Tokens.dimen(context, start)
        marginEnd = Tokens.dimen(context, end)
        bottomMargin = Tokens.dimen(context, bottom)
    }

/** 占满剩余宽度。 */
fun glassWeight(start: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = start }

/** 文本用 sp，别用 dp：`setTextSize` 默认接受的单位就是 sp。 */
fun TextView.setTextPx(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
