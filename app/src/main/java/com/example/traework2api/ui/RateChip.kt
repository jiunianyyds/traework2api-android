package com.example.traework2api.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/**
 * 积分倍率 chip（对应上游 `consumption_rate.data.rate`）。
 *
 * **为什么值得单独做一块 UI**：同一批模型里倍率从 0.06 到 1.83，**差 30 倍**。
 * 用户看不到这个数字，选模型就完全是盲选——用 glm-5.3（0.78）跑日常对话
 * 比 glm-5.3-flash（0.06）贵 13 倍，而两者在列表里长得一模一样。
 *
 * 三档配色，只表达"贵不贵"，不表达精确数值（精确值在详情里）：
 *
 * | 倍率 | 档位 | 颜色 |
 * |---|---|---|
 * | ≤ 0.20 | 便宜 | success（绿） |
 * | 0.20–0.80 | 中等 | 中性（灰） |
 * | > 0.80 | 贵 | warning（黄） |
 *
 * 阈值是按真实分布定的：16 个模型里 0.06×3、0.08、0.09、0.15、0.2 属"便宜档"，
 * 0.8 以上的只有 4 个，中间是大多数。
 */
class RateChip(context: Context, private val rate: Double) : TextView(context) {

    init {
        text = format(rate)
        textSize = Tokens.TEXT_XS
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.MONOSPACE
        setPadding(
            Tokens.dimen(context, 6), Tokens.dimen(context, 2),
            Tokens.dimen(context, 6), Tokens.dimen(context, 2)
        )
        val (fill, ink) = when {
            rate <= 0.20 -> "success_container" to "success"
            rate <= 0.80 -> "surface_variant" to "on_surface_variant"
            else -> "warning_container" to "warning"
        }
        setTextColor(Tokens.color(ink))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Tokens.color(fill))
            cornerRadius = Tokens.dimen(context, 5f).toFloat()
        }
        contentDescription = "积分倍率 ${format(rate)}"
        // 与右侧「复制」之间留一点空隙，避免两个可读元素贴在一起
        layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = Tokens.dimen(context, 6)
        }
    }

    /**
     * `0.06×` / `0.2×` / `1.83×` / `2×`
     *
     * 整数不带小数点（2× 而不是 2.0×），小数按 Double.toString 的原样输出
     * （0.06 不会变成 0.060）。上游给的就是 2 位以内，不需要额外舍入。
     */
    private fun format(r: Double): String {
        val s = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        return "$s×"
    }
}