package com.example.traework2api.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.example.traework2api.AppStyle
import android.animation.ValueAnimator

/**
 * 数量筛选胶囊（对应设计稿 `.count-chip`：「全部 5 / 国服 4 / 国际服 1」）。
 *
 * 做成可点的筛选器而不是纯展示：点「国服」只显示国服账号。
 * 选中态用 `primary_on_soft` 配 `primary_container` —— 裸露 `primary` 只有 3.85:1。
 *
 * **点击区必须 ≥44dp**：设计稿阶段这组 chip 只有 26dp 高，被体检拦下来了。
 *
 * 动效（本轮）：按压统一走 [Motion.installPressFeedback]；选中态**只插值颜色**
 * （底色 + 文字色 140ms），圆角、尺寸、语义色名一律不动 —— 所以快速连选时
 * 先 cancel 上一个 ofArgb，再从当前视觉色继续，不会闪回初始色。
 */
class CountChip(context: Context, private val label: String) : TextView(context) {

    private var checked = false

    /** 保留同一个 drawable 做颜色插值：每次 render 重建 drawable 会把动画起点打断。 */
    private val chipBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = Tokens.dimen(context, Tokens.RADIUS_PILL).toFloat()
    }

    /** 当前视觉色（插值起点）；-1 = 还没画过，首帧直接落终值，不闪一下。 */
    private var curBg = -1
    private var curInk = -1
    /** 在跑的底色/文字色动画（声明在 init 之前：init 里的首次 render 就会用到）。 */
    private val animators = mutableListOf<ValueAnimator>()

    init {
        text = label
        textSize = Tokens.TEXT_XS
        gravity = Gravity.CENTER
        minHeight = Tokens.dimen(context, Tokens.MIN_TOUCH)
        setPadding(Tokens.dimen(context, 12), 0, Tokens.dimen(context, 12), 0)
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        background = chipBg
        Motion.installPressFeedback(this)
        render()
    }

    /** 数量单独设，切换筛选时只改数字不动布局。 */
    fun setCount(n: Int) {
        text = "$label $n"
    }

    fun setChecked(on: Boolean) {
        if (checked == on) return
        checked = on
        isSelected = on
        render()
    }

    private fun render() {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val toBg = Tokens.color(if (checked) "primary_container" else "surface_container")
        val toInk = Tokens.color(if (checked) "primary_on_soft" else "on_surface")

        // 快速连选：先把上一轮颜色动画掐掉，起点用"当前视觉色"而不是目标色
        animators.forEach { it.cancel() }
        animators.clear()

        val fromBg = if (curBg == -1) toBg else curBg
        val fromInk = if (curInk == -1) toInk else curInk

        if (!Motion.animationsEnabled(context) || (fromBg == toBg && fromInk == toInk)) {
            chipBg.setColor(toBg)
            setTextColor(toInk)
        } else {
            val a = ValueAnimator.ofArgb(fromBg, toBg)
            a.duration = 140L
            a.interpolator = Motion.ease()
            a.addUpdateListener { chipBg.setColor(it.animatedValue as Int) }
            a.start()
            val b = ValueAnimator.ofArgb(fromInk, toInk)
            b.duration = 140L
            b.interpolator = Motion.ease()
            b.addUpdateListener { setTextColor(it.animatedValue as Int) }
            b.start()
            animators += a
            animators += b
        }
        curBg = toBg
        curInk = toInk
    }

    override fun onDetachedFromWindow() {
        animators.forEach { it.cancel() }
        animators.clear()
        super.onDetachedFromWindow()
    }
}

/**
 * 三段选择器（对应设计稿 `.segmented`，用于「浅色 / 深色 / 跟随系统」）。
 *
 * 选中块用**内圆角 = 外圆角 − 内边距**，与设计稿一致；不这么做时选中块会显得比外框"鼓"。
 *
 * 动效（本轮）：按压统一；选中态用**同一个常驻 drawable 的透明度**从 0 淡到实底
 * （等价于原来 checked 才给背景），文字色同步 ofArgb 140ms。未选中仍是"没有块"，
 * 只是它现在是 alpha=0 而不是 null —— 外观完全一样，换来的是可插值。
 */
