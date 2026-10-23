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
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
 * 颜色一律走 `tw_*` 资源（`values` / `values-night` 各一套），不要写字面量色值。
 */
class MainActivity : Activity() {
    companion object {
        /** 通知权限请求码（Android 13+ 前台服务的常驻通知要用）。 */
        private const val REQ_NOTIF = 1002
        /** SAF：导出账号备份（ACTION_CREATE_DOCUMENT）。 */
        private const val REQ_EXPORT = 1003
        /** SAF：导入账号备份（ACTION_OPEN_DOCUMENT）。 */
        private const val REQ_IMPORT = 1004
        private const val SIXTY_MINUTES_SEC = 3600L
        /** 模型页刷新按钮文案：空状态卡与工具条共用同一份，改一处两边同步。 */
        private const val REFRESH_MODELS_LABEL = "刷新模型目录"

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

        // ---- 版权信息 ----
        // 概览页与更多页各出现一次，**共用这一份常量**：改一处两边同步，
        // 不会出现"概览页写了新邮箱、更多页还是旧的"。文案按「标签 + 值」成对给，
        // 排版交给 `copyrightLine()`，页面里不再拼字符串。
        private const val AUTHOR_NAME = "玖念"
        private const val AUTHOR_EMAIL = "2820184772@qq.com"
        private const val AUTHOR_LABEL = "作者"
        private const val CONTACT_LABEL = "联系邮箱"

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
        MORE("更多", "外观 · 说明 · 保活", R.drawable.ic_more),
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
    private var accountBalance: Long? = null   // 当前可用积分（= total_amount − consumed_amount）
    private var accountCheckin: TraeUpstream.CheckinStatus? = null   // 签到状态（含 enable）
    /** 签到请求进行中（防连点：实测重复 claim 会白发请求）。 */
    private var checkinBusy = false
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

    /**
     * 「积分监测」卡里会被后台监测器改写的控件（状态行）。
     * 与 [quotaApply] 同一套"原地改文字、不重建页面"的做法 —— 监测器每 30 秒回一次，
     * 重建页面会把用户正在看的卡片弹走。
     */
    private var monitorApply: (() -> Unit)? = null

    /** 已提示过的自动换号时间戳，避免同一次换号被重复 toast。 */
    private var seenSwitchAt = 0L

    /** 导出到 SAF 时暂存的 JSON（点了「保存」拿到 Uri 才有地方写）。 */
    private var pendingExportJson: String? = null

    /**
     * 导出完成后附在 toast 后面的说明（有账号读不出 token 时告诉用户少导了几个）。
     * 与 [pendingExportJson] 成对存在、成对清空 —— 收口在 [clearPendingExport]，
     * 别在两个字段上各自手工置 null（迟早漏一个，下一次导出会带着上一轮的说明）。
     */
    private var pendingExportNote: String? = null

    /**
     * 监测器回调引用本身。
     *
     * 留着它的唯一目的是 [onDestroy] 里做**身份比对**：旋转屏幕/窗口重建时新旧 Activity
     * 会短暂共存，旧实例的 onDestroy 晚于新实例的 onCreate 执行时，无条件置 null 会把
     * 新实例刚装上的监听清掉 —— 表现是"转屏后监测数字再也不动"。
     */
    private val monitorListener: (CreditMonitor.Snapshot) -> Unit = { onMonitorSnapshot(it) }

    /**
     * 「刷新模型」完成后**原地更新模型 Tab**（重建卡片 + 更新计数），不整页 render。
     *
     * 与 [quotaApply] 同一个理由：`render()`/`redraw()` 会 `removeAllViews()`，
     * 把搜索框内容与滚动位置一起丢掉（这个坑项目里踩过，见 MEMORY「积分刷新」）。
     * 每次 [modelsPage] 构建时重新登记，离开本页后作废。
     */
    private var modelsRefreshApply: (() -> Unit)? = null

    /** 模型表刷新进行中：按钮置灰 + 文案变「刷新中…」，否则用户点了没反馈会连点。 */
    private var modelsLoading = false

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
        // 批量签到纯计算自检：时刻/日期去重/结果往返判错会让用户白丢积分或多打上游。
        BatchCheckin.selfCheck()
        // 积分监测的换号判据：余额未知（null）被判成 0 会因一次网络抖动把好好的账号切走。
        CreditMonitor.selfCheck()
        // 国际服 plan_item 累积快照 → 增量的 diff 自检（错则客户端看到内容重复）
        TraeIntlUpstream.selfCheck()
        // 上游模型表解析自检：空 config_name 必须跳过、非聊天项必须过滤、name/context 回落规则
        // （解析错会让"刷新模型"把好好的清单覆盖成垃圾）。
        TraeUpstream.selfCheck()
        // 模型表自检：空表绝不允许生效 —— 那会让所有客户端看不到任何模型（本任务最贵的失败面）。
        ModelsRepo.selfCheck()
        // 装入磁盘模型缓存（必须在 selfCheck 之后、任何 /v1/models 请求之前）：
        // 客户端在 App 刚重启、还没手动刷模型时也能拉到上次刷到的清单。
        ModelsRepo.init(this)
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

        // 积分监测回调（监测器在主线程 post）。这里只改文字或整页重绘，
        // 绝不在此做网络/持锁操作 —— 它运行在 UI 线程上。
        CreditMonitor.listener = monitorListener

        // 网关由 Gateway（业务）+ GatewayService（保活）持有，**Activity 不再拥有它**：
        // 这样 Activity 被销毁时端口不会跟着断，用户才能"网关开着、切到别的客户端用"。
        // 0.4.8 起**进入 App 默认启动**（用户要求）：除非用户主动停止过（shouldRun=false），
        // 否则一进 App 就把前台服务拉起来 —— 省掉每次手动点「启动服务」。
        autoStartServiceIfWanted()

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
        // 进 App 静默补新模型表：客户端拉 /v1/models 的数据源重启后来自磁盘缓存，
        // 这里补一次刷新保证新鲜（不弹 toast，失败无感）。
        maybeAutoRefreshModels()
        // 定时签到开着时：闹钟自愈重挂 + 补签（今天没跑过或上轮有失败才真的打上游）。
        maybeCatchUpCheckin()
    }

