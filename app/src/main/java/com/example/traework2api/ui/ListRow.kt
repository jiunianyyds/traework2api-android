package com.example.traework2api.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.traework2api.R
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 一张卡里的行式列表项（对应设计稿 `.acct` / `.log-row` / `.list-item`）。
 *
 * 设计稿里把"一个模型一张卡 / 一条记录一张卡"改成了"一张卡里多行"——
 * 同样 6 个模型，卡片式占 1.5 屏，行式只占 0.8 屏。所以这三个页都用它。
 *
 * 三种形态由构造函数决定，不搞继承：
 * - **账号行**：[Avatar] 圆形首字 + 标题 + 副标题 + 右侧箭头/勾
 * - **模型行**：标题 + 等宽副标题（id · context）+ 右侧复制按钮
 * - **记录行**：左侧状态 chip + 标题 + 等宽副标题 + 右侧时间
 */
class ListRow private constructor(context: Context) : LinearLayout(context) {

    enum class Kind { ACCOUNT, MODEL, RECORD }

    /** 行与行之间的分隔线：除第一行外都画（用 1px View，不用 background 边框，避免圆角冲突）。 */
    fun showDivider(show: Boolean) {
        divider.visibility = if (show) View.VISIBLE else View.GONE
    }

    private val divider = View(context)

    /**
     * 行的水平内边距。
     *
     * **必须由行自己加**：这些列表卡为了「分隔线撑满整宽」把 card padding 设成了 0，
     * 于是行内容会顶到卡片左右边缘、溢出圆角（真机截图：模型行贴着屏幕边）。
     * 20dp = 卡片的标准内边距（Tokens.CARD_PAD）。
     */
    private val RowPad = Tokens.dimen(context, Tokens.CARD_PAD)

    init {
        orientation = VERTICAL
        // 分隔线放在顶部，由调用方对非首行 showDivider(true)
        divider.setBackgroundColor(Tokens.color("outline_variant"))
        addView(divider, LayoutParams(-1, Tokens.dimen(context, 1)).apply {
            marginStart = Tokens.dimen(context, Tokens.CARD_PAD)
        })
        divider.visibility = View.GONE
    }

