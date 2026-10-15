package com.example.traework2api

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * 外观风格表 + 亮暗模式（本轮新增）。
 *
 * **为什么必须有这个文件**：原先全站用 `getColor(R.color.tw_xxx)` 取色，而 `R.color.*` 是
 * **编译期常量 id** —— 它只能区分 `values/` 与 `values-night/`（系统级亮暗），
 * **无法区分"风格"**。所以颜色改成「语义名 → 运行期查表」：
 * 布局代码只写 `color("primary")`，具体色值由"当前风格 + 当前亮暗"决定。
 *
 * 这个文件负责三件事：
 * 1. [Style] = 一套风格（中文名 + 圆角/间距形态 + 亮暗两份色表）；
 * 2. [color] = 按当前风格取色（取不到回退 `on_surface`，绝不返回透明）；
 * 3. [Mode] = 跟随系统 / 浅色 / 深色，通过 [applyNightOverride] 覆写 uiMode 落地。
 *
 * 持久化走 SharedPreferences（这是外观偏好，不是业务数据）。
 * 自检见 [selfCheck]，在 `MainActivity.onCreate` 里调用，不达标直接崩 —— 这是本项目惯例。
 */
object AppStyle {

    /** 语义色名：与 [Style.light] / [Style.dark] 的键一一对应。 */
    val KEYS = listOf(
        "primary", "primary_container", "on_primary", "on_primary_container", "secondary",
        "background", "surface", "surface_container", "surface_variant",
        "on_surface", "on_surface_variant", "outline", "outline_variant",
        "success", "success_container", "warning", "warning_container", "error", "error_container",
    )

    /**
     * 必须达标的对比度组合（前景 vs 其底色）。阈值 [MIN_CONTRAST]。
     *
     * 钉住的是"真的会同框出现"的组合：正文/说明文字压在 surface / background 上、
     * 按钮文字压在主色上、4 个语义 chip 的文字压在各自容器色上，
     * 以及**强调卡（primary_container 底）/ 柔和卡（surface_container 底）里的正文与说明**。
     */
    private val CONTRAST_PAIRS = listOf(
        "on_surface" to "surface",
        "on_surface" to "background",
        "on_surface_variant" to "surface",
        "on_surface_variant" to "background",
        "on_primary" to "primary",
        "primary" to "surface",
        "primary" to "primary_container",
        "on_primary_container" to "primary_container",
        // 强调卡 / 柔和卡：卡片底色不是 surface，里面的文字要单独钉一遍
        "on_surface" to "primary_container",
        "on_surface_variant" to "primary_container",
        "on_surface" to "surface_container",
        "on_surface_variant" to "surface_container",
        "primary" to "surface_container",
        "success" to "success_container",
        "warning" to "warning_container",
        "error" to "error_container",
    )

    /** WCAG AA：正文 4.5:1。每套风格的两套色都必须过，不是只有默认那套。 */
    private const val MIN_CONTRAST = 4.5

    private const val PREFS = "ui_appearance"
    private const val KEY_STYLE = "style_id"
    private const val KEY_MODE = "mode_id"

    /** 亮暗模式（用户可独立于风格选择）。 */
    enum class Mode(val id: String, val label: String, val note: String) {
        SYSTEM("system", "跟随系统", "系统开夜间模式时自动变深"),
        LIGHT("light", "浅色", "始终使用浅色"),
        DARK("dark", "深色", "始终使用深色"),
    }

    /**
     * 一套风格。
     *
     * 圆角与间距**也进风格表** —— 这样切换风格时"形态"跟着变，
     * 而不是只换了个主色（只换色的话四套风格看起来会是同一个东西）。
     */
    data class Style(
        val id: String,
        val label: String,
        val note: String,
        val cardRadius: Float,
        val controlRadius: Float,
        val pillRadius: Float,
        val gap: Int,
        val pagePadH: Int,
        val pagePadTop: Int,
        val pagePadBottom: Int,
        val cardPad: Int,
        val barPadTop: Int,
        val barPadBottom: Int,
        val contentMaxDp: Int,
        val elevation: Int,
        val light: Map<String, String>,
        val dark: Map<String, String>,
    ) {
        fun palette(night: Boolean): Map<String, String> = if (night) dark else light
    }

