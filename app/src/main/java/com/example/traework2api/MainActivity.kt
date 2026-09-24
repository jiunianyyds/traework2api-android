package com.example.traework2api

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 阶段3：本地"假服务" + Trae 官方登录入口。
 *
 * 单一 Activity + 底部导航结构（移植 WorkBuddy2API-Android 的纯代码 View 组装方式）：
 * 概览 / 账号 / 模型 / 记录 / 更多 五个持久 Tab（`Page.entries` 驱动），
 * 顶部栏 + 卡片风格 + 选中态胶囊指示器。
 *
 * 视觉规则：圆角/间距/字号一律走下面的 design token，不要在页面里再写字面量；
 * 颜色一律走 `wb_*` 资源（`values` / `values-night` 各一套），不要写字面量色值。
 */
class MainActivity : Activity() {
    companion object {
        /** 通知权限请求码（Android 13+ 前台服务的常驻通知要用）。 */
        private const val REQ_NOTIF = 1002
        private const val SIXTY_MINUTES_SEC = 3600L

        /** 二级页「保活设置」的 onSaveInstanceState 标记值。 */
        private const val SUB_KEEP_ALIVE = "keepalive"
        /** 二级页「外观风格」的 onSaveInstanceState 标记值。 */
        private const val SUB_STYLE = "style"
        private const val STATE_PAGE = "page"
        private const val STATE_SUB = "sub"

        // ---- design tokens ----
        // 圆角/间距/内容宽度**不在这里**：它们跟着「外观风格」走，见下面按风格读取的属性。
        // 这里只留不随风格变的字号 token。
        private const val FS_TITLE = 22f     // 顶部栏标题
        private const val FS_SECTION = 14    // 区块标题（小号灰色，与卡片标题拉开层级）
        private const val FS_CARD_TITLE = 17 // 卡片标题
        private const val FS_METRIC = 24     // 主指标数值
        private const val FS_BODY = 15
        private const val FS_VALUE = 14
        private const val FS_CAPTION = 13
        private const val FS_CHIP = 12

        /** 本地 API Key 打码占位（概览页默认显示它，避免截屏直接泄露）。 */
        private const val MASKED_KEY = "sk-••••••••••••••••••••••••"

        /** 「使用引导」列出的端点（顺序即展示顺序）。复制时拼成完整 URL。 */
        private val ENDPOINTS = listOf(
            "POST" to "/v1/chat/completions",
            "GET" to "/v1/models",
            "GET" to "/healthz",
        )
    }

    // ------------------------------------------------------------------
    // design token：圆角 / 间距 / 内容宽度 —— **跟随当前风格**（多风格本轮新增）
    // 值只写在 AppStyle.Style 里；页面与 helper 只读这些属性，别在页面里写字面量。
    // 之所以做成读属性的函数而不是 const：切风格时"形态"也要跟着变，const 改不了。
    // ------------------------------------------------------------------
    /** 容器类（卡片/搜索框）圆角。 */
    private val RADIUS_CARD get() = AppStyle.current.cardRadius
    /** 控件类（按钮）圆角。 */
    private val RADIUS_CONTROL get() = AppStyle.current.controlRadius
    /** 胶囊（chip / 导航指示器）圆角：给一个大值即为全圆角。 */
    private val RADIUS_PILL get() = AppStyle.current.pillRadius

    /** 卡片之间的标准间距；页面里不再手传 8/10/12 混用。 */
    private val GAP get() = AppStyle.current.gap
    /** 页面左右内边距。 */
    private val PAGE_PAD_H get() = AppStyle.current.pagePadH
    /** 页面上下内边距。 */
    private val PAGE_PAD_TOP get() = AppStyle.current.pagePadTop
    private val PAGE_PAD_BOTTOM get() = AppStyle.current.pagePadBottom
    /** 卡片内边距。 */
    private val CARD_PAD get() = AppStyle.current.cardPad
    /** 顶栏上下内边距。 */
    private val BAR_PAD_TOP get() = AppStyle.current.barPadTop
    private val BAR_PAD_BOTTOM get() = AppStyle.current.barPadBottom
    /** 内容最大宽度（dp）：手机竖屏不受影响，平板/横屏下不再让一行文字横跨整屏。 */
    private val CONTENT_MAX_DP get() = AppStyle.current.contentMaxDp

    private enum class Page(val title: String, val subtitle: String, val icon: Int) {
        OVERVIEW("概览", "本地服务运行状态", R.drawable.ic_dashboard),
        ACCOUNT("账号", "当前 Trae 登录状态", R.drawable.ic_account),
        MODELS("模型", "本地可用的模型表", R.drawable.ic_models),
        RECORDS("记录", "调用用量与记录", R.drawable.ic_history),
        MORE("更多", "说明与致谢", R.drawable.ic_more),
    }

    private lateinit var contentHost: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView
    /** 顶栏里的「← 返回」，只在二级页可见（见 [render] / [openKeepAlive] / [openStylePage]）。 */
    private lateinit var backAction: TextView
    private lateinit var navItems: Map<Page, TextView>
    private var currentPage = Page.OVERVIEW
    /**
     * 是否正停在二级页（保活设置）。
     *
     * 做成二级页而不是第 6 个 Tab：底部导航已有 5 项，再加会挤；且保活是"设置一次就不管"的
     * 低频页面，不值得占一个常驻 Tab。
     */
    private var showKeepAlive = false
    /** 是否正停在外观风格二级页（与保活设置共用同一套二级页机制）。 */
    private var showStyle = false
    private val apiKey: String by lazy { Gateway.apiKey(this) }
    /** 账号页积分/额度缓存（null=未拉到），展示用，不存凭据。 */
    private var accountBalance: Long? = null   // 当前可用积分
    private var accountCap: Long? = null       // 权益额度(上限)
    private var accountCheckin: Pair<Boolean, Long>? = null // (今日已签, 每日可领)
    /** 本地 API Key 明文默认打码（防止截图/旁观泄露），点「显示」才展开。 */
    private var apiKeyVisible = false
    /** 服务启停进行中（"启动中…" / "停止中…"），null = 空闲。启停是异步的，中间得给个状态。 */
    private var serviceBusy: String? = null
    /** 积分刷新进行中：数值位显示「…」，否则用户点了「刷新」会觉得没反应。 */
    private var quotaLoading = false
    /**
     * 「积分与用量」卡里那几个会被刷新改掉的控件 —— 刷新时**原地改文字**，不重建页面。
     *
     * 为什么不用 `render()`：重建会把滚动位置弹回页首，用户就看不到自己刚点的那张卡在变
     * （实测：加载态的「…」因此根本看不见）。原地更新也顺带没有闪烁。
     */
    private var quotaApply: (() -> Unit)? = null