class SegmentedControl(
    context: Context,
    private val labels: List<String>,
    private val onPick: (Int) -> Unit,
) : LinearLayout(context) {

    private val buttons = mutableListOf<TextView>()
    private val chipBgs = mutableListOf<GradientDrawable>()
    private val inkNow = mutableListOf<Int>()
    private val alphaNow = mutableListOf<Int>()
    private val animators = mutableListOf<ValueAnimator>()
    private var selected = 0

    init {
        orientation = HORIZONTAL
        val pad = Tokens.dimen(context, Tokens.SPACE_1)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Tokens.color("surface_container"))
            cornerRadius = Tokens.dimen(context, Tokens.RADIUS_CONTROL).toFloat()
        }

        labels.forEachIndexed { i, label ->
            val chip = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Tokens.dimen(context, Tokens.RADIUS_SMALL).toFloat()
                setColor(Tokens.color("surface"))
            }
            val tv = TextView(context).apply {
                text = label
                textSize = Tokens.TEXT_MD
                gravity = Gravity.CENTER
                minHeight = Tokens.dimen(context, Tokens.MIN_TOUCH)
                isClickable = true
                isFocusable = true
                contentDescription = label
                background = chip
            }
            tv.setOnClickListener {
                selected = i
                render()
                onPick(i)
            }
            // 按压：与别的可点控件同一套 0.98 / 70–120ms
            Motion.installPressFeedback(tv)
            addView(tv, LinearLayout.LayoutParams(0, Tokens.dimen(context, Tokens.MIN_TOUCH), 1f))
            buttons += tv
            chipBgs += chip
            inkNow += -1
            alphaNow += -1
        }
        render()
    }

    fun setSelected(i: Int) {
        if (i !in labels.indices || i == selected) return
        selected = i
        render()
    }

    private fun render() {
        val animate = Motion.animationsEnabled(context)
        animators.forEach { it.cancel() }
        animators.clear()

        buttons.forEachIndexed { i, tv ->
            val on = i == selected
            // 选中态只留 surface 抬升：描边会让分段控件比周围卡片更抢（unslop 审计 P8）。
            tv.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)

            val toInk = Tokens.color(if (on) "on_surface" else "on_surface_variant")
            val toAlpha = if (on) 255 else 0
            val fromInk = if (inkNow[i] == -1) toInk else inkNow[i]
            val fromAlpha = if (alphaNow[i] == -1) toAlpha else alphaNow[i]
            val base = Tokens.color("surface")

            if (!animate || (fromInk == toInk && fromAlpha == toAlpha)) {
                tv.setTextColor(toInk)
                chipBgs[i].setColor(android.graphics.Color.argb(toAlpha, android.graphics.Color.red(base),
                    android.graphics.Color.green(base), android.graphics.Color.blue(base)))
            } else {
                val a = ValueAnimator.ofArgb(fromInk, toInk)
                a.duration = 140L
                a.interpolator = Motion.ease()
                a.addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
                a.start()
                val b = ValueAnimator.ofInt(fromAlpha, toAlpha)
                b.duration = 140L
                b.interpolator = Motion.ease()
                b.addUpdateListener {
                    val al = it.animatedValue as Int
                    chipBgs[i].setColor(android.graphics.Color.argb(al,
                        android.graphics.Color.red(base), android.graphics.Color.green(base),
                        android.graphics.Color.blue(base)))
                }
                b.start()
                animators += a
                animators += b
            }
            inkNow[i] = toInk
            alphaNow[i] = toAlpha
        }
    }

    override fun onDetachedFromWindow() {
        animators.forEach { it.cancel() }
        animators.clear()
        super.onDetachedFromWindow()
    }
}