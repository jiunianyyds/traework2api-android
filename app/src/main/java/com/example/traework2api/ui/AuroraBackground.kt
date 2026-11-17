package com.example.traework2api.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.example.traework2api.AppStyle

/**
 * 页面底色（对应设计稿 tokens.css body 的两团柔光 radial-gradient）。
 *
 * ```
 * radial-gradient(circle at 10% 56%, --glow-1 0, transparent 45%),
 * radial-gradient(circle at 92% 84%, --glow-2 0, transparent 48%)
 * ```
 *
 * 设计史（别来回摇摆）：机架面板版删过柔光；玻璃质感版的设计稿把它加回来了 ——
 * 玻璃卡的半透明底需要底下有东西才"透"得有意义。色团压到很淡（.72/.78 的 soft 色，
 * 再叠到画布上已接近背景色），只是给玻璃提供可透的层次，不抢内容。
 */
class AuroraBackground(context: Context) : View(context) {

    private val basePaint = Paint()
    private val glow1 = Paint().apply { isAntiAlias = true }
    private val glow2 = Paint().apply { isAntiAlias = true }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        basePaint.color = AppStyle.color("background")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
        val dark = AppStyle.isNight
        val c1 = AppStyle.color("glow1")
        val c2 = AppStyle.color("glow2")
        // 柔光透明度：设计稿原值 .72/.78 在实机上偏重（背景读起来是彩色的）。
        // unslop 审计后压到 .40/.45：只给玻璃卡留一点可透的暖/冷层次，
        // 背景仍读作中性灰蓝；深色 .40/.42。
        val a1 = (if (dark) 0.40f else 0.40f) * 255
        val a2 = (if (dark) 0.42f else 0.45f) * 255
        glow1.shader = RadialGradient(
            width * 0.10f, height * 0.56f, width * 0.38f,
            intArrayOf((c1 and 0x00FFFFFF) or ((a1.toInt()) shl 24), c1 and 0x00FFFFFF),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        glow2.shader = RadialGradient(
            width * 0.92f, height * 0.84f, width * 0.40f,
            intArrayOf((c2 and 0x00FFFFFF) or ((a2.toInt()) shl 24), c2 and 0x00FFFFFF),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow1)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow2)
    }
}