    companion object {

        /**
         * 账号行。
         *
         * @param initial 头像里的字（取名字首字；英文取首字母大写）
         * @param active 是否为当前使用中的账号（右侧显示 ✓ 而不是箭头）
         */
        fun account(
            context: Context,
            initial: String,
            title: String,
            subtitle: String,
            active: Boolean,
            /** 右侧附加控件（账号页的「⋯」更多菜单）。null = 不显示。 */
            trailing: View? = null,
            onClick: (() -> Unit)?,
        ): ListRow = ListRow(context).apply {
            val body = glassRow(context) {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(RowPad, Tokens.dimen(context, 11), RowPad, Tokens.dimen(context, 11))
                minimumHeight = Tokens.dimen(context, 50)

                addView(avatar(context, initial, active), LinearLayout.LayoutParams(
                    Tokens.dimen(context, 30), Tokens.dimen(context, 30)
                ))

                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        textSize = Tokens.TEXT_MD
                        setTextColor(Tokens.color("on_surface"))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(glassCaption(context, subtitle).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, Tokens.dimen(context, 1), 0, 0)
                    })
                }, glassWeight(Tokens.dimen(context, 12)))

                // 右侧标记用图标，不是文字符号：`✓` / `›` 在不同字体下大小不一致，
                // 而且它们属于「emoji 当图标」那一类 slop（unslop 审计第 11 条）
                if (trailing != null) {
                    addView(trailing, LinearLayout.LayoutParams(-2, -2).apply {
                        marginStart = Tokens.dimen(context, 4)
                    })
                } else {
                    addView(android.widget.ImageView(context).apply {
                        setImageResource(if (active) R.drawable.ic_check else R.drawable.ic_chevron_right)
                        setColorFilter(Tokens.color(if (active) "primary_on_soft" else "on_surface_variant"))
                        contentDescription = if (active) "当前使用中" else null
                    }, LinearLayout.LayoutParams(
                        Tokens.dimen(context, 15), Tokens.dimen(context, 15)
                    ).apply { marginStart = Tokens.dimen(context, 6) })
                }
            }
            addView(body, LayoutParams(-1, -2))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                // 按压反馈统一走 Motion：按下 0.98 / 70ms，抬起 120ms。
                // 旧版这里是本文件独有的一套 0.95 / 140ms —— 同一屏里两套缩放会看起来"轻重不一"。
                Motion.installPressFeedback(this)
            }
        }

        /**
         * 模型行：标题 + `context · id` 等宽副标题 + 倍率 chip + 右侧「复制」。
         *
         * @param rate 积分消耗倍率。**null = 该区没有倍率数据**（例如国际服），
         *   此时不画 chip —— 显示 `0` 会被读成"免费"，显示 `—` 又占地方。
         * @param highlight 是否高亮（当前被搜索命中/最便宜等由调用方决定），暂未使用。
         */
        fun model(
            context: Context,
            name: String,
            idAndContext: String,
            rate: Double? = null,
            /** 点整行（「复制」以外的区域）时回调，用来展开/收起参数详情。 */
            onClick: (() -> Unit)? = null,
            onCopy: () -> Unit,
        ): ListRow = ListRow(context).apply {
            addView(glassRow(context) {
                setPadding(RowPad, Tokens.dimen(context, 11), RowPad, Tokens.dimen(context, 11))
                minimumHeight = Tokens.dimen(context, 48)
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = name
                        textSize = Tokens.TEXT_MD
                        setTextColor(Tokens.color("on_surface"))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = idAndContext
                        textSize = Tokens.TEXT_XS
                        setTextColor(Tokens.color("on_surface_variant"))
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, Tokens.dimen(context, 1), 0, 0)
                    })
                }, glassWeight())
                if (rate != null) addView(RateChip(context, rate))
                addView(TextAction(context, "复制", onCopy))
            }, LayoutParams(-1, -2))
            if (onClick != null) {
                // 整行可点：ListRow 本身是 FrameLayout，子 View 填满它。
                // 子 View（文本、chip）默认不消费点击，事件会落到这里；
                // 「复制」是 TextAction 自己带 OnClickListener，照常工作。
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                // 与账号行同一套 0.98 按压：整行轻微下沉，右侧「复制」仍走自己的点击 + 透明度反馈
                Motion.installPressFeedback(this)
            }
        }

        /**
         * 记录行：状态 chip + 模型名 + 等宽明细 + 右侧时间。
         *
         * @param detail 形如 `2048 · 892 · 1240ms`（设计稿里省掉了 in/out 前缀，一行放得下）
         */
        fun record(
            context: Context,
            ok: Boolean,
            model: String,
            detail: String,
            time: String,
        ): ListRow = ListRow(context).apply {
            addView(glassRow(context) {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(RowPad, Tokens.dimen(context, 10), RowPad, Tokens.dimen(context, 10))
                minimumHeight = Tokens.dimen(context, 44)

                addView(StatusChip(context, if (ok) "OK" else "ERR", ok))

                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = model.ifBlank { "?" }
                        textSize = Tokens.TEXT_MD
                        setTextColor(Tokens.color("on_surface"))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = detail
                        textSize = Tokens.TEXT_XS
                        setTextColor(Tokens.color("on_surface_variant"))
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, Tokens.dimen(context, 1), 0, 0)
                    })
                }, glassWeight(Tokens.dimen(context, 10)))

                addView(TextView(context).apply {
                    text = time
                    textSize = Tokens.TEXT_XS
                    setTextColor(Tokens.color("on_surface_variant"))
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER_VERTICAL
                })
            }, LayoutParams(-1, -2))
        }

        /** 圆形头像：首字 + primary_soft 底（tokens.css .acct .av）。当前账号同色，其它用中性底。 */
        private fun avatar(context: Context, initial: String, active: Boolean): FrameLayout =
            FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Tokens.color(if (active) "primary_container" else "surface_container"))
                }
                addView(TextView(context).apply {
                    text = initial
                    textSize = Tokens.TEXT_MD
                    setTextColor(Tokens.color(if (active) "primary_on_soft" else "on_surface_variant"))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            }
    }
}

/** 行内文字动作（「复制」「刷新」「修改」）—— 点击区 44dp，视觉上只是文字；accent 蓝（tokens.css .text-btn）。 */
class TextAction(context: Context, label: String, onClick: () -> Unit) : TextView(context) {
    init {
        text = label
        textSize = Tokens.TEXT_MD
        setTextColor(Tokens.color("accent"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = Tokens.dimen(context, Tokens.MIN_TOUCH)
        setPadding(Tokens.dimen(context, 10), 0, Tokens.dimen(context, 2), 0)
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        // 文字动作只降透明度（0.65 / 60ms），不缩尺寸、不动布局
        Motion.installAlphaFeedback(this)
    }
}

/** 状态 chip（OK / ERR）。 */
class StatusChip(context: Context, label: String, ok: Boolean) : TextView(context) {
    init {
        text = label
        textSize = Tokens.TEXT_XS
        setTextColor(Tokens.color(if (ok) "success" else "error"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(Tokens.dimen(context, 8), Tokens.dimen(context, 3), Tokens.dimen(context, 8), Tokens.dimen(context, 3))
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Tokens.color(if (ok) "success_container" else "error_container"))
            cornerRadius = Tokens.dimen(context, Tokens.RADIUS_PILL).toFloat()
        }
    }
}
