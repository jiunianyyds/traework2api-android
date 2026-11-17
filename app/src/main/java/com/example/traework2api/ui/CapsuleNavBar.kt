package com.example.traework2api.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.traework2api.AppStyle

/**
 * 底部漂浮液态玻璃胶囊导航（对应设计稿 `nav.tabbar` + `.tab-indicator`）。
 *
 * 结构：
 * ```
 * FrameLayout(胶囊背景)
 *  ├── 指示器（绝对定位，随选中项平移）
 *  └── LinearLayout(5 个等宽项)
 * ```
 *
 * **四条关键决定**：
 *
 * 1. **leftMargin 固定为 inset，位移只走 `translationX`；宽度直接落终值，不叠 `scaleX`**
 *    —— 旧版是「改 leftMargin + width 然后播 `scaleX(1f)`」：前者根本不是属性动画能插值的量，
 *    后者起点终点都是 1，等于什么都没动（真机表现为指示器瞬移）。混用 `scaleX` 还会围绕
 *    元素中心缩放、把偏移量放大，指示器会跑到胶囊外面。
 *
 * 2. **指示器宽度按实际按钮宽度算**，不写死 —— 改字号、加项都不会错位。
 *
 * 3. **胶囊半透明到 .99 而不是 .70**
 *    —— 原生没有 `backdrop-filter`，没有磨砂的"半透明"就是脏玻璃：实测 .94 时背后的
 *    地址文字 `http://10.0.2.15:8787/v1` 整条清楚可读，比实色更糟。留 1% 通透感即可。
 *
 * 4. **快速连切从"当前视觉位置"继续**：新选择先 cancel 上一个 ValueAnimator，
 *    再以 `indicator.translationX` 为起点走向新目标，不会先跳回旧位置再动。
 */