    /** 靛蓝：原 `tw_*` 色板，中圆角 + 标准间距 —— 默认风格，视觉与上一轮保持一致。 */
    private val INDIGO = Style(
        id = "indigo",
        label = "靛蓝",
        note = "经典靛蓝 · 中圆角 · 标准间距",
        cardRadius = 16f, controlRadius = 12f, pillRadius = 100f,
        gap = 12, pagePadH = 20, pagePadTop = 16, pagePadBottom = 24, cardPad = 16,
        barPadTop = 14, barPadBottom = 12, contentMaxDp = 640, elevation = 1,
        light = mapOf(
            "primary" to "#4F46E5", "primary_container" to "#E6E7FF",
            "on_primary" to "#FFFFFF", "on_primary_container" to "#17115B",
            "secondary" to "#2563A9", "background" to "#F7F7FC", "surface" to "#FFFFFF",
            "surface_container" to "#F0F0F8", "surface_variant" to "#E7E7F0",
            "on_surface" to "#1B1B22", "on_surface_variant" to "#5F5F6B",
            "outline" to "#777783", "outline_variant" to "#C7C7D1",
            "success" to "#16794B", "success_container" to "#D7F6E5",
            "warning" to "#8A5100", "warning_container" to "#FFE9C2",
            "error" to "#BA1A1A", "error_container" to "#FFDAD6",
        ),
        dark = mapOf(
            "primary" to "#BEC2FF", "primary_container" to "#34358B",
            "on_primary" to "#20205F", "on_primary_container" to "#E1E0FF",
            "secondary" to "#8AB8E8", "background" to "#101016", "surface" to "#19191F",
            "surface_container" to "#23232B", "surface_variant" to "#2E2E38",
            "on_surface" to "#E5E1EA", "on_surface_variant" to "#B5B3C0",
            "outline" to "#8B8B97", "outline_variant" to "#3A3A44",
            "success" to "#66E3A0", "success_container" to "#1E4A34",
            "warning" to "#FFD08A", "warning_container" to "#5A3D17",
            "error" to "#FFB4A8", "error_container" to "#5A2B2B",
        ),
    )

    /** 石墨：冷灰近中性、方正小圆角、紧凑间距 —— 与靛蓝拉开"方/圆、紧/松"两维。 */
    private val GRAPHITE = Style(
        id = "graphite",
        label = "石墨",
        note = "冷灰中性 · 方正小圆角 · 紧凑间距",
        cardRadius = 6f, controlRadius = 6f, pillRadius = 8f,
        gap = 10, pagePadH = 16, pagePadTop = 14, pagePadBottom = 18, cardPad = 14,
        barPadTop = 12, barPadBottom = 10, contentMaxDp = 680, elevation = 0,
        light = mapOf(
            "primary" to "#3F4754", "primary_container" to "#DDE2EA",
            "on_primary" to "#FFFFFF", "on_primary_container" to "#191C22",
            "secondary" to "#4A5568", "background" to "#ECEDEF", "surface" to "#FFFFFF",
            "surface_container" to "#F4F5F7", "surface_variant" to "#E2E4E8",
            "on_surface" to "#16181D", "on_surface_variant" to "#55595F",
            "outline" to "#6E737B", "outline_variant" to "#C6C9CE",
            "success" to "#14663F", "success_container" to "#D2EEDD",
            "warning" to "#7A4700", "warning_container" to "#FBE3BC",
            "error" to "#A81B1B", "error_container" to "#FBDAD8",
        ),
        dark = mapOf(
            "primary" to "#B8C2D0", "primary_container" to "#2A313C",
            "on_primary" to "#1A2028", "on_primary_container" to "#DCE2EA",
            "secondary" to "#9AA6B5", "background" to "#101215", "surface" to "#17191D",
            "surface_container" to "#1F2227", "surface_variant" to "#272B31",
            "on_surface" to "#E3E5E9", "on_surface_variant" to "#A9AEB6",
            "outline" to "#868C95", "outline_variant" to "#34383E",
            "success" to "#7FD9A8", "success_container" to "#12321F",
            "warning" to "#F5C87A", "warning_container" to "#3A2A06",
            "error" to "#FFB3AC", "error_container" to "#521413",
        ),
    )

