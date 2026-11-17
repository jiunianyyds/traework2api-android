package com.example.traework2api.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.example.traework2api.R

/**
 * 动效的唯一出口（本轮 UI 动效方案）。
 *
 * 三条规矩：
 * 1. 只用系统动画类（ViewPropertyAnimator / ValueAnimator），不引任何依赖；
 * 2. 统一缓动是 [EASE] = cubic-bezier(0.2, 0.8, 0.2, 1)：无过冲、无循环、无 stagger；
 * 3. 系统关掉动画时（开发者选项 / 无障碍"移除动画"）**直接落最终状态** ——
 *    绝不留半透明、半缩放或 0 高度的中间态。
 *
 * 这个文件只做"怎么动"，不做"动什么"：业务状态、点击回调、加载守卫全部留在原调用点。
 */
object Motion {

    /** 统一缓动：ease-out。 */
    private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    /** 给页面 / 指示器 / 颜色过渡共用（同一个插值器实例，省重复构造）。 */
    fun ease(): PathInterpolator = EASE

    private const val DUR_PRESS_IN = 70L
    private const val DUR_PRESS_OUT = 120L
    private const val DUR_ALPHA_IN = 60L
    private const val DUR_ALPHA_OUT = 100L

    /**
     * 系统是否允许播放动画。
     *
     * **每次调用都现读，不缓存**：用户可能进了 App 再去开发者选项把动画关掉，
     * 缓存一次就会一直用旧值（这是本项目踩过的"设置改了界面没变"那类问题）。
     *
     * API 26+ 用 `areAnimatorsEnabled()`（同时覆盖开发者选项的 animator scale 与
     * 无障碍"移除动画"）；24–25 没有这个 API，读全局 scale 兜底。
     */
    fun animationsEnabled(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return ValueAnimator.areAnimatorsEnabled()
        return runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }

    /**
     * 可点控件的按压反馈：按下缩到 [scale]，抬起 / 取消回到 1。
     *
     * **必须返回 false**：不消费事件，`OnClickListener`、长按菜单、`isEnabled` 守卫
     * 全部照原样工作，也不加任何点击延迟。
     *
     * 每次 DOWN / UP / CANCEL 先 `cancel()`，从当前缩放继续 —— 快速连点不会叠加出
     * "越点越小"的残留。
     */
    fun installPressFeedback(view: View, scale: Float = 0.98f) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 禁用态不给按压反馈（否则用户以为还能点）
                    if (v.isEnabled && animationsEnabled(v.context)) {
                        v.animate().cancel()
                        v.animate().scaleX(scale).scaleY(scale)
                            .setDuration(DUR_PRESS_IN).setInterpolator(EASE).start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    if (animationsEnabled(v.context)) {
                        v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(DUR_PRESS_OUT).setInterpolator(EASE).start()
                    } else {
                        v.scaleX = 1f
                        v.scaleY = 1f
                    }
                }
            }
            false
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = cancelAndReset(v)
        })
    }

    /**
     * 文字动作（复制 / 刷新 / 修改 / ⋯）的按压反馈：只降透明度，不动布局、不缩尺寸 ——
     * 这类控件视觉上就是一行字，缩一下会显得比卡片还重。
     */
    fun installAlphaFeedback(view: View, down: Float = 0.65f) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (v.isEnabled && animationsEnabled(v.context)) {
                        v.animate().cancel()
                        v.animate().alpha(down).setDuration(DUR_ALPHA_IN)
                            .setInterpolator(EASE).start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    if (animationsEnabled(v.context)) {
                        v.animate().alpha(1f).setDuration(DUR_ALPHA_OUT)
                            .setInterpolator(EASE).start()
                    } else {
                        v.alpha = 1f
                    }
                }
            }
            false
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = cancelAndReset(v)
        })
    }

    /**
     * 数据写完后的状态淡入：`手工把 alpha 拨到 [from]`，再回到 1。
     *
     * 只给"原地更新"的调用点用（积分、模型计数、监测行…）。**绝不能让它替代加载态本身**：
     * 数据先写、动画后放，失败路径不跟着"成功"闪一下。
     */
    fun fadeState(view: View, from: Float = 0.65f, durationMs: Long = 120L) {
        view.animate().cancel()
        if (!animationsEnabled(view.context)) {
            view.alpha = 1f
            return
        }
        view.alpha = from
        view.animate().alpha(1f).setDuration(durationMs).setInterpolator(EASE).start()
    }

    /**
     * 可取消的显隐：透明度 + 固定位移（[offsetYPx] 传负值即"从下往上"，正值"往下退"）。
     *
     * 收起结束时**先隐藏再回调** [onHidden]，让调用方在这里做 `removeView`。
     * 被取消（用户又点了一次）时不会触发 [onHidden] —— 这一条是靠 [AnimatorListenerAdapter]
     * 里的 cancelled 标记实现的：`ValueAnimator.cancel()` 会先 cancel 再 end，
     * 若只看 end 就会把"正在反向展开"的 View 误删（这是本项目注释里踩过的坑）。
     */
    fun reveal(
        view: View,
        show: Boolean,
        durationMs: Long,
        offsetYPx: Float,
        onHidden: (() -> Unit)? = null,
    ) {
        view.animate().cancel()
        if (!animationsEnabled(view.context)) {
            view.visibility = if (show) View.VISIBLE else View.GONE
            view.alpha = if (show) 1f else 0f
            view.translationY = 0f
            if (!show) onHidden?.invoke()
            return
        }
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = offsetYPx
            view.animate().alpha(1f).translationY(0f)
                .setDuration(durationMs).setInterpolator(EASE).start()
            return
        }
        var cancelled = false
        view.animate().alpha(0f).translationY(offsetYPx)
            .setDuration(durationMs).setInterpolator(EASE)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // 清掉监听：ViewPropertyAnimator 是复用的，留着下次会被误调
                    runCatching { view.animate().setListener(null) }
                    if (cancelled) return
                    view.visibility = View.GONE
                    onHidden?.invoke()
                }
            })
            .start()
    }

    /** 取消这个 View 上所有属性动画，并把会残留的属性复位（detach / 销毁时用）。 */
    fun cancelAndReset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    /**
     * 让 Dialog 走统一进出动画（样式见 `res/values/styles.xml` 的 `DialogMotion`）。
     *
     * **必须在 show() 之前设置**：窗口动画是窗口被 add 的那一刻取的，show 之后再设
     * 只对下一次显示生效（进场就没有动画了）。
     */
    fun applyDialogMotion(dialog: Dialog) {
        runCatching { dialog.window?.setWindowAnimations(R.style.DialogMotion) }
    }
}