    /** 版本号取自 PackageManager，避免依赖 AGP 的 BuildConfig 开关。 */
    private val versionName: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "-"
    }

    /**
     * 外观偏好比任何控件都早一步生效：先把「亮/暗」落到 uiMode，
     * 这样 `values-night` 资源、平台对话框、光标颜色都跟着用户的选择走。
     */
    override fun attachBaseContext(newBase: Context) {
        AppStyle.init(newBase)
        super.attachBaseContext(AppStyle.applyNightOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 外观偏好必须在**建控件之前**加载并刷新亮暗，否则第一帧会用旧风格画一遍。
        // （init 在 attachBaseContext 里已经调过一次，这里是幂等的二道保险。）
        AppStyle.init(this)
        AppStyle.refreshNight(this)
        // 风格表自检：色表缺键 / 色值透明 / 对比度 < 4.5:1 都在启动时立刻炸，
        // 而不是让用户在界面上看到"看不见的字"。
        AppStyle.selfCheck()
        removeLegacyPlaintextToken()
        // 请求体按字节读的自检：中文 body 曾因按字符读而挂死，这里早失败早暴露。
        LocalFakeServer.selfCheck()
        // 区域常量自检：CN 是已实测链路，不能被后续改动弄坏。
        TraeRegion.selfCheck()
        // 多账号索引自检：uid 是主键，序列化错了会串账号。
        TraeAuthStore.selfCheck()
        // 续期判定边界自检：服务现在每个请求都靠它决定要不要续期，判错就是持续 401。
        TraeAuthApi.selfCheck()
        // 国际服 plan_item 累积快照 → 增量的 diff 自检（错则客户端看到内容重复）
        TraeIntlUpstream.selfCheck()
        // 恢复上次选择的区域：不恢复的话重启后会回落 CN，
        // 国际服 token 就会被发到国内服 host（实测 401/1001 或 4001）。
        TraeRegion.load(this)
        // 但**当前账号自带的区域才是权威**：它可能和上次手动选的区域不一致
        // （例：手动选了国服、当前账号却是国际服）→ 必须以账号为准。
        TraeAuthStore.syncRegionToActiveAccount(this)
        // 本地 API Key 稳定性自检：key 一旦"每次取值都变"，所有已配置的客户端全 401。
        Gateway.selfCheck(this)
        // 保活页的 ROM 识别自检：SystemProperties 是隐藏 API（靠反射），读不到也不能崩。
        KeepAlive.selfCheck()

        // 网关由 Gateway（业务）+ GatewayService（保活）持有，**Activity 不再拥有它**：
        // 这样 Activity 被销毁时端口不会跟着断，用户才能"网关开着、切到别的客户端用"。
        // ⚠️ 依然**不自动启动**：默认关闭，必须用户点「启动服务」（见 toggleService）。

        val root = FrameLayout(this).apply {
            setBackgroundColor(color("background"))
            fitsSystemWindows = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // 顶部栏（标题 + 副标题 + 1dp 分隔线）
        // 背景用 `background` 而不是 `surface`：让顶栏"退"到页面底色里、卡片"凸"出来，
        // 层次只由卡片承载（旧版顶栏和卡片同色，整屏看着一样平）。
        // 外层全宽撑背景，内层限宽居中，这样平板/横屏下标题与内容左边缘是对齐的
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color("background"))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PAGE_PAD_H), dp(BAR_PAD_TOP), dp(PAGE_PAD_H), dp(BAR_PAD_BOTTOM))
                // 二级页才出现的返回入口。不靠系统返回键：纯小白不一定知道那个键，
                // 顶栏给一个看得见的出口。放标题**上方**（不是左侧），
                // 这样标题左边缘始终与内容区对齐。
                addView(linkAction("← 返回") { render(Page.MORE) }
                    .also { backAction = it }
                    .apply { visibility = View.GONE })
                addView(TextView(this@MainActivity).apply {
                    textSize = FS_TITLE
                    setTextColor(color("on_surface"))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }.also { pageTitle = it }, top(6))
                addView(TextView(this@MainActivity).apply {
                    textSize = FS_CAPTION.toFloat()
                    setTextColor(color("on_surface_variant"))
                }.also { pageSubtitle = it }, top(4))
            }, LinearLayout.LayoutParams(contentWidth(), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        })
        col.addView(divider())

        // 内容区
        contentHost = FrameLayout(this)
        col.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))

        // 底部导航（5 Tab，由 Page.entries 驱动；选中态 = 居中胶囊指示器 + 主色）
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(color("background"))
        }
        navItems = Page.entries.associate { p ->
            val tv = TextView(this).apply {
                text = p.title
                textSize = FS_CHIP.toFloat()
                gravity = Gravity.CENTER
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, p.icon, 0, 0)
                compoundDrawablePadding = dp(3)
                setPadding(dp(18), dp(6), dp(18), dp(6))
            }
            // 外层撑满 1/5 宽（保证点击区足够大），内层 TextView 包住图标+文字，
            // 选中胶囊因此永远贴合内容：手机竖屏和 3440px 平板横屏都好看。
            navBar.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setOnClickListener { render(p) }
                addView(tv, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(0, dp(64), 1f))
            p to tv
        }
        col.addView(divider())
        col.addView(navBar)

        setContentView(root)
        // 重建时回到原处：切风格/切亮暗（recreate）与系统夜间模式切换都会走到这里，
        // 不恢复的话用户切一下风格就被弹回「概览」，体验很跳。
        val restored = savedInstanceState?.getString(STATE_PAGE)
            ?.let { name -> Page.entries.firstOrNull { it.name == name } } ?: Page.OVERVIEW
        render(restored)
        when (savedInstanceState?.getString(STATE_SUB)) {
            SUB_KEEP_ALIVE -> openKeepAlive()
            SUB_STYLE -> openStylePage()
        }
        loadAndRefreshToken()
    }

    // ------------------------------------------------------------------
    // Tab 路由
    // ------------------------------------------------------------------

    private fun render(page: Page) {
        currentPage = page
        // 点任意底部 Tab 都算"退出二级页"，否则会卡在保活页/外观风格页上出不去。
        showKeepAlive = false
        showStyle = false
        backAction.visibility = View.GONE
        // 页面要重建了，旧的"原地刷新"句柄作废（accountPage 构建时会重新登记）
        quotaApply = null
        pageTitle.text = page.title
        pageSubtitle.text = page.subtitle
        contentHost.removeAllViews()
        contentHost.addView(when (page) {
            Page.OVERVIEW -> overviewPage()
            Page.ACCOUNT -> accountPage()
            Page.MODELS -> modelsPage()
            Page.RECORDS -> recordsPage()
            Page.MORE -> morePage()
        })
        navItems.forEach { (key, item) ->
            val active = key == page
            item.setTextColor(color(if (active) "primary" else "on_surface_variant"))
            item.typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            // 选中态：贴在图标+文字上的胶囊（宽度由内容决定，见 onCreate 的 navBar 组装）
            item.background = if (active) shape("primary_container", RADIUS_PILL) else null
            item.compoundDrawables.filterNotNull().forEach { it.mutate().setTint(color(if (active) "primary" else "on_surface_variant")) }
        }
    }

    /** 打开保活设置二级页（顶栏换成"返回"，Tab 高亮保持在「更多」）。 */
    private fun openKeepAlive() {
        showKeepAlive = true
        pageTitle.text = "保活设置"
        pageSubtitle.text = "让服务在后台长期活着"
        backAction.visibility = View.VISIBLE
        contentHost.removeAllViews()
        contentHost.addView(keepAlivePage())
    }

    /** 打开「外观风格」二级页（顶栏换标题，Tab 高亮保持在「更多」）。 */
    private fun openStylePage() {
        showStyle = true
        pageTitle.text = "外观风格"
        pageSubtitle.text = "配色与形态，随你挑"
        backAction.visibility = View.VISIBLE
        contentHost.removeAllViews()
        contentHost.addView(stylePage())
    }

    /**
     * 按"现在所在的位置"重绘：Tab 页或二级页都还原。
     *
     * ⚠️ 不能拿 `render(currentPage)` 当"刷新界面"用：那会把正停在二级页（保活设置 /
     * 外观风格）的用户踢回 Tab 页。实测：切完风格重建后状态恢复是对的，但 token 加载
     * 线程随后又 `render(currentPage)` 一次，人就被送回「更多」了。
     */
    private fun redraw() {
        when {
            showKeepAlive -> openKeepAlive()
            showStyle -> openStylePage()
            else -> render(currentPage)
        }
    }

    /** 系统返回键：停在任一二级页时先退回「更多」，而不是直接退出 App。 */
    @Deprecated("Activity.onBackPressed 已废弃，但本项目未引 androidx（无 OnBackPressedDispatcher）")
    override fun onBackPressed() {
        if (showKeepAlive || showStyle) render(Page.MORE) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * 记住当前 Tab 与二级页。
     *
     * 两个场景都靠它才不会"跳页"：① 切风格/亮暗走 [recreate]；
     * ② 系统夜间模式切换时本 Activity 会被系统重建（manifest 未声明 configChanges）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, currentPage.name)
        outState.putString(STATE_SUB, when {
            showKeepAlive -> SUB_KEEP_ALIVE
            showStyle -> SUB_STYLE
            else -> ""
        })
    }

    // ------------------------------------------------------------------
    // 概览
    // ------------------------------------------------------------------

    private fun overviewPage(): View = scroll {
        val running = GatewayService.running
        val busy = serviceBusy
        addView(when {
            busy != null -> chipInfo(busy)
            running -> statusChip("服务运行中", "success", true)
            else -> statusChip("服务已停止", "error", false)
        })
        // 服务默认是**关闭**的（见 onCreate：不自动 start）。关闭时先说清"为什么连不上、
        // 该点哪里"，否则用户看到地址却连不上会以为坏了。
        if (!running) {
            addView(card(tone = CardTone.SOFT) {
                addView(text("服务未启动", FS_CARD_TITLE, true))
                addView(caption("本服务默认关闭，需要你手动开启后才会监听本地端口。\n" +
                    "点下面的「启动服务」即可；不开启时客户端连不上，也不会有任何端口在监听。"), top(8))
            }, top(GAP))
        } else {
            addView(card(tone = CardTone.SOFT) {
                addView(text("后台常驻中", FS_CARD_TITLE, true))
                addView(caption("服务跑在前台服务里：退出本 App、划掉最近任务都不会断，" +
                    "你可以切到任意客户端使用。\n停止请点下面的「停止服务」或通知栏的「停止」。"), top(8))
                // 保活体检：国产 ROM 会无视 START_STICKY 直接强杀，能不能长期活着
                // 取决于几项系统授权 —— 用户很难自己想到，所以这里给个醒目入口。
                val pending = KeepAlive.check(this@MainActivity).count { !it.ok }
                if (pending > 0) {
                    addView(caption("⚠️ 还有 $pending 项保活设置未开启，真机上可能被系统清掉"), top(10))
                }
                addView(action(if (pending == 0) "保活设置：全部正常" else "去设置保活（$pending 项）",
                    full = true, outlined = pending == 0) { openKeepAlive() }, top(10))
            }, top(GAP))
        }
        // 服务地址是本页核心信息：强调卡（有底色、无阴影）+ 等宽主色地址 + 一键复制
        addView(card(tone = CardTone.EMPHASIS) {
            addView(caption("服务地址"))
            addView(row(8) {
                addView(mono("http://127.0.0.1:${LocalFakeServer.PORT}", FS_BODY, true, "primary"), weighted())
                // 卡底本身就是 primary_container，按钮底要换成 surface 才看得见
                addView(linkAction("复制", overContainer = true) { copyServerUrl() })
            }, top(6))
            addView(caption("把它作为 base URL 填进任意 OpenAI 兼容客户端"), top(8))
        }, top(GAP))
        addView(action(busy ?: if (running) "停止服务" else "启动服务", full = true, outlined = running || busy != null) { toggleService() }
            .apply {
                // 处理中禁用：不给点第二次，也不让用户以为"没点动"
                if (busy != null) { isEnabled = false; alpha = 0.6f }
            }, top(GAP))
        addView(card { addTraeCard(includeLogin = true) })
        addView(card {
            addView(caption("本地 API Key"))
            addView(row(8) {
                addView(text(if (apiKeyVisible) apiKey else MASKED_KEY, FS_VALUE).apply {
                    setTextIsSelectable(apiKeyVisible)
                }, weighted())
                addView(linkAction(if (apiKeyVisible) "隐藏" else "显示") {
                    apiKeyVisible = !apiKeyVisible
                    render(currentPage)
                }, lp(-2, -2, start = 8))
                addView(linkAction("复制") { copyApiKey() }, lp(-2, -2, start = 8))
            }, top(6))
            addView(caption("供任意 OpenAI 客户端连接鉴权使用"), top(8))
        })
        addView(card(tone = CardTone.SOFT) {
            addView(text("使用引导", FS_CARD_TITLE, true))
            addView(caption("把上面的地址与 API Key 填进任意 OpenAI 兼容客户端。要单独测某个端点，点右边的「复制」："), top(8))
            ENDPOINTS.forEach { (method, path) ->
                addView(row(8) {
                    addView(mono("$method $path", FS_CAPTION, colorName = "on_surface"), weighted())
                    addView(linkAction("复制") { copyEndpoint(path) })
                }, top(8))
            }
        })
        addView(action("登录 / 切换 Trae 账号", full = true) { openLogin() }, top(GAP))
    }

    private fun LinearLayout.addTraeCard(includeLogin: Boolean) {
        val cred = TraeAuthStore.load(this@MainActivity)
        if (cred == null) {
            addView(text("未登录 Trae", FS_CARD_TITLE, true))
            addView(caption("已登录后走真实 Trae 转发；当前返回固定假回复"), top(6))
            if (includeLogin) addView(action("立即登录", full = true) { openLogin() }, top(14))
            return
        }
        val hasToken = !cred.accessToken.isBlank()
        addView(row {
            addView(text(cred.nickname.ifBlank { "Trae 账号" }, FS_CARD_TITLE, true), weighted())
            addView(if (hasToken) statusChip("转发 已启用", "success", true) else statusChip("转发 未配置", "warning", false))
        })
        if (cred.uid.isNotBlank()) addView(caption("UID: ${cred.uid} · ${regionLabelFull(cred.region)}"), top(6))
        if (!cred.region.isSupported) addView(caption(TraeRegion.SG.unsupportedNote), top(4))
        if (cred.expiresAt > 0L) addView(caption("Token 到期: ${formatExp(cred.expiresAt)}"), top(4))
        val accountCount = TraeAuthStore.list(this@MainActivity).size
        if (accountCount > 1) addView(caption("已保存 $accountCount 个账号（在「账号」页切换）"), top(4))
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------

    private fun accountPage(): View = scroll {
        addView(section("Trae 账号"))
        val metas = TraeAuthStore.list(this@MainActivity)
        val activeUid = TraeAuthStore.activeUid(this@MainActivity)
        if (metas.isEmpty()) {
            addView(card(tone = CardTone.SOFT) {
                addView(text("未登录", FS_CARD_TITLE, true))
                addView(caption("登录后使用 Trae 账号进行真实转发"), top(6))
                addView(action("立即登录", full = true) { openLogin() }, top(14))
            })
        } else {
            val cred = TraeAuthStore.load(this@MainActivity)
            // 当前账号详情
            addView(card {
                addView(row {
                    addView(text(cred?.nickname?.ifBlank { null } ?: "Trae 账号", FS_CARD_TITLE, true), weighted())
                    addView(chipOk("当前使用"))
                })
                if (cred != null) {
                    addView(labelValue("UID", cred.uid), top(12))
                    addView(labelValue("区域", regionLabelFull(cred.region)), top(4))
                    if (!cred.region.isSupported) addView(caption(TraeRegion.SG.unsupportedNote), top(6))
                    addView(labelValue("Access Token", "已持久化（加密存储）"), top(4))
                    addView(labelValue("Token 状态", tokenRenewLabel(cred.expiresAt)), top(4))
                    if (cred.expiresAt > 0L) addView(labelValue("到期时间", formatExp(cred.expiresAt)), top(4))
                }
            })
            // 其它账号：点击切换，长按/按钮删除
            val others = metas.filter { it.uid != activeUid }
            if (others.isNotEmpty()) {
                addView(section("其它账号（共 ${metas.size} 个已保存）"))
                others.forEach { m ->
                    addView(card {
                        addView(row {
                            addView(text(m.nickname.ifBlank { m.uid }, FS_BODY, true), weighted())
                            if (m.region.isSupported) {
                                addView(chipInfo(regionLabel(m.region)))
                            } else {
                                addView(chipWarn("${regionLabel(m.region)} · 未开发"))
                            }
                        })
                        addView(caption("UID: ${m.uid} · Token: ${tokenRenewLabel(m.expiresAt)}"), top(6))
                        addView(row(8) {
                            addView(action("切换到此账号", outlined = true) { switchAccount(m.uid) }, weighted())
                            addView(action("删除", outlined = true) { confirmRemoveAccount(m) }, weighted(start = 8))
                        }, top(10))
                    })
                }
            }
        }
        // 登录只有这一条路：**浏览器 + 粘贴回调**。
        // （原内置 WebView 登录窗已移除：它依赖页面 JS 回调，第三方 SDK 一超时就等不到回调，
        //  部分网络下必失败；这条不依赖 WebView/页面 JS，是实测可用路径。）
        addView(card {
            addView(text("登录（浏览器 + 粘贴回调）", FS_CARD_TITLE, true))
            addView(caption(
                "1. 按区域复制登录链接\n" +
                "2. 在电脑或手机浏览器打开并登录\n" +
                "3. 登录后地址栏停在 127.0.0.1:18080 打不开是正常的\n" +
                "4. 复制地址栏那条完整链接，回到这里粘贴"), top(8))
            addView(action("复制国服登录链接", outlined = true) { copyLoginUrl(TraeRegion.CN) }, top(12))
            addView(action("复制国际服登录链接（未开发）", outlined = true) {
                toast(TraeRegion.SG.unsupportedNote)
                copyLoginUrl(TraeRegion.SG)
            }, top(8))
            addView(caption(TraeRegion.SG.unsupportedNote), top(6))
            addView(action("粘贴回调链接完成登录", full = true) { promptPasteCallback() }, top(12))
        })
        addView(section("积分与用量"))
        addView(card {
            val refreshLabel = linkAction(if (quotaLoading) "刷新中…" else "刷新") { refreshQuota() }
            val balanceView = text(quotaValue(accountBalance?.toString()), FS_METRIC, true, "primary")
            val capView = text(quotaValue(accountCap?.toString()), FS_BODY, true)
            val checkinView = text(quotaValue(checkinLabel()), FS_BODY, true)
            // 登记"怎么把最新数据刷进这几个控件"，刷新时直接调用（见 quotaApply 的注释）
            quotaApply = {
                refreshLabel.text = if (quotaLoading) "刷新中…" else "刷新"
                balanceView.text = quotaValue(accountBalance?.toString())
                capView.text = quotaValue(accountCap?.toString())
                checkinView.text = quotaValue(checkinLabel())
            }
            addView(row {
                addView(caption("当前可用积分"), weighted())
                addView(refreshLabel)
            })
            // 主数值放大，一眼能读到；次要指标并排
            addView(balanceView, top(2))
            addView(row(12) {
                addView(metricOf("权益额度(上限)", capView), weighted())
                addView(metricOf("今日签到", checkinView), weighted(start = 12))
            }, top(14))
        })
    }

    private fun checkinLabel(): String? =
        accountCheckin?.let { if (it.first) "已签 · +${it.second}/日" else "未签" }

    /** 积分数值位：刷新中显示「…」，没拿到显示「—」。 */
    private fun quotaValue(v: String?): String = if (quotaLoading) "…" else v ?: "—"

    /** Token 是否需要续期：过期则需重新登录，临近则由后台自动用 refresh 续期。 */
    private fun tokenRenewLabel(expiresAt: Long): String {
        if (expiresAt <= 0L) return "未知（无到期信息）"
        val remainSec = expiresAt - System.currentTimeMillis() / 1000
        return if (remainSec <= 0) "已过期 · 需重新登录"
        else if (remainSec <= SIXTY_MINUTES_SEC) "临近到期 · 将自动续期"
        else "有效"
    }

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    private fun modelsPage(): View = scroll {
        // 走同一个 scroll{} 容器：原先这里自己抄了一遍内边距，改 scroll 时会漏掉它
        val edit = searchInput("搜索模型…")
        addView(edit, lp(-1, -2))

        // 搜不到时的空态。没有它，用户打错一个字就是**整页空白**，会以为 App 坏了。
        val empty = card(tone = CardTone.SOFT) {
            addView(caption("没有匹配的模型。换个关键词，或清空搜索框看看全部。"))
        }.apply { visibility = View.GONE }
        addView(empty)

        // 客户端过滤：改可见性而非重建容器，避免丢失输入框焦点
        val cards = buildModelCards(this)
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val f = edit.text.toString().trim()
                cards.forEach { (key, v) -> v.visibility = if (f.isBlank() || key.contains(f, true)) View.VISIBLE else View.GONE }
                empty.visibility = if (f.isNotBlank() && cards.values.none { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
            }
        })
    }

    /** 用当前内存模型表（固定默认清单）构建卡片；返回 (匹配词→View) 供过滤。 */
    private fun buildModelCards(container: LinearLayout): HashMap<String, View> {
        val cards = HashMap<String, View>()
        for (i in 0 until ModelsRepo.current().length()) {
            val m = ModelsRepo.current().optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            val c = modelCard(m)
            cards[id] = c
            container.addView(c, top(GAP))
        }
        return cards
    }

    private fun modelCard(m: org.json.JSONObject): View = card {
        val id = m.optString("id", "").ifBlank { m.optString("config_name", "") }
        val name = m.optString("name", "").ifBlank { id }
        addView(text(name, FS_CARD_TITLE, true))
        addView(row(4) {
            addView(mono(id, FS_CHIP, colorName = "on_surface_variant").apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, weighted())
            addView(linkAction("复制") { copyModelId(id) })
        })
        addView(caption("context ${m.optInt("context_length", 0)} · trae-solo"), top(4))
    }

    private fun copyModelId(id: String) = copyToClipboard("modelId", id, "已复制模型 ID: $id")

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    private fun recordsPage(): View = scroll {
        val records = UsageStore.all(this@MainActivity)
        if (records.isEmpty()) {
            addView(section("消耗汇总"))
            addView(card(tone = CardTone.SOFT) { addView(caption("暂无记录 —— 发一次聊天请求后这里会显示用量与汇总")) })
            return@scroll
        }
        addView(card {
            addView(text("模型消耗汇总", FS_CARD_TITLE, true))
            val groups = UsageStore.aggregate(this@MainActivity)
            // 条形按**相对最大的那组**画（不是占总量）：模型一多的话每条都只剩一小截，反而看不出差别
            val max = groups.maxOfOrNull { it.total } ?: 0L
            for (g in groups) {
                addView(row(8) {
                    addView(text(g.model.ifBlank { "?" }, FS_VALUE, true), weighted())
                    addView(mono("总 ${g.total} · 输出 ${g.output}", FS_CAPTION, colorName = "on_surface_variant"))
                }, top(12))
                addView(shareBar(g.total, max), top(6))
            }
        })
        addView(section("调用记录（最近 ${records.size} 条）"))
        for (r in records.asReversed()) addView(recordCard(r))
    }

    private fun recordCard(r: UsageStore.UsageRecord): View = card {
        addView(row {
            addView(if (r.ok) statusChip("OK", "success", true) else statusChip("ERR", "error", false))
            addView(text(r.model.ifBlank { "?" }, FS_VALUE), weighted(start = 10))
            // 时间戳用等宽字体，一列扫下来是对齐的
            addView(mono(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.ts)), FS_CHIP,
                colorName = "on_surface_variant"))
        })
        val detail = StringBuilder()
            .append("in ${r.prompt} · out ${r.completion} · 总 ${r.total} · ${r.durationMs}ms")
        if (r.errorType.isNotBlank()) detail.append(" · ").append(r.errorType)
        addView(caption(detail.toString()), top(6))
    }

    // ------------------------------------------------------------------
    // 更多
    // ------------------------------------------------------------------

    private fun morePage(): View = scroll {
        // 外观入口放在最上面：这是"改长相"的地方，也顺带告诉用户当前在用什么风格。
        addView(section("外观"))
        addView(card {
            addView(row {
                addView(text("外观风格", FS_CARD_TITLE, true), weighted())
                addView(chipInfo(AppStyle.current.label))
            })
            addView(caption("当前：${AppStyle.current.label} · ${AppStyle.mode.label}。" +
                "可切换多套配色与形态，亮/暗可单独设置。"), top(8))
            addView(action("打开外观风格", full = true) { openStylePage() }, top(12))
        })
        addView(section("关于"))
        addView(card(tone = CardTone.SOFT) {
            addView(text("应用说明", FS_CARD_TITLE, true))
            addView(caption("本应用针对 Trae 当前协议的个人学习自用适配，不保证长期兼容，不代表官方支持。"), top(8))
            addView(labelValue("版本", versionName), top(12))
        })
        addView(card(tone = CardTone.SOFT) {
            addView(text("致谢", FS_CARD_TITLE, true))
            addView(caption("参考与致谢：\n• traework2api（本项目）\n• WorkBuddy2API-Android（UI 风格与组件写法参考）"), top(8))
        })
        addView(card(tone = CardTone.SOFT) {
            addView(text("版权与免责声明", FS_CARD_TITLE, true))
            addView(caption("仅供个人学习与研究使用。使用本项目与 Trae 账号存在风险，请自行评估并遵守相关服务条款。"), top(8))
        })
        addView(card {
            addView(text("账号区域", FS_CARD_TITLE, true))
            addView(caption("国内版与国际版是两套 host 与登录入口，各自独立登录。"), top(8))
            addView(caption("国际服（未开发）：登录可通，但聊天不支持多轮上下文与外部工具调用；" +
                "且免费额度极少（约 \$1/月）。建议用国服。"), top(8))
            addView(action("登录国际服（Trae SG · 未开发）", outlined = true) { openLoginSg() }, top(10))
        })
        addView(section("保活"))
        addView(card {
            addView(row {
                addView(text("后台保活设置", FS_CARD_TITLE, true), weighted())
                val pending = KeepAlive.check(this@MainActivity).count { !it.ok }
                addView(if (pending == 0) chipOk("已就绪") else chipWarn("$pending 项待开启"))
            })
            addView(caption("让网关在息屏、被系统回收后仍能存活。国产 ROM 会强杀前台服务，" +
                "需要在系统里放开几项权限。"), top(8))
            addView(action("打开保活设置", full = true) { openKeepAlive() }, top(12))
        })
    }

    // ------------------------------------------------------------------
    // 保活设置（二级页）
    // ------------------------------------------------------------------

    /**
     * 保活设置页。
     *
     * 刻意把「能一键的」和「只能自己找的」分开摆：安卓**没有**打开厂商私有页面
     * （自启动 / 省电策略 / 后台弹出）的公开接口，硬装成"一键全搞定"只会让用户
     * 点完以为好了、实际仍被杀。所以能跳的给按钮，跳不了的老实给路径。
     */
    private fun keepAlivePage(): View = scroll {
        val items = KeepAlive.check(this@MainActivity)
        val pending = items.filter { !it.ok }
        val rom = KeepAlive.rom()

        // 顶部总状态（首卡不留上间距，紧跟页面顶部）
        addView(card(topMargin = 0) {
            addView(row {
                addView(text(if (pending.isEmpty()) "保活已就绪" else "还有 ${pending.size} 项待开启",
                    FS_CARD_TITLE, true), weighted())
                addView(if (pending.isEmpty()) chipOk("✓ 全部正常") else chipWarn("⚠ 需要处理"))
            })
            addView(caption(if (pending.isEmpty())
                "网关已能在后台长期运行，切到别的 App、息屏都不会断。"
            else
                "下面标 ⚠ 的项会让服务在真机上被系统清掉，建议逐项开启。"), top(8))
        })

        // 一键配置
        val autoCount = pending.count { it.autoOpenable }
        addView(card {
            addView(text("一键配置", FS_CARD_TITLE, true))
            addView(caption(if (autoCount == 0)
                "能自动跳转的项都已就绪。"
            else
                "有 $autoCount 项可以自动打开系统页面，你点「允许」即可。"), top(8))
            addView(action("开始自动配置${if (autoCount > 0) "（$autoCount 项）" else ""}",
                full = true) { runAutoConfig() }, top(12))
        })

        // 逐项清单
        addView(section("逐项检查"))
        addView(card {
            items.forEachIndexed { i, it ->
                if (i > 0) addView(divider(), top(12))
                addView(row(top = if (i > 0) 12 else 0) {
                    addView(text(if (it.ok) "✓" else "⚠", FS_BODY, true,
                        if (it.ok) "success" else "warning"), lp(-2, -2, end = 8))
                    addView(text(it.title, FS_VALUE, true), weighted())
                    if (!it.ok) addView(if (it.autoOpenable) chipInfo("可一键") else chipWarn("需手动"))
                })
                addView(caption(it.why), top(4))
                if (!it.ok && it.autoOpenable) {
                    addView(action("去开启", outlined = true) { openAutoItem(it.title) }, top(8))
                }
            }
        })

        // 厂商私有路径（系统查不到、跳不了）
        addView(section("本机需要手动确认的项"))
        addView(card {
            addView(text("检测到：${rom.label}", FS_CARD_TITLE, true))
            addView(caption("以下页面系统不开放跳转，需要你按路径自己找一下。"), top(6))
            KeepAlive.romGuide(rom).forEach { line ->
                addView(caption("• $line"), top(6))
            }
            addView(action("复制这些路径", outlined = true) {
                copyToClipboard("keepAliveGuide", KeepAlive.romGuide(rom).joinToString("\n"),
                    "路径已复制，可到系统设置里照着找")
            }, top(12))
        })
    }

    /** 「一键配置」：按顺序跳到**第一个**未就绪且可自动打开的项。 */
    private fun runAutoConfig() {
        val next = KeepAlive.check(this).firstOrNull { !it.ok && it.autoOpenable }
        if (next == null) {
            toast("能自动配置的项都已就绪")
            return
        }
        // 一次只跳一个：连着弹多个系统页面会互相顶掉，用户也来不及看。
        openAutoItem(next.title)
    }

    private fun openAutoItem(title: String) {
        val ok = when (title) {
            "电池优化白名单" -> KeepAlive.openBatteryWhitelist(this)
            "精确闹钟" -> KeepAlive.openExactAlarm(this)
            "通知权限", "通知渠道未被关闭" -> KeepAlive.openAppNotificationSettings(this)
            else -> false
        }
        if (!ok) {
            toast("这个页面在本机打不开，请按下方路径手动设置")
        }
        // 用户可能去系统页面勾选后返回，回来时重绘一次以便显示最新状态。
        contentHost.postDelayed({ if (showKeepAlive) openKeepAlive() }, 800)
    }

    // ------------------------------------------------------------------
    // 外观风格（二级页）
    // ------------------------------------------------------------------

    /**
     * 「外观风格」页：风格 + 亮暗两组，全部用**当前风格实时渲染**（选中项主色描边 + ✓）。
     *
     * 点一下即持久化并重建生效 —— 颜色/圆角/间距散在几十个控件上（还有 chip、矢量图标
     * tint、间距），逐个回刷既长又容易漏；重建则一定是完整、正确的一套。
     */
    private fun stylePage(): View = scroll {
        addView(section("风格"))
        AppStyle.STYLES.forEach { s -> addView(styleOption(s), top(GAP)) }

        addView(section("亮暗模式"))
        addView(card {
            AppStyle.Mode.entries.forEachIndexed { i, m ->
                if (i > 0) addView(divider(), top(12))
                addView(modeOption(m), top(if (i > 0) 12 else 0))
            }
        })
        addView(caption("切换后立即生效，并会记住你的选择；重开 App 仍是这套。"), top(12))
    }

    /** 风格卡：左侧色样 + 名称说明，选中项 = 主色 2dp 描边 + ✓ 标记。 */
    private fun styleOption(s: AppStyle.Style): LinearLayout {
        val selected = s.id == AppStyle.current.id
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(CARD_PAD), dp(14), dp(CARD_PAD), dp(14))
            // 选中用主色加粗描边：不靠颜色单独传达（色盲也能从描边粗细 + ✓ 分辨）
            background = shape("surface", RADIUS_CARD,
                if (selected) "primary" else "outline_variant", if (selected) 2 else 1)
            elevation = dp(AppStyle.current.elevation + (if (selected) 1 else 0)).toFloat()
            addView(styleSwatch(s), lp(dp(48), dp(48)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(s.label, FS_CARD_TITLE, true, if (selected) "primary" else "on_surface"))
                addView(caption(s.note), top(3))
            }, weighted(start = 14))
            if (selected) addView(chipInfo("✓ 使用中"), lp(-2, -2, start = 10))
            minimumHeight = dp(56)   // 触控目标 ≥ 48dp
            setOnClickListener { switchStyle(s) }
        }
    }

    /**
     * 色样：用**该风格自己**的主色与圆角画一个小方块，内部一条 on_primary 短胶囊。
     * 不这么做的话，没被选中的风格只能靠文字去猜它长什么样。
     */
    private fun styleSwatch(s: AppStyle.Style): View = FrameLayout(this).apply {
        val dark = AppStyle.isNight
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(AppStyle.previewColor(s, "primary", dark))
            // 圆角上限 = 方块边长的一半（24dp）：让"方正"与"大圆角"在色样上就分得出来
            cornerRadius = dp(minOf(s.cardRadius, 24f)).toFloat()
            setStroke(dp(1), AppStyle.previewColor(s, "outline_variant", dark))
        }
        addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(AppStyle.previewColor(s, "on_primary", dark))
                cornerRadius = dp(minOf(s.controlRadius, 8f)).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(24), dp(7), Gravity.CENTER))
    }

    /** 亮暗模式选项：整行可点（高 ≥ 52dp），右侧圆形单选标记。 */
    private fun modeOption(m: AppStyle.Mode): LinearLayout {
        val selected = m.id == AppStyle.mode.id
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(m.label, FS_BODY, true, if (selected) "primary" else "on_surface"))
                addView(caption(m.note), top(3))
            }, weighted())
            addView(radioDot(selected), lp(dp(22), dp(22), start = 12))
            setOnClickListener { switchMode(m) }
        }
    }

    /** 单选圆点：选中 = 主色实心 + 内圈 on_primary 小点；未选中 = 描边空心。 */
    private fun radioDot(selected: Boolean): View = FrameLayout(this).apply {
        background = if (selected) shape("primary", RADIUS_PILL) else shape("surface", RADIUS_PILL, "outline")
        if (selected) addView(View(this@MainActivity).apply {
            background = shape("on_primary", RADIUS_PILL)
        }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
    }

    /** 切风格：持久化 → 提示 → 重建。 */
    private fun switchStyle(s: AppStyle.Style) {
        if (s.id == AppStyle.current.id) return
        AppStyle.setStyle(this, s.id)
        toast("已切换为「${s.label}」")
        recreate()
    }

    /** 切亮暗：持久化 → 提示 → 重建（重建会重新走 attachBaseContext 覆写 uiMode）。 */
    private fun switchMode(m: AppStyle.Mode) {
        if (m.id == AppStyle.mode.id) return
        AppStyle.setMode(this, m)
        toast("亮暗模式：${m.label}")
        recreate()
    }

    // ------------------------------------------------------------------
    // 底层数据 / 生命周期（保持原逻辑）
    // ------------------------------------------------------------------

    /**
     * 「去登录」入口：跳到账号页并直接复制国服登录链接。
     *
     * 原先是打开内置 WebView 登录窗（[LoginWebActivity]），**已整体移除**：
     * 那条路依赖页面里的 JS 回调，第三方 SDK 一超时就等不到回调、且部分网络下必失败；
     * 而「浏览器 + 粘贴回调」是不依赖 WebView/页面 JS 的实测可用路径，所以现在只留它。
     */
    private fun openLogin(region: TraeRegion = TraeRegion.current) {
        if (currentPage != Page.ACCOUNT) {
            currentPage = Page.ACCOUNT
            render(Page.ACCOUNT)
        }
        copyLoginUrl(region)
    }

    // ------------------------------------------------------------------
    // 确认弹窗（统一样式）
    // ------------------------------------------------------------------

    /**
     * 统一风格的确认弹窗。
     *
     * **为什么不直接用 `AlertDialog.Builder(this).setTitle(…).setMessage(…)`**：
     * 那个走平台主题，配色来自 `colorAccent`（写死在 `values/colors.xml` 的**靛蓝**），
     * 所以切到苔绿/落日/石墨之后，弹窗还是靛蓝高亮 + 系统灰底，和 App 完全脱节。
     * 这里标题/正文/按钮全部自己画，窗口底也换成当前风格的 `surface` + 圆角。
     *
     * @param danger 破坏性操作（删除），确定键走 `error` 色描边
     * @param content 需要额外控件时传进来（如粘贴回调的输入框）
     */
    private fun confirmDialog(
        title: String,
        message: String,
        okText: String,
        cancelText: String? = "取消",
        danger: Boolean = false,
        content: View? = null,
        onOk: () -> Unit,
    ): AlertDialog {
        var dialog: AlertDialog? = null
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD))
            addView(text(title, FS_CARD_TITLE, true))
            addView(text(message, FS_VALUE, false, "on_surface_variant"), top(8))
            content?.let { addView(it, top(14)) }
            addView(row(8) {
                if (cancelText != null) {
                    addView(action(cancelText, outlined = true) { dialog?.dismiss() }, weighted())
                }
                addView(action(okText, danger = danger) {
                    dialog?.dismiss()
                    onOk()
                }, weighted(start = if (cancelText != null) 8 else 0))
            }, top(18))
        }
        val dlg = AlertDialog.Builder(this).setView(panel).create()
        dialog = dlg
        // 窗口底换成当前风格的 surface + 统一圆角，替掉平台那层灰底
        dlg.setOnShowListener { dlg.window?.setBackgroundDrawable(shape("surface", RADIUS_CARD)) }
        dlg.show()
        return dlg
    }

    /**
     * 国际服（SG）登录：回调流与国内服相同，只是 host/ClientID 不同。
     * 该区域**已冻结为未开发**，所以先弹确认，避免用户白登一个用不上的账号。
     */
    private fun openLoginSg() {
        if (!TraeRegion.SG.canLogin) { toast("国际服常量未配置完整"); return }
        confirmDialog(
            title = "国际服尚未开发",
            message = TraeRegion.SG.unsupportedNote + "\n\n仍然要登录吗？",
            okText = "仍要登录",
            cancelText = "用国服",
        ) { openLogin(TraeRegion.SG) }
    }

    /**
     * 复制指定区域的登录链接，供用户拿到系统浏览器里打开。
     * 同时把 [TraeRegion.current] 切到该区域——粘贴回调时要按同一区域换 token。
     */
    private fun copyLoginUrl(region: TraeRegion) {
        if (!region.canLogin) { toast("${region.id} 区域常量未配置完整"); return }
        TraeRegion.set(this, region)
        val url = TraeAuthApi.buildLoginUrl()
        copyToClipboard("traeLoginUrl", url, "${regionLabel(region)}登录链接已复制，请用系统浏览器打开并登录")
    }

    /**
     * 切到指定账号：改 active uid，并把**区域切成该账号自己的区域**（转发才会打对 host），
     * 然后重新载入 token（必要时续期）并重绘。
     */
    private fun switchAccount(uid: String) {
        val meta = TraeAuthStore.list(this).firstOrNull { it.uid == uid } ?: return
        // 切到**未开发区域**的账号会让转发走进半残链路（无多轮/无外部工具），先确认。
        if (!meta.region.isSupported) {
            confirmDialog(
                title = "该账号属未开发区域",
                message = "${meta.nickname.ifBlank { uid }}（${regionLabel(meta.region)}）\n\n" +
                    TraeRegion.SG.unsupportedNote + "\n\n仍然切换吗？",
                okText = "仍要切换",
                cancelText = "取消",
            ) { doSwitchAccount(meta) }
            return
        }
        doSwitchAccount(meta)
    }

    /** 真正的切号动作（[switchAccount] 确认后调用）。 */
    private fun doSwitchAccount(meta: TraeAuthStore.AccountMeta) {
        TraeAuthStore.setActive(this, meta.uid)
        // 区域以账号为准并持久化（复用同一处逻辑，别在这里再抄一遍）
        TraeAuthStore.syncRegionToActiveAccount(this)
        Toast.makeText(this, "已切换到 ${meta.nickname.ifBlank { meta.uid }}", Toast.LENGTH_SHORT).show()
        loadAndRefreshToken()
    }

    /** 删除账号（二次确认）：连密文一起删，避免残留凭据。 */
    private fun confirmRemoveAccount(meta: TraeAuthStore.AccountMeta) {
        confirmDialog(
            title = "删除账号",
            message = "将删除「${meta.nickname.ifBlank { meta.uid }}」的本地凭据（含加密 token）。\n" +
                "不影响 Trae 服务端的账号本身。",
            okText = "删除",
            danger = true,
        ) {
            TraeAuthStore.remove(this, meta.uid)
            toast("已删除")
            render(currentPage)
        }
    }

    private fun regionLabel(region: TraeRegion): String =
        if (region == TraeRegion.SG) "国际服" else "国服"

    /** 带「未开发」后缀的区域名（用于详情展示，一眼看出该区域能不能用）。 */
    private fun regionLabelFull(region: TraeRegion): String =
        regionLabel(region) + if (region.isSupported) "" else "（未开发）"

    /**
     * 粘贴回调链接完成登录。后台换 token，成功后刷新界面。
     * 注意：回调链接里含 refreshToken（敏感），这里只在内存里传递，不打印、不落盘明文。
     */
    private fun promptPasteCallback() {
        val edit = EditText(this).apply {
            hint = "粘贴登录后跳转的完整链接"
            isSingleLine = false
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(color("on_surface"))
            setHintTextColor(color("on_surface_variant"))
            // 跟全站的输入框同一套外观，别用平台那根下划线
            background = shape("surface_container", RADIUS_CONTROL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        confirmDialog(
            title = "粘贴回调链接",
            message = "用系统浏览器打开登录链接登录后，把地址栏里那串 " +
                "http://127.0.0.1:18080/authorize?... 完整粘进来。",
            okText = "登录",
            content = edit,
        ) {
            val url = edit.text?.toString().orEmpty()
            if (url.isBlank()) { toast("没有粘贴内容，请重新打开本窗口"); return@confirmDialog }
            toast("正在换取 token…")
            thread(name = "trae-paste-login") {
                val result = runCatching { TraeAuthApi.loginWithCallbackUrl(this, url) }
                runOnUiThread {
                    result.onSuccess { cred ->
                        toast("登录成功：${cred.nickname.ifBlank { cred.uid }}")
                        loadAndRefreshToken()
                    }.onFailure { e ->
                        toast("登录失败：${e.message}")
                        android.util.Log.w("TraeLogin", "粘贴登录失败", e)
                    }
                }
            }
        }
    }

    private fun copyApiKey() = copyToClipboard("apiKey", apiKey, "已复制 API Key")

    /** 复制某个端点的完整地址（提示里把完整 URL 也念出来，用户才知道复制到了什么）。 */
    private fun copyEndpoint(path: String) {
        val url = "http://127.0.0.1:${LocalFakeServer.PORT}$path"
        copyToClipboard("endpoint", url, "已复制 $url")
    }

    private fun copyServerUrl() =
        copyToClipboard("serverUrl", "http://127.0.0.1:${LocalFakeServer.PORT}", "已复制服务地址")

    /** 复制到剪贴板 + 提示。原先三处各写一遍 getSystemService/setPrimaryClip，收口到这一处。 */
    private fun copyToClipboard(label: String, value: String, tip: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast(tip)
    }

    /** 拉积分/额度（后台线程，用解密后的真实 token 打 ug 接口），成功后重绘账号页。 */
    private fun refreshQuota() {
        val cred = TraeAuthStore.load(this)
        if (cred == null || cred.accessToken.isBlank()) { toast("未登录 Trae，请先登录"); return }
        if (quotaLoading) return
        // 数值位立刻变「…」：网络往返有几百毫秒，不给反馈用户会以为没点动。
        // 原地改控件而不是 redraw() —— 重建页面会把滚动位置弹回页首，用户就看不见这张卡了。
        quotaLoading = true
        quotaApply?.invoke()
        thread(name = "trae-fetch-quota") {
            val u = TraeUpstream.fetchUsage(cred.accessToken)
            val c = TraeUpstream.fetchCheckin(cred.accessToken)
            runOnUiThread {
                accountBalance = u?.second
                accountCap = u?.first
                accountCheckin = c
                quotaLoading = false
                toast(if (u == null) "未获取到额度" else "已更新")
                quotaApply?.invoke()
            }
        }
    }

    /**
     * 启停本地服务（幂等）。
     *
     * 启动 = 拉起 [GatewayService]（前台服务，负责保活）；停止 = 让它收摊。
     * 状态一律读 [GatewayService.running]，**不本地记**：服务可能被系统回收后由 START_STICKY 重建，
     * 本地字段会和真实情况脱节。
     */
    private fun toggleService() {
        if (serviceBusy != null) return
        val stopping = GatewayService.running
        // 启停是异步的（onStartCommand / onDestroy 都晚于这里的调用），
        // 所以先切到「处理中」，否则这几百毫秒里界面毫无反应，用户会连点。
        serviceBusy = if (stopping) "停止中…" else "启动中…"
        redraw()
        if (stopping) {
            GatewayService.stop(this)
        } else {
            GatewayService.start(this)
            // Android 13+ 通知要运行时授权。被拒也能跑（前台服务照常），
            // 只是通知栏看不到"正在运行" —— 那是保活体验的一部分，所以该问就问。
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
        // 等异步动作落地再重绘，否则会画成旧状态；顺便把结果用 toast 说清楚。
        contentHost.postDelayed({
            serviceBusy = null
            toast(if (stopping) "服务已停止" else "服务已启动")
            redraw()
        }, 500)
    }

    /**
     * 主动续期一次并重绘 UI（登录返回 / 切账号时调用）。
     *
     * 注意：**不往网关推 token 快照**。转发时的凭证由 `Gateway` 的 credProvider 每次自取，
     * 这里推会和它重复、且一旦续期轮换就可能是过期的那个。
     * 调用它的意义是：让"切账号/刚登录"立刻反映到界面，并把续期提前到用户等待之外。
     */
    private fun loadAndRefreshToken() {
        thread(name = "trae-load-token") {
            // 与 credProvider **共用同一把锁**（Gateway.locked）：refresh 是轮换的，并发会互相作废。
            Gateway.locked {
                TraeAuthApi.restartWithValidToken(this, skewSec = SIXTY_MINUTES_SEC)
            }
            runOnUiThread { redraw() }
        }
    }

    override fun onDestroy() {
        // ⚠️ **刻意不在这里停网关**。网关归 GatewayService 管：Activity 销毁（用户划掉最近任务、
        // 系统回收界面）不该把正在服务别的客户端的端口一起掐掉。停止只有两条路 ——
        // 用户在概览页点「停止服务」，或点通知栏的「停止」。
        // （不要补 Gateway.stop()：那会退回"退出界面即断线"，保活就白做了。）
        super.onDestroy()
    }

    /** 阶段2调试遗留的明文 token key，新链路已不读取；删掉避免明文凭据滞留磁盘。 */
    private fun removeLegacyPlaintextToken() {
        runCatching {
            getSharedPreferences("traework2api_prefs", MODE_PRIVATE)
                .edit().remove("trae_token").apply()
        }
    }

    /** unix 秒 → "yyyy-MM-dd HH:mm（剩余 x 天/即将过期）" 人性化显示。 */
    private fun formatExp(epochSec: Long): String {
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val s = tf.format(Date(epochSec * 1000))
        val remainDays = (epochSec - System.currentTimeMillis() / 1000) / 86400.0
        val label = if (remainDays >= 1) "剩 ${"%.1f".format(remainDays)} 天" else "即将过期"
        return "$s（$label）"
    }

    // ------------------------------------------------------------------
    // View 组装 helper（这就是本项目的"组件库"）
    // 改样式只改这一段；圆角/间距/字号只看上面跟随风格的 design token。
    // 颜色**只写语义名**（"primary" / "on_surface" …），色值全在 AppStyle 的风格表里。
    // ------------------------------------------------------------------

    private fun scroll(build: LinearLayout.() -> Unit): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_PAD_H), dp(PAGE_PAD_TOP), dp(PAGE_PAD_H), dp(PAGE_PAD_BOTTOM))
            build()
        }
        // 平板/横屏下把内容限宽居中，否则一行文字会横跨整个 3440px 屏幕
        sv.addView(col, FrameLayout.LayoutParams(contentWidth(), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        return sv
    }

    /** 手机竖屏 = 屏宽（不受影响）；平板/横屏 = CONTENT_MAX_DP 并居中。 */
    private fun contentWidth(): Int = minOf(resources.displayMetrics.widthPixels, dp(CONTENT_MAX_DP))

    /**
     * 卡片层次（三种，用来拉开信息优先级，而不是每张卡都长一样）。
     *
     * - [NORMAL] `surface` + 1dp 描边 + 阴影 = 常规内容卡，"凸起"在页面之上；
     * - [SOFT] `surface_container` 无描边无阴影 = 说明 / 空态，退到后面；
     * - [EMPHASIS] `primary_container` 无描边无阴影 = 本页最该看的那块（如服务地址），最抢眼。
     *
     * 底色不是 `surface` 的卡，里面的正文/说明对比度由 `AppStyle.selfCheck()` 单独钉过。
     */
    private enum class CardTone(val fill: String, val stroke: String?) {
        NORMAL("surface", "outline_variant"),
        SOFT("surface_container", null),
        EMPHASIS("primary_container", null),
    }

    private fun card(topMargin: Int = GAP, tone: CardTone = CardTone.NORMAL, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD))
        background = shape(tone.fill, RADIUS_CARD, tone.stroke)
        // 只有常规卡有阴影：有底色的面板靠颜色分层，再叠阴影会显脏
        elevation = dp(if (tone == CardTone.NORMAL) AppStyle.current.elevation else 0).toFloat()
        build()
        layoutParams = lp(-1, -2, top = topMargin)
    }

    private fun row(top: Int = 0, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
        layoutParams = lp(-1, -2, top = top)
    }

    private fun text(value: String, size: Int = FS_BODY, bold: Boolean = false, colorName: String = "on_surface"): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color(colorName))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    /** 等宽文本：地址、模型 id、时间戳用它，数字和标识符能对齐。 */
    private fun mono(value: String, size: Int = FS_VALUE, bold: Boolean = false, colorName: String = "on_surface"): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color(colorName))
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(0f, 1.12f)
        }

    private fun caption(value: String): TextView = text(value, FS_CAPTION, false, "on_surface_variant")

    /** 区块标题：小号灰字；原先 16sp 深色，和卡片标题（17sp）几乎分不出层级。 */
    private fun section(value: String): TextView = text(value, FS_SECTION, true, "on_surface_variant").apply {
        letterSpacing = 0.04f
        setPadding(0, dp(18), 0, dp(8))
    }

    /** 1dp 分隔线：顶栏/底栏与内容区分层。 */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(color("outline_variant"))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    /**
     * 状态圆点：**形状 + 颜色双编码**（色盲只靠颜色分不出的状态，靠实心/空心也能分）。
     *
     * 实心 = 成立 / 进行中；空心（描边）= 未成立 / 已停止。
     */
    private fun dot(tone: String, on: Boolean, sizeDp: Int = 9): View = View(this).apply {
        background = if (on) shape(tone, RADIUS_PILL) else shape("surface", RADIUS_PILL, tone, 2)
        layoutParams = lp(dp(sizeDp), dp(sizeDp))
    }

    /**
     * 底层胶囊。语义场景请用 chipOk / chipWarn / chipInfo / statusChip，别在这里手配颜色对。
     *
     * 传了 [dotTone] 会在文字左边加一个状态点（[dotOn] 决定实心还是空心）。
     */
    private fun chip(value: String, backgroundName: String, textName: String, dotTone: String? = null, dotOn: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(32)
        setPadding(dp(12), 0, dp(12), 0)
        background = shape(backgroundName, RADIUS_PILL)
        dotTone?.let { addView(dot(it, dotOn), lp(dp(9), dp(9), end = 7)) }
        addView(text(value, FS_CHIP, true, textName))
        layoutParams = lp(-2, -2)   // 显式 wrap：否则在竖直容器里会被拉成整条横幅
    }

    /** 带状态点的语义胶囊：**状态一眼可见**（点 + 字，不只靠颜色）。 */
    private fun statusChip(value: String, tone: String, on: Boolean): LinearLayout =
        chip(value, "${tone}_container", tone, dotTone = tone, dotOn = on)

    private fun chipOk(value: String) = chip(value, "success_container", "success")
    private fun chipWarn(value: String) = chip(value, "warning_container", "warning")
    private fun chipInfo(value: String) = chip(value, "primary_container", "primary")

    /** 键值行：标签列只给最小宽度（不再是死 96dp，长标签能撑开），值可长按复制。 */
    private fun labelValue(label: String, value: String): View = row {
        addView(caption(label).apply { minWidth = dp(96) }, lp(-2, -2))
        addView(text(value, FS_VALUE).apply { setTextIsSelectable(true) }, weighted(start = 12))
    }

    /**
     * 指标块：小标签在上、数值在下，并排展示两个次要指标用。
     *
     * 值控件由调用方给（而不是这里现造）—— 这样"刷新数据"可以**原地改文字**而不重建页面
     * （重建会把滚动位置弹回页首，见 `quotaApply`）。
     */
    private fun metricOf(label: String, valueView: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(caption(label))
        addView(valueView, top(2))
    }

    /**
     * 占比条：整条 = [value] 占 [max] 的比例（用 weight 分两段，不引任何依赖）。
     * 给"模型消耗汇总"用 —— 两列数字扫不出谁吃得多，加条一眼就有结论。
     */
    private fun shareBar(value: Long, max: Long): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape("surface_variant", RADIUS_PILL)
        val ratio = if (max <= 0L) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
        addView(View(this@MainActivity).apply { background = shape("primary", RADIUS_PILL) },
            LinearLayout.LayoutParams(0, dp(6), ratio))
        if (ratio < 1f) addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(6), 1f - ratio))
    }

    /** 搜索框：与卡片同一套白底 + 描边。 */
    private fun searchInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(color("on_surface"))
        setHintTextColor(color("on_surface_variant"))
        background = shape("surface", RADIUS_CARD, "outline_variant")
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /**
     * 小胶囊文字按钮。
     *
     * [overContainer] 用在**强调卡内部**（底色本身就是 `primary_container`）——
     * 那时按钮底要改成 `surface`，否则和卡片同色，会只剩一行字飘在那里。
     */
    private fun linkAction(label: String, overContainer: Boolean = false, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = FS_CAPTION.toFloat()
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color("primary"))
        gravity = Gravity.CENTER
        minHeight = dp(48)   // 触控目标 ≥ 48dp（无障碍最低要求），原来 40dp 偏小
        minWidth = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = shape(if (overContainer) "surface" else "primary_container", RADIUS_PILL)
        setOnClickListener { click() }
    }

    /**
     * 主按钮。
     *
     * [danger] 用于**删除**这类破坏性操作：文字与描边走 `error` 色，一眼看出"这个按钮点了会少东西"。
     * 不给实心红底 —— 色板里没有 `on_error`，浅色的 error 配白字在深色风格下会看不清。
     */
    private fun action(label: String, outlined: Boolean = false, full: Boolean = false, danger: Boolean = false, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(18), 0, dp(18), 0)
        setTextColor(color(if (danger) "error" else if (outlined) "primary" else "on_primary"))
        background = if (danger) shape("surface", RADIUS_CONTROL, "error")
        else if (outlined) shape("surface", RADIUS_CONTROL, "outline")
        else shape("primary", RADIUS_CONTROL)
        setOnClickListener { click() }
        if (full) layoutParams = lp(-1, dp(48))
    }

    /** 所有背景的唯一来源。`strokeDp` 供选中态加粗描边用（如「外观风格」页的选中卡）。 */
    private fun shape(fillName: String, radiusDp: Float, strokeName: String? = null, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color(fillName))
        cornerRadius = dp(radiusDp).toFloat()
        strokeName?.let { setStroke(dp(strokeDp), color(it)) }
    }

    /**
     * 按语义名取色 —— 风格与亮暗在**运行期**决定。
     *
     * ⚠️ 这里**不能**改回 `getColor(R.color.wb_*)`：资源 id 是编译期常量，
     * 只有 `values/` 与 `values-night/` 两份，换不了风格。
     */
    private fun color(name: String) = AppStyle.color(name)
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(width: Int, height: Int, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }
    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(start) }
    private fun top(value: Int) = lp(-1, -2, top = value)
}