    /** 苔绿：自然绿、大圆角、舒展间距 —— 与石墨在"圆/方、松/紧"上正好相反。 */
    private val MOSS = Style(
        id = "moss",
        label = "苔绿",
        note = "自然苔绿 · 大圆角 · 舒展间距",
        cardRadius = 24f, controlRadius = 18f, pillRadius = 100f,
        gap = 16, pagePadH = 24, pagePadTop = 20, pagePadBottom = 28, cardPad = 20,
        barPadTop = 16, barPadBottom = 14, contentMaxDp = 600, elevation = 2,
        light = mapOf(
            "primary" to "#2F6C46", "primary_container" to "#D6EFDD",
            "on_primary" to "#FFFFFF", "on_primary_container" to "#0E2416",
            "secondary" to "#4A7C59", "background" to "#F4F7F2", "surface" to "#FFFFFF",
            "surface_container" to "#EDF3EA", "surface_variant" to "#DFE9DD",
            "on_surface" to "#131A15", "on_surface_variant" to "#4F5B52",
            "outline" to "#6C7A6F", "outline_variant" to "#C3CFC2",
            "success" to "#1A6B3C", "success_container" to "#D9F2E1",
            "warning" to "#86530A", "warning_container" to "#FCE8C3",
            "error" to "#A8261F", "error_container" to "#FBDCD7",
        ),
        dark = mapOf(
            "primary" to "#8FD5A6", "primary_container" to "#1D3A28",
            "on_primary" to "#0D2A18", "on_primary_container" to "#CDEBD8",
            "secondary" to "#A9C9AF", "background" to "#0F1410", "surface" to "#161C17",
            "surface_container" to "#1E251F", "surface_variant" to "#262E27",
            "on_surface" to "#E1E8E1", "on_surface_variant" to "#AAB7AC",
            "outline" to "#8A978A", "outline_variant" to "#333B33",
            "success" to "#8FE0AE", "success_container" to "#12331F",
            "warning" to "#F7CA80", "warning_container" to "#3B2B06",
            "error" to "#FFB4AC", "error_container" to "#53140F",
        ),
    )

    /** 落日：暖橙、大圆角、宽松内边距 —— 冷暖维度上与前三套明显不同。 */
    private val SUNSET = Style(
        id = "sunset",
        label = "落日",
        note = "暖橙落日 · 大圆角 · 宽松内边距",
        cardRadius = 20f, controlRadius = 14f, pillRadius = 100f,
        gap = 12, pagePadH = 20, pagePadTop = 18, pagePadBottom = 24, cardPad = 18,
        barPadTop = 14, barPadBottom = 12, contentMaxDp = 640, elevation = 1,
        light = mapOf(
            "primary" to "#A8431A", "primary_container" to "#FFE2D4",
            "on_primary" to "#FFFFFF", "on_primary_container" to "#3A1406",
            "secondary" to "#8A5A2B", "background" to "#FDF7F3", "surface" to "#FFFFFF",
            "surface_container" to "#F8EFE9", "surface_variant" to "#EFE2DA",
            "on_surface" to "#211711", "on_surface_variant" to "#5C4A3E",
            "outline" to "#7D6A5D", "outline_variant" to "#D6C5B8",
            "success" to "#1A6B3C", "success_container" to "#D9F2E1",
            "warning" to "#8A5100", "warning_container" to "#FFE9C2",
            "error" to "#B3261E", "error_container" to "#FFDAD5",
        ),
        dark = mapOf(
            "primary" to "#FFB59B", "primary_container" to "#6E2607",
            "on_primary" to "#571D06", "on_primary_container" to "#FFDBCB",
            "secondary" to "#E0B58C", "background" to "#17110D", "surface" to "#1F1813",
            "surface_container" to "#291F18", "surface_variant" to "#33261D",
            "on_surface" to "#EFE1D9", "on_surface_variant" to "#C4B0A3",
            "outline" to "#9C8A7C", "outline_variant" to "#45372C",
            "success" to "#8FE0AE", "success_container" to "#12331F",
            "warning" to "#F7CA80", "warning_container" to "#3B2B06",
            "error" to "#FFB4AB", "error_container" to "#5C1412",
        ),
    )

    /** 可选风格（顺序即「外观风格」页的展示顺序，第一项是默认）。 */
    val STYLES: List<Style> = listOf(INDIGO, GRAPHITE, MOSS, SUNSET)

    private var styleId = INDIGO.id
    private var modeId = Mode.SYSTEM.id
    private var night = false
    private var loaded = false

    /** 当前风格（持久化值，读不到就退回默认风格）。 */
    val current: Style get() = STYLES.firstOrNull { it.id == styleId } ?: STYLES.first()

    /** 当前亮暗模式。 */
    val mode: Mode get() = Mode.entries.firstOrNull { it.id == modeId } ?: Mode.SYSTEM

    /** 当前是否走深色色板（由 [refreshNight] 从资源配置刷新）。 */
    val isNight: Boolean get() = night

    /** 载入持久化的偏好（只做一次；[attachBaseContext] 与 [onCreate] 都会调）。 */
    fun init(context: Context) {
        if (loaded) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedStyle = sp.getString(KEY_STYLE, null)
        val savedMode = sp.getString(KEY_MODE, null)
        // 校验一遍：偏好里存了已删除的风格 id 时要退回默认，不能拿它去查表。
        styleId = STYLES.firstOrNull { it.id == savedStyle }?.id ?: INDIGO.id
        modeId = Mode.entries.firstOrNull { it.id == savedMode }?.id ?: Mode.SYSTEM.id
        loaded = true
    }