    /**
     * 进 App 的签到兜底：补签 + 闹钟自愈（逻辑在 [BatchCheckin.maybeCatchUp]，后台线程）。
     *
     * 覆盖的场景：定时到了但进程被杀/手机重启/闹钟被 ROM 拦 —— 用户打开 App 的那一刻
     * 全部补上。跑完后如果在账号页就原地刷新一次，让用户看到"补签完成"。
     */
    private fun maybeCatchUpCheckin() {
        if (!BatchCheckin.isEnabled(this)) return
        thread(name = "checkin-catchup") {
            val ran = runCatching { BatchCheckin.maybeCatchUp(this@MainActivity) }.getOrDefault(false)
            if (ran) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast("定时签到补签完成")
                    // 账号页上每账号结果行是新数据，重绘一次（离开账号页时 render 也会拉到）
                    if (currentPage == Page.ACCOUNT && !showKeepAlive && !showStyle) render(Page.ACCOUNT)
                }
            }
        }
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
        // 页面要重建了，旧的"原地刷新"句柄作废（accountPage / modelsPage 构建时会重新登记）
        quotaApply = null
        monitorApply = null
        modelsRefreshApply = null
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
        // 账号页**自动拉一次**积分/额度：不拉的话三个数值位永远显示「—」，
        // 用户会以为功能坏了（实测就是这个现象），得先手动点「刷新」才有数据。
        // 判据只看 accountCheckin：它是一份拉取里**最后一个**回填的字段，
        // 拿它当"这个账号是否已经拉过"的单一标志（旧写法要求三个都为 null，
        // 只要余额先回填成功就会整体跳过 → 签到状态可能一直是 null，按钮变"未登录"却可点）。
        if (page == Page.ACCOUNT && accountCheckin == null && !quotaLoading) {
            contentHost.post { refreshQuota() }
        }
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
        // 服务现在是**默认开启**的（进 App 自动拉起，0.4.8）。走到这个分支基本只有一种情况：
        // 用户主动停过。文案说清"怎么再开"，别再写"本服务默认关闭"（那已经是过去式）。
        if (!running) {
            addView(card(tone = CardTone.SOFT) {
                addView(text("服务未启动", FS_CARD_TITLE, true))
                addView(caption("你（或通知栏）停止过服务，端口未在监听，客户端暂时连不上。\n" +
                    "点下面的「启动服务」即可重新开启；下次进 App 也会保持关闭，直到你手动启动。"), top(8))
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
        // 服务地址是本页核心信息：强调卡（有底色、无阴影）+ 等宽主色地址 + 一键复制。
        // 两行分别是「本机用」与「局域网其它设备用」——都带 /v1（客户端 Base URL 的格式），
        // 不带 /v1 的裸地址此前最容易踩：复制粘贴进客户端后拼接路径就错了。
        addView(card(tone = CardTone.EMPHASIS) {
            addView(caption("服务地址（本机使用）"))
            addView(row(8) {
                addView(mono("http://127.0.0.1:${LocalFakeServer.PORT}/v1", FS_BODY, true, "primary"), weighted())
                // 卡底本身就是 primary_container，按钮底要换成 surface 才看得见
                addView(linkAction("复制", overContainer = true) { copyServerUrl() })
            }, top(6))
            // 局域网行：拿不到 IP（未连 WiFi、只连了蜂窝）时整行不出现，不显示一个假地址
            lanAddress()?.let { lan ->
                addView(caption("局域网其它设备使用"), top(12))
                addView(row(8) {
                    addView(mono(lan, FS_VALUE, true, "primary"), weighted())
                    addView(linkAction("复制", overContainer = true) { copyLanUrl(lan) })
                }, top(6))
                addView(caption("同一 WiFi 下的电脑/平板/手机等设备，base URL 填这一条；" +
                    "API Key 不变，用下面同一个。"), top(8))
            } ?: addView(caption("（未检测到局域网 IP：连上 WiFi 后此处会显示可直接给其它设备用的地址）"), top(12))
            addView(caption("base URL 填进任意 OpenAI 兼容客户端"), top(8))
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
        addView(copyrightCard())
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
        // 登录状态在这里算一次，供签到文案用（避免在文案函数里重复 KeyStore 解密）
        val loggedIn = TraeAuthStore.activeUid(this@MainActivity) != null
        addView(card {
            // 内边距加宽：linkAction 默认左右各 14dp，"刷新"两字只有 ~54dp 宽，
            // 配 48dp 高就成了一个正圆。让它随文字自适应撑成胶囊（"刷新中…"也不会被截断）。
            val refreshLabel = linkAction(if (quotaLoading) "刷新中…" else "刷新") { refreshQuota() }
                .apply { setPadding(dp(24), 0, dp(24), 0) }
            // 刻意**不用 action()（Button）**：Material Button 的默认 style 自带
            // minHeight=48dp 与 inset 阴影，minimumHeight 压不下去 —— 已签时想把它缩成
            // 一枚 32dp 小 chip 根本做不到（实测仍是 48dp 的砖）。TextView + 自绘背景
            // 高度完全可控，也和项目里其它胶囊（linkAction / chip）同一套做法。
            val checkinAction = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER
                setOnClickListener { claimCheckin() }
            }
            // 未登录时 accountCheckin 为 null，quotaApply 不会被调用，所以这里先按初值同步一次状态
            refreshCheckinButton(checkinAction, loggedIn)
            val balanceView = text(quotaValue(accountBalance?.toString()), FS_METRIC, true, "primary")
            // 奖励说明（补充信息）：按钮已经说了"今日已签到"，这里不再重复状态，只说能领多少
            val rewardView = caption(quotaValue(checkinRewardLabel())).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            // 登记"怎么把最新数据刷进这几个控件"，刷新时直接调用（见 quotaApply 的注释）
            quotaApply = {
                refreshLabel.text = if (quotaLoading) "刷新中…" else "刷新"
                balanceView.text = quotaValue(accountBalance?.toString())
                rewardView.text = quotaValue(checkinRewardLabel())
                refreshCheckinButton(checkinAction, loggedIn)
            }
            addView(row {
                addView(caption("当前可用积分"), weighted())
                addView(refreshLabel, lp(-2, -2))
            })
            // 主数值独占一行：原先它和两个胶囊挤在同一行，行高被 48dp 按钮撑开，数值反被压住
            addView(balanceView, top(2))
            // 签到单独一行：按钮（仅在可签时是主色）+ 右侧奖励说明。
            // 高度留给 refreshCheckinButton 用 minimumHeight 控制（已签时会缩成一枚小 chip），
            // 这里不能写死 dp(48)，否则固定高度会盖掉它的收缩。
            addView(row {
                addView(checkinAction, lp(-2, -2))
                addView(rewardView, lp(-2, -2, start = 12))
            }, top(14))
        })
        addView(section("积分监测 · 自动换号"))
        addView(card {
            var autoLabel: TextView? = null
            var thresholdLabel: TextView? = null
            val statusView = caption(monitorStatusText())

            autoLabel = linkAction(autoSwitchLabel()) {
                val next = !CreditMonitor.isAutoSwitchEnabled(this@MainActivity)
                CreditMonitor.setAutoSwitchEnabled(this@MainActivity, next)
                autoLabel?.text = autoSwitchLabel()
            }
            thresholdLabel = linkAction(thresholdLabelText()) {
                promptThreshold { thresholdLabel?.text = thresholdLabelText() }
            }
            addView(row(top = 2) {
                addView(autoLabel!!, weighted())
                addView(thresholdLabel!!, weighted(start = 8))
            })
            addView(caption(
                "服务运行中每 ${CreditMonitor.TICK_MS / 1000} 秒查一次当前账号的剩余积分；" +
                    "低于阈值就自动切到余额最多的另一个国服账号。"), top(10))
            addView(statusView, top(8))
            // 监测器每 30 秒回调一次，只改这一行文字（重建页面会把滚动位置弹回去）
            monitorApply = { statusView.text = monitorStatusText() }
        })

        addView(section("账号备份"))
        addView(card {
            addView(text("导出 / 导入（JSON）", FS_CARD_TITLE, true))
            addView(caption(
                "导出内容含「明文」access / refresh token，等于账号钥匙：只在本机与自己的设备之间传，" +
                    "别发到公开地方。换设备时在新机导入即可，不用重新登录。\n" +
                    "导出前可以勾选要带走哪几个账号；导入会先列出「新增 / 更新 / 跳过」，勾完再导。\n" +
                    "同一份文件别在多台设备同时用 —— refresh token 是轮换的，后来的会让先前的失效。"), top(6))
            addView(row(top = 12) {
                addView(action("导出为文件", outlined = true) { promptExportAccounts() }, weighted())
                addView(action("从文件导入", outlined = true) { importFromFile() }, weighted(start = 8))
            })
        })
        addView(section("批量签到"))
        addView(batchCheckinCard())
        addView(section("每日定时签到"))
        addView(scheduleCard())
    }

    /**
     * 「批量签到」卡：一键签到全部账号 + 上轮每账号结果。
     *
     * 结果持久化在 [BatchCheckin]（跨进程存活），进账号页总是显示**上一次**批量的事实，
     * 不是当前内存态 —— 所以"今天签没签"的权威判据是每行的状态文案（ok/already），
     * 与单账号卡里的 accountCheckin 各自独立、互不污染。
     */
    private fun batchCheckinCard(): View = card {
        val metas = TraeAuthStore.list(this@MainActivity)
        val supported = metas.count { it.region.isSupported }
        val skipped = metas.size - supported
        val batchBtn = action(if (BatchCheckin.busy.get()) "签到中…" else "一键签到全部账号", full = true) {
            runBatchCheckin()
        }
        if (metas.isEmpty()) {
            addView(caption("还没有账号，先登录一个再说。"))
        } else {
            addView(caption(
                "对全部 ${supported} 个国服账号逐个签到（不限于当前使用的那个）" +
                    (if (skipped > 0) "；另有 $skipped 个国际服账号不支持签到，已跳过。" else "。")))
            addView(batchBtn, top(10))
            // 定时开关在卡片里只给入口级提示，完整设置在下面的定时卡
            if (BatchCheckin.isEnabled(this@MainActivity) && BatchCheckin.lastRunToday(this@MainActivity)) {
                addView(caption("今天已由定时/补签跑过一轮，重复点击也只会补漏（已签的账号自动跳过）。"), top(8))
            }
        }
        // 上轮结果行（无结果时不显示这块，不编造）
        val last = BatchCheckin.lastResults(this@MainActivity)
        if (last != null && last.results.isNotEmpty()) {
            addView(caption("上次批量（${last.date}${if (BatchCheckin.lastRunToday(this@MainActivity)) " · 今天" else ""}）："), top(12))
            last.results.forEach { r ->
                addView(row(6) {
                    addView(text(r.nickname.ifBlank { r.uid }, FS_VALUE, true), weighted())
                    addView(text(BatchCheckin.statusLabel(r), FS_CAPTION, false,
                        if (r.ok || r.status == "already") "success" else "on_surface_variant"))
                })
            }
        }
    }

    /**
     * 「每日定时签到」设置卡。**默认关闭**（用户要求：需要手动开启）。
     *
     * 开启即挂次日 HH:mm 的精确闹钟（独立 receiver，见 [CheckinReceiver]）；
     * 到点没执行成（进程被杀/重启/ROM 拦截）的场景由进 App 补签兜底 ——
     * 文案里说清这条，用户才知道"关机重开后会不会漏签"（不会，打开就补）。
     */
    private fun scheduleCard(): View = card {
        val enabled = BatchCheckin.isEnabled(this@MainActivity)
        val time = BatchCheckin.timeText(this@MainActivity)
        // 开关用 TextView 自绘胶囊（项目无 androidx Switch；且这风格与其它胶囊一致）。
        // 点击 = 翻转开关 + 挂/取消闹钟 + 重绘本卡（重建本页即可，状态都从 BatchCheckin 读）。
        val toggle = TextView(this@MainActivity).apply {
            gravity = Gravity.CENTER
            text = if (enabled) "已开启" else "已关闭"
            textSize = FS_VALUE.toFloat()
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = shape(if (enabled) "success_container" else "surface_variant", RADIUS_PILL)
            setTextColor(color(if (enabled) "success" else "on_surface_variant"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            minimumHeight = dp(32)
            setOnClickListener {
                if (BatchCheckin.isEnabled(this@MainActivity)) {
                    BatchCheckin.disable(this@MainActivity)
                    toast("定时签到已关闭")
                } else {
                    BatchCheckin.enable(this@MainActivity, BatchCheckin.timeText(this@MainActivity))
                    toast("定时签到已开启：每天 ${BatchCheckin.timeText(this@MainActivity)}")
                }
                render(currentPage)
            }
        }
        addView(row {
            addView(text("定时自动签到", FS_VALUE, true), weighted())
            addView(toggle, lp(-2, -2))
        })
        addView(caption(
            "开启后每天 $time 自动为全部国服账号签到。\n" +
            "到点若 App 没在后台（手机重启/被系统清理），下次打开本 App 时会自动补签。\n" +
            "手机重启后闹钟需要重新挂：打开一次本 App 即可恢复。"), top(8))
        // 用户明确要的一句说明：定时签到签的是**全部**账号，不存在"只签某一个"的隐忧
        addView(caption("签到范围：App 里已登录的全部国服账号，逐个都签一遍，不会只签当前使用的那个；" +
            "国际服账号不支持签到，会跳过。"), top(6))
        // 时刻选择：仅开启时显示（关着改时刻没意义，开启时也存了值）
        if (enabled) {
            addView(row(8) {
                addView(caption("触发时刻：$time"), weighted())
                addView(linkAction("修改时刻") { promptPickTime() })
            }, top(4))
        }
    }

    /** 选定时时刻：TimePickerDialog（系统组件，风格差异可接受；本地时区）。 */
    private fun promptPickTime() {
        val cur = BatchCheckin.timeText(this).split(":").map { it.toIntOrNull() ?: 0 }
        val dlg = android.app.TimePickerDialog(this, { _, h, m ->
            val t = String.format(Locale.US, "%02d:%02d", h, m)
            BatchCheckin.setTime(this, t)
            toast("定时时刻已设为 $t")
            render(currentPage)
        }, cur.getOrElse(0) { 8 }, cur.getOrElse(1) { 0 }, true)
        dlg.show()
    }

    /**
     * 「一键签到全部账号」：后台线程跑批量（[BatchCheckin.runAll] 是阻塞调用），
     * 完成后重绘账号页。busy 互斥在 [BatchCheckin] 内部（手动/定时/补签三方共用）。
     */
    private fun runBatchCheckin() {
        if (TraeAuthStore.activeUid(this) == null) { toast("未登录 Trae，请先登录"); return }
        if (!BatchCheckin.busy.compareAndSet(false, true)) { toast("签到正在进行中…"); return }
        toast("开始批量签到…")
        thread(name = "checkin-batch") {
            val result = try {
                // busy 已在主线程占住：这里不再 compareAndSet，只负责释放。
                // 不调 notifyIfScheduled：手动路径用户就在屏幕前，toast 已汇总，再弹通知是噪音。
                BatchCheckin.runAll(this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "批量签到异常: ${e.javaClass.simpleName}: ${e.message}")
                null
            } finally {
                BatchCheckin.busy.set(false)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) { toast("批量签到异常，请重试"); render(currentPage); return@runOnUiThread }
                val okCount = result.results.count { it.ok }
                val alreadyCount = result.results.count { it.status == "already" }
                val failCount = result.results.count { it.status == "fail" }
                toast(when {
                    okCount > 0 -> "批量签到完成：$okCount 成功" +
                        (if (alreadyCount > 0) "、$alreadyCount 已签" else "") +
                        (if (failCount > 0) "、$failCount 失败" else "")
                    alreadyCount > 0 -> "全部账号今日已签到"
                    else -> "批量签到未生效，详见账号页结果"
                })
                if (currentPage == Page.ACCOUNT && !showKeepAlive && !showStyle) render(Page.ACCOUNT)
            }
        }
    }

    /**
     * 「签到」按钮文案：已签/进行中/未开放 时置灰。
     *
     * **必须置灰已签状态**：实测重复 claim 虽不扣积分，但会白发一次请求
     * （上游回 `code=9004`），所以从 UI 上就堵住。
     *
     * `accountCheckin == null` 分两种：真未登录、或已登录但还没拉到状态。
     * 后者若也写「未登录」就是**文案撒谎**（用户明明登录着），故分开措辞。
     * 登录状态由调用方传入（`accountPage()` 已经读过了），这里**不重复解密** KeyStore。
     */
    private fun checkinActionLabel(loggedIn: Boolean): String = when {
        checkinBusy -> "签到中…"
        accountCheckin == null -> if (loggedIn) "加载中…" else "未登录"
        accountCheckin?.checkedIn == true -> "今日已签到"
        accountCheckin?.enable == false -> "未开放"
        else -> "签到"
    }

    /**
     * 同步签到按钮的文案与可用性。
     *
     * ⚠️ 可用性判据用**显式 null 判断**，不用 `x?.f != false`：后者在 `x == null` 时
     * 求值为 null，`null != false` 为 true → 读起来像守卫、实际只在非 null 时生效，
     * 后续改动一旦删掉 `!= null` 兜底就会静默放行。这里把判据收成一条显式 null 检查。
     */
    private fun refreshCheckinButton(btn: android.widget.TextView, loggedIn: Boolean) {
        val st = accountCheckin
        val actionable = !checkinBusy && st != null && !st.checkedIn && st.enable
        val done = st?.checkedIn == true
        btn.text = if (done) "\u2713 " + checkinActionLabel(loggedIn) else checkinActionLabel(loggedIn)
        btn.isEnabled = actionable
        btn.alpha = 1f
        // 三态三种外观，**用颜色和尺寸一起表达语义**，而不是只靠一个 alpha：
        //   可签  = 主色实心按钮（能点，最醒目）
        //   已签  = 缩成 success 小胶囊（是**状态**不是操作，占位小、颜色是"已完成"）
        //   其它  = 浅底灰字（加载中/未登录/未开放，弱化）
        //
        // 原先三种状态都是主色实心、只靠 alpha 0.5 区分，于是**不可点的「今日已签到」
        // 比旁边可点的「刷新」还抢眼**（主次颠倒）。已签还额外占着 48dp 高、5 个字的宽度，
        // 一个纯状态坨在那里像块砖。
        btn.background = when {
            actionable -> shape("primary", RADIUS_CONTROL)
            done -> shape("success_container", RADIUS_PILL)
            else -> shape("surface_variant", RADIUS_PILL)
        }
        btn.setTextColor(color(when {
            actionable -> "on_primary"
            done -> "success"
            else -> "on_surface_variant"
        }))
        // 已签收成一枚小 chip（32dp 高、12sp 粗体）；可签/其它仍是 48dp 主按钮尺寸。
        // 32dp 只用于**不可点**的状态展示 —— 可点时必须 ≥48dp 满足无障碍触控目标下限。
        val h = if (done) 32 else 48
        val pad = if (done) 14 else 18
        btn.minimumHeight = dp(h)
        btn.setPadding(dp(pad), 0, dp(pad), 0)
        btn.textSize = (if (done) FS_CHIP else FS_BODY).toFloat()
        btn.setTypeface(Typeface.DEFAULT, if (done) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * 签到按钮右侧的**奖励说明**。
     *
     * 只讲"能领多少"这类按钮文案装不下的补充信息，**不再重复签到状态** ——
     * 按钮本身已经写着「今日已签到」/「签到」/「未开放」，再来一句「已签」就是同义反复。
     *
     * `accountCheckin == null` 返回 null 让调用方显示「—」（没数据就是没数据，不编）。
     */
    private fun checkinRewardLabel(): String? = accountCheckin?.let {
        if (!it.enable) "该账号未开放签到" else "每日可领 +${it.credits}"
    }

    /** 积分数值位：刷新中显示「…」，没拿到显示「—」。 */
    private fun quotaValue(v: String?): String = if (quotaLoading) "…" else v ?: "—"

    /**
     * 账号身份变化时**清掉按账号缓存**的积分/额度/签到状态。
     *
     * 这三个字段是"当前账号"的快照，跟账号走。不清的话切号/删除/登录新号后，
     * 新账号会**继承上一个账号的签到状态** —— 表现就是"明明没签到却显示今日已签到"
     * （同一个误报的另一个入口，与判据无关，是缓存没失效）。
     *
     * 只清缓存、不主动拉取：调用方在页面重建后会经 [render] 的自动加载逻辑补拉，
     * 保持"一个动作只打一次上游"。
     */
    private fun invalidateAccountQuota() {
        accountBalance = null
        accountCheckin = null
    }

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

        // 刷新按钮 + 计数。计数是"刷新真的起作用了"的证据 —— 否则点了没新增时用户会以为没生效。
        val countView = caption("")
        fun refreshCountLabel() {
            countView.text = "已加载 · ${ModelsRepo.current().length()} 个模型"
        }
        val refreshBtn = action(REFRESH_MODELS_LABEL) { refreshModels() }
        // 刷新中：按钮置灰 + 文案变「刷新中…」（照抄记录页/积分刷新那套，别新造轮子）
        fun refreshButtonLabel() {
            refreshBtn.isEnabled = !modelsLoading
            refreshBtn.text = if (modelsLoading) "刷新中…" else REFRESH_MODELS_LABEL
        }
        // 工具条（计数 + 按钮）：只在"已加载"时显示 —— 空状态下列表还没有，
        // 顶上摆一个计数「已加载 · 0 个模型」纯属添乱，刷新按钮统一放到空状态卡里。
        val toolbar = row(top = GAP) {
            addView(refreshBtn)
            addView(countView, weighted(start = GAP))
        }
        addView(toolbar)

        // 未加载：整页只有一张说明卡 + 刷新按钮（用户要的"空状态"，见 CHANGELOG 0.4.3）
        val notLoaded = card {
            addView(text("模型目录尚未加载", FS_CARD_TITLE, true))
            addView(caption("连接账号后点击「$REFRESH_MODELS_LABEL」，将从上游加载真实可用模型。"), top(8))
            addView(action(REFRESH_MODELS_LABEL, full = true) { refreshModels() }, top(12))
        }
        addView(notLoaded)

        // 搜不到时的空态。没有它，用户打错一个字就是**整页空白**，会以为 App 坏了。
        val empty = card(tone = CardTone.SOFT) {
            addView(caption("没有匹配的模型。换个关键词，或清空搜索框看看全部。"))
        }.apply { visibility = View.GONE }
        addView(empty)

        // 卡片容器：刷新时**只清空并重塞它的子项**，不动搜索框/按钮/空态（原地更新，滚动位置不丢）
        val cardHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(cardHost)

        // 过滤逻辑收成一个函数：刷新重塞后也要重跑一次，否则过滤会作用在已失效的旧 View 上
        var cards = HashMap<String, View>()
        fun applyFilter() {
            val f = edit.text.toString().trim()
            cards.forEach { (key, v) -> v.visibility = if (f.isBlank() || key.contains(f, true)) View.VISIBLE else View.GONE }
            empty.visibility = if (f.isNotBlank() && cards.values.none { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        }
        fun rebuildCards() {
            cardHost.removeAllViews()
            cards = buildModelCards(cardHost)
            applyFilter()
        }
        /** 未加载时藏起搜索框/工具条/列表，只留说明卡；加载后反过来。 */
        fun applyLoadedState() {
            val loaded = ModelsRepo.isDynamic
            notLoaded.visibility = if (loaded) View.GONE else View.VISIBLE
            edit.visibility = if (loaded) View.VISIBLE else View.GONE
            toolbar.visibility = if (loaded) View.VISIBLE else View.GONE
            cardHost.visibility = if (loaded) View.VISIBLE else View.GONE
            if (!loaded) empty.visibility = View.GONE
        }

        // 客户端过滤：改可见性而非重建容器，避免丢失输入框焦点
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })

        // 刷新回调交给 build 完之后的代码用：本页重建后句柄要跟着换，不能留旧的
        modelsRefreshApply = {
            rebuildCards()
            refreshCountLabel()
            refreshButtonLabel()
            applyLoadedState()
        }
        rebuildCards()
        refreshCountLabel()
        refreshButtonLabel()
        applyLoadedState()
    }

    /** 用当前内存模型表构建卡片；返回 (匹配词→View) 供过滤。 */
    private fun buildModelCards(container: LinearLayout): HashMap<String, View> {
        val cards = HashMap<String, View>()
        val arr = ModelsRepo.current()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
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
            addView(text("版权与免责声明", FS_CARD_TITLE, true))
            addView(caption("仅供个人学习与研究使用。使用本项目与 Trae 账号存在风险，请自行评估并遵守相关服务条款。"), top(8))
            // 与概览页同一份常量 + 同一个 [copyrightLine]，两处排版一致
            addView(divider(), top(14))
            addView(copyrightLine(AUTHOR_LABEL, AUTHOR_NAME), top(12))
            addView(copyrightLine(CONTACT_LABEL, AUTHOR_EMAIL, monoValue = true,
                onCopy = { copyEmail() }), top(6))
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
        // 界面若已销毁就不要再重建视图树（用户此时在系统设置页，回来也不会看这个实例）。
        contentHost.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (showKeepAlive) openKeepAlive()
        }, 800)
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
        // **无条件重绘**：原先写成 `if (currentPage != Page.ACCOUNT)`，于是用户已经停在账号页时
        // 点「立即登录」/「复制国服登录链接」什么都不会重建 —— 页面继续显示点之前那份
        // 「未登录 / 加载中…」的快照，刚做完的动作在界面上没有回音。
        currentPage = Page.ACCOUNT
        render(Page.ACCOUNT)
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
        /**
         * 返回 false 时**不关窗、不回调**。用于"一个都没选就别放行"这类前置校验 ——
         * 确定按钮是先 dismiss 再 onOk 的，没有这个守卫就只能关掉窗再弹 toast，体验很差。
         */
        okGuard: (() -> Boolean)? = null,
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
                    if (okGuard?.invoke() == false) return@action
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
        // 切号 = 换了身份：上一个账号的积分/签到状态必须作废，
        // 否则新账号会继承"今日已签到"（这是误报的另一个入口）
        invalidateAccountQuota()
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
            // 删掉的若是当前账号，缓存必须作废（下一个激活账号会不一样）
            invalidateAccountQuota()
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
                        // 新登录的账号可能不是原来那个：缓存作废，否则会显示上一个账号的签到状态
                        invalidateAccountQuota()
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

    /**
     * 本机在局域网里的 IPv4 地址（如 `http://192.168.1.5:8787/v1`）；拿不到返回 null。
     *
     * 为什么不用 `WifiManager`：API 31+ 的 `getConnectionInfo` 需要定位权限（用户还得授权位置），
     * 而 `NetworkInterface` 遍历纯属标准库能力、无权限、无需读系统状态 —— 服务本来就监听
     * 0.0.0.0（见 LocalFakeServer），任何一块网卡的地址都会响应。
     *
     * 筛选：`isUp` + 非回环 + 非 `dummy*`（部分 ROM 的虚拟网卡，不是真能连的接口），
     * 地址取 **IPv4**（`Inet4Address`，Android 会把 IPv6 也列出来）且**可用的**——
     * 即 `siteLocal`（192.168/10/172.16 私网）**或** `linkLocal`（169.254 自分配）。
     *
     * 为什么要放行 linkLocal：DHCP 拿不到地址时手机会自分配 169.254.x.x，**同一链路上的
     * 设备照样能连**（实测过）。只认 siteLocal 会让这种情况整行隐藏，用户以为功能坏了。
     *
     * ⚠️ 多块网卡（WiFi + 热点同开）时取**第一个命中的**，顺序 Java 不保证 —— 两个地址
     * 都能连（服务听 0.0.0.0），但显示的可能是另一个。用户反馈过"设置里是 .6、软件里是 .12"
     * 的情况，就是这个原因；两个都对，绑路由器 DHCP 才是根治。不主动列全部地址：
     * 那是给少数多网卡场景的额外复杂度，用户没要。
     */
    private fun lanAddress(): String? {
        val ip = runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback && it.name?.startsWith("dummy") != true }
                .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress || it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: return null
        // hostAddress 在极少数栈上会带 "%wlan0" 这类 scope 后缀，URL 里必须去掉
        return "http://${ip.substringBefore('%')}:${LocalFakeServer.PORT}/v1"
    }

    private fun copyLanUrl(url: String) = copyToClipboard("lanUrl", url, "已复制局域网地址 $url")

    /** 复制某个端点的完整地址（提示里把完整 URL 也念出来，用户才知道复制到了什么）。 */
    private fun copyEndpoint(path: String) {
        val url = "http://127.0.0.1:${LocalFakeServer.PORT}$path"
        copyToClipboard("endpoint", url, "已复制 $url")
    }

    private fun copyServerUrl() =
        copyToClipboard("serverUrl", "http://127.0.0.1:${LocalFakeServer.PORT}/v1", "已复制本机服务地址")

    /**
     * 版权信息行：`标签 | 值`，标签列与「版本」等 labelValue 统一走 `minWidth 96dp`，
     * 所以「作者 / 联系邮箱」两行的值**左边界严格对齐** —— 这正是"两行字"和
     * "一张排版工整的小表"的区别。邮箱用等宽体：字母数字对齐、`@` 与 `.` 不会被
     * 比例字体挤在一起，读串的概率低一截。
     */
    private fun copyrightLine(label: String, value: String, monoValue: Boolean = false, onCopy: (() -> Unit)? = null): View =
        labelValue(label, value, monoValue) { v -> onCopy?.let { v.setOnClickListener { it() } } }

    /** 概览页底部的版权卡：整页最后一个元素，用 SOFT 退到背景层，不与业务卡抢注意力。
     *  详情小表只在「更多」页排（copyrightLine），这里一行 caption 带过，信息不重复。 */
    private fun copyrightCard(): View = card(tone = CardTone.SOFT) {
        addView(text("版权信息", FS_CARD_TITLE, true))
        addView(caption("$AUTHOR_LABEL：$AUTHOR_NAME · $CONTACT_LABEL：$AUTHOR_EMAIL"), top(8))
    }

    /** 邮箱复制：手打 11 位数字容易错，点一下拿完整的比让用户自己抄靠谱。 */
    private fun copyEmail() = copyToClipboard("authorEmail", AUTHOR_EMAIL, "已复制邮箱 $AUTHOR_EMAIL")

    /** 复制到剪贴板 + 提示。原先三处各写一遍 getSystemService/setPrimaryClip，收口到这一处。 */
    private fun copyToClipboard(label: String, value: String, tip: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast(tip)
    }

    // ------------------------------------------------------------------
    // 积分监测 · 自动换号
    // ------------------------------------------------------------------

    private fun autoSwitchLabel(): String =
        if (CreditMonitor.isAutoSwitchEnabled(this)) "自动换号：开" else "自动换号：关"

    private fun thresholdLabelText(): String = "换号阈值：≤ ${CreditMonitor.threshold(this)}"

    /**
     * 监测卡的状态行。
     *
     * 刻意不把"每 30 秒"写成保证：Doze/省电模式会冻结后台线程，间隔会漂。
     * 显示**上次检查的实际时间**，用户看到的才是事实。
     */
    private fun monitorStatusText(): String {
        val snap = CreditMonitor.currentSnapshot()
            ?: return if (Gateway.isRunning) "等待首次检查…" else "服务未运行，监测暂停"
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snap.atMs))
        val balance = snap.balance?.let { "余 $it" } ?: "余额未取到"
        val sw = CreditMonitor.lastSwitchOrNull()
        val tail = if (sw != null && System.currentTimeMillis() - sw.atMs < 10 * 60_000L) {
            " · 已自动从 ${sw.fromNick} 切到 ${sw.toNick}"
        } else {
            ""
        }
        return "上次检查 $time · $balance$tail"
    }

    /**
     * 监测器回调（主线程）。
     *
     * 自动换号发生时**必须作废账号级缓存**：否则账号页会拿新账号的昵称配旧账号的
     * 余额/签到状态（"新账号继承今日已签到"这个坑项目里踩过，见 doSwitchAccount 注释）。
     */
    private fun onMonitorSnapshot(snapshot: CreditMonitor.Snapshot) {
        if (isFinishing || isDestroyed) return
        val sw = snapshot.switch
        if (sw != null && sw.atMs > seenSwitchAt) {
            seenSwitchAt = sw.atMs
            invalidateAccountQuota()
            toast("积分低于阈值，已自动切到 ${sw.toNick}（余 ${sw.toBalance}）")
            // 账号页要整页重绘：昵称 / UID / 区域都变了，改一行文字覆盖不了
            if (currentPage == Page.ACCOUNT && !showKeepAlive && !showStyle) redraw()
            return
        }
        monitorApply?.invoke()
    }

    /** 阈值输入：任意非负整数（不是固定几档，用户要能填自己的数字）。 */
    private fun promptThreshold(onDone: () -> Unit) {
        val edit = EditText(this).apply {
            hint = "剩余积分 ≤ 这个值就换号"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(CreditMonitor.threshold(this@MainActivity).toString())
            setSelection(text.length)
            setTextColor(color("on_surface"))
            setHintTextColor(color("on_surface_variant"))
            background = shape("surface_container", RADIUS_CONTROL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        confirmDialog(
            title = "换号阈值",
            message = "当前账号剩余积分低于或等于这个数字时，自动切到余额更多的账号。\n" +
                "填 0 = 积分用完才切。",
            okText = "保存",
            content = edit,
        ) {
            val value = edit.text?.toString()?.trim()?.toLongOrNull()
            if (value == null || value < 0) {
                toast("请输入 0 或正整数")
                return@confirmDialog
            }
            CreditMonitor.setThreshold(this, value)
            toast("阈值已设为 $value")
            onDone()
        }
    }

    // ------------------------------------------------------------------
    // 账号备份（JSON）
    // ------------------------------------------------------------------

    /** 导出第一步：**总是**让用户勾选（1 个账号也弹，行为一致比省一次点击重要）。 */
    private fun promptExportAccounts() {
        val metas = TraeAuthStore.list(this)
        if (metas.isEmpty()) {
            toast("还没有账号，先登录一个再导出")
            return
        }
        promptPickAccounts(
            title = "选择要导出的账号",
            message = "勾选要写进这个文件的账号（共 ${metas.size} 个）。\n" +
                "文件里是明文 token，等于账号钥匙，别发到公开地方。",
            items = metas.map { m ->
                PickItem(
                    uid = m.uid,
                    title = m.nickname.ifBlank { m.uid },
                    subtitle = "UID ${m.uid} · ${regionLabelFull(m.region)} · ${expShort(m.expiresAt)}",
                    chipText = regionLabel(m.region),
                    chipBg = if (m.region.isSupported) "primary_container" else "warning_container",
                    chipFg = if (m.region.isSupported) "primary" else "warning",
                )
            },
            okText = "导出",
        ) { uids -> exportToFile(uids) }
    }

    /** 导出到文件。SAF 不需要任何存储权限，也不需要 FileProvider（全程 content:// 单次授权）。 */
    private fun exportToFile(uids: Set<String>) {
        val json = runCatching { TraeAuthStore.exportJson(this, uids) }.getOrElse {
            toast("导出失败：${it.message}")
            return
        }
        // 解不开 token 的账号已被 exportJson 跳过 —— 文件名必须写**真实**条数，
        // 否则"勾 3 个、文件名写 3、文件里只有 2 条"，导入端汇总数对不上，用户会以为文件坏了。
        val n = TraeAuthStore.countAccounts(json)
        if (n == 0) {
            toast("选中的账号都读不出 token，未导出")
            return
        }
        pendingExportJson = json
        pendingExportNote = if (n < uids.size) "（${uids.size - n} 个账号读不出 token，已跳过）" else ""
        val name = "traework2api-${n}accounts-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT) }.onFailure {
            clearPendingExport()
            toast("没有可用的文件选择器")
        }
    }

    /** 暂存的明文一律从这一个出口清 —— 两个字段成对存在，别在别处单独置 null。 */
    private fun clearPendingExport() {
        pendingExportJson = null
        pendingExportNote = null
    }

    private fun importFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // 有的文件管理器把 .json 报成 text/plain，只写 application/json 会选不中
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure { toast("没有可用的文件选择器") }
    }

    /**
     * 导入第一步：先出示"会发生什么"（新增/更新/跳过各几个），再让用户勾。
     *
     * 为什么不选完文件直接导：用户从旧备份恢复时，最想知道的是"导进来会不会把现在能用的号弄坏"。
     * 预览把这件事摊开，而不是导完再告诉他"跳过 3 个"。
     */
    private fun promptImportPreview(text: String) {
        val previews = runCatching { TraeAuthStore.previewImport(this, text) }.getOrElse {
            toast("导入失败：${it.message}")
            return
        }
        val added = previews.count { it.status == TraeAuthStore.ImportStatus.ADD }
        val updated = previews.count { it.status == TraeAuthStore.ImportStatus.UPDATE }
        val skipped = previews.count { it.status == TraeAuthStore.ImportStatus.SKIP }

        if (added + updated == 0) {
            // 全是"跳过"：仍然给出理由，别让用户以为导入失败
            confirmDialog(
                title = "没有需要导入的账号",
                message = "文件里 ${previews.size} 个账号，本地凭据都比它新且没过期，导进来也会被跳过。\n" +
                    "想强制用文件里这份：先在账号页删掉那个号，再导入。",
                okText = "知道了",
                cancelText = null,
            ) {}
            return
        }

        promptPickAccounts(
            title = "确认要导入的账号",
            message = "文件里 ${previews.size} 个：新增 $added，更新 $updated，跳过 $skipped。\n" +
                "「跳过」= 本地这条凭据更新且还没过期 —— 覆盖会把能用的 refresh token 换成旧的。",
            items = previews.map { p ->
                PickItem(
                    uid = p.uid,
                    title = p.nickname.ifBlank { p.uid },
                    subtitle = importRowSubtitle(p),
                    chipText = when (p.status) {
                        TraeAuthStore.ImportStatus.ADD -> "新增"
                        TraeAuthStore.ImportStatus.UPDATE -> "更新"
                        TraeAuthStore.ImportStatus.SKIP -> "跳过"
                    },
                    chipBg = when (p.status) {
                        TraeAuthStore.ImportStatus.ADD -> "success_container"
                        TraeAuthStore.ImportStatus.UPDATE -> "primary_container"
                        TraeAuthStore.ImportStatus.SKIP -> "surface_variant"
                    },
                    chipFg = when (p.status) {
                        TraeAuthStore.ImportStatus.ADD -> "success"
                        TraeAuthStore.ImportStatus.UPDATE -> "primary"
                        TraeAuthStore.ImportStatus.SKIP -> "on_surface_variant"
                    },
                    // 跳过项不给勾：勾了也会被合并规则再判一次跳过，
                    // "明确要求导入却什么都没发生"是最糟的体验。
                    selectable = p.status != TraeAuthStore.ImportStatus.SKIP,
                    checked = p.status != TraeAuthStore.ImportStatus.SKIP,
                )
            },
            okText = "导入",
        ) { uids -> applyImport(text, uids) }
    }

    /** 预览行副标题：三种状态各自说清"为什么"。 */
    private fun importRowSubtitle(p: TraeAuthStore.ImportPreview): String = when (p.status) {
        TraeAuthStore.ImportStatus.ADD -> "新账号 · ${expShort(p.fileExpiresAt)}"
        TraeAuthStore.ImportStatus.UPDATE ->
            "覆盖本地 · ${expShort(p.localExpiresAt)} → ${expShort(p.fileExpiresAt)}"
        TraeAuthStore.ImportStatus.SKIP ->
            "本地凭据更新（${expShort(p.localExpiresAt)}），已跳过"
    }

    /** 紧凑到期时间：`formatExp` 会写"剩 x 天"，在列表一行里放不下。 */
    private fun expShort(epochSec: Long): String =
        if (epochSec <= 0L) "到期未知"
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochSec * 1000)) + " 到期"

    /**
     * 导入：解析 + N 次 KeyStore 加密都是重活，必须离开主线程。
     * 结果原样报"新增 / 更新 / 跳过"三个数 —— 只说"导入成功"用户不知道覆盖了什么。
     */
    private fun applyImport(text: String, uids: Set<String>) {
        toast("正在导入…")
        thread(name = "account-import") {
            val result = runCatching { TraeAuthStore.importJson(this, text, uids) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { report ->
                    // 跳过要解释清楚：用户把旧备份导回同一台机器时全被跳过，不说明会以为失败
                    val note = if (report.skipped > 0) "，跳过 ${report.skipped}（本地凭据更新）" else ""
                    toast("导入完成：新增 ${report.added}，更新 ${report.updated}$note")
                    invalidateAccountQuota()
                    redraw()
                }.onFailure { e ->
                    toast("导入失败：${e.message}")
                }
            }
        }
    }

    /**
     * 系统文件选择器的回执。
     *
     * 用平台的 startActivityForResult 而不是 registerForActivityResult：本项目
     * **没有任何 androidx 依赖**（只有 okhttp），为两个按钮引入 androidx.activity
     * 就得连带 AppCompat 迁移，不值得。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            // 取消 / 异常回执：暂存的明文一律清掉，别挂到下一次导出上
            clearPendingExport()
            return
        }
        val uri = data?.data ?: run {
            // 用户点了返回但没选文件时 resultCode 通常已是 CANCEL；这里兜住个别 ROM 的异常回执
            clearPendingExport()
            toast("没有选中文件")
            return
        }
        when (requestCode) {
            REQ_EXPORT -> {
                // Activity 被系统重建（转屏 / 换风格）时字段会丢 —— 静默什么都不做是最糟的
                val json = pendingExportJson ?: run {
                    toast("导出内容已失效，请重新导出")
                    return
                }
                val note = pendingExportNote.orEmpty()
                clearPendingExport()
                thread(name = "account-export") {
                    val result = runCatching {
                        // "wt" 而不是默认的 "w"：默认模式**不截断**，覆盖一个内容变短的同名文件时
                        // 尾部会残留旧字节，导出的 JSON 直接坏掉 —— 而用户看到的是"导出成功"。
                        contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: throw java.io.IOException("无法打开目标文件")
                    }
                    runOnUiThread {
                        toast(
                            if (result.isSuccess) "已导出到所选位置$note"
                            else "写入失败：${result.exceptionOrNull()?.message}"
                        )
                    }
                }
            }

            REQ_IMPORT -> thread(name = "account-import-read") {
                val result = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)
                        ?: throw java.io.IOException("无法读取该文件")
                }
                runOnUiThread {
                    result.onSuccess { text ->
                        if (text.length > 1_048_576) toast("文件太大，不像是账号备份")
                        else promptImportPreview(text)   // 先给预览，勾完才真正写盘
                    }.onFailure { toast("读取失败：${it.message}") }
                }
            }
        }
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
            // runCatching 同 refreshModels：抛异常会让线程在复位前死掉，`quotaLoading`
            // 卡 true → 按钮永远「刷新中…」且不再响应点击。
            val pair = runCatching {
                TraeUpstream.fetchUsage(cred.accessToken, cred.uid) to
                    TraeUpstream.fetchCheckin(cred.accessToken, cred.uid)
            }.getOrNull()
            val u = pair?.first
            val c = pair?.second
            runOnUiThread {
                if (isFinishing || isDestroyed) { quotaLoading = false; return@runOnUiThread }
                // 拉失败时**保留旧值**（与 claimCheckin 一致）：网络抖一下就把已显示的
                // 积分/签到状态擦回「—」是净损失，用户会以为数据丢了。
                accountBalance = u ?: accountBalance
                accountCheckin = c ?: accountCheckin
                quotaLoading = false
                toast(if (u == null) "未获取到额度" else "已更新")
                quotaApply?.invoke()
            }
        }
    }

    /**
     * 「签到」：领取今日积分。
     *
     * 流程照 reference `scheduler.go:100` 的顺序：**先看状态再决定发不发 claim** ——
     * 已签 / 账号未开放签到时不发请求（实测重复 claim 会白发一次并回 `code=9004`）。
     *
     * ⚠️ **成功判据是"成功码 AND 状态跃迁"的合取**（见 [TraeUpstream.checkinSucceeded]）。
     * 两个单一判据都会被骗，别退回任一个：
     *  - 旧写法 `when (code) { 0 -> "签到成功" }`：`ug()` 在 HTTP 200 + 业务拒绝时
     *    也会返回带 `code` 的 JSON，于是被拒也报成功 —— 这就是"没签到却提示签到成功"。
     *  - 只看状态跃迁：卡片状态陈旧时（显示"未签"、今天其实早已签过），
     *    `code=9004` + 重查已签会被误报成本次成功，而积分并不会变。
     * 合取之后 `code=9004` 正确落到「今日已签到」，不再冒充本次成功。
     *
     * ⚠️ **状态未知时必须先查再决定**：旧守卫写的是 `accountCheckin?.enable == false`，
     * 而 `?.` 在 null 时求值为 null，`null == false` 是 false → **守卫被静默绕过**，
     * 于是"状态未知"也会直接打 claim。这里用 [known] 显式补查一次堵掉。
     *
     * 签到成功后**重查一次**积分：用户最想看的就是"签完变成多少"。
     */
    private fun claimCheckin() {
        val cred = TraeAuthStore.load(this)
        if (cred == null || cred.accessToken.isBlank()) { toast("未登录 Trae，请先登录"); return }
        if (checkinBusy) return
        val before = accountCheckin
        if (before?.checkedIn == true) { toast("今日已签到"); return }
        if (before?.enable == false) { toast("该账号未开放签到"); return }
        checkinBusy = true
        quotaApply?.invoke()          // 按钮立刻变「签到中…」
        thread(name = "trae-checkin") {
            // 整段包 try/finally：`checkinBusy` 只在主线程复位，若中途抛异常（KeyStore 解密、
            // 网络层），线程死掉后标志永远为 true → 签到按钮永久卡在「签到中…」。
            val result = try {
                // 状态未知（before == null）先补查：不能在不知道 enable/checkedIn 的情况下打 claim
                val known = before ?: TraeUpstream.fetchCheckin(cred.accessToken, cred.uid)
                if (known?.checkedIn == true) {
                    Triple(known, null, null as Long?)
                } else if (known?.enable == false) {
                    Triple(known, null, null as Long?)
                } else {
                    val code = TraeUpstream.claimCheckin(cred.accessToken, cred.uid)
                    // 签到后重查积分与状态：拿到最新余额，同时把按钮切成「今日已签到」
                    val u = TraeUpstream.fetchUsage(cred.accessToken, cred.uid)
                    val after = TraeUpstream.fetchCheckin(cred.accessToken, cred.uid)
                    Triple(known, Triple(code, after, u), null)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "签到异常: ${e.javaClass.simpleName}: ${e.message}")
                Triple(before, null, null as Long?)
            }
            val known = result.first
            val tail = result.second
            runOnUiThread {
                checkinBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (tail == null) {
                    accountCheckin = known ?: accountCheckin
                    toast(if (known?.enable == false) "该账号未开放签到" else "今日已签到")
                    quotaApply?.invoke()
                    return@runOnUiThread
                }
                val (code, after, u) = tail
                accountBalance = u ?: accountBalance
                accountCheckin = after ?: accountCheckin
                toast(
                    when {
                        TraeUpstream.checkinSucceeded(known, after, code) -> "签到成功"
                        after?.checkedIn == true -> "今日已签到"
                        code == null -> "签到失败：网络不通，请稍后重试"
                        // 9074 = 上游判"操作太过频繁"。参考 trae-mate（trae_instance.rs:111）：
                        // **多账号共用同一设备 id 领取**就会撞上它。走到这里说明重查后仍未签
                        // （重查已签会被上面 after.checkedIn 分支拦下并报「今日已签到」）。
                        code == 9074 -> "签到未生效：上游判定操作过于频繁（code=9074），请稍后再试"
                        else -> "签到未生效（code=$code），今日未签到"
                    }
                )
                quotaApply?.invoke()
            }
        }
    }

    /**
     * 「刷新模型」：从上游拉真实模型表并**原地更新**模型 Tab。
     *
     * 三条必须守住的行为（任务书 §3.2）：
     *  - **原地更新**，不 `render()`/`redraw()` —— 那会丢搜索框内容与滚动位置；
     *  - 网络在后台线程，回主线程改 UI（项目无协程库，用 `Thread{}` + `runOnUiThread`）；
     *  - **禁止静默失败**：拉不到要 toast 说人话（失败时保持空状态，不伪造清单）。
     *
     * [quiet] = 静默模式（进 App 自动补新用）：不 toast、不改按钮态。
     * 失败在静默模式下是**正常边界**（离线启动很常见）—— 磁盘缓存还在顶着，
     * 客户端拿到的仍是上次刷到的清单，所以只打日志，绝不能打扰用户。
     */
    private fun refreshModels(quiet: Boolean = false) {
        val cred = TraeAuthStore.load(this)
        if (cred == null || cred.accessToken.isBlank()) {
            if (!quiet) toast("未登录 Trae，请先登录")
            return
        }
        if (modelsLoading) return
        modelsLoading = true
        if (!quiet) {
            // 立刻切「刷新中…」：网络往返有几百毫秒，不给反馈用户会连点
            modelsRefreshApply?.invoke()
            toast("正在加载模型目录…")
        }
        thread(name = "trae-refresh-models") {
            // ⚠️ 必须 runCatching：`fetchModels` 走真实 OkHttp + JSON 解析，任何 RuntimeException
            // 都会让线程在 `runOnUiThread` **之前**死掉 → `modelsLoading` 永远为 true →
            // `refreshModels()` 在开头直接 return、按钮永久 disabled + 文案卡在「刷新中…」。
            // 这是"唯一会让用户可见控件永久失效"的失败面，所以失败也要把标志捎回来复位。
            val arr = runCatching { TraeUpstream.fetchModels(cred.accessToken, cred.uid) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) { modelsLoading = false; return@runOnUiThread }
                modelsLoading = false
                val before = ModelsRepo.current().length()
                if (arr == null) {
                    // 拉不到：**保持现有状态**（磁盘缓存继续顶），但必须明确告诉用户
                    if (!quiet) toast("拉取失败，请检查网络后重试")
                } else {
                    ModelsRepo.applyFetched(arr)
                    val after = ModelsRepo.current().length()
                    if (!quiet) toast(if (after > before) "已更新：$before → $after 个模型" else "已是最新（$after 个模型）")
                }
                modelsRefreshApply?.invoke()
            }
        }
    }

    /**
     * 进 App 自动补新：模型 Tab / 客户端 `/v1/models` 的数据源重启后来自磁盘缓存，
     * 可能落后于上游 —— 静默刷一次，让用户无感拿到最新表。
     *
     * 判据**不能**用 `ModelsRepo.isDynamic`（init 装过磁盘缓存后它恒为 true，
     * 有缓存的设备将永远补不到新表），用进程级一次性标志：每个冷启动恰好刷一次，
     * recreate（转屏/切主题）不重刷。
     */
    private var autoModelsTried = false

    private fun maybeAutoRefreshModels() {
        if (autoModelsTried || modelsLoading) return
        autoModelsTried = true
        contentHost.post { refreshModels(quiet = true) }
    }

    /**
     * 进入 App 默认拉起网关服务（0.4.8 起）。
     *
     * 以前默认关闭、必须手动点「启动服务」，用户嫌麻烦 —— 现在改成：只要用户**没有主动停过**，
     * 打开 App 就当他在用客户端、需要端口在听。[GatewayService.shouldRun] 持久化了用户意图：
     * 用户点过「停止服务」或通知栏「停止」后 it 为 false，这里就不拉 ——
     * 「除非用户手动停止」的承诺靠它兑现，不会出现"关了又自己开"。
     * 已经在跑（前台服务被系统重建、屏幕旋转重建 Activity 等）则直接跳过。
     */
    private fun autoStartServiceIfWanted() {
        if (GatewayService.running || Gateway.isRunning) return
        // 启动条件：用户表达过意图且希望运行（shouldRun=true），或从未表达过（全新安装，默认开）。
        // 唯一不启动的情形 = 用户**主动停止过**（shouldRun=false 且意图存在）。
        if (GatewayService.hasUserIntent(this) && !GatewayService.shouldRun(this)) return
        GatewayService.start(this)
        // Android 13+ 通知要运行时授权。被拒也能跑（前台服务照常），
        // 只是通知栏看不到"正在运行" —— 那是保活体验的一部分，所以该问就问。
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
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
        // ⚠️ 复位**不能只挂在这个 postDelayed 上**：它绑在 `contentHost` 这个视图上，
        // 若 500ms 内界面被销毁（返回/划掉），runnable 不再执行 → `serviceBusy` 残留
        // → 概览按钮停在 disabled 的「启动中…」，且 `toggleService()` 因开头守卫拒绝再进入。
        // 所以真正的兜底是 onDestroy 里的复位（见该处），这里只是正常路径。
        contentHost.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
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
        //
        // 但**进行中的 UI 标志必须清掉**：`serviceBusy` 的正常复位挂在 `contentHost.postDelayed`
        // 上，界面在此被销毁时那个 runnable 永不执行，残留值会让下次进来的概览页显示一个
        // 永远禁用、且 `toggleService()` 拒绝再进入的「启动中…」按钮。
        serviceBusy = null
        quotaLoading = false
        modelsLoading = false
        checkinBusy = false
        // 监测器是 object，活得比 Activity 久：不摘监听就是长驻内存泄漏，且回调还会打到
        // 已销毁的界面上。它自己会继续跑（服务还在），只是不再往这里推。
        // 用身份比对而不是无条件置 null：窗口重建时旧实例的 onDestroy 可能晚于新实例的 onCreate。
        if (CreditMonitor.listener === monitorListener) CreditMonitor.listener = null
        monitorApply = null
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

    // ------------------------------------------------------------------
    // 多选弹窗（账号导出 / 导入共用）
    // ------------------------------------------------------------------

    /**
     * 多选弹窗的一行。文案全由调用方给，这里只管"能不能选 / 默认选没选 / 右边那枚标记"。
     *
     * @param selectable false = 只展示不可选（导入时"会跳过"的账号）
     */
    private data class PickItem(
        val uid: String,
        val title: String,
        val subtitle: String,
        val chipText: String? = null,
        val chipBg: String = "primary_container",
        val chipFg: String = "primary",
        val selectable: Boolean = true,
        val checked: Boolean = true,
    )

    /**
     * 自绘勾选框外观。颜色/描边全走 AppStyle，不写字面量色值。
     *
     * 圆角取 `minOf(RADIUS_CONTROL, 6f)`：大圆角风格下也不会被揉成一枚圆点 ——
     * 那会和单选（[radioDot]）视觉混淆。选中态是"主色实心 + ✓"，不靠颜色单独传达。
     */
    private fun paintCheckBox(box: TextView, checked: Boolean) {
        val radius = minOf(RADIUS_CONTROL, 6f)
        box.text = if (checked) "✓" else ""
        box.setTextColor(color(if (checked) "on_primary" else "on_surface_variant"))
        box.background =
            if (checked) shape("primary", radius) else shape("surface", radius, "outline", 2)
    }

    /**
     * 单行：勾选框 + 两行文案 + 右侧标记。整行可点，行高 56dp（触控目标远大于 48dp）。
     *
     * @return (行 View, "把勾选态改成 X"的重绘函数) —— 后半段给"全选/清空"用，
     *   避免它们自己去找每一行的勾选框（找到也未必是同一个实例）。
     */
    private fun pickRow(
        item: PickItem,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): Pair<View, (Boolean) -> Unit> {
        var on = checked
        val box = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = FS_CHIP.toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        paintCheckBox(box, on)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(
                item.title, FS_VALUE, true,
                if (item.selectable) "on_surface" else "on_surface_variant",
            ).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(caption(item.subtitle).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, top(2))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            if (item.selectable) addView(box, lp(dp(22), dp(22), end = 12))
            addView(texts, weighted())
            item.chipText?.let { addView(chip(it, item.chipBg, item.chipFg), lp(-2, -2, start = 10)) }
        }

        // 只在 row 建好之后定义：它要改 row 的 contentDescription（读屏用户靠这句知道选没选上）
        fun applyState(state: Boolean) {
            on = state
            paintCheckBox(box, state)
            // 不可选的行没有勾选框，不能说"已选中/未选中"——读屏用户会以为自己能点
            row.contentDescription = if (!item.selectable) {
                "${item.title}，不可选（${item.chipText ?: "已跳过"}）"
            } else {
                item.title + if (state) "，已选中" else "，未选中"
            }
        }

        applyState(on)
        if (item.selectable) row.setOnClickListener {
            applyState(!on)
            onToggle(on)
        }
        return row to { want -> if (item.selectable) applyState(want) }
    }

    /**
     * 账号多选弹窗（项目没有 androidx，也不为一个弹窗引依赖 → 自绘，见文件头设计 token 约定）。
     *
     * 三条硬约束：
     *  - 列表**必须限高滚动**：确定/取消按钮是内容末尾的最后一个 View，行一多按钮会被顶出屏幕；
     *  - 点一行只重绘**该行 + 计数行**，绝不 render()/redraw() —— 那会重建整页、滚回页首；
     *  - 一个都没选时**不关窗**（[confirmDialog] 的 okGuard），而不是关掉再 toast。
     */
    private fun promptPickAccounts(
        title: String,
        message: String,
        items: List<PickItem>,
        okText: String,
        onConfirm: (Set<String>) -> Unit,
    ) {
        val selected = items.filter { it.selectable && it.checked }.map { it.uid }.toMutableSet()
        val appliers = arrayOfNulls<(Boolean) -> Unit>(items.size)
        val selectableCount = items.count { it.selectable }

        val countLabel = text("", FS_CAPTION, true, "on_surface_variant")
        var allBtn: TextView? = null

        fun refreshCount() {
            countLabel.text = if (selected.isEmpty()) "已选 0 个 · 至少选 1 个" else "已选 ${selected.size} 个"
            countLabel.setTextColor(color(if (selected.isEmpty()) "warning" else "on_surface_variant"))
            allBtn?.text = if (selectableCount > 0 && selected.size == selectableCount) "清空" else "全选"
        }

        allBtn = linkAction("全选") {
            val selectAll = selected.size < selectableCount
            selected.clear()
            if (selectAll) selected += items.filter { it.selectable }.map { it.uid }
            items.forEachIndexed { i, item ->
                appliers[i]?.invoke(item.selectable && item.uid in selected)
            }
            refreshCount()
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, item ->
            val (rowView, apply) = pickRow(item, item.uid in selected) { isOn ->
                if (isOn) selected += item.uid else selected -= item.uid
                refreshCount()
            }
            appliers[i] = apply
            list.addView(rowView, LinearLayout.LayoutParams(-1, -2))
            if (i < items.lastIndex) list.addView(divider())
        }
        refreshCount()

        // 行高固定 56dp，所以列表高度算得准；超出上限就在框内滚。
        // 分隔线要按 dp(1) 算（divider() 用的是 dp(1)）：少算的话最后一行的分隔线会被裁掉。
        val rowsHeight = dp(56) * items.size + dp(1) * (items.size - 1)
        val maxHeight = minOf(dp(280), (resources.displayMetrics.heightPixels * 0.35f).toInt())
        val scroller = ScrollView(this).apply {
            isFillViewport = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(6) {
                addView(countLabel, weighted())
                addView(allBtn, lp(-2, -2))
            })
            addView(scroller, lp(-1, minOf(rowsHeight, maxHeight), top = 6))
        }

        confirmDialog(
            title = title,
            message = message,
            okText = okText,
            content = body,
            okGuard = { selected.isNotEmpty() },
        ) { onConfirm(selected.toSet()) }
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

    /**
     * 键值行：标签列只给最小宽度（不再是死 96dp，长标签能撑开），值可长按复制。
     *
     * [monoValue] 给邮箱这类"标识符型"的值用（见 [copyrightLine]）；
     * [decorate] 让调用方在**不重写这个布局**的前提下附加行为（如整行可点复制）——
     * 比再写一个几乎一样、只多一行 setOnClickListener 的行构建器省事且不会走样。
     */
    private fun labelValue(
        label: String,
        value: String,
        monoValue: Boolean = false,
        decorate: ((TextView) -> Unit)? = null,
    ): View = row {
        addView(caption(label).apply { minWidth = dp(96) }, lp(-2, -2))
        val v = if (monoValue) mono(value, FS_VALUE) else text(value, FS_VALUE)
        v.setTextIsSelectable(true)
        decorate?.invoke(v)
        addView(v, weighted(start = 12))
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
     * ⚠️ 这里**不能**改回 `getColor(R.color.tw_*)`：资源 id 是编译期常量，
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
