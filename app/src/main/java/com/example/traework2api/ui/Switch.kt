package com.example.traework2api.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/**
 * 开关（对应设计稿 `.switch`）。
 *
 * 自绘而不是用 `android.widget.Switch`：系统 Switch 的轨道/滑块尺寸被主题写死，
 * 在全国产 ROM 上形状不一致，且无法贴合设计稿的 50×30 比例。自绘只多十几行。
 *
 * 动效（本轮）：按压统一 0.98；"开/关"文字**立即落终值**（文字做插值没有意义），
 * 底色与文字色走 ofArgb 140ms。**不新增滑块**：这个控件的语义一直是"一枚写着开/关的胶囊"，
 * 加滑块等于改形态。
 */
class Switch(context: Context, initialState: Boolean, private val onChange: (Boolean) -> Unit) : TextView(context) {

    private var checked = initialState

    /** 常驻底色 drawable：做颜色插值必须复用同一个实例（重建会打断动画起点）。 */
    private val track = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = Tokens.dimen(context, Tokens.RADIUS_PILL).toFloat()
    }

    private var curBg = -1
    private var curInk = -1
    private val animators = mutableListOf<ValueAnimator>()

    init {
        textSize = Tokens.TEXT_XS
        gravity = Gravity.CENTER
        minWidth = Tokens.dimen(context, 50)
        minHeight = Tokens.dimen(context, Tokens.MIN_TOUCH)
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        background = track
        Motion.installPressFeedback(this)
        setOnClickListener {
            checked = !checked
            render()
            onChange(checked)
        }
        render()
    }

    fun setChecked(on: Boolean) {
        if (checked == on) return
        checked = on
        render()
    }

    private fun render() {
        // 文字用「开/关」而不是图标：更省地方，也不会在深色下糊成一团
        text = if (checked) "开" else "关"
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(Tokens.dimen(context, 12), 0, Tokens.dimen(context, 12), 0)
        contentDescription = if (checked) "已开启，点击关闭" else "已关闭，点击开启"

        val toBg = Tokens.color(if (checked) "primary" else "surface_variant")
        val toInk = Tokens.color(if (checked) "on_primary" else "on_surface_variant")
        val fromBg = if (curBg == -1) toBg else curBg
        val fromInk = if (curInk == -1) toInk else curInk

        // 描边只属于"关"态，跟着状态立即切（它不是一个能插值的颜色过渡）
        if (checked) track.setStroke(0, Color.TRANSPARENT)
        else track.setStroke(Tokens.dimen(context, 1), Tokens.color("outline_variant"))

        animators.forEach { it.cancel() }
        animators.clear()

        if (!Motion.animationsEnabled(context) || (fromBg == toBg && fromInk == toInk)) {
            track.setColor(toBg)
            setTextColor(toInk)
        } else {
            val a = ValueAnimator.ofArgb(fromBg, toBg)
            a.duration = 140L
            a.interpolator = Motion.ease()
            a.addUpdateListener { track.setColor(it.animatedValue as Int) }
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