    fun setStyle(context: Context, id: String) {
        if (STYLES.none { it.id == id }) return
        styleId = id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, id).apply()
    }

    fun setMode(context: Context, m: Mode) {
        modeId = m.id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, m.id).apply()
    }

    /** 从（可能已被覆写过的）资源配置里刷新当前亮暗。必须在建控件之前调用。 */
    fun refreshNight(context: Context) {
        night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 把用户选的亮暗模式落到 uiMode 上（供 `Activity.attachBaseContext` 用）。
     *
     * 选「浅色 / 深色」时强制覆写 uiMode —— 这样 `resources.configuration` 与
     * 平台的对话框、光标、`values-night` 资源全都跟着走，不会出现"界面深色、弹窗浅色"。
     * 「跟随系统」原样返回，系统夜间模式一变 Activity 会自动重建。
     */
    fun applyNightOverride(base: Context): Context {
        if (mode == Mode.SYSTEM) return base
        val cfg = Configuration(base.resources.configuration)
        val want = if (mode == Mode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or want
        return base.createConfigurationContext(cfg)
    }

    /**
     * 按语义名取色（当前风格 + 当前亮暗）。
     *
     * 取不到时回退 `on_surface` —— **绝不能返回 0**：那是全透明，文字会直接看不见。
     * （色表齐全由 [selfCheck] 在启动时保证，这里的回退只是二道保险。）
     */
    fun color(name: String): Int {
        val pal = current.palette(night)
        return parse(pal[name] ?: pal[FALLBACK] ?: "#000000")
    }

    /**
     * 指定风格的语义色：「外观风格」页的色样预览要用**该风格自己**的色，
     * 而不是当前风格的（否则四张色样全长一样）。
     */
    fun previewColor(s: Style, name: String, dark: Boolean): Int {
        val pal = s.palette(dark)
        return parse(pal[name] ?: pal[FALLBACK] ?: "#000000")
    }

    private const val FALLBACK = "on_surface"

    private fun parse(hex: String): Int = Color.parseColor(hex)

    /**
     * 启动自检（失败直接崩，属预期行为 —— 与另外 7 个 selfCheck 一致）。
     *
     * 钉三件事：① 每套风格的亮/暗两套色**齐全**；② 色值**合法且不透明**；
     * ③ 正文/说明/语义 chip 的**对比度 ≥ 4.5:1**。任何一条不达标，
     * 与其让用户在界面上看到"看不见的字"，不如在启动时就炸出来。
     */
    fun selfCheck() {
        check(STYLES.size >= 3) { "至少要有 3 套风格，当前 ${STYLES.size} 套" }
        check(STYLES.map { it.id }.toSet().size == STYLES.size) { "风格 id 有重复" }

        val hexRe = Regex("^#[0-9a-fA-F]{6}$")
        for (s in STYLES) {
            for ((modeLabel, pal) in listOf("亮色" to s.light, "暗色" to s.dark)) {
                for (k in KEYS) {
                    check(pal.containsKey(k)) { "风格「${s.label}」$modeLabel 缺少色：$k" }
                }
                for ((k, v) in pal) {
                    check(hexRe.matches(v)) { "风格「${s.label}」$modeLabel 的 $k=$v 不是 #RRGGBB" }
                    // #RRGGBB 天然是不透明的，这条是防以后有人写成 8 位 #00RRGGBB（全透明）。
                    check(Color.alpha(Color.parseColor(v)) == 255) {
                        "风格「${s.label}」$modeLabel 的 $k=$v 是透明色（0x00RRGGBB 会被当全透明）"
                    }
                }
                for ((fg, bg) in CONTRAST_PAIRS) {
                    val r = contrast(pal.getValue(fg), pal.getValue(bg))
                    check(r >= MIN_CONTRAST) {
                        "风格「${s.label}」$modeLabel：$fg / $bg 对比度 ${"%.2f".format(r)} < $MIN_CONTRAST"
                    }
                }
            }
            check(s.cardRadius > 0f && s.controlRadius > 0f && s.pillRadius > 0f) {
                "风格「${s.label}」圆角不能为 0"
            }
            check(s.cardPad > 0 && s.pagePadH > 0 && s.gap >= 0) { "风格「${s.label}」间距不合法" }
            check(s.contentMaxDp in 480..900) { "风格「${s.label}」内容最大宽度 ${s.contentMaxDp}dp 不合理" }
        }
    }

    /** WCAG 相对对比度。 */
    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(hex: String): Double {
        val h = hex.trimStart('#')
        fun channel(i: Int): Double {
            val c = h.substring(i * 2, i * 2 + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2)
    }
}