class CapsuleNavBar(
    context: Context,
    private val items: List<Item>,
    private val onSelect: (Int) -> Unit,
) : FrameLayout(context) {

    /**
     * @param label 文字（概览/账号/…）
     * @param icon 图标 drawable 资源 id
     */
    data class Item(val label: String, val icon: Int)

    /** 指示器位移时长：unslop 的 toggle 区间是 120–180ms，取 160。 */
    private val TOKENS_DURATION_FAST = 160L

    /** 选中态颜色过渡时长：与指示器同步，看起来是"一个动作"。 */
    private val COLOR_DURATION = 160L

    private val indicator = View(context)
    private val strip = LinearLayout(context)
    private val labelViews = mutableListOf<TextView>()
    private val holders = mutableListOf<LinearLayout>()
    private var selected = 0

    /** 指示器位移动画（快速连切要 cancel 它）。 */
    private var indicatorAnimator: ValueAnimator? = null

    /** 文字/图标颜色动画（onSizeChanged、detach 时要一起收掉）。 */
    private val colorAnimators = mutableListOf<ValueAnimator>()

    /** 当前视觉色（插值起点）；-1 = 还没画过，首帧直接落终值。 */
    private val textNow = IntArray(items.size) { -1 }
    private val iconNow = IntArray(items.size) { -1 }

    init {
        val inset = Tokens.dimen(context, 6)
        setPadding(inset, inset, inset, inset)
        background = capsule()
        // 不再给 elevation：底部栏贴着内容，阴影会在卡片边缘糊出一条灰带。
        // 深度靠「实底 surface + 一圈发丝线」，与卡片的做法一致。
        elevation = 0f

        // 指示器：leftMargin 恒为 inset（位置靠 translationX 挪），宽度在 onSizeChanged / select 时算
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
            leftMargin = inset
            topMargin = inset
            bottomMargin = inset
        })
        indicator.background = indicatorBg()

        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        items.forEachIndexed { index, item ->
            val tv = TextView(context).apply {
                text = item.label
                textSize = Tokens.TEXT_XS
                gravity = Gravity.CENTER
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, item.icon, 0, 0)
                compoundDrawablePadding = Tokens.dimen(context, 3)
                setPadding(Tokens.dimen(context, 4), 0, Tokens.dimen(context, 4), 0)
                includeFontPadding = false
            }
            val holder = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = item.label
                setOnClickListener {
                    select(index, animate = true)
                    onSelect(index)
                }
                addView(tv, LinearLayout.LayoutParams(-2, -2))
            }
            // 按压：与全站可点控件同一套 0.98 / 70–120ms（不再只有文字变色）
            Motion.installPressFeedback(holder)
            strip.addView(holder, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            labelViews += tv
            holders += holder
        }
        applyColors()
    }

    /**
     * 底栏底：**半透明玻璃 + 白描边**（tokens.css .tabbar）。
     *
     * 原生没有 backdrop-filter，「半透明 + 磨砂」的网页做法直接搬过来会让背后的
     * 文字透出来（实测 .94 时地址文字整条可读）。压到 .97：留 3% 的通透感让柔光透出，
     * 又不至于让任何文字干扰 5 个 Tab 的可读性 —— 玻璃感靠白描边 + 选中指示器表达。
     */
    private fun capsule(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Tokens.alpha(Tokens.color("tabbar"), if (AppStyle.isNight) 0.94f else 0.97f))
        cornerRadius = Tokens.dimen(context, Tokens.NAV_HEIGHT / 2f).toFloat()
        setStroke(
            Tokens.dimen(context, 1),
            Tokens.alpha(Tokens.color("tabbar_line"), if (AppStyle.isNight) 0.12f else 0.82f)
        )
    }

    /** 指示器：选中态下那枚贴在图标+文字上的小胶囊（tokens.css --tab-indicator-bg）。 */
    private fun indicatorBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Tokens.alpha(Tokens.color("tab_indicator"), if (AppStyle.isNight) 0.45f else 0.9f))
        cornerRadius = Tokens.dimen(context, Tokens.NAV_HEIGHT / 2f).toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变了（旋屏/平板宽度变化）：位置重算，**不做动画** ——
        // 做动画的话进页面会看到指示器从左边"飞"过来，旋转时还会临时越出胶囊。
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        post { select(selected, animate = false) }
    }

    /**
     * 选中第 [index] 项。
     *
     * @param animate 切 Tab 用 true；首次布局/尺寸变化/程序化同步用 false（直接落位）。
     */
    fun select(index: Int, animate: Boolean) {
        if (index !in items.indices) return
        selected = index
        applyColors()

        val holder = holders.getOrNull(index) ?: return
        if (holder.width == 0) return // 还没测量完，等 onSizeChanged 再算

        val pad = Tokens.dimen(context, 6)
        val targetWidth = holder.width - pad * 2
        // 指示器的基准 left = paddingLeft + leftMargin(inset)，所以位移 = 目标左缘 − 基准左缘。
        // pad 与 inset 都是 6dp，简化后就是 holder.left；写成显式减法是为了将来改 padding 不跑偏。
        val targetX = (holder.left + pad - paddingLeft).toFloat()

        // 快速连切：先掐掉上一段位移，再从当前视觉位置起步（不跳回起点）
        indicatorAnimator?.cancel()
        indicatorAnimator = null

        indicator.layoutParams = (indicator.layoutParams as LayoutParams).apply {
            // 宽度不插值：选中块就是一整格宽，拉长再回弹是弹跳感（unslop 审计第 19 条）
            width = targetWidth
        }
        indicator.requestLayout()

        if (!animate || !Motion.animationsEnabled(context)) {
            indicator.translationX = targetX
            return
        }
        val from = indicator.translationX
        if (from == targetX) return
        indicatorAnimator = ValueAnimator.ofFloat(from, targetX).apply {
            duration = TOKENS_DURATION_FAST
            interpolator = Motion.ease() as PathInterpolator
            addUpdateListener { indicator.translationX = it.animatedValue as Float }
            start()
        }
    }

    /**
     * 选中项：文字/图标用 primary_on_soft（浅色 #A2351A；primary 直接压在玻璃上对比度不够）。
     *
     * 颜色走 `ofArgb` 过渡，**字重立即落终值**：字体粗细不是一个能插值的属性，
     * 硬做只会变成"半粗不粗"的糊字。快速连切先 cancel 上一轮颜色动画。
     */
    private fun applyColors() {
        val activeColor = Tokens.color(if (AppStyle.isNight) "primary" else "primary_on_soft")
        val idleColor = Tokens.color("on_surface_variant")
        val animate = Motion.animationsEnabled(context)

        colorAnimators.forEach { it.cancel() }
        colorAnimators.clear()

        labelViews.forEachIndexed { i, tv ->
            val on = i == selected
            tv.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)

            val target = if (on) activeColor else idleColor
            val fromText = if (textNow[i] == -1) target else textNow[i]
            if (!animate || fromText == target) {
                tv.setTextColor(target)
            } else {
                colorAnimators += ValueAnimator.ofArgb(fromText, target).apply {
                    duration = COLOR_DURATION
                    interpolator = Motion.ease()
                    addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
                    start()
                }
            }
            textNow[i] = target

            val fromIcon = if (iconNow[i] == -1) target else iconNow[i]
            tv.compoundDrawables.filterNotNull().forEach { d ->
                d.mutate()
                if (!animate || fromIcon == target) {
                    d.setTint(target)
                } else {
                    colorAnimators += ValueAnimator.ofArgb(fromIcon, target).apply {
                        duration = COLOR_DURATION
                        interpolator = Motion.ease()
                        addUpdateListener { d.setTint(it.animatedValue as Int) }
                        start()
                    }
                }
            }
            iconNow[i] = target
        }
    }

    override fun onDetachedFromWindow() {
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        colorAnimators.forEach { it.cancel() }
        colorAnimators.clear()
        super.onDetachedFromWindow()
    }

    /** 当前选中项（供 render 时同步，不触发回调）。 */
    fun current(): Int = selected
}