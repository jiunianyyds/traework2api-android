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
        "primary", "primary_container", "on_primary", "on_primary_container",
        "background", "surface", "surface_container", "surface_variant",
        "on_surface", "on_surface_variant", "outline", "outline_variant",
        "success", "success_container", "warning", "warning_container", "error", "error_container",
        // 玻璃质感版新增（对应设计稿 tokens.css 的 --glass* / --tabbar* / --glow-* / --color-accent*）
        "glass", "glass_strong", "glass_line", "tabbar", "tabbar_line", "tab_indicator",
        "glow1", "glow2", "accent", "accent_container", "accent_on_soft", "primary_on_soft",
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
    /**
     * 唯一一套风格：**机架面板**。
     *
     * 方向来自产品本身 —— 这是一个「在你手机里悄悄跑着的小服务」，最像的不是温暖的
     * 消费级 App，而是路由器的指示灯面板、终端里的 `listening on :8787`。
     *
     * 三条硬规矩（改色板前先读）：
     * ① **表面全中性**（石墨），不带色相。旧版背景是蓝灰 `#EAF0F6`、强调卡是暖粉
     *    `#FBE3DA`，两个色相对撞，整屏读起来像"彩色"而不是"安静"；
     * ② **一个强调色，用得极少**（余烬橙）—— 只标主操作、选中态和一个关键数字。
     *    旧版 6 处用 `primary_container`，处处高亮等于没有重点；
     * ③ 语义色（成功/警告/错误）**只表意**，且压到能坐在中性色阶里。
     *
     * `Style` 保留下来是因为 [color] 按语义名查表的机制依赖它：页面代码只写
     * `color("primary")`，具体色值在这里按亮暗决定，换色板不用动任何页面。
     */
    private val GLASS = Style(
        id = "glass",
        label = "玻璃质感",
        note = "柔光底 · 半透明玻璃卡 · 余烬橙",
        cardRadius = 15f, controlRadius = 12f, pillRadius = 100f,
        gap = 10, pagePadH = 20, pagePadTop = 6, pagePadBottom = 16, cardPad = 16,
        barPadTop = 14, barPadBottom = 10, contentMaxDp = 560, elevation = 0,
        light = mapOf(
            // ---- 主色（tokens.css --color-primary #B83A17 系）----
            "primary" to "#B83A17", "primary_container" to "#FBE3DA",
            "on_primary" to "#FFFFFF", "on_primary_container" to "#7A2C10",
            "primary_on_soft" to "#A2351A",
            // ---- 中性色阶 ----
            "background" to "#EAF0F6", "surface" to "#F7F9FC",
            "surface_container" to "#EDF3F9", "surface_variant" to "#D5DEE8",
            "on_surface" to "#10151C", "on_surface_variant" to "#4F5B69",
            "outline" to "#8A94A3", "outline_variant" to "#D5DEE8",
            // ---- 次级信息色（tokens.css --color-accent #2660A0）----
            "accent" to "#2660A0", "accent_container" to "#DDEAFA", "accent_on_soft" to "#215A96",
            // ---- 语义色 ----
            "success" to "#1B7A4B", "success_container" to "#DCF3E7",
            "warning" to "#8A5100", "warning_container" to "#FFE9C2",
            "error" to "#BA1A1A", "error_container" to "#FFDAD6",
            // ---- 玻璃（rgba → 就近不透明档；真正的半透明在 GlassCard 里叠 alpha）----
            "glass" to "#F7F9FC", "glass_strong" to "#FFFFFF",
            "glass_line" to "#FFFFFF",
            "tabbar" to "#F2F6FA", "tabbar_line" to "#FFFFFF", "tab_indicator" to "#FFFFFF",
            // ---- 柔光团（glow-1 暖橙 / glow-2 冷蓝）----
            "glow1" to "#FFD9C7", "glow2" to "#C9E2FF",
        ),
        dark = mapOf(
            "primary" to "#FF7A52", "primary_container" to "#3A1C12",
            "on_primary" to "#20120C", "on_primary_container" to "#FFD9CC",
            "primary_on_soft" to "#FF7A52",
            "background" to "#0C1016", "surface" to "#141A22",
            "surface_container" to "#1A222C", "surface_variant" to "#232C38",
            "on_surface" to "#E8EDF3", "on_surface_variant" to "#9BA8B7",
            "outline" to "#5C6878", "outline_variant" to "#232C38",
            "accent" to "#6FB3E8", "accent_container" to "#17293C", "accent_on_soft" to "#6FB3E8",
            "success" to "#6BE0A4", "success_container" to "#123024",
            "warning" to "#FFCF88", "warning_container" to "#37260A",
            "error" to "#FFB4AC", "error_container" to "#3D1512",
            "glass" to "#161C24", "glass_strong" to "#1D242D",
            "glass_line" to "#2A3440",
            "tabbar" to "#1A212A", "tabbar_line" to "#2A3440", "tab_indicator" to "#2C3540",
            "glow1" to "#7A2C12", "glow2" to "#16304A",
        ),
    )

    /** 唯一风格：玻璃质感。查表机制保留（页面代码只写语义名）。 */
    val STYLES: List<Style> = listOf(GLASS)


    private var styleId = GLASS.id
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
        styleId = STYLES.firstOrNull { it.id == savedStyle }?.id ?: GLASS.id
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
     *
     * 解析结果按「风格 × 亮暗 × 键」缓存：建一页 UI 要查几百次色，
     * 每次都 `Color.parseColor` 是纯字符串浪费；换风格/换亮暗时整表失效重建。
     */
    private val colorCache = HashMap<String, Int>()

    fun color(name: String): Int {
        val style = current
        val key = "${style.id}|$night|$name"
        synchronized(colorCache) {
            colorCache[key]?.let { return it }
        }
        val pal = style.palette(night)
        val parsed = parse(pal[name] ?: pal[FALLBACK] ?: "#000000")
        synchronized(colorCache) { colorCache[key] = parsed }
        return parsed
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
        check(STYLES.isNotEmpty()) { "至少要有 1 套风格，当前 ${STYLES.size} 套" }
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