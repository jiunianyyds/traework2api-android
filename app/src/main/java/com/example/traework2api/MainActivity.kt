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
import org.json.JSONObject
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.traework2api.ui.AuroraBackground
import com.example.traework2api.ui.CapsuleNavBar
import com.example.traework2api.ui.CountChip
import com.example.traework2api.ui.GlassCard
import com.example.traework2api.ui.ListRow
import com.example.traework2api.ui.Motion
import com.example.traework2api.ui.SegmentedControl
import com.example.traework2api.ui.Switch
import com.example.traework2api.ui.TextAction
import com.example.traework2api.ui.Tokens
import com.example.traework2api.ui.glassCaption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 阶段3：本地"假服务" + Trae 官方登录入口。
 *
 * 单一 Activity + 漂浮玻璃胶囊导航（对齐设计稿 `ui-v2-app/`，七屏：五 Tab + 账号详情/设置）。
 * 结构：AuroraBackground 柔光底 → 顶栏（大标题+副标题）→ 滚动内容 → 底部漂浮胶囊。
 *
 * 视觉规则：全部走 [Tokens]（对应设计稿 tokens.css）+ [AppStyle.color] 语义色，
 * 不写字面量尺寸/色值；玻璃卡/行列表/开关/分段控件用 ui/ 组件库。
 */
class MainActivity : Activity() {
    companion object {
        /** 通知权限请求码（Android 13+ 前台服务的常驻通知要用）。 */
        private const val REQ_NOTIF = 1002
        /** SAF：导出账号备份（ACTION_CREATE_DOCUMENT）。 */
        private const val REQ_EXPORT = 1003
        /** SAF：导入账号备份（ACTION_OPEN_DOCUMENT）。 */
        private const val REQ_IMPORT = 1004

        /**
         * 账号备份文件大小上限。**边界而非可调项**：正常备份是几十个账号的 JSON（几十 KB），
         * 超过 1 MB 基本就是用户选错了文件；不先判大小就直接 readBytes 是一次 OOM 尝试。
         */
        private const val MAX_IMPORT_BYTES = 1_048_576L
        private const val SIXTY_MINUTES_SEC = 3600L
        /** 模型页刷新按钮文案：空状态卡与工具条共用同一份，改一处两边同步。 */
        private const val REFRESH_MODELS_LABEL = "刷新"

        /** 二级页「保活设置」的 onSaveInstanceState 标记值。 */
        private const val SUB_KEEP_ALIVE = "keepalive"
        /** 二级页「账号详情」的标记值（值 = uid）。 */
        private const val SUB_ACCOUNT_DETAIL = "acctdetail"
        /** 二级页「账号设置」的 onSaveInstanceState 标记值。 */
        private const val SUB_ACCOUNT_MORE = "acctmore"
        private const val STATE_PAGE = "page"
        private const val STATE_SUB = "sub"
        private const val STATE_SUB_EXTRA = "subextra"

        /** 本地 API Key 打码占位（概览页默认显示它，避免截屏直接泄露）。 */
        private const val MASKED_KEY = "sk-••••••••••••••••••••••••"

        // ---- 版权信息 ----
        // 概览页与更多页各出现一次，**共用这一份常量**：改一处两边同步。
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

        /**
         * 本次**进程**是否已经静默补过一次模型表。
         * 必须是进程级（companion）：放 Activity 实例上时，切亮暗触发的 recreate()
         * 会重置它、再刷一次上游，与"每个冷启动恰好一次"的语义不符。
         */
        @Volatile
        private var autoModelsTried = false
    }

    private lateinit var contentHost: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView
    /** 二级页的返回箭头：只在 [subPage] 非空时出现（一级页没有"上一级"）。 */
    private lateinit var backBtn: TextView
    private lateinit var navBar: CapsuleNavBar
    private var currentPage = Page.OVERVIEW
    /**
     * 是否正停在二级页（保活设置 / 账号详情 / 账号设置）。
     *
     * 做成二级页而不是第 6 个 Tab：底部导航 5 项已满；且这些都是"设置一次就不管"或
     * "从列表点进去"的低频页面，不值得占一个常驻 Tab。
     */
    private var subPage: String? = null
    /** 账号详情二级页当前展示的账号 uid（[SUB_ACCOUNT_DETAIL] 配套）。 */
    private var detailUid: String? = null
    private val apiKey: String by lazy { Gateway.apiKey(this) }
    /** 账号页积分/额度缓存（null=未拉到），展示用，不存凭据。 */
    private var accountBalance: Long? = null   // 当前可用积分（= total_amount − consumed_amount）
    private var accountCheckin: TraeUpstream.CheckinStatus? = null   // 签到状态（含 enable）
    /** 签到请求进行中（防连点：实测重复 claim 会白发请求）。 */
    private var checkinBusy = false
    /** 本地 API Key 明文默认打码（防止截图/旁观泄露），点「显示」才展开。 */
    private var apiKeyVisible = false
    /** 当前可见的轻提示控件与其自动隐藏任务（同一时刻只留一条，新的顶掉旧的）。 */
    private var toastView: View? = null
    private var toastHide: Runnable? = null
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
     * 「刷新模型」完成后**原地更新模型 Tab**（重建列表 + 更新计数），不整页 render。
     *
     * 与 [quotaApply] 同一个理由：`render()` 会 `removeAllViews()`，
     * 把搜索框内容与滚动位置一起丢掉（这个坑项目里踩过，见 MEMORY「积分刷新」）。
     * 每次 [modelsPage] 构建时重新登记，离开本页后作废。
     */
    private var modelsRefreshApply: (() -> Unit)? = null

    /**
     * 模型刷新**成功**时的列表淡入（只动 listCard）。
     *
     * 与 [modelsRefreshApply] 分开：后者成功失败都会调用（要把按钮从「刷新中…」恢复），
     * 而"拉到了新表"才该有内容提示 —— 失败还闪一下等于假装更新了。
     */
    private var modelsListPulse: (() -> Unit)? = null

    /** 模型表刷新进行中：按钮置灰 + 文案变「刷新中…」，否则用户点了没反馈会连点。 */
    private var modelsLoading = false

    /** 账号列表「刷新积分」进行中（与 [BatchCheckin.busy] 互斥，二者都是整批打上游）。 */
    private val quotaBulkBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 工具栏那枚「刷新积分」文字动作（刷完/进行中改文案用，参照 [refreshLabelRef] 的写法）。 */
    private var quotaBulkLabel: TextAction? = null

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
        // 悬浮球：App 在运行就该有球（服务停着显示红点）。
        // 服务启动成功时 GatewayService 会自己 show；这里补的是"服务没启动 / 被划掉后重新进入"
        // 那条路径。已显示时 show 内部只 applyState，不会重复 addView；没权限时静默跳过。
        if (!FloatingTag.isShowing()) runCatching { FloatingTag.show(this) }
        // 把 CreditMonitor 的磁盘余额快照装进内存：网关这次没起来/还没起时，
        // 账号列表也要能显示上次查到的积分。幂等，重复调无害。
        runCatching { CreditMonitor.prime(this) }

        // 外壳：柔光底铺满整屏（对应设计稿 body 的 radial-gradient），全部层叠在它上面。
        val root = FrameLayout(this).apply { fitsSystemWindows = true }
        root.addView(AuroraBackground(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // 顶部栏：大标题 + 副标题（tokens.css .topbar；无分隔线，层次由玻璃卡承担）。
        // 外层全宽撑背景，内层限宽居中 —— 平板/横屏下标题与内容左边缘对齐。
        // 二级页在最左侧多一个返回箭头（此前只有系统返回手势，页面上没有任何返回入口）。
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Tokens.PAGE_PAD_H), dp(Tokens.PAGE_PAD_TOP + 10), dp(Tokens.PAGE_PAD_H), dp(10))
                addView(TextView(this@MainActivity).apply {
                    text = "‹"
                    textSize = 30f
                    setTextColor(color("on_surface"))
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    // 触摸区必须自己撑开：「‹」只有一个字符宽，靠文字宽度当点击区点不中
                    minWidth = dp(44)
                    minHeight = dp(44)
                    setPadding(0, 0, dp(10), 0)
                    visibility = View.GONE
                    contentDescription = "返回"
                    setOnClickListener { backFromSub() }
                    Motion.installPressFeedback(this)
                }.also { backBtn = it })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        textSize = Tokens.TEXT_XL
                        setTextColor(color("on_surface"))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        includeFontPadding = false
                    }.also { pageTitle = it })
                    addView(TextView(this@MainActivity).apply {
                        textSize = Tokens.TEXT_XS
                        setTextColor(color("on_surface_variant"))
                        includeFontPadding = false
                    }.also { pageSubtitle = it }.let { tv ->
                        tv.setPadding(0, dp(3), 0, 0); tv
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(contentWidth(), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        })

        // 内容区
        contentHost = FrameLayout(this)
        col.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))

        // 底部漂浮玻璃胶囊（tokens.css .tabbar）：悬浮在内容之上，不占布局高度。
        // 内容列底部留出胶囊高度 + 距底间隙 + 余量，滚到底时最后一张卡不被胶囊压住。
        navBar = CapsuleNavBar(this, Page.entries.map { CapsuleNavBar.Item(it.title, it.icon) }) { idx ->
            val target = Page.entries[idx]
            // 已经在目标一级页（且不在二级页）：不重建、不重播 —— 旧版点当前 Tab 会整页
            // 重建一次并再播一遍入场，看起来像"点了没反应，但页面自己闪了一下"。
            if (target != currentPage || subPage != null) render(target, animate = true)
        }
        val navLp = FrameLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(Tokens.NAV_INSET_H * 2), dp(Tokens.NAV_MAX_WIDTH)),
            dp(Tokens.NAV_HEIGHT)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(Tokens.NAV_BOTTOM_MARGIN)
        }
        root.addView(navBar, navLp)

        setContentView(root)
        // 重建时回到原处：切亮暗（recreate）与系统夜间模式切换都会走到这里，
        // 不恢复的话用户切一下主题就被弹回「概览」，体验很跳。
        val restored = savedInstanceState?.getString(STATE_PAGE)
            ?.let { name -> Page.entries.firstOrNull { it.name == name } } ?: Page.OVERVIEW
        render(restored)
        when (val sub = savedInstanceState?.getString(STATE_SUB)) {
            SUB_KEEP_ALIVE -> openKeepAlive()
            SUB_ACCOUNT_MORE -> openAccountMore()
            SUB_ACCOUNT_DETAIL -> {
                detailUid = savedInstanceState?.getString(STATE_SUB_EXTRA)
                openAccountDetail(detailUid)
            }
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
                    if (currentPage == Page.ACCOUNT && subPage == null) render(Page.ACCOUNT)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Tab 路由
    // ------------------------------------------------------------------

    /** Page 的标题与副标题（对齐设计稿各屏 .topbar）。 */
    private enum class Page(val title: String, val subtitle: String, val icon: Int) {
        OVERVIEW("概览", "本地服务运行状态", R.drawable.ic_dashboard),
        ACCOUNT("账号", "", R.drawable.ic_account),
        MODELS("模型", "本地可用的模型表", R.drawable.ic_models),
        RECORDS("记录", "调用用量与记录", R.drawable.ic_history),
        MORE("更多", "外观 · 保活 · 说明", R.drawable.ic_more),
    }

    /**
     * @param animate 只有**真实导航**传 true（点底部 Tab、进二级页、二级页返回）。
     *   签到完成 / 切号成功 / 服务启停 / Token 续期回写 / 主题 recreate 恢复 / [redraw] /
     *   [renderKeepingScroll] 一律走默认 false —— 那些是"同页数据变了"，不是"换了一页"，
     *   整页淡入上浮只会让用户以为 App 在跳页。
     */
    private fun render(page: Page, animate: Boolean = false) {
        currentPage = page
        // 点任意底部 Tab 都算"退出二级页"，否则会卡在二级页上出不去。
        subPage = null
        detailUid = null
        if (::backBtn.isInitialized) backBtn.visibility = View.GONE
        // 页面要重建了，旧的"原地刷新"句柄作废（各页构建时会重新登记）
        quotaApply = null
        monitorApply = null
        modelsRefreshApply = null
        modelsListPulse = null
        pageTitle.text = page.title
        // 账号页副标题是动态的（N 个已保存 · 当前 X）
        pageSubtitle.text = if (page == Page.ACCOUNT) accountSubtitle() else page.subtitle
        val newPage = when (page) {
            Page.OVERVIEW -> overviewPage()
            Page.ACCOUNT -> accountPage()
            Page.MODELS -> modelsPage()
            Page.RECORDS -> recordsPage()
            Page.MORE -> morePage()
        }
        // 切页过渡：新内容淡入 + 从下方 6dp 滑到原位（克制，200ms，不喧宾夺主）。
        // 先挂新页、再摘旧页 —— 顺序反了会露一帧空白（旧版就是那样）。
        swapContent(newPage, animate)
        // 账号页**自动拉一次**积分/额度：不拉的话三个数值位永远显示「—」，
        // 用户会以为功能坏了（实测就是这个现象），得先手动点「刷新」才有数据。
        // 判据只看 accountCheckin：它是一份拉取里**最后一个**回填的字段，
        // 拿它当"这个账号是否已经拉过"的单一标志。
        // 概览页与账号页都显示积分/签到卡，两页都要自动拉取（真机实测）。
        if ((page == Page.ACCOUNT || page == Page.OVERVIEW) && accountCheckin == null && !quotaLoading) {
            contentHost.post { refreshQuota(quiet = true) }
        }
        // 索引相同时不重设：否则会把刚刚点击启动的指示器位移动画当场掐掉（尺寸变化由
        // onSizeChanged 兜底）。
        val navIndex = Page.entries.indexOf(page)
        if (navBar.current() != navIndex) navBar.select(navIndex, animate = false)
        // 局部操作（置顶/重命名/移动/删除/切换）后重建页面时恢复滚动位置，
        // 否则用户每点一次菜单就被弹回顶部，看不到刚改的那一行。
        if (pendingScrollY >= 0) {
            val y = pendingScrollY
            pendingScrollY = -1
            contentHost.post {
                (contentHost.getChildAt(0) as? ScrollView)?.scrollTo(0, y)
            }
        }
    }

    /** 下次 render 后要恢复的滚动位置（-1 = 不恢复）。 */
    private var pendingScrollY = -1

    /** 当前内容区滚动位置（页面不是 ScrollView 时为 0）。 */
    private fun currentScrollY(): Int =
        (contentHost.getChildAt(0) as? ScrollView)?.scrollY ?: 0

    /** 重建当前页但**保持滚动位置**（用于账号页的局部操作刷新）。 */
    private fun renderKeepingScroll(page: Page) {
        pendingScrollY = currentScrollY()
        render(page)
    }

    /** 账号页副标题：「共 N 个 · 当前 X」；未登录给「还没有账号」。 */
    private fun accountSubtitle(): String {
        val s = TraeAuthStore.snapshot(this)
        if (s.metas.isEmpty()) return "还没有账号"
        val cur = s.byUid(s.activeUid)
        val curLabel = cur?.nickname?.ifBlank { null } ?: cur?.uid
        return if (curLabel == null) "共 ${s.metas.size} 个 · 未选定"
        else "共 ${s.metas.size} 个 · 当前 $curLabel"
    }

    /** 导航代次：每次换页自增，过期的那次 post 直接放弃（快速连切只认最后一页）。 */
    private var pageGeneration = 0

    /**
     * 内容区换页：**先挂新页、再摘旧页**，可选 6dp / 200ms 的淡入上浮。
     *
     * 顺序是硬要求：旧版先 `removeAllViews()` 再 `addView()`，中间会露一帧空白底
     * （Aurora 柔光底），看起来就是"切页闪一下"。现在旧页在新页挂上去之后再摘，
     * 帧里至少总有一页内容。
     *
     * 只对**新页自己**做 alpha/translationY：容器 contentHost 的属性一旦被动过，
     * 后续 layout / 滚动恢复都会拿着一个中间值。
     *
     * 系统关掉动画时直接落终态：不设 alpha=0、不排 post，避免"页面永远半透明"。
     */
    private fun swapContent(newPage: View, animate: Boolean) {
        pageGeneration++
        val gen = pageGeneration
        contentHost.animate().cancel()
        val play = animate && Motion.animationsEnabled(this)
        if (play) {
            newPage.alpha = 0f
            newPage.translationY = dp(6).toFloat()
        } else {
            newPage.alpha = 1f
            newPage.translationY = 0f
        }
        contentHost.addView(newPage)
        // 摘掉除新页以外的所有子 View（旧页、以及任何历史残页）
        while (contentHost.childCount > 1) contentHost.removeViewAt(0)
        if (!play) return
        animatePageEnter(newPage, gen)
    }

    /**
     * 切页/进二级页的入场：整页淡入 + 6dp 上浮，200ms。
     *
     * `post` 到下一帧再启动：`addView` 后立刻 animate 时 view 还没 measure，
     * 位移起点会被首帧 layout 覆盖（真机复现"试不出来"）。post 里先比对代次，
     * 过期任务直接退出 —— 快速连切时不能让两段动画抢同一页。
     */
    private fun animatePageEnter(page: View, gen: Int) {
        page.post {
            if (gen != pageGeneration || !page.isAttachedToWindow) return@post
            contentHost.animate().cancel()
            page.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(Motion.ease())
                .start()
        }
    }

    /**
     * 二级页的公共入口：顶栏加返回、副标题固定、页高亮保持在宿主 Tab。
     *
     * @param animate 真实入口（设置卡片、列表、"去设置保活"）传 true；
     *   [redraw] / 系统返回 / 状态恢复走默认 false。
     */
    private fun openSub(
        title: String,
        subtitle: String,
        key: String,
        animate: Boolean = false,
        build: () -> View,
    ) {
        subPage = key
        quotaApply = null
        monitorApply = null
        modelsRefreshApply = null
        modelsListPulse = null
        pageTitle.text = title
        pageSubtitle.text = subtitle
        if (::backBtn.isInitialized) backBtn.visibility = View.VISIBLE
        swapContent(build(), animate)
        val navIndex = Page.entries.indexOf(currentPage)
        if (navBar.current() != navIndex) navBar.select(navIndex, animate = false)
    }

    /** 点顶栏返回箭头：退回宿主页。与系统返回键同一条路径（[onBackPressed]）。 */
    private fun backFromSub() {
        if (subPage != null) render(currentPage, animate = true) else onBackPressed()
    }

    /** 打开保活设置二级页（Tab 高亮保持在「更多」）。 */
    private fun openKeepAlive(animate: Boolean = false) {
        currentPage = Page.MORE
        openSub("保活设置", "让服务在后台长期活着", SUB_KEEP_ALIVE, animate) { keepAlivePage() }
    }

    /** 打开「账号设置」二级页（Tab 高亮保持在「账号」）。 */
    private fun openAccountMore(animate: Boolean = false) {
        currentPage = Page.ACCOUNT
        openSub("账号设置", "添加 · 监测 · 备份", SUB_ACCOUNT_MORE, animate) { accountMorePage() }
    }

    /** 打开「账号详情」二级页；uid 无效时回落账号页。 */
    private fun openAccountDetail(uid: String?, animate: Boolean = false) {
        currentPage = Page.ACCOUNT
        val meta = TraeAuthStore.list(this).firstOrNull { it.uid == (uid ?: detailUid) }
        if (meta == null) { render(Page.ACCOUNT); return }
        detailUid = meta.uid
        val nick = meta.nickname.ifBlank { meta.uid }
        openSub(nick, "${regionLabel(meta.region)} · UID ${meta.uid}", SUB_ACCOUNT_DETAIL, animate) {
            accountDetailPage(meta)
        }
    }

    /**
     * 按"现在所在的位置"重绘：Tab 页或二级页都还原。
     *
     * ⚠️ 不能拿 `render(currentPage)` 当"刷新界面"用：那会把正停在二级页的用户踢回 Tab 页。
     */
    private fun redraw() {
        when (subPage) {
            SUB_KEEP_ALIVE -> openKeepAlive()
            SUB_ACCOUNT_MORE -> openAccountMore()
            SUB_ACCOUNT_DETAIL -> openAccountDetail(detailUid)
            else -> render(currentPage)
        }
    }

    /** 系统返回键：停在任一二级页时先退回宿主 Tab，而不是直接退出 App。 */
    @Deprecated("Activity.onBackPressed 已废弃，但本项目未引 androidx（无 OnBackPressedDispatcher）")
    override fun onBackPressed() {
        if (subPage != null) render(currentPage) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * 记住当前 Tab 与二级页。
     *
     * 两个场景都靠它才不会"跳页"：① 切亮暗走 [recreate]；
     * ② 系统夜间模式切换时本 Activity 会被系统重建（manifest 未声明 configChanges）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, currentPage.name)
        outState.putString(STATE_SUB, subPage.orEmpty())
        outState.putString(STATE_SUB_EXTRA, detailUid.orEmpty())
    }

    // ------------------------------------------------------------------
    // 概览（对齐设计稿 home.html）
    // ------------------------------------------------------------------

    private fun overviewPage(): View = scroll {
        val running = GatewayService.running
        val busy = serviceBusy
        // 「端口在听」和「服务可用」是两件事：没登录 / 模型表为空时客户端连得上却用不了。
        // 状态 chip 必须说清是哪一种，否则用户以为"已就绪"就是能用了（真机踩过）。
        val loggedIn = TraeAuthStore.activeUid(this@MainActivity) != null
        val hasModels = ModelsRepo.current().length() > 0
        // 地址卡左侧标签列宽：跟着系统字体缩放走。写死 40dp 时，「局域网」在 1.3 倍字体下
        // 会被挤成「局域」+「网」两行，地址也跟着断成两截。
        val laneW = (40 * resources.configuration.fontScale).toInt().coerceIn(40, 64)
        // ---- 状态卡：整屏焦点 ----
        addView(glassCard(topMargin = 2) {
            addView(row {
                addView(pulseDot(running), lp(dp(8), dp(8), end = 10))
                addView(text(
                    when {
                        busy != null -> busy
                        running -> "服务运行中"
                        else -> "服务已停止"
                    }, Tokens.TEXT_MD, true
                ), weighted())
                addView(when {
                    busy != null -> chipMute("处理中")
                    !running -> chipBad("未运行")
                    !loggedIn -> chipWarn("未登录")
                    !hasModels -> chipWarn("模型表为空")
                    else -> chipOk("已就绪")
                }, lp(-2, -2, start = 8))
            })
            addView(captionText(when {
                !running && Gateway.lastStartError != null -> Gateway.lastStartError ?: "服务启动失败"
                !running -> "你（或通知栏）停止过服务，端口未在监听，客户端暂时连不上。\n点下面的「启动服务」重新开启。"
                !loggedIn -> "端口已在监听，但还没登录 Trae —— 客户端拉到的是空模型表、对话走固定假回复。先到「账号」页登录。"
                !hasModels -> "端口已在监听，但模型表为空 —— 到「模型」页点「刷新」，客户端才能选到模型。"
                else -> "前台服务常驻，退出 App 或划掉最近任务都不会断。停止请点下方按钮或通知栏的「停止」。"
            }), top(8))
            if (running) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(16), 0, 0)
                    addView(text("${LocalFakeServer.PORT}", Tokens.TEXT_2XL, true, "accent"))
                    addView(captionText("监听端口").let { it.setPadding(0, dp(2), 0, 0); it })
                })
            }
        })

        // ---- 服务地址：本页核心信息（强调卡，对应设计稿 .card.tint）----
        // 每段一行：区域标签 + 地址 + 复制。原来「本机使用」单独占一行标题、复制按钮又是
        // 44dp 高，三段叠起来卡片有半屏高，地址文字还悬在按钮中间（看着像错位）。
        addView(glassCard(GlassCard.Tone.TINT) {
            addView(row {
                addView(text("服务地址", Tokens.TEXT_MD, true), weighted())
                // 服务没跑时地址仍然展示（用户要能复制），但必须标明"现在连不上"
                if (!running) addView(chipBad("服务未运行"), lp(-2, -2, start = 8))
            })
            addView(row(top = 12) {
                addView(captionText("本机").apply { includeFontPadding = false; maxLines = 1 }, lp(dp(laneW), -2))
                addView(mono("http://127.0.0.1:${LocalFakeServer.PORT}/v1", Tokens.TEXT_XS, true, "primary_on_soft")
                    .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, weighted(start = 4))
                addView(ghostBtn("复制", heightDp = 30, textSize = Tokens.TEXT_XS) { copyServerUrl() },
                    lp(-2, -2, start = 8))
            })
            // 局域网行：拿不到 IP（未连 WiFi、只连了蜂窝）时整行不出现，不显示一个假地址
            lanAddress()?.let { lan ->
                addView(row(top = 6) {
                    addView(captionText("局域网").apply { includeFontPadding = false; maxLines = 1 }, lp(dp(laneW), -2))
                    addView(mono(lan, Tokens.TEXT_XS, true, "primary_on_soft")
                        .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, weighted(start = 4))
                    addView(ghostBtn("复制", heightDp = 30, textSize = Tokens.TEXT_XS) { copyLanUrl(lan) },
                        lp(-2, -2, start = 8))
                })
                addView(captionText("同一 WiFi 下的电脑/平板填这条；API Key 与下面同一个。"), top(8))
            } ?: addView(captionText("（未检测到局域网 IP：连上 WiFi 后此处会显示可直接给其它设备用的地址）"), top(10))
        })

        // ---- 启停按钮（busy 时禁用，不给点第二次）----
        addView(glassButton(
            busy ?: if (running) "停止服务" else "启动服务",
            style = if (running || busy != null) BtnStyle.GHOST else BtnStyle.PRIMARY,
            enabled = busy == null,
        ) { toggleService() })

        // ---- 当前账号卡 ----
        addView(sectionRow("当前账号", 0))
        addView(glassCard(topMargin = 0) { addTraeCard() })

        // ---- 积分卡（概览页与账号页共用 quotaCardView）----
        addView(quotaCardView(loggedIn))

        // ---- 本地 API Key ----
        addView(glassCard {
            addView(row {
                addView(iconBadge("key", "accent_container", "accent"), lp(dp(30), dp(30)))
                addView(text("本地 API Key", Tokens.TEXT_MD, true), weighted(start = 10))
            })
            // key 独占一行 + maxLines=2：360dp 下 44 字符的 key 与按钮挤同一行会被截断（真机实测）
            addView(mono(if (apiKeyVisible) apiKey else MASKED_KEY, Tokens.TEXT_XS, colorName = "on_surface_variant")
                .apply { maxLines = 2; setPadding(0, dp(12), 0, 0) })
            addView(row(top = 4) {
                addView(TextAction(this@MainActivity, if (apiKeyVisible) "隐藏" else "显示") {
                    apiKeyVisible = !apiKeyVisible
                    redraw()
                }, lp(-2, -2))
                addView(TextAction(this@MainActivity, "复制") { copyApiKey() }, lp(-2, -2, start = 8))
                addView(View(this@MainActivity), weighted())
            })
            addView(captionText("供任意 OpenAI 客户端连接鉴权使用。"), top(8))
        })

        // ---- 使用引导 ----
        addView(sectionRow("使用引导", 0))
        addView(glassCard(GlassCard.Tone.SOFT, topMargin = 0) {
            addView(captionText("把上面的地址与 API Key 填进任意 OpenAI 兼容客户端。单独测端点时点右侧复制："))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
                ENDPOINTS.forEachIndexed { i, (method, path) ->
                    addView(row {
                        addView(chipStatic(method, if (method == "POST") "info" else "mute"), lp(dp(52), -2))
                        addView(mono(path, Tokens.TEXT_XS), weighted(start = 8))
                        addView(TextAction(this@MainActivity, "复制") { copyEndpoint(path) }, lp(-2, -2))
                    })
                    if (i < ENDPOINTS.lastIndex) addView(rowDivider())
                }
            })
        })

        // ---- 保活提醒：只有存在待处理项时才出现（设计稿 home.html 尾卡）----
        val pending = KeepAlive.check(this@MainActivity).count { it.needsAction }
        if (pending > 0) {
            addView(glassCard(GlassCard.Tone.WARN, topMargin = Tokens.CARD_GAP + Tokens.SPACE_2) {
                addView(row {
                    addView(text("$pending 项保活设置未开启", Tokens.TEXT_MD, true, "warning"), weighted())
                    addView(chipWarn("建议处理"), lp(-2, -2, start = 8))
                })
                addView(captionText("国产 ROM 会无视前台服务直接强杀，真机上服务可能被系统清掉。"), top(8))
                addView(TextAction(this@MainActivity, "去设置保活") { openKeepAlive(animate = true) }, lp(-2, -2, top = 8))
            })
        }

        addView(captionText("$AUTHOR_LABEL $AUTHOR_NAME · $AUTHOR_EMAIL")
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(90)) })
    }

    /** 概览页的当前账号卡（设计稿 home.html「当前账号」）：头像行 + Token 到期。 */
    private fun LinearLayout.addTraeCard() {
        val cred = TraeAuthStore.load(this@MainActivity)
        if (cred == null) {
            addView(row {
                addView(iconBadge("user", "primary_container", "primary_on_soft"), lp(dp(36), dp(36)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("未登录 Trae", Tokens.TEXT_MD, true))
                    addView(captionText("已登录后走真实 Trae 转发；当前返回固定假回复").let { it.setPadding(0, dp(2), 0, 0); it })
                }, weighted(start = 10))
            })
            return
        }
        val hasToken = cred.accessToken.isNotBlank()
        addView(row {
            addView(avatar(cred.nickname), lp(dp(36), dp(36)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(cred.nickname.ifBlank { "Trae 账号" }, Tokens.TEXT_MD, true))
                val sub = buildString {
                    if (cred.uid.isNotBlank()) append("UID ${cred.uid} · ")
                    append(regionLabel(cred.region))
                }
                addView(captionText(sub).let { it.setPadding(0, dp(2), 0, 0); it })
            }, weighted(start = 10))
            addView(if (hasToken) chipMute("转发已启用") else chipWarn("未配置"), lp(-2, -2, start = 8))
        })
        if (!cred.region.isSupported) {
            addView(captionText(TraeRegion.SG.unsupportedNote).let { it.setPadding(0, dp(6), 0, 0); it })
        }
        if (cred.expiresAt > 0L) {
            addView(labelValue("Token 到期", formatExp(cred.expiresAt)), top(12))
        }
        val accountCount = TraeAuthStore.list(this@MainActivity).size
        if (accountCount > 1) {
            addView(captionText("已保存 $accountCount 个账号（在「账号」页切换）").let { it.setPadding(0, dp(6), 0, 0); it })
        }
    }

    /**
     * 积分与用量卡（账号页与概览页共用；对齐设计稿 home.html 的「当前可用积分」卡）：
     * 「当前可用积分 + 刷新」行、主数值行、签到 chip 行。
     *
     * 三个会被原地刷新改掉的控件（刷新文字 / 数值 / 奖励说明 / 签到按钮）在 [quotaApply]
     * 里统一更新 —— **必须在这里赋值**：只置 null 不赋值的话回调永远是 no-op，
     * refreshQuota 拉到数据页面也看不到（模拟器实测）。
     */
    private fun quotaCardView(loggedIn: Boolean): View {
        val card = glassCard {}
        // 刻意**不用 Button**：Material Button 的默认 style 自带 minHeight=48dp 与 inset 阴影，
        // 已签时想缩成一枚 32dp 小 chip 做不到（实测仍是 48dp 的砖）。TextView + 自绘背景
        // 高度完全可控，也和项目里其它胶囊同一套做法。
        val checkinAction = TextView(this@MainActivity).apply {
            gravity = Gravity.CENTER
            setOnClickListener { claimCheckin() }
        }
        // 未登录时 accountCheckin 为 null，quotaApply 不会被调用，所以这里先按初值同步一次状态
        refreshCheckinButton(checkinAction, loggedIn)
        val balanceView = text(quotaValue(accountBalance?.toString()), Tokens.TEXT_2XL, true, "primary")
            .apply { includeFontPadding = false }
        // 奖励说明（补充信息）：按钮已经说了"今日已签到"，这里不再重复状态，只说能领多少
        val rewardView = captionText(quotaValue(checkinRewardLabel())).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        // 登记"怎么把最新数据刷进这几个控件"，刷新时直接调用（见 quotaApply 的注释）
        quotaApply = {
            refreshLabelRef?.text = if (quotaLoading) "刷新中…" else "刷新"
            balanceView.text = quotaValue(accountBalance?.toString())
            rewardView.text = quotaValue(checkinRewardLabel())
            refreshCheckinButton(checkinAction, loggedIn)
            // 数据先写、再淡入：刷新开始时数值变「…」、回来变数字，各闪一次（0.65 → 1）。
            // 只在原地更新的这几个控件上做 —— 重建整页会把滚动位置弹回页首。
            Motion.fadeState(balanceView)
            Motion.fadeState(rewardView)
            Motion.fadeState(checkinAction)
        }
        val refreshLabel = TextAction(this@MainActivity, if (quotaLoading) "刷新中…" else "刷新") { refreshQuota() }
            .also { refreshLabelRef = it }
        card.addView(row {
            addView(captionText("当前可用积分"), weighted())
            addView(refreshLabel, lp(-2, -2))
        })
        // 主数值独占一行
        card.addView(balanceView, top(6))
        // 签到单独一行：按钮（仅在可签时是主色）+ 右侧奖励说明
        card.addView(row {
            addView(checkinAction, lp(-2, -2))
            addView(rewardView, lp(-2, -2, start = 12))
        }, top(Tokens.SPACE_3))
        return card
    }

    /** 「刷新」TextAction 的引用：quotaApply 原地改它的文字（View 在卡构建后才创建，先留槽位）。 */
    private var refreshLabelRef: TextView? = null

    // ------------------------------------------------------------------
    // 账号（对齐设计稿 account.html）
    // ------------------------------------------------------------------

    private fun accountPage(): View = scroll {
        // 一次拿一致快照：metas / activeUid / 健康状态 来自同一时刻（旧写法分别调
        // list() + activeUid() + load()，三次读之间账号可能已被删）。
        val snap = TraeAuthStore.snapshot(this@MainActivity)
        val metas = snap.metas
        val activeUid = snap.activeUid
        val cred = TraeAuthStore.load(this@MainActivity)

        // 存储异常警示条（可关闭；关闭只影响显示，不解除写入闸门）
        if (TraeAuthStore.shouldShowHealthBanner()) {
            addView(storeHealthBanner())
        }

        if (metas.isEmpty()) {
            // 空态：一张卡说清两条路——要么登录、要么从备份文件导入（核心功能，未登录必须可见）。
            addView(glassCard(GlassCard.Tone.SOFT, topMargin = 2) {
                addView(text("未登录 Trae", Tokens.TEXT_MD, true))
                addView(captionText("登录后即可多账号转发、批量签到与积分监测。已有备份文件也可以直接导入。"), top(6))
                addView(row(top = Tokens.SPACE_3) {
                    addView(ghostBtn("立即登录", full = true) { openLogin() },
                        LinearLayout.LayoutParams(0, -2, 1f))
                    addView(View(this@MainActivity), lp(dp(8), 1))
                    addView(ghostBtn("从文件导入", full = true) { importFromFile() },
                        LinearLayout.LayoutParams(0, -2, 1f))
                })
            })
        } else {
            // ---- 当前账号卡（瘦身：删掉「国服总积分」块，避免和「当前账号积分」两个大数字打架）----
            addView(glassCard(topMargin = 2) {
                val activeMeta = metas.firstOrNull { it.uid == activeUid }
                val expSoon = cred?.expiresAt?.takeIf { it > 0 }?.let {
                    it - System.currentTimeMillis() / 1000 < 86400 * 3
                } == true
                addView(row {
                    addView(avatar(activeMeta?.nickname ?: cred?.nickname), lp(dp(44), dp(44)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text((cred?.nickname ?: activeMeta?.nickname)?.ifBlank { null } ?: "Trae 账号",
                            Tokens.TEXT_MD, true))
                        val sub = buildString {
                            append(regionLabel(cred?.region ?: activeMeta?.region ?: TraeRegion.CN))
                            if (cred?.uid?.isNotBlank() == true) append(" · UID ${cred.uid}")
                            cred?.expiresAt?.takeIf { it > 0 }?.let {
                                append(" · Token ${tokenShortLabel(it)}")
                            }
                        }
                        addView(captionText(sub).let { it.setPadding(0, dp(2), 0, 0); it })
                    }, weighted(start = 10))
                    // 状态 chip 按真实状态变色：Token 即将过期 → 黄，正常在线 → 绿
                    if (expSoon) {
                        addView(chip("Token 将到期", "warning_container", "warning"), lp(-2, -2, start = 8))
                    } else {
                        addView(chipWithDot("在线"), lp(-2, -2, start = 8))
                    }
                    // 当前账号的管理入口：与列表行同一套菜单（详情/置顶/重命名/签到/删除），
                    // 但没有「切换」和「上下移」（已是当前账号，也不在可排序列表里）。
                    activeMeta?.let { m ->
                        addView(TextAction(this@MainActivity, "⋯") {
                            showAccountMenu(m, isActive = true, canMove = false, moveUp = null, moveDown = null)
                        }, lp(-2, -2, start = 6))
                    }
                })
                addView(row(top = Tokens.SPACE_3) {
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(quotaValue(accountBalance?.toString()), Tokens.TEXT_2XL, true, "primary_on_soft")
                            .apply { includeFontPadding = false })
                        addView(captionText("可用积分").let { it.setPadding(0, dp(2), 0, 0); it })
                    }, weighted())
                    addView(TextAction(this@MainActivity, if (quotaLoading) "刷新中…" else "刷新") { refreshQuota() },
                        lp(-2, -2))
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, 0)
                    addView(TextAction(this@MainActivity, "查看详情与 Token") {
                        activeMeta?.let { openAccountDetail(it.uid, animate = true) }
                    }, lp(-2, -2).apply { marginStart = dp(-10) })
                })
            })

            // ---- 签到卡上提：最高频收益动作紧跟「当前账号」（设计稿 account.html 签到区）----
            addView(sectionRow("签到", Tokens.CARD_GAP))
            addView(batchCheckinCard())

            // ---- 全部账号：搜索 + 分类筛选（计数排除当前账号）+ 列表 ----
            addView(sectionRow("其他账号", Tokens.CARD_GAP))
            addView(accountListSection(snap))

            // ---- 设置入口沉底：从独立玻璃卡降为文字链，首屏让位给核心信息 ----
            addView(glassCard(topMargin = Tokens.CARD_GAP, padH = 0, padV = 0) {
                addView(subPageRow("账号设置", "积分监测 · 添加账号 · 导入导出") { openAccountMore(animate = true) })
            })
        }
        // 登录只有这一条路：**浏览器 + 粘贴回调**（原内置 WebView 登录窗已移除，实测可用路径）。
        if (metas.isEmpty()) {
            addView(sectionRow("登录", 0))
            addView(glassCard(topMargin = 0) {
                // 说明默认折叠成一行「如何登录？」，点开才展开 4 步；不展开时只留 3 个按钮
                val steps = TextView(this@MainActivity).apply {
                    text = "1. 按区域复制登录链接\n" +
                        "2. 在电脑或手机浏览器打开并登录\n" +
                        "3. 登录后地址栏停在 127.0.0.1:18080 打不开是正常的\n" +
                        "4. 复制地址栏那条完整链接，回到这里粘贴"
                    setTextColor(color("on_surface_variant"))
                    textSize = Tokens.TEXT_XS
                    visibility = View.GONE
                    alpha = 0f
                    setPadding(0, dp(Tokens.SPACE_2), 0, 0)
                }
                // 展开态用**显式布尔**判断，不看 visibility：动画进行中 visibility 已经是
                // VISIBLE/VISIBLE 之外的值不可靠，连点会读出错误的状态。
                var stepsOpen = false
                // 文字动作本身要在 setStepsOpen 之前声明：setStepsOpen 会改它的文案
                var toggle: TextAction? = null
                // 在跑的高度动画：连点时先 cancel，否则两段 ValueAnimator 会同时改同一个
                // layoutParams.height（视觉上是"卡一下然后跳到终值"）。
                var stepsAnim: android.animation.ValueAnimator? = null
                // 收起时的目标高度按"卡片内可用宽度"实测：不依赖 post 时机，
                // 首帧就能算出准确高度（懒测量会在宽度还是 0 时算出 1 行高）。
                fun measuredStepsHeight(): Int {
                    val w = (contentWidth() - dp(Tokens.PAGE_PAD_H) * 2 - dp(Tokens.CARD_PAD) * 2)
                        .coerceAtLeast(dp(120))
                    steps.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    return steps.measuredHeight
                }
                fun setStepsOpen(open: Boolean) {
                    stepsOpen = open
                    toggle?.text = if (open) "收起说明" else "如何登录？"
                    val lp = steps.layoutParams as LinearLayout.LayoutParams
                    stepsAnim?.cancel()
                    stepsAnim = null
                    if (!Motion.animationsEnabled(this@MainActivity)) {
                        lp.height = if (open) LinearLayout.LayoutParams.WRAP_CONTENT else 0
                        steps.layoutParams = lp
                        steps.visibility = if (open) View.VISIBLE else View.GONE
                        steps.alpha = if (open) 1f else 0f
                        return
                    }
                    if (open) {
                        val target = measuredStepsHeight()
                        steps.visibility = View.VISIBLE
                        steps.alpha = 0f
                        lp.height = 0
                        steps.layoutParams = lp
                        val a = android.animation.ValueAnimator.ofInt(0, target)
                        a.duration = 160L
                        a.interpolator = Motion.ease()
                        a.addUpdateListener {
                            lp.height = it.animatedValue as Int
                            steps.requestLayout()
                        }
                        a.addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                // 结束恢复 WRAP_CONTENT：写死像素高度在字体缩放/换行变化后会把字裁掉
                                if (stepsOpen) {
                                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                    steps.requestLayout()
                                }
                            }
                        })
                        stepsAnim = a
                        a.start()
                        steps.animate().alpha(1f).setDuration(160).setInterpolator(Motion.ease()).start()
                    } else {
                        val from = if (steps.height > 0) steps.height else measuredStepsHeight()
                        steps.animate().alpha(0f).setDuration(120).setInterpolator(Motion.ease()).start()
                        val a = android.animation.ValueAnimator.ofInt(from, 0)
                        a.duration = 120L
                        a.interpolator = Motion.ease()
                        a.addUpdateListener {
                            lp.height = it.animatedValue as Int
                            steps.requestLayout()
                        }
                        a.addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                if (stepsOpen) return
                                steps.visibility = View.GONE
                                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                                steps.layoutParams = lp
                            }
                        })
                        stepsAnim = a
                        a.start()
                    }
                }
                toggle = TextAction(this@MainActivity, "如何登录？") {
                    setStepsOpen(!stepsOpen)
                }
                addView(toggle, lp(-2, -2))
                addView(steps)
                addView(ghostBtn("复制国服登录链接", full = true) { copyLoginUrl(TraeRegion.CN) }, top(Tokens.SPACE_3))
                addView(ghostBtn("复制国际服登录链接", full = true) {
                    toast(TraeRegion.SG.unsupportedNote)
                    copyLoginUrl(TraeRegion.SG)
                }, top(Tokens.SPACE_2))
                addView(glassButton("粘贴回调链接完成登录", style = BtnStyle.PRIMARY) { promptPasteCallback() },
                    top(Tokens.SPACE_3))
            })
        }
        addView(View(this@MainActivity), lp(-1, dp(84)))
    }

    /** 账号列表区：筛选 chip 行 + 一张卡装全部账号行（滚动限高，账号多也不撑长页面）。 */
    /** 账号排序模式（**UI 偏好**，存 prefs 的 `account_sort_mode`，不进 AccountMeta）。 */
    private enum class AccountSort(val label: String) {
        RECENT("最近使用"), CREATED("添加时间"), CUSTOM("自定义顺序")
    }

    private fun sortMode(): AccountSort {
        val id = getSharedPreferences("traework2api_prefs", Context.MODE_PRIVATE)
            .getString("account_sort_mode", null)
        return AccountSort.entries.firstOrNull { it.name == id } ?: AccountSort.CUSTOM
    }

    private fun saveSortMode(m: AccountSort) {
        getSharedPreferences("traework2api_prefs", Context.MODE_PRIVATE)
            .edit().putString("account_sort_mode", m.name).apply()
    }

    /**
     * 可切换账号（排除当前账号）按模式排序，置顶恒在最前。
     *
     * `sortedByDescending` 是稳定排序：`createdAt` / `lastUsedAt` 为 0 的历史账号排在
     * 有值的之后，且**彼此保持原数组相对顺序** —— 不伪造历史时间，也不打乱既有顺序。
     */
    private fun orderedSwitchable(
        snap: TraeAuthStore.Snapshot,
        mode: AccountSort,
    ): List<TraeAuthStore.AccountMeta> {
        val base = snap.metas.filter { it.uid != snap.activeUid }
        val sorted = when (mode) {
            AccountSort.CUSTOM -> base
            AccountSort.CREATED -> base.sortedByDescending { it.createdAt }
            AccountSort.RECENT -> base.sortedByDescending { it.lastUsedAt }
        }
        val (pinned, normal) = sorted.partition { it.pinned }
        return pinned + normal
    }

    /** 存储异常警示条（可关闭；关闭只影响显示，**不**解除写入闸门）。 */
    private fun storeHealthBanner(): View = glassCard(GlassCard.Tone.SOFT, topMargin = 2) {
        addView(row {
            addView(chip("存储异常", "warning_container", "warning"), lp(-2, -2))
            addView(View(this@MainActivity), weighted())
            addView(TextAction(this@MainActivity, "关闭") {
                TraeAuthStore.dismissHealthBanner()
                renderKeepingScroll(Page.ACCOUNT)
            }, lp(-2, -2))
        })
        addView(captionText("存储写入失败，本次改动可能未保存。为避免数据错乱，账号相关修改已暂停；请清理存储空间后重启 App 恢复。"), top(6))
    }

    /** 可切换账号行的副标题：区域 · 积分 · 置顶/到期提示。 */
    private fun switchableSubtitle(m: TraeAuthStore.AccountMeta): String = buildString {
        append(regionLabel(m.region))
        // 积分来自 CreditMonitor 的读数表（含磁盘快照），**不做保鲜期过滤**：
        // 列表要看的是"上次查到多少"，所以旧值也照显，超过 30 分钟补一句"多久之前"。
        // 国际服根本查不了余额（canQueryQuota=false），明确写「不支持查询」而不是留空或 "—"。
        val known = CreditMonitor.lastKnown(m.uid)
        if (known != null) {
            append(" · ${formatNumber(known.first)}")
            if (System.currentTimeMillis() - known.second > 30 * 60_000L) {
                append("（${agoLabel(known.second)}）")
            }
        } else {
            // 判据用 isSupported（= 仅国服），不是 canQueryQuota：
            // 本工程里 SG 的 ugHost 非空（`https://ug-normal.trae.ai`），canQueryQuota 对国际服
            // 也是 true —— 但国际服链路已冻结为「未开发」，批量刷新与展示都按 isSupported 走，
            // 与 TraeRegion.selfCheck / 签到跳过国际服的口径一致。
            if (m.region.isSupported) append(" · 积分未刷新") else append(" · 不支持查询")
        }
        if (m.pinned) append(" · 置顶")
        if (m.expiresAt > 0 && m.expiresAt - System.currentTimeMillis() / 1000 < 86400 * 3) {
            append(" · Token 将到期")
        }
    }

    /** 读数距今多久（只用于列表副标题的括号提示）。 */
    private fun agoLabel(atMs: Long): String {
        val minutes = (System.currentTimeMillis() - atMs) / 60_000L
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            else -> "${minutes / 60} 小时前"
        }
    }

    /**
     * 可切换账号列表：搜索 + 分类（计数**排除当前账号**）+ 排序 + 更多菜单。
     *
     * 整页统一滚动（不套内层 ScrollView）：嵌套滚动的触摸仲裁太容易出错，
     * 账号行本身紧凑，几十个账号也只是正常的一页长内容。
     */
    private fun accountListSection(snap: TraeAuthStore.Snapshot): View {
        val switchable = snap.metas.filter { it.uid != snap.activeUid }
        val cnCount = switchable.count { it.region.isSupported }
        val intlCount = switchable.size - cnCount

        val host = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        if (switchable.isEmpty()) {
            host.addView(glassCard(GlassCard.Tone.SOFT, topMargin = 0) {
                addView(captionText("暂无其他账号。需要登录新账号时，从账号设置进入。"))
            })
            return host
        }

        val edit = searchInput("搜索账号名或 UID…")
        host.addView(edit, lp(-1, -2, top = 0))

        var filter = "all"
        var mode = sortMode()

        val chips = linkedMapOf(
            "all" to CountChip(this@MainActivity, "全部").apply { setCount(switchable.size) },
            "cn" to CountChip(this@MainActivity, "国服").apply { setCount(cnCount) },
            "intl" to CountChip(this@MainActivity, "国际服").apply { setCount(intlCount) },
        )
        val listHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val listCard = glassCard(GlassCard.Tone.NORMAL, topMargin = Tokens.SPACE_2, padH = 0, padV = 0) {}
        listCard.addView(listHost, LinearLayout.LayoutParams(-1, -2))

        var sortBtn: TextAction? = null
        val emptyHint = captionText("没有匹配的账号，换个关键词或切换分类。").apply {
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }

        fun rebuild(animate: Boolean = false) {
            listHost.removeAllViews()
            val ordered = orderedSwitchable(snap, mode)
            val q = edit.text?.toString()?.trim().orEmpty()
            val visible = ordered.filter { m ->
                val regionOk = when (filter) {
                    "cn" -> m.region.isSupported
                    "intl" -> !m.region.isSupported
                    else -> true
                }
                regionOk && (q.isBlank() || m.nickname.contains(q, true) || m.uid.contains(q, true))
            }
            // 上下移**只在**「自定义顺序 + 全部分类 + 无搜索词」时提供：
            // 筛选/搜索后的可见序列只是原数组的子集，在那里移动会让整体顺序变得不可预期。
            val canMove = mode == AccountSort.CUSTOM && filter == "all" && q.isBlank()
            val visibleUids = ordered.map { it.uid }
            for (m in visible) {
                val more = TextAction(this@MainActivity, "⋯") {
                    showAccountMenu(
                        m, isActive = false, canMove = canMove,
                        moveUp = if (canMove) ({ moveAccountBy(m, -1, visibleUids) }) else null,
                        moveDown = if (canMove) ({ moveAccountBy(m, +1, visibleUids) }) else null,
                    )
                }
                val row = ListRow.account(
                    this@MainActivity,
                    avatarInitial(m.nickname.ifBlank { m.uid }),
                    m.nickname.ifBlank { m.uid },
                    switchableSubtitle(m),
                    active = false,
                    trailing = more,
                ) { doSwitchAccount(m) }
                row.tag = m.uid
                row.setOnLongClickListener {
                    showAccountMenu(
                        m, isActive = false, canMove = canMove,
                        moveUp = if (canMove) ({ moveAccountBy(m, -1, visibleUids) }) else null,
                        moveDown = if (canMove) ({ moveAccountBy(m, +1, visibleUids) }) else null,
                    )
                    true
                }
                listHost.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            emptyHint.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            chips.forEach { (k, c) -> c.setChecked(k == filter) }
            sortBtn?.text = "排序：${mode.label}"
            // 只有用户主动换筛选/排序才轻淡入；搜索逐字符调用的是 rebuild() 默认值 false，
            // 否则每敲一个字整块列表都闪一下（这是"列表更新"与"输入过滤"的区别）。
            if (animate) Motion.fadeState(listHost, 0.75f, 120L)
        }

        val sortAction = TextAction(this@MainActivity, "排序：${mode.label}") {
            val dlg = AlertDialog.Builder(this@MainActivity)
                .setTitle("排序方式")
                .setItems(AccountSort.entries.map { it.label }.toTypedArray()) { _, i ->
                    val picked = AccountSort.entries[i]
                    saveSortMode(picked)
                    mode = picked
                    rebuild(animate = true)
                }
                .create()
            trackDialog(dlg)
            Motion.applyDialogMotion(dlg)
            dlg.show()
        }
        sortBtn = sortAction

        val toolbar = row(top = Tokens.SPACE_2) {
            chips.forEach { (key, chip) ->
                addView(chip, lp(-2, -2, end = 6))
                chip.setOnClickListener { filter = key; rebuild(animate = true) }
            }
            addView(View(this@MainActivity), weighted())
            addView(sortAction, lp(-2, -2))
        }
        // 「刷新积分」单独占一行：三个计数 chip + 「排序：最近使用」已经把这行占满，
        // 再塞第四块会被压成 30px 宽（实测文字直接看不见）。换行比挤爆更清楚。
        val refreshQuotaAll = TextAction(this@MainActivity, "刷新积分") { refreshAllQuotas() }
        quotaBulkLabel = refreshQuotaAll
        val refreshRow = row(top = 0) {
            addView(View(this@MainActivity), weighted())
            addView(refreshQuotaAll, lp(-2, -2))
        }

        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = rebuild()
        })

        rebuild()
        host.addView(toolbar)
        host.addView(refreshRow)
        host.addView(listCard)
        host.addView(emptyHint)
        return host
    }

    /** 通用条目菜单（AlertDialog 列表）。 */
    private fun showItemMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        if (items.isEmpty()) return
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .create()
        trackDialog(dlg)
        Motion.applyDialogMotion(dlg)
        dlg.show()
    }

    /**
     * 账号「⋯」菜单。当前账号与普通账号**能力一致**，区别只有：
     * - 当前账号没有「切换到该账号」（已经是了）
     * - 当前账号**永远**没有上移/下移（它不在可排序列表里）
     */
    private fun showAccountMenu(
        m: TraeAuthStore.AccountMeta,
        isActive: Boolean,
        canMove: Boolean,
        moveUp: (() -> Unit)?,
        moveDown: (() -> Unit)?,
    ) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "查看详情" to { openAccountDetail(m.uid, animate = true) }
        items += (if (m.pinned) "取消置顶" else "置顶") to { togglePinned(m) }
        items += "重命名" to { renameAccountDialog(m) }
        items += "单独签到" to { checkinSingle(m) }
        if (canMove && !isActive) {
            moveUp?.let { items += "上移一位" to it }
            moveDown?.let { items += "下移一位" to it }
        }
        items += "删除" to { confirmRemoveAccount(m) }
        showItemMenu(m.nickname.ifBlank { m.uid }, items)
    }

    /** 置顶/取消置顶（后台写 + 回来重建页面）。 */
    private fun togglePinned(m: TraeAuthStore.AccountMeta) {
        thread(name = "account-pin") {
            val r = TraeAuthStore.setPinned(this@MainActivity, m.uid, !m.pinned)
            runOnUiThread {
                when (r) {
                    TraeAuthStore.StoreResult.Success -> renderKeepingScroll(Page.ACCOUNT)
                    TraeAuthStore.StoreResult.PersistenceLocked ->
                        toast("存储异常，已暂停修改。请重启 App 后重试")
                    else -> toast("置顶失败（${r.javaClass.simpleName}）")
                }
            }
        }
    }

    /** 重命名账号（复用 updateMeta 同一套写事务）。 */
    private fun renameAccountDialog(m: TraeAuthStore.AccountMeta) {
        val input = EditText(this).apply {
            setText(m.nickname)
            hint = "账号名称"
            isSingleLine = true
            setTextColor(color("on_surface"))
            setHintTextColor(color("on_surface_variant"))
            background = shape("surface_container", Tokens.RADIUS_CONTROL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        confirmDialog(
            title = "重命名账号",
            message = "只改本地显示名，不影响账号本身。",
            content = input,
            okText = "保存",
        ) {
            val name = input.text?.toString().orEmpty().trim()
            if (name.isBlank()) { toast("名称不能为空"); return@confirmDialog }
            thread(name = "account-rename") {
                val r = TraeAuthStore.renameAccount(this@MainActivity, m.uid, name)
                runOnUiThread {
                    when (r) {
                        TraeAuthStore.StoreResult.Success -> { toast("已重命名"); renderKeepingScroll(Page.ACCOUNT) }
                        TraeAuthStore.StoreResult.PersistenceLocked ->
                            toast("存储异常，已暂停修改。请重启 App 后重试")
                        else -> toast("重命名失败")
                    }
                }
            }
        }
    }

    /** 在可见序列里上移/下移一位（底层交换，隐藏的当前账号不动）。 */
    private fun moveAccountBy(
        m: TraeAuthStore.AccountMeta,
        delta: Int,
        visibleUids: List<String>,
    ) {
        thread(name = "account-move") {
            val r = TraeAuthStore.moveAccount(this@MainActivity, m.uid, delta, visibleUids)
            runOnUiThread {
                when (r) {
                    TraeAuthStore.StoreResult.Success -> renderKeepingScroll(Page.ACCOUNT)
                    TraeAuthStore.StoreResult.NoChange -> toast(if (delta < 0) "已经在最前面了" else "已经在最后面了")
                    TraeAuthStore.StoreResult.PersistenceLocked ->
                        toast("存储异常，已暂停修改。请重启 App 后重试")
                    else -> toast("移动失败")
                }
            }
        }
    }

    /** Token 剩余时间的短文案（列表行用；「14 天后到期」/「即将过期」）。 */
    private fun tokenShortLabel(expiresAt: Long): String {
        if (expiresAt <= 0L) return "到期未知"
        val days = (expiresAt - System.currentTimeMillis() / 1000) / 86400
        return when {
            days >= 1 -> "${days}天后到期"
            else -> "即将过期"
        }
    }

    /**
     * 「签到」卡（对齐设计稿 account.html「签到」区）：
     * 图标行（N 个国服账号 · 今天已签 M 个）+ 主按钮 + 定时开关一行。
     *
     * 结果持久化在 [BatchCheckin]（跨进程存活），进账号页总是显示**上一次**批量的事实，
     * 不是当前内存态 —— 所以"今天签没签"的权威判据是每行的状态文案（ok/already），
     * 与单账号卡里的 accountCheckin 各自独立、互不污染。
     */
    private fun batchCheckinCard(): View {
        val metas = TraeAuthStore.list(this@MainActivity)
        val supported = metas.count { it.region.isSupported }
        val skipped = metas.size - supported
        val last = BatchCheckin.lastResults(this@MainActivity)
        val doneToday = last?.results?.count { it.ok || it.status == "already" } ?: 0

        val card = glassCard {}
        card.addView(row {
            addView(iconBadge("gift", "primary_container", "primary_on_soft"), lp(dp(30), dp(30)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("今日签到", Tokens.TEXT_MD, false))
                addView(captionText(when {
                    metas.isEmpty() -> "还没有账号，先登录一个再说"
                    supported == 0 -> "没有支持签到的国服账号"
                    else -> "$supported 个国服账号 · 今天已签 $doneToday 个" +
                        (if (supported > doneToday) "，${supported - doneToday} 个待签" else "")
                }).let { it.setPadding(0, dp(2), 0, 0); it })
            }, weighted(start = 10))
            if (doneToday > 0) addView(chipOk("+${last?.results?.filter { it.ok }?.sumOf { it.credits.coerceAtLeast(0) } ?: 0}"),
                lp(-2, -2, start = 8))
        })
        val batchBtn = glassButton(
            if (BatchCheckin.busy.get()) "签到中…" else if (doneToday in 1 until supported) "补齐剩余账号" else "一键签到全部账号",
            style = BtnStyle.PRIMARY,
        ) { runBatchCheckin() }
        card.addView(batchBtn, top(Tokens.SPACE_3))
        // 国际服跳过说明（有国际服账号才出现，不占空间）
        if (skipped > 0) {
            card.addView(captionText("另有 $skipped 个国际服账号不支持签到，已自动跳过。"), top(6))
        }
        // 上轮结果行（无结果时不显示这块，不编造）
        if (last != null && last.results.isNotEmpty()) {
            card.addView(captionText("上次批量（${last.date}${if (BatchCheckin.lastRunToday(this@MainActivity)) " · 今天" else ""}）："),
                top(Tokens.SPACE_3))
            last.results.forEach { r ->
                card.addView(row(top = 4) {
                    addView(text(r.nickname.ifBlank { r.uid }, Tokens.TEXT_XS, true), weighted())
                    addView(text(BatchCheckin.statusLabel(r), Tokens.TEXT_XS, false,
                        if (r.ok || r.status == "already") "success" else "on_surface_variant"), lp(-2, -2))
                })
            }
        }
        // 定时开关一行（卡内分隔；完整设置在「账号设置」二级页）
        val schedOn = BatchCheckin.isEnabled(this@MainActivity)
        card.addView(topLineSection(14) {
            addView(row(top = 0) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("每日定时签到", Tokens.TEXT_MD, false))
                    addView(captionText("每天 ${BatchCheckin.timeText(this@MainActivity)} · 覆盖全部国服账号")
                        .let { it.setPadding(0, dp(2), 0, 0); it })
                }, weighted())
                addView(Switch(this@MainActivity, schedOn) {
                    if (BatchCheckin.isEnabled(this@MainActivity)) {
                        BatchCheckin.disable(this@MainActivity)
                        toast("定时签到已关闭")
                    } else {
                        BatchCheckin.enable(this@MainActivity, BatchCheckin.timeText(this@MainActivity))
                        toast("定时签到已开启：每天 ${BatchCheckin.timeText(this@MainActivity)}")
                    }
                    redraw()
                }, lp(-2, -2))
            })
        }, top(Tokens.SPACE_3))
        return card
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
        trackDialog(dlg)
        Motion.applyDialogMotion(dlg)
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
                if (currentPage == Page.ACCOUNT && subPage == null) render(Page.ACCOUNT)
            }
        }
    }

    /**
     * 账号详情页「单独签到」：对单个账号跑一次签到（复用 [BatchCheckin.runOne]）。
     *
     * 之前这里是个 toast 死胡同（"请到账号页操作"）——放了个不能用的按钮等于骗用户。
     * 现在真执行：后台线程跑，结果 toast 报 ok/already/fail，并原地刷新详情页。
     */
    private fun checkinSingle(meta: TraeAuthStore.AccountMeta) {
        if (!BatchCheckin.busy.compareAndSet(false, true)) { toast("签到正在进行中…"); return }
        toast("正在签到 ${meta.nickname.ifBlank { meta.uid }}…")
        thread(name = "checkin-single") {
            val r = try {
                BatchCheckin.runOne(this@MainActivity, meta.uid)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "单账号签到异常: ${e.javaClass.simpleName}: ${e.message}")
                null
            } finally {
                BatchCheckin.busy.set(false)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val rv = r ?: run { toast("签到异常，请重试"); return@runOnUiThread }
                toast(when (rv.status) {
                    "ok" -> "签到成功 +${rv.credits.coerceAtLeast(0)}"
                    "already" -> "该账号今日已签到"
                    "disabled" -> "该账号未开放签到"
                    "unsupported" -> rv.message.ifBlank { "该区域不支持签到" }
                    else -> rv.message.ifBlank { "签到失败" }
                })
                if (currentPage == Page.ACCOUNT) render(Page.ACCOUNT)
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
        btn.text = checkinActionLabel(loggedIn)
        btn.isEnabled = actionable
        btn.alpha = 1f
        // 三态三种外观，**用颜色和尺寸一起表达语义**，而不是只靠一个 alpha：
        //   可签  = 主色实心按钮（能点，最醒目）
        //   已签  = 缩成 success 小胶囊（是**状态**不是操作，占位小、颜色是"已完成"）
        //   其它  = 浅底灰字（加载中/未登录/未开放，弱化）
        btn.background = when {
            actionable -> shape("primary", Tokens.RADIUS_PILL)
            done -> shape("success_container", Tokens.RADIUS_PILL)
            else -> shape("surface_container", Tokens.RADIUS_PILL)
        }
        btn.setTextColor(color(when {
            actionable -> "on_primary"
            done -> "success"
            else -> "on_surface_variant"
        }))
        // 已签收成一枚小 chip（32dp 高、11sp 粗体）；可签/其它仍是 44dp 主按钮尺寸。
        // 32dp 只用于**不可点**的状态展示 —— 可点时必须 ≥44dp 满足无障碍触控目标下限。
        val h = if (done) 32 else 44
        val pad = if (done) 14 else 18
        btn.minimumHeight = dp(h)
        btn.setPadding(dp(pad), 0, dp(pad), 0)
        btn.textSize = (if (done) Tokens.TEXT_XS else Tokens.TEXT_MD)
        btn.setTypeface(Typeface.DEFAULT, if (done) Typeface.BOLD else Typeface.NORMAL)
        btn.includeFontPadding = false
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
    // 模型（对齐设计稿 models.html：搜索 + 工具条 + 一张卡里的行式列表）
    // ------------------------------------------------------------------

    private fun modelsPage(): View = scroll {
        // 走同一个 scroll{} 容器：原先这里自己抄了一遍内边距，改 scroll 时会漏掉它
        val edit = searchInput("搜索模型名或 ID…")
        addView(edit, lp(-1, -2, top = 2))

        // 工具条：刷新按钮 + 计数。计数是"刷新真的起作用了"的证据 —— 否则点了没新增时用户会以为没生效。
        val countView = captionText("")
        fun refreshCountLabel() {
            countView.text = "已加载 ${ModelsRepo.current().length()} 个"
        }
        val refreshBtn = glassButton(REFRESH_MODELS_LABEL, style = BtnStyle.GHOST, compact = true) { refreshModels() }
        // 空态卡里那枚刷新按钮：与工具条那枚必须同步禁用/改文案 ——
        // 业务守卫（modelsLoading）本来就会挡住重复请求，但只有工具条那枚会变灰，
        // 空态这枚点了没任何变化，用户会以为没点动。
        val emptyRefreshBtn = glassButton(REFRESH_MODELS_LABEL, style = BtnStyle.PRIMARY) { refreshModels() }
        // 刷新中：按钮置灰 + 文案变「刷新中…」（照抄记录页/积分刷新那套，别新造轮子）
        fun refreshButtonLabel() {
            refreshBtn.isEnabled = !modelsLoading
            refreshBtn.text = if (modelsLoading) "刷新中…" else REFRESH_MODELS_LABEL
            emptyRefreshBtn.isEnabled = !modelsLoading
            emptyRefreshBtn.text = if (modelsLoading) "刷新中…" else REFRESH_MODELS_LABEL
            emptyRefreshBtn.alpha = if (modelsLoading) 0.5f else 1f
        }
        // 工具条（计数 + 按钮）：只在"已加载"时显示 —— 空状态下列表还没有，
        // 顶上摆一个计数「已加载 · 0 个模型」纯属添乱，刷新按钮统一放到空状态卡里。
        val toolbar = row(top = Tokens.SPACE_3) {
            addView(refreshBtn, lp(-2, -2))
            addView(countView, weighted(start = Tokens.SPACE_3))
        }
        addView(toolbar)

        // 未加载：整页只有一张说明卡 + 刷新按钮（用户要的"空状态"，见 CHANGELOG 0.4.3）
        val notLoaded = glassCard(GlassCard.Tone.SOFT, topMargin = Tokens.SPACE_3) {
            addView(text("模型目录尚未加载", Tokens.TEXT_MD, true))
            addView(captionText("连接账号后点「$REFRESH_MODELS_LABEL」，将从上游加载真实可用模型。"), top(8))
            addView(emptyRefreshBtn, top(Tokens.SPACE_3))
        }
        addView(notLoaded)

        // 搜不到时的空态。没有它，用户打错一个字就是**整页空白**，会以为 App 坏了。
        val empty = glassCard(GlassCard.Tone.SOFT, topMargin = Tokens.SPACE_3) {
            addView(captionText("没有匹配的模型。换个关键词，或清空搜索框看看全部。"))
        }.apply { visibility = View.GONE }
        addView(empty)

        // 卡片容器：刷新时**只清空并重塞它的子项**，不动搜索框/按钮/空态（原地更新，滚动位置不丢）
        val listCard = glassCard(GlassCard.Tone.NORMAL, topMargin = Tokens.SPACE_3, padH = 0, padV = 0) {}
            .apply { visibility = View.GONE }
        addView(listCard)

        // 过滤逻辑收成一个函数：刷新重塞后也要重跑一次，否则过滤会作用在已失效的旧 View 上
        var rows = ArrayList<Pair<String, View>>()
        fun applyFilter() {
            val f = edit.text.toString().trim()
            rows.forEach { (key, v) -> v.visibility = if (f.isBlank() || key.contains(f, true)) View.VISIBLE else View.GONE }
            empty.visibility = if (f.isNotBlank() && rows.none { it.second.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
        }
        fun rebuildRows() {
            listCard.removeAllViews()
            rows = buildModelRows(listCard)
            applyFilter()
        }
        /** 未加载时藏起搜索框/工具条/列表，只留说明卡；加载后反过来。 */
        fun applyLoadedState() {
            val loaded = ModelsRepo.isDynamic
            notLoaded.visibility = if (loaded) View.GONE else View.VISIBLE
            edit.visibility = if (loaded) View.VISIBLE else View.GONE
            toolbar.visibility = if (loaded) View.VISIBLE else View.GONE
            listCard.visibility = if (loaded) View.VISIBLE else View.GONE
            if (!loaded) empty.visibility = View.GONE
        }

        // 客户端过滤：改可见性而非重建容器，避免丢失输入框焦点
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })

        // 刷新回调交给 build 完之后的代码用：本页重建后句柄要跟着换，不能留旧的
        modelsListPulse = { Motion.fadeState(listCard, 0.72f, 140L) }
        modelsRefreshApply = {
            rebuildRows()
            refreshCountLabel()
            refreshButtonLabel()
            applyLoadedState()
            // 按钮与计数是原地改文字的：改完轻淡入一次，让人看见"它真的响应了"
            Motion.fadeState(refreshBtn)
            Motion.fadeState(countView)
        }
        rebuildRows()
        refreshCountLabel()
        refreshButtonLabel()
        applyLoadedState()

        addView(captionText("点「$REFRESH_MODELS_LABEL」从上游重拉完整模型表")
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(90)); layoutParams = lp(-1, -2, top = 0) })
    }

    /** 用当前内存模型表构建行式列表（一张卡装完，行间 1px 分隔线）；返回 (匹配词→View) 供过滤。 */
    private fun buildModelRows(container: LinearLayout): ArrayList<Pair<String, View>> {
        val rows = ArrayList<Pair<String, View>>()
        val arr = ModelsRepo.current()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id", "").ifBlank { m.optString("config_name", "") }
            val name = m.optString("name", "").ifBlank { id }
            val ctx = m.optInt("context_length", 0)
            val rate = if (m.has("rate")) m.optDouble("rate") else null
            // 副标题只留「id · 上下文」，不再拼「思考/多模态」——那些塞进去会把一行挤爆
            // （ellipsize 截断成 …），能力详情放到点开后的展开卡里。
            val sub = "$id · ${if (ctx > 0) "${ctx / 1000}K" else "?"}"
            val row = ListRow.model(this@MainActivity, name,
                sub, rate = rate, onClick = { toggleModelDetail(container, m) }, onCopy = { copyModelId(id) })
            // 行 tag 存模型 id，供 toggleModelDetail 定位（详情卡另存 tag_region）
            row.tag = id
            // 匹配词 = 名称 + id（搜索命中任一即显示）
            rows.add("$name $id" to row)
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return rows
    }

    /**
     * 点模型行 → 在它下面插入一张详情卡（倍率/原价/折扣/上下文/多模态/思考），再点收起。
     *
     * 详情卡挂在行的 [View.getTag] 上（tag 存的是详情卡 View），点第二次把它移除并清 tag。
     * 展开/收起都用 `animate()` 做高度 + 透明度过渡，不做生硬的 add/remove 跳变。
     *
     * 字段全部来自 [ModelsRepo] 里 [TraeUpstream.parseModels] 落好的条目，不重新解析上游。
     */
    private fun toggleModelDetail(container: LinearLayout, m: JSONObject) {
        val id = m.optString("id", "").ifBlank { m.optString("config_name", "") }
        // 找到这个模型对应的行（container 的直接子视图里，用 tag 存 id 定位）
        val row = (0 until container.childCount)
            .mapNotNull { container.getChildAt(it) as? ListRow }
            .firstOrNull { it.tag == id }
            ?: return
        val existing = row.getTag(R.id.tag_region) as? View
        if (existing != null) {
            // 详情卡还挂着：按它当前的目标态决定这次是"反向展开"还是"收起"。
            // isSelected 存的就是目标态（true=该展开）。这样快速连点不会出现
            // "旧卡在淡出、新卡已插入"的两张卡叠在一起。
            val opening = !existing.isSelected
            existing.isSelected = opening
            if (opening) {
                Motion.reveal(existing, show = true, durationMs = 180L, offsetYPx = -dp(4).toFloat())
            } else {
                Motion.reveal(existing, show = false, durationMs = 120L, offsetYPx = -dp(4).toFloat()) {
                    // 结束回调必须核对身份：期间用户可能已经又点开了一张新的
                    if (!existing.isSelected && row.getTag(R.id.tag_region) === existing) {
                        container.removeView(existing)
                        row.setTag(R.id.tag_region, null)
                    }
                }
            }
            return
        }
        // 展开：构建详情卡，插入到行下方，再淡入 + 从上方 4dp 落位。
        // 刻意**不做高度动画**：详情卡高由 WRAP_CONTENT 决定，逐帧改高度会让整页 layout
        // 抖一下（列表在 ScrollView 里），只做透明度 + 短位移看起来更安静。
        val detail = buildModelDetailCard(m)
        detail.isSelected = true
        val idx = container.indexOfChild(row) + 1
        container.addView(detail, idx, LinearLayout.LayoutParams(-1, -2))
        row.setTag(R.id.tag_region, detail)
        Motion.reveal(detail, show = true, durationMs = 180L, offsetYPx = -dp(4).toFloat())
    }

    /** 模型详情卡：倍率（含原价/折扣）、上下文、多模态、思考。字段缺失的行不显示。 */
    private fun buildModelDetailCard(m: JSONObject): View {
        val ctx = this@MainActivity
        return glassCard(GlassCard.Tone.SOFT, topMargin = 0, padH = Tokens.CARD_PAD, padV = Tokens.CARD_PAD) {
            // 倍率行
            val rate = if (m.has("rate")) m.optDouble("rate") else -1.0
            if (rate >= 0.0) {
                addView(labelValue("倍率", "${formatRate(rate)}×"))
                val orig = if (m.has("original_rate")) m.optDouble("original_rate") else -1.0
                val disc = if (m.has("discount")) m.optInt("discount") else -1
                if (orig > 0 && disc > 0) {
                    // discount 是百分比（50 = 50%），折数 = discount / 10（50 → 5 折）
                    addView(captionText("原价 ${formatRate(orig)}× · 补贴 ${disc / 10} 折").let {
                        it.setPadding(0, dp(2), 0, 0); it
                    })
                }
            }
            // 上下文
            val c = m.optInt("context_length", 0)
            if (c > 0) addView(labelValue("上下文", "${c / 1000}K"))
            // 多模态 / 思考（能力，只有 true 才显示）
            if (m.optBoolean("multimodal", false)) addView(labelValue("多模态", "支持"))
            if (m.optBoolean("thinking", false)) addView(labelValue("思考", "支持"))
        }
    }

    /** 倍率数字格式化：`0.06` / `0.2` / `1.83`（整数不带小数点）。 */
    private fun formatRate(r: Double): String =
        if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()

    private fun copyModelId(id: String) = copyToClipboard("modelId", id, "已复制模型 ID: $id")

    // ------------------------------------------------------------------
    // 记录（对齐设计稿 records.html：汇总卡（数字行 + 条形）+ 一张卡的流水行）
    // ------------------------------------------------------------------

    private fun recordsPage(): View = scroll {
        val records = UsageStore.all(this@MainActivity)
        if (records.isEmpty()) {
            addView(glassCard(GlassCard.Tone.SOFT, topMargin = 2) {
                addView(captionText("还没有调用记录。"))
                addView(captionText("发一次聊天请求后，这里会显示用量与汇总。").let { it.setPadding(0, dp(4), 0, 0); it })
            })
            addView(View(this@MainActivity), lp(-1, dp(84)))
            return@scroll
        }
        // ---- 今日概览 + 汇总合一张卡（设计稿 records.html 首卡）----
        addView(glassCard(topMargin = 2) {
            val totalTokens = records.sumOf { it.total.toLong() }
            val okCount = records.count { it.ok }
            val rate = if (records.isEmpty()) "—" else "${okCount * 100 / records.size}%"
            addView(row {
                // 三组数字：今日 tokens（大）/ 调用 / 成功率
                addView(statColumn(formatTokens(totalTokens), "今日 tokens", Tokens.TEXT_2XL), weighted())
                addView(statColumn("${records.size}", "调用", Tokens.TEXT_MD), weighted())
                addView(statColumn(rate, "成功率", Tokens.TEXT_MD), weighted())
            })
            // 分模型条形（按相对最大那组画，模型一多每条都剩一小截反而看不出差别）
            val groups = UsageStore.aggregate(this@MainActivity)
            if (groups.isNotEmpty()) {
                addView(topLineSection(14) {
                    val max = groups.maxOfOrNull { it.total } ?: 0L
                    groups.take(4).forEachIndexed { i, g ->
                        addView(row(top = if (i > 0) 12 else 0) {
                            addView(text(g.model.ifBlank { "?" }, Tokens.TEXT_MD), weighted())
                            addView(mono("${formatTokens(g.total)} · 出 ${formatTokens(g.output)}",
                                Tokens.TEXT_XS, colorName = "on_surface_variant"), lp(-2, -2, start = 8))
                        })
                        addView(shareBar(g.total, max, i), top(6))
                    }
                }, top(16))
            }
        })

        // ---- 流水：一行一条，一张卡装完 ----
        addView(sectionRow("调用记录", 0))
        val listCard = glassCard(topMargin = 0, padH = 0, padV = 0) {}
        // 取最近 30 条（旧记录继续留在磁盘上，不给 UI 装太多行）
        records.asReversed().take(30).forEachIndexed { i, r ->
            val detail = buildString {
                append("${r.prompt} · ${r.completion} · ${r.durationMs}ms")
                if (r.errorType.isNotBlank()) append(" · ${r.errorType}")
            }
            val row = ListRow.record(this@MainActivity, r.ok, r.model.ifBlank { "?" }, detail,
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.ts)))
            row.showDivider(i > 0)
            listCard.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        addView(listCard)
        addView(captionText("仅保留最近 ${UsageStore.all(this@MainActivity).size} 条 · 清空不影响账号")
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(90)); layoutParams = lp(-1, -2, top = 0) })
    }

    /** 一列统计数字（数值 + 标签），对齐设计稿 records.html 的三列。 */
    private fun statColumn(value: String, label: String, size: Float): LinearLayout =
        LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(value, size, true).apply { includeFontPadding = false })
            addView(captionText(label).let { it.setPadding(0, dp(2), 0, 0); it })
        }

    /** token 数的人话格式：184.2K / 3.4M / 812。 */
    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> "$n"
    }

    /** 千分位（积分用）：2480 → 2,480。 */
    private fun formatNumber(n: Long): String = java.text.DecimalFormat("#,###").format(n)

    // ------------------------------------------------------------------
    // 更多（对齐设计稿 more.html：外观分段 + 保活 + 关于 + 维护）
    // ------------------------------------------------------------------

    /** 切亮暗：持久化 → 重建（重建会重新走 attachBaseContext 覆写 uiMode）。 */
    private fun switchMode(m: AppStyle.Mode) {
        if (m.id == AppStyle.mode.id) return
        AppStyle.setMode(this, m)
        // 悬浮窗是独立 window，recreate 不会重建它 —— 不主动刷就留一块旧主题的卡片。
        runCatching { FloatingTag.refresh() }
        recreate()
    }

    private fun morePage(): View = scroll {
        // ---- 外观：只保留深浅（多风格机制已删）----
        addView(sectionRow("外观", 0))
        addView(glassCard(topMargin = 0) {
            addView(row {
                addView(iconBadge("moon", "primary_container", "primary"), lp(dp(30), dp(30)))
                addView(text("深浅模式", Tokens.TEXT_MD, true), weighted(start = 10))
            })
            addView(SegmentedControl(this@MainActivity,
                AppStyle.Mode.entries.map { it.label },
                onPick = { i -> switchMode(AppStyle.Mode.entries[i]) }
            ).apply { setSelected(AppStyle.Mode.entries.indexOf(AppStyle.mode)) }, top(Tokens.SPACE_3))
            addView(captionText("切换立即生效并记住选择，重开 App 仍是这一套。"), top(8))
        })

        // ---- 保活 ----
        addView(sectionRow("保活", 0))
        addView(glassCard(topMargin = 0) {
            val pending = KeepAlive.check(this@MainActivity).count { it.needsAction }
            addView(row {
                addView(iconBadge("shield", "warning_container", "warning"), lp(dp(30), dp(30)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("后台保活设置", Tokens.TEXT_MD, false))
                    addView(captionText(if (pending == 0) "全部就绪，服务可长期存活"
                        else "还有 $pending 项需要开启").let { it.setPadding(0, dp(2), 0, 0); it })
                }, weighted(start = 10))
                addView(if (pending == 0) chipOk("已就绪") else chipWarn("待处理"), lp(-2, -2, start = 8))
            })
            addView(captionText("国产 ROM 会强杀前台服务，需要在系统里放开几项权限才能长期存活。"), top(8))
            // 更多页只是入口：实心主按钮留在保活设置页内（unslop 审计 P7）
            addView(ghostBtn("打开保活设置", full = true) { openKeepAlive(animate = true) }, top(Tokens.SPACE_3))
        })

        // ---- 关于 ----
        addView(sectionRow("关于", 0))
        addView(glassCard(GlassCard.Tone.SOFT, topMargin = 0) {
            addView(text("应用说明", Tokens.TEXT_MD, true))
            addView(captionText("本应用针对 Trae 当前协议的个人学习自用适配，不保证长期兼容，不代表官方支持。"), top(6))
            addView(labelValue("版本", versionName), top(Tokens.SPACE_3))
        })
        addView(glassCard(GlassCard.Tone.SOFT, topMargin = Tokens.CARD_GAP) {
            addView(text("版权与免责声明", Tokens.TEXT_MD, true))
            addView(captionText("仅供个人学习与研究使用。使用本项目与 Trae 账号存在风险，请自行评估并遵守相关服务条款。"), top(6))
            addView(topLineSection(12) {
                addView(labelValue(AUTHOR_LABEL, AUTHOR_NAME))
                addView(labelValue(CONTACT_LABEL, AUTHOR_EMAIL, monoValue = true).apply {
                    setOnClickListener { copyEmail() }
                }, top(6))
            }, top(14))
        })

        // ---- 维护 ----
        addView(sectionRow("维护", 0))
        addView(glassCard(topMargin = 0, padH = 0, padV = 0) {
            addView(subPageRow("清空调用记录", "不影响账号与模型缓存", icon = "trash") {
                confirmDialog(
                    title = "清空调用记录",
                    message = "将删除全部调用用量记录（不影响账号与模型缓存）。",
                    okText = "清空",
                    danger = true,
                ) {
                    UsageStore.clear(this@MainActivity)
                    toast("已清空调用记录")
                    redraw()
                }
            })
            addView(subPageRow("查看服务日志", "最近的请求与错误", icon = "info") { showServiceLog() })
        })
        addView(View(this@MainActivity), lp(-1, dp(84)))
    }

    /** 服务日志查看（logcat 里本进程最近的相关行）。最新在最上面，可一键复制带走。 */
    private fun showServiceLog() {
        val lines = runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200"))
            proc.inputStream.bufferedReader().readLines()
                .filter { it.contains("LocalFakeServer") || it.contains("Gateway") || it.contains("TraeUpstream") }
                .takeLast(60)
                .reversed()   // logcat 是时间正序；排障要看的是「刚刚发生了什么」，放顶上
        }.getOrDefault(listOf("（读不到系统日志）"))
        val scroller = ScrollView(this).apply {
            addView(mono(lines.joinToString("\n"), Tokens.TEXT_XS, colorName = "on_surface_variant")
                .apply { setPadding(dp(12), dp(12), dp(12), dp(12)) })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroller, LinearLayout.LayoutParams(-1, dp(340)))
            addView(ghostBtn("复制全部日志") {
                copyToClipboard("serviceLog", lines.joinToString("\n"), "日志已复制")
            }, lp(-2, -2, top = 10))
        }
        confirmDialog(title = "服务日志", message = "共 ${lines.size} 行 · 最新在上", okText = "关闭",
            cancelText = null, content = body) {}
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
        val pending = items.filter { it.needsAction }
        val rom = KeepAlive.rom()

        // 顶部总状态（首卡不留上间距，紧跟页面顶部）
        addView(glassCard(topMargin = 2) {
            addView(row {
                addView(text(if (pending.isEmpty()) "保活已就绪" else "还有 ${pending.size} 项待开启",
                    Tokens.TEXT_MD, true), weighted())
                addView(if (pending.isEmpty()) chipOk("全部正常") else chipWarn("需要处理"),
                    lp(-2, -2, start = 8))
            })
            addView(captionText(if (pending.isEmpty())
                "网关已能在后台长期运行，切到别的 App、息屏都不会断。"
            else
                "下面标 ⚠ 的项会让服务在真机上被系统清掉，建议逐项开启。"), top(8))
        })

        // 一键配置
        val autoCount = pending.count { it.autoOpenable }
        addView(glassCard {
            addView(text("一键配置", Tokens.TEXT_MD, true))
            addView(captionText(if (autoCount == 0)
                "能自动跳转的项都已就绪。"
            else
                "有 $autoCount 项可以自动打开系统页面，你点「允许」即可。"), top(8))
            // 全就绪时不再挂一个"开始自动配置"的大按钮 —— 那会让人以为还有事没做。
            if (autoCount > 0) {
                addView(glassButton("开始自动配置（$autoCount 项）", style = BtnStyle.PRIMARY) {
                    runAutoConfig()
                }, top(Tokens.SPACE_3))
            } else {
                addView(glassButton("重新检查", style = BtnStyle.GHOST) { openKeepAlive() }, top(Tokens.SPACE_3))
            }
        })

        // 逐项清单
        addView(sectionRow("逐项检查", 0))
        addView(glassCard(topMargin = 0, padH = 0, padV = 0) {
            items.forEachIndexed { i, it ->
                if (i > 0) addView(rowDivider())
                // 三态：✓ 已就绪 / ⚠ 需处理 / — 查不到。
                // 「查不到」既不能打勾（编造事实），也不能打⚠（会永远挂在待办上，见 Item.detectable）。
                val glyph = if (!it.detectable) "—" else if (it.ok) "✓" else "⚠"
                val tone = if (!it.detectable) "on_surface_variant" else if (it.ok) "success" else "warning"
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(Tokens.CARD_PAD), dp(12), dp(Tokens.CARD_PAD), dp(12))
                    addView(row {
                        addView(text(glyph, Tokens.TEXT_MD, true, tone), lp(-2, -2, end = 10))
                        addView(text(it.title, Tokens.TEXT_MD, true), weighted())
                        when {
                            !it.detectable -> addView(chipMute("无法检测"), lp(-2, -2, start = 8))
                            it.needsAction -> addView(
                                if (it.autoOpenable) chipMute("可一键") else chipWarn("需手动"),
                                lp(-2, -2, start = 8))
                        }
                    })
                    addView(captionText(it.why), top(4))
                    if (it.needsAction && it.autoOpenable) {
                        addView(ghostBtn("去开启") { openAutoItem(it) }, top(8))
                    }
                })
            }
        })

        // 厂商私有路径（系统查不到、跳不了）
        addView(sectionRow("本机需要手动确认的项", 0))
        addView(glassCard(topMargin = 0) {
            addView(text("检测到：${rom.label}", Tokens.TEXT_MD, true))
            addView(captionText("以下页面系统不开放跳转，需要你按路径自己找一下。"), top(6))
            KeepAlive.romGuide(rom).forEach { line ->
                addView(captionText("• $line"), top(6))
            }
            addView(ghostBtn("复制这些路径") {
                copyToClipboard("keepAliveGuide", KeepAlive.romGuide(rom).joinToString("\n"),
                    "路径已复制，可到系统设置里照着找")
            }, top(Tokens.SPACE_3))
        })
        addView(View(this@MainActivity), lp(-1, dp(84)))
    }

    /** 「一键配置」：按顺序跳到**第一个**未就绪且可自动打开的项。 */
    private fun runAutoConfig() {
        val next = KeepAlive.check(this).firstOrNull { it.needsAction && it.autoOpenable }
        if (next == null) {
            toast("能自动配置的项都已就绪")
            return
        }
        // 一次只跳一个：连着弹多个系统页面会互相顶掉，用户也来不及看。
        openAutoItem(next)
    }

    /**
     * 打开某一项对应的系统页面。跳转动作由 [KeepAlive.Item.open] 自带，
     * 不再按中文标题字符串匹配（文案改一个字就静默失效的那种错配）。
     */
    private fun openAutoItem(item: KeepAlive.Item) {
        val ok = runCatching { item.open?.invoke(this) == true }.getOrDefault(false)
        if (!ok) {
            toast("这个页面在本机打不开，请按下方路径手动设置")
        }
        // 用户可能去系统页面勾选后返回，回来时重绘一次以便显示最新状态。
        // 界面若已销毁就不要再重建视图树（用户此时在系统设置页，回来也不会看这个实例）。
        contentHost.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            // 走 redraw()（不播入场）：这是"从系统设置页回来重读一遍"，不是导航到新页；
            // 旧版写 openKeepAlive()，会把用户正在看的保活页重建并整页淡入一次。
            if (subPage == SUB_KEEP_ALIVE) redraw()
        }, 800)
    }

    // ------------------------------------------------------------------
    // 账号二级页（对齐设计稿 account-detail.html / account-more.html）
    // ------------------------------------------------------------------

    /** 账号详情：KV 表 + 切换/签到/删除 + 最近调用（设计稿 account-detail.html）。 */
    private fun accountDetailPage(meta: TraeAuthStore.AccountMeta): View = scroll {
        val isActive = meta.uid == TraeAuthStore.activeUid(this@MainActivity)
        val balance = CreditMonitor.balanceOf(meta.uid)
        val checkinLabel = when {
            isActive && accountCheckin?.checkedIn == true -> "今日已签"
            isActive && accountCheckin != null && !accountCheckin!!.enable -> "未开放"
            else -> "—"
        }
        addView(glassCard(topMargin = 2) {
            addView(labelValue("UID", meta.uid, monoValue = true), top(0))
            addView(labelValue("区域", "${regionLabel(meta.region)}（${meta.region.id.uppercase()}）"), top(6))
            addView(labelValue("积分", (balance?.let { formatNumber(it) } ?: "—") +
                (if (checkinLabel != "—" && balance != null) " · $checkinLabel +100" else "")), top(6))
            addView(labelValue("Token", tokenRenewLabel(meta.expiresAt), colorValue =
                if (meta.expiresAt > 0 && tokenRenewLabel(meta.expiresAt) == "有效") "success" else null), top(6))
            addView(labelValue("到期", formatExp(meta.expiresAt), monoValue = true), top(6))
            addView(labelValue("存储", "AndroidKeyStore 加密"), top(6))
        })

        addView(glassButton(
            if (isActive) "当前使用中" else "切换到此账号",
            style = BtnStyle.PRIMARY, enabled = !isActive,
        ) { switchAccount(meta) })

        addView(row(top = Tokens.SPACE_2) {
            addView(ghostBtn("单独签到", full = true) { checkinSingle(meta) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(this@MainActivity), lp(dp(8), 1))
            addView(ghostBtn("删除", full = true, danger = true) { confirmRemoveAccount(meta) },
                LinearLayout.LayoutParams(0, -2, 1f))
        })

        // 最近调用：只显示这个账号的（UsageStore 记录带 uid；升级前旧记录无归属，不塞给任何账号）
        addView(sectionRow("最近调用", 0))
        val recent = UsageStore.recentFor(this@MainActivity, meta.uid, 5)
        val recentCard = glassCard(topMargin = 0, padH = 0, padV = 0) {}
        if (recent.isEmpty()) {
            val pad = dp(Tokens.CARD_PAD)
            recentCard.addView(captionText(
                if (UsageStore.hasLegacyRecords(this@MainActivity))
                    "这个账号还没有调用记录。升级前的旧记录没有账号归属，不会显示在这里。"
                else "这个账号还没有调用记录。"
            ).apply { setPadding(pad, pad, pad, pad) })
        } else {
            recent.forEachIndexed { i, r ->
                val row = ListRow.record(this@MainActivity, r.ok, r.model,
                    "${r.prompt} · ${r.completion} · ${r.durationMs}ms",
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(r.ts)))
                row.showDivider(i > 0)
                recentCard.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        addView(recentCard)
        addView(View(this@MainActivity), lp(-1, dp(84)))
    }

    /** 账号设置：添加账号、自动换号、备份、定时签到（设计稿 account-more.html）。 */
    private fun accountMorePage(): View = scroll {
        // ---- 添加账号 ----
        addView(sectionRow("添加账号", 0))
        addView(glassCard(topMargin = 0) {
            addView(captionText(
                "1. 复制对应区域的登录链接\n" +
                "2. 在浏览器打开并完成登录\n" +
                "3. 登录后页面打不开，这是正常的（地址栏停在下面这个地址）\n" +
                "4. 复制整条链接，回到 App 粘贴"))
            addView(mono("127.0.0.1:18080", Tokens.TEXT_XS).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = shape("surface_container", Tokens.RADIUS_SMALL)
            }, top(Tokens.SPACE_1))
            addView(ghostBtn("复制国服登录链接", full = true) { copyLoginUrl(TraeRegion.CN) }, top(Tokens.SPACE_3))
            addView(ghostBtn("复制国际服登录链接", full = true) {
                toast(TraeRegion.SG.unsupportedNote)
                copyLoginUrl(TraeRegion.SG)
            }, top(Tokens.SPACE_2))
            addView(captionText("国际服链路尚未开发，登录后不会生效。", "warning"), top(Tokens.SPACE_2))
            addView(glassButton("粘贴回调链接完成登录", style = BtnStyle.PRIMARY) { promptPasteCallback() },
                top(Tokens.SPACE_3))
        })

        // ---- 积分监测 · 自动换号 ----
        addView(sectionRow("积分监测 · 自动换号", 0))
        addView(glassCard(topMargin = 0) {
            val autoOn = CreditMonitor.isAutoSwitchEnabled(this@MainActivity)
            addView(toggleRow("自动换号", "余额低于阈值时切到余额最多的国服账号", autoOn) {
                val next = !CreditMonitor.isAutoSwitchEnabled(this@MainActivity)
                CreditMonitor.setAutoSwitchEnabled(this@MainActivity, next)
                toast(if (next) "自动换号已开启" else "自动换号已关闭")
                redraw()
            })
            addView(topLineSection(4) {
                addView(row {
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text("触发阈值", Tokens.TEXT_MD, false))
                        addView(captionText("当前 ${CreditMonitor.threshold(this@MainActivity)} 积分")
                            .let { it.setPadding(0, dp(2), 0, 0); it })
                    }, weighted())
                    addView(TextAction(this@MainActivity, "修改") { promptThreshold { redraw() } }, lp(-2, -2))
                })
            }, top(Tokens.SPACE_2))
            addView(captionText(monitorStatusText()).also { statusView ->
                // 监测器每 30 秒回调一次，只改这一行文字（重建页面会把滚动位置弹回去）
                monitorApply = { statusView.text = monitorStatusText(); Motion.fadeState(statusView) }
            }.let { it.setPadding(0, dp(10), 0, 0); it })
        })

        // ---- 备份 ----
        addView(sectionRow("备份", 0))
        addView(glassCard(topMargin = 0) {
            addView(captionText(
                "导出内容含明文 access / refresh token，等于账号钥匙 —— 只在本机与自己的设备之间传。" +
                    "同一份文件别在多台设备同时用：refresh token 是轮换的，后来的会让先前的失效。"))
            addView(row(top = Tokens.SPACE_3) {
                addView(ghostBtn("导出为文件", full = true) { promptExportAccounts() },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(View(this@MainActivity), lp(dp(8), 1))
                addView(ghostBtn("从文件导入", full = true) { importFromFile() },
                    LinearLayout.LayoutParams(0, -2, 1f))
            })
        })

        // ---- 签到时间 ----
        addView(sectionRow("签到时间", 0))
        addView(glassCard(topMargin = 0) {
            val schedOn = BatchCheckin.isEnabled(this@MainActivity)
            val time = BatchCheckin.timeText(this@MainActivity)
            addView(toggleRow("每日定时签到", "每天 $time · 全部国服账号", schedOn) {
                if (BatchCheckin.isEnabled(this@MainActivity)) {
                    BatchCheckin.disable(this@MainActivity)
                    toast("定时签到已关闭")
                } else {
                    BatchCheckin.enable(this@MainActivity, BatchCheckin.timeText(this@MainActivity))
                    toast("定时签到已开启：每天 ${BatchCheckin.timeText(this@MainActivity)}")
                }
                redraw()
            })
            if (schedOn) {
                addView(topLineSection(4) {
                    addView(row {
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text("触发时刻", Tokens.TEXT_MD, false))
                            addView(captionText(time).let { it.setPadding(0, dp(2), 0, 0); it })
                        }, weighted())
                        addView(TextAction(this@MainActivity, "修改") { promptPickTime() }, lp(-2, -2))
                    })
                }, top(Tokens.SPACE_2))
            }
            addView(captionText(
                "到点若 App 没在后台（重启 / 被系统清理），下次打开会自动补签；手机重启后需打开一次 App 恢复闹钟。"),
                top(Tokens.SPACE_2))
        })
        addView(View(this@MainActivity), lp(-1, dp(84)))
    }

    /** 「标题 + 副标题 + 右侧开关」一行（账号设置页）。 */
    private fun toggleRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit): LinearLayout =
        row {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, Tokens.TEXT_MD, false))
                addView(captionText(sub).let { it.setPadding(0, dp(2), 0, 0); it })
            }, weighted())
            addView(Switch(this@MainActivity, on) { onToggle() }, lp(-2, -2))
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
        // 顺手把系统浏览器拉起来：「立即登录」与下方的「复制国服登录链接」本来是同一个动作
        // （只复制），用户点完还得自己去桌面找浏览器。这里多做一步，两个入口才有区别。
        if (!region.canLogin) return
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(TraeAuthApi.buildLoginUrl())))
        }.isSuccess
        if (!opened) toast("本机没找到浏览器，链接已复制，请手动打开")
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
            setPadding(dp(Tokens.CARD_PAD), dp(Tokens.CARD_PAD), dp(Tokens.CARD_PAD), dp(Tokens.CARD_PAD))
            addView(text(title, Tokens.TEXT_MD, true))
            addView(text(message, Tokens.TEXT_XS, false, "on_surface_variant"), top(8))
            content?.let { addView(it, top(14)) }
            addView(row(8) {
                if (cancelText != null) {
                    addView(ghostBtn(cancelText) { dialog?.dismiss() }, weighted())
                }
                addView(glassButton(okText, if (danger) BtnStyle.DANGER else BtnStyle.PRIMARY, full = false) {
                    if (okGuard?.invoke() == false) return@glassButton
                    dialog?.dismiss()
                    onOk()
                }, weighted(start = if (cancelText != null) 8 else 0))
            }, top(18))
        }
        val dlg = AlertDialog.Builder(this).setView(panel).create()
        dialog = dlg
        trackDialog(dlg)
        // 窗口底换成当前风格的 surface + 统一圆角，替掉平台那层灰底
        dlg.setOnShowListener { dlg.window?.setBackgroundDrawable(shape("surface", Tokens.RADIUS_CARD)) }
        // 统一进出动画（style: DialogMotion → dialog_enter/dialog_exit）。
        // 必须在 show() 之前设：窗口动画是窗口被 add 的那一刻取的。
        Motion.applyDialogMotion(dlg)
        dlg.show()
        return dlg
    }

    /**
     * 活着的应用内弹窗（onDestroy 要关掉）。
     *
     * 不收集的话，Activity 被销毁时这些窗口还挂在 WindowManager 上：既泄漏，又会在
     * 下一次进来时留下一个"没主的"灰框（真机观感：屏幕中间一块半透明）。
     */
    private val liveDialogs = mutableListOf<android.app.Dialog>()

    private fun <T : android.app.Dialog> trackDialog(dlg: T): T {
        liveDialogs += dlg
        dlg.setOnDismissListener { liveDialogs.remove(dlg) }
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
        copyToClipboard("traeLoginUrl", url, "${regionLabel(region)}登录链接已复制，请用系统浏览器打开并登录", sensitive = true)
    }

    /**
     * 切到指定账号：改 active uid，并把**区域切成该账号自己的区域**（转发才会打对 host），
     * 然后重新载入 token（必要时续期）并重绘。
     */
    private fun switchAccount(meta: TraeAuthStore.AccountMeta) {
        val uid = meta.uid
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
    /**
     * 执行切换（**后台线程**：`switchTo` 内含一次同步 commit，不能落主线程）。
     *
     * 切换成功的判定点是 `switchTo` 返回 Success —— 本地 active 已写入。
     * 之后的 `loadAndRefreshToken` 是异步续期，成功与否都不改变"已切换"这个事实。
     */
    private fun doSwitchAccount(meta: TraeAuthStore.AccountMeta) {
        thread(name = "account-switch") {
            val r = TraeAuthStore.switchTo(this, meta.uid)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    TraeAuthStore.StoreResult.Success -> {
                        // 区域以账号为准（锁内校验 active 一致才写，旧线程不会覆盖新账号）
                        TraeAuthStore.publishRegionIfActive(this, meta.uid, meta.region)
                        // 切号 = 换了身份：上一个账号的积分/签到状态作废
                        invalidateAccountQuota()
                        toast("已切换到 ${meta.nickname.ifBlank { meta.uid }}")
                        loadAndRefreshToken()
                        render(Page.ACCOUNT)
                    }
                    TraeAuthStore.StoreResult.NotFound -> {
                        toast("账号已不存在，列表已刷新")
                        render(Page.ACCOUNT)
                    }
                    TraeAuthStore.StoreResult.PersistenceLocked ->
                        toast("存储异常，已暂停修改。请重启 App 后重试")
                    TraeAuthStore.StoreResult.PersistFailed ->
                        toast("保存失败，切换可能未写入；请重试")
                    else -> toast("切换失败")
                }
            }
        }
    }

    /** 删除账号（二次确认）：连密文一起删，避免残留凭据。**后台线程**执行写事务。 */
    private fun confirmRemoveAccount(meta: TraeAuthStore.AccountMeta) {
        confirmDialog(
            title = "删除账号",
            message = "将删除「${meta.nickname.ifBlank { meta.uid }}」的本地凭据（含加密 token）。\n" +
                "不影响 Trae 服务端的账号本身。",
            okText = "删除",
            danger = true,
        ) {
            thread(name = "account-remove") {
                val r = TraeAuthStore.remove(this, meta.uid)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (r) {
                        TraeAuthStore.StoreResult.Success -> {
                            // 删掉的若是当前账号，缓存必须作废（下一个激活账号不一样）
                            invalidateAccountQuota()
                            // active 已回退 → 区域要跟上，否则会用已删账号的区
                            TraeAuthStore.syncRegionToActiveAccount(this)
                            toast("已删除")
                            render(currentPage)
                        }
                        TraeAuthStore.StoreResult.NotFound -> {
                            toast("账号已不存在，列表已刷新")
                            render(currentPage)
                        }
                        TraeAuthStore.StoreResult.PersistenceLocked ->
                            toast("存储异常，已暂停修改。请重启 App 后重试")
                        TraeAuthStore.StoreResult.PersistFailed ->
                            toast("删除失败（存储空间不足？），凭据可能未清理")
                        else -> toast("删除失败")
                    }
                }
            }
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
            background = shape("surface_container", Tokens.RADIUS_CONTROL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val dlg = confirmDialog(
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
        // 这个弹窗存在的唯一目的就是"粘一串东西"，让用户先点一下输入框才能粘贴是多余的一步。
        edit.requestFocus()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun copyApiKey() = copyToClipboard("apiKey", apiKey, "已复制 API Key", sensitive = true)

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

    /** 邮箱复制：手打 11 位数字容易错，点一下拿完整的比让用户自己抄靠谱。 */
    private fun copyEmail() = copyToClipboard("authorEmail", AUTHOR_EMAIL, "已复制邮箱 $AUTHOR_EMAIL")

    /**
     * 复制到剪贴板 + 提示。原先三处各写一遍 getSystemService/setPrimaryClip，收口到这一处。
     *
     * [sensitive] = 内容是凭据（API Key / 登录链接里的 refreshToken）。Android 13+ 会在
     * 剪贴板浮层与剪贴板历史里**明文展示**，打上 isSensitive 后系统会遮住预览、
     * 且不写入剪贴板历史。只是一个 extra，两行代价。
     */
    private fun copyToClipboard(label: String, value: String, tip: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
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
            if (currentPage == Page.ACCOUNT && subPage == null) redraw()
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
            background = shape("surface_container", Tokens.RADIUS_CONTROL)
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
                    // 先看文件大小再读：以前是 readBytes() 全读进来之后才判 1MB，
                    // 用户误选一个视频就是一次 OOM 尝试（账号备份撑死几十 KB）。
                    val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    if (size > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException("文件太大（${size / 1024} KB），不像是账号备份")
                    }
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)
                        ?: throw java.io.IOException("无法读取该文件")
                }
                runOnUiThread {
                    result.onSuccess { text ->
                        // statSize 在部分 provider 上返回 -1（大小未知），这里再兜一次
                        if (text.length > MAX_IMPORT_BYTES) toast("文件太大，不像是账号备份")
                        else promptImportPreview(text)   // 先给预览，勾完才真正写盘
                    }.onFailure { toast("读取失败：${it.message}") }
                }
            }
        }
    }

    /**
     * 账号列表「刷新积分」：把**所有可查余额的国服账号**各刷一次。
     *
     * 三条硬约束（照 [BatchCheckin.checkinOne] 与 CreditMonitor 的锁纪律）：
     *  - 只在这一处读一次 `TraeAuthStore.list`（短锁），**绝不持 Gateway.locked 做网络**；
     *  - token 一律走 `TraeAuthApi.validTokenForUid`（锁内续期、不切 active、不读全局区域）；
     *  - 查余额显式传 `meta.region`，不看 `TraeRegion.current`（全局随时可能被别的账号改掉）。
     *
     * 失败保留旧值：拿不到就什么都不写，列表继续显示上次的数（配「多久之前」）。
     */
    private fun refreshAllQuotas() {
        if (!quotaBulkBusy.compareAndSet(false, true)) {
            toast("正在刷新积分，请稍候")
            return
        }
        // 批量签到全程在打上游，这时候再插一批余额查询只会互相等
        if (BatchCheckin.busy.get()) {
            quotaBulkBusy.set(false)
            toast("签到进行中，稍后再试")
            return
        }
        quotaBulkLabel?.text = "刷新中…"
        thread(name = "quota-bulk") {
            val metas = Gateway.locked { TraeAuthStore.list(this@MainActivity) }
            val targets = metas.filter { it.region.isSupported && it.region.canQueryQuota }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) quotaBulkLabel?.text = "刷新中 0/${targets.size}"
            }
            var ok = 0
            var activeValue: Long? = null
            val activeUid = runCatching { TraeAuthStore.activeUid(this@MainActivity) }.getOrNull()
            for ((i, meta) in targets.withIndex()) {
                // 逐个之间歇一下：连续 5 次上游往返太快容易撞设备维度风控（9074 那类）
                if (i > 0) runCatching { Thread.sleep(400) }
                try {
                    val token = TraeAuthApi.validTokenForUid(this@MainActivity, meta.uid, 3600)
                    if (!token.isNullOrBlank()) {
                        val v = TraeUpstream.fetchUsage(token, meta.uid, meta.region)
                        if (v != null) {
                            CreditMonitor.storeReading(this@MainActivity, meta.uid, v)
                            ok++
                            if (meta.uid == activeUid) activeValue = v
                        }
                    }
                } catch (t: Throwable) {
                    // 一个账号失败不拖垮整批：续期/网络都可能抛，继续下一个
                    android.util.Log.w("MainActivity", "刷新积分失败 ${meta.uid}: ${t.javaClass.simpleName}: ${t.message}")
                }
                val done = i + 1
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) quotaBulkLabel?.text = "刷新中 $done/${targets.size}"
                }
            }
            runOnUiThread {
                quotaBulkBusy.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                quotaBulkLabel?.text = "刷新积分"
                // 当前账号刷新成功时顺带更新顶上那张「当前可用积分」卡（原地，不重建页面）
                activeValue?.let {
                    accountBalance = it
                    quotaApply?.invoke()
                }
                toast("已更新 $ok 个账号")
                // 列表副标题是整页一部分：重建时保持滚动位置，别把人弹回页首
                if (currentPage == Page.ACCOUNT && subPage == null) renderKeepingScroll(Page.ACCOUNT)
            }
        }
    }

    /**
     * 拉积分/额度（后台线程，用解密后的真实 token 打 ug 接口），成功后原地更新控件。
     *
     * [quiet] = **自动拉取**（进账号页时触发）：不弹 toast、未登录也不提示。
     * 用户没点任何按钮，屏幕上不该出现"已更新"或"未登录"——那是噪声。
     */
    private fun refreshQuota(quiet: Boolean = false) {
        val cred = TraeAuthStore.load(this)
        if (cred == null || cred.accessToken.isBlank()) {
            if (!quiet) toast("未登录 Trae，请先登录")
            return
        }
        if (quotaLoading) return
        // 数值位立刻变「…」：网络往返有几百毫秒，不给反馈用户会以为没点动。
        // 原地改控件而不是 redraw() —— 重建页面会把滚动位置弹回页首，用户就看不见这张卡了。
        quotaLoading = true
        quotaApply?.invoke()
        thread(name = "trae-fetch-quota") {
            // runCatching 同 refreshModels：抛异常会让线程在复位前死掉，`quotaLoading`
            // 卡 true → 按钮永远「刷新中…」且不再响应点击。
            val pair = runCatching {
                TraeUpstream.fetchUsage(cred.accessToken, cred.uid, cred.region) to
                    TraeUpstream.fetchCheckin(cred.accessToken, cred.uid, cred.region)
            }.getOrNull()
            val u = pair?.first
            val c = pair?.second
            runOnUiThread {
                if (isFinishing || isDestroyed) { quotaLoading = false; return@runOnUiThread }
                // ⚠️ 写回前比对账号：等待期间用户可能切过号（自动换号也会），
                // 无条件写回会让新账号显示上一个账号的积分/签到状态。
                if (TraeAuthStore.activeUid(this@MainActivity) != cred.uid) {
                    quotaLoading = false
                    quotaApply?.invoke()
                    return@runOnUiThread
                }
                // 拉失败时**保留旧值**（与 claimCheckin 一致）：网络抖一下就把已显示的
                // 积分/签到状态擦回「—」是净损失，用户会以为数据丢了。
                accountBalance = u ?: accountBalance
                accountCheckin = c ?: accountCheckin
                quotaLoading = false
                // [quiet] = 进账号页的自动拉取：用户只是点了个 Tab，不该被"已更新"打扰。
                // 只有他真的点了「刷新」才需要回执。
                if (!quiet) toast(if (u == null) "未获取到额度" else "已更新")
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
                val known = before ?: TraeUpstream.fetchCheckin(cred.accessToken, cred.uid, cred.region)
                if (known?.checkedIn == true) {
                    Triple(known, null, null as Long?)
                } else if (known?.enable == false) {
                    Triple(known, null, null as Long?)
                } else {
                    val code = TraeUpstream.claimCheckin(cred.accessToken, cred.uid, cred.region)
                    // 签到后重查积分与状态：拿到最新余额，同时把按钮切成「今日已签到」
                    val u = TraeUpstream.fetchUsage(cred.accessToken, cred.uid, cred.region)
                    val after = TraeUpstream.fetchCheckin(cred.accessToken, cred.uid, cred.region)
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
                // ⚠️ 写回前比对账号：这次请求是给 cred.uid 发的，等待期间用户可能已经切号。
                // 无条件写回就会出现"新账号顶着上一个账号的签到状态/余额"（同一个误报的入口）。
                if (TraeAuthStore.activeUid(this@MainActivity) != cred.uid) {
                    toast("签到请求已完成，但你已切换账号，结果未写入当前账号")
                    return@runOnUiThread
                }
                accountBalance = u ?: accountBalance
                accountCheckin = after ?: accountCheckin
                toast(
                    when {
                        TraeUpstream.checkinSucceeded(known, after, code) -> "签到成功"
                        // 成功码但**重查没回来**：code=0 是成功码，上游多半已经签上了，
                        // 只是那次状态重查失败（网络抖一下）。以前这里落到 else 分支报
                        // "签到未生效（code=0）"，等于把成功说成失败。
                        code == 0 && after == null -> "签到请求已提交，状态未取回，请点「刷新」确认"
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
            val arr = runCatching { TraeUpstream.fetchModels(cred.accessToken, cred.uid, cred.region) }.getOrNull()
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
                // 只有真拉到表才让列表淡入一次；失败时按钮已经恢复，列表不该跟着闪
                if (arr != null) modelsListPulse?.invoke()
            }
        }
    }

    /**
     * 进 App 自动补新：模型 Tab / 客户端 `/v1/models` 的数据源重启后来自磁盘缓存，
     * 可能落后于上游 —— 静默刷一次，让用户无感拿到最新表。
     *
     * 判据**不能**用 `ModelsRepo.isDynamic`（init 装过磁盘缓存后它恒为 true，
     * 有缓存的设备将永远补不到新表）。标志必须放在 companion（**进程级**）：
     * 写成 Activity 实例字段的话，切外观风格触发的 `recreate()` 会再刷一次上游 ——
     * 注释说"每个冷启动恰好刷一次"就与实现不一致了。
     */
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
            // 失败时如实报原因（典型：端口被占）。以前这种情况是进程崩溃，
            // 现在至少能把"为什么没起来"说清楚，用户才知道该去关掉什么。
            toast(
                when {
                    Gateway.lastStartError != null -> "启动失败：${Gateway.lastStartError}"
                    stopping -> "服务已停止"
                    else -> "服务已启动"
                }
            )
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
            // ⚠️ token 失效（ExchangeToken 401）是常态而非异常：未捕获的 IOException 会崩掉
            // 整个进程 —— 必须兜住，失败时 UI 走「未加载/未登录」展示，用户重新登录即可。
            runCatching {
                Gateway.locked {
                    TraeAuthApi.restartWithValidToken(this, skewSec = SIXTY_MINUTES_SEC)
                }
            }.onFailure { e ->
                android.util.Log.w("MainActivity", "token 刷新失败: ${e.javaClass.simpleName}: ${e.message}")
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
        // ---- 动效收尾：动画、待执行任务、原地刷新句柄、弹窗，一个都不能留给已销毁的界面 ----
        // 让还在跑的 post 全部作废（页面代次一变，过期的入场动画就会自己退出）
        pageGeneration++
        if (::contentHost.isInitialized) {
            // 先摘掉可能还在排队的 toast 隐藏任务，再取消动画
            val host = findViewById<ViewGroup>(android.R.id.content)
            toastHide?.let { h -> host?.removeCallbacks(h) }
            toastView?.let { v ->
                v.animate().cancel()
                runCatching { host?.removeView(v) }
            }
            toastView = null
            toastHide = null
            contentHost.animate().cancel()
            (contentHost.getChildAt(0))?.animate()?.cancel()
            // 内容区的滚动位置恢复是 post 到 contentHost 上的，销毁后不会再跑，无需 removeCallbacks
        }
        quotaApply = null
        modelsRefreshApply = null
        modelsListPulse = null
        monitorApply = null
        refreshLabelRef = null
        // 还开着的应用内弹窗：不关就是窗口泄漏，下次进来还会看到一块没主的半透明面板
        liveDialogs.toList().forEach { runCatching { it.dismiss() } }
        liveDialogs.clear()
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
    // View 组装 helper（玻璃质感；对应设计稿 app.css 的类）
    // 改样式只改这一段；尺寸走 Tokens（tokens.css），颜色走 AppStyle 语义名。
    // ------------------------------------------------------------------

    /** 滚动容器：内边距 = 页边距 20dp；底部留出胶囊高度 + 距底 + 余量（滚到底不被胶囊压住）。 */
    private fun scroll(build: LinearLayout.() -> Unit): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(Tokens.PAGE_PAD_H), dp(Tokens.PAGE_PAD_TOP), dp(Tokens.PAGE_PAD_H),
                dp(Tokens.NAV_HEIGHT + Tokens.NAV_BOTTOM_MARGIN + Tokens.SPACE_4)
            )
            build()
        }
        // 平板/横屏下把内容限宽居中，否则一行文字会横跨整个 3440px 屏幕
        sv.addView(col, FrameLayout.LayoutParams(contentWidth(), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sv.clipToPadding = false
        return sv
    }

    /** 手机竖屏 = 屏宽（不受影响）；平板/横屏 = NAV_MAX_DP 并居中。 */
    private fun contentWidth(): Int = minOf(resources.displayMetrics.widthPixels, dp(Tokens.NAV_MAX_WIDTH))

    /**
     * 玻璃卡（对应设计稿 `.card`）。语气见 [GlassCard.Tone]。
     *
     * [padH]=0 且 [padV]=0 时得到"分隔线撑满整宽"的行式列表卡（ListRow 同款做法）。
     */
    private fun glassCard(
        tone: GlassCard.Tone = GlassCard.Tone.NORMAL,
        topMargin: Int = Tokens.CARD_GAP,
        padH: Int = Tokens.CARD_PAD,
        padV: Int = (Tokens.CARD_PAD * 0.875f).toInt(),
        build: LinearLayout.() -> Unit,
    ): LinearLayout = GlassCard(this, tone).apply {
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        build()
        layoutParams = lp(-1, -2, top = topMargin)
    }

    private fun row(top: Int = 0, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
        layoutParams = lp(-1, -2, top = top)
    }

    private fun text(value: String, size: Float = Tokens.TEXT_MD, bold: Boolean = false, colorName: String = "on_surface"): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(colorName))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.35f)
        includeFontPadding = false
    }

    /** 等宽文本：地址、模型 id、时间戳用它，数字和标识符能对齐。 */
    private fun mono(value: String, size: Float = Tokens.TEXT_XS, bold: Boolean = false, colorName: String = "on_surface"): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color(colorName))
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(0f, 1.3f)
            includeFontPadding = false
        }

    /** 说明文字（11sp，次要色；tokens.css .caption）。 */
    private fun captionText(value: String, colorName: String = "on_surface_variant"): TextView =
        text(value, Tokens.TEXT_XS, false, colorName).apply { setLineSpacing(0f, 1.6f) }

    /**
     * 区块标题（11sp 600；tokens.css .section-label）。
     *
     * letterSpacing 压到 0：0.06em 的加宽是给拉丁字型的，中文加宽后笔画散、
     * 层级反而糊（unslop 审计 P5）——中文靠颜色和字号分层就够。
     */
    private fun section(value: String): TextView = text(value, Tokens.TEXT_XS, true, "on_surface_variant").apply {
        letterSpacing = 0f
        setPadding(0, dp(22), 0, dp(8))
        layoutParams = lp(-1, -2)
    }

    /** [section] 的带间距版本：挂进竖直容器时用（addView 不收 margin 参数）。 */
    private fun sectionRow(value: String, topMargin: Int = 0): View = section(value).apply {
        layoutParams = lp(-1, -2, top = topMargin)
    }

    /**
     * 卡内分区容器（对应 tokens.css 的 `.card` 内 border-top 分区）。
     *
     * 顶部一条 1px outline_variant 分隔线 + 下方内容区（[topPad] dp 顶距）。
     * GradientDrawable 做不了"只有顶边"的线，所以用「1px View + 内容容器」实现；
     * 调用形态：`addView(topLineSection(14) { addView(...) }, top(14))`。
     */
    private fun topLineSection(topPad: Int, build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@MainActivity).apply {
                setBackgroundColor(color("outline_variant"))
            }, lp(-1, dp(1)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(topPad), 0, 0)
                build()
            }, lp(-1, -2))
            layoutParams = lp(-1, -2)
        }

    /** 卡内分区行分隔线（挂在 vertical LinearLayout 中间，撑满宽）。 */
    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(color("outline_variant"))
        layoutParams = lp(-1, dp(1), top = 0)
    }

    /**
     * 键值行（tokens.css .kv）：标签列 minWidth 84dp，值可长按选择复制。
     *
     * [colorValue] 给 Token「有效」这类需要着色的值用；null = 默认正文色。
     */
    private fun labelValue(
        label: String,
        value: String,
        monoValue: Boolean = false,
        colorValue: String? = null,
        decorate: ((TextView) -> Unit)? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(-1, -2)
        addView(captionText(label).apply { minWidth = dp(84) }, lp(-2, -2))
        val v = if (monoValue) mono(value, Tokens.TEXT_XS, colorName = colorValue ?: "on_surface")
        else text(value, Tokens.TEXT_XS, false, colorValue ?: "on_surface")
        v.setTextIsSelectable(true)
        decorate?.invoke(v)
        addView(v, weighted(start = 10))
        setPadding(0, dp(3), 0, dp(3))
    }

    /**
     * 占比条（tokens.css .bar）：整条 = [value] 占 [max] 的比例。
     * [idx] 决定填充色（首条主色，其后 accent / muted 轮替），与设计稿 records.html 一致。
     */
    private fun shareBar(value: Long, max: Long, idx: Int = 0): View = FrameLayout(this).apply {
        background = shape("surface_container", Tokens.RADIUS_PILL)
        val fill = View(this@MainActivity).apply {
            background = shape(
                when (idx % 3) { 0 -> "primary"; 1 -> "accent"; else -> "on_surface_variant" },
                Tokens.RADIUS_PILL)
        }
        val ratio = if (max <= 0L) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
        // 初始宽度必须是 **0**，不能是 WRAP_CONTENT：
        // FrameLayout 里 WRAP_CONTENT 的子 View 会走 View.getDefaultSize(AT_MOST) → 返回 specSize，
        // 也就是**撑满整行**。于是每次进记录页都先画一条满格彩条，post 里再改成真实长度 ——
        // 眼睛看到的就是"闪一下满进度然后秒回"。
        addView(fill, FrameLayout.LayoutParams(0, dp(6)))
        // 递归等一次真实宽度：post 有可能赶在 layout 之前跑到，那时 width=0，
        // 按它算出来会永远是一条 6dp 的点。宽度拿到前不落最终值。
        fun applyRatio() {
            val w = width
            if (w <= 0) {
                // 只有还挂在窗口上才继续等：detach 之后继续 post 会变成无休止的排队
                // （记录页反复进出时能看到主线程被这些空转 Runnable 占着）。
                if (isAttachedToWindow) post { applyRatio() }
                return
            }
            fill.layoutParams = FrameLayout.LayoutParams((w * ratio).toInt().coerceAtLeast(dp(6)), dp(6))
        }
        post { applyRatio() }
        minimumHeight = dp(6)
    }

    /** 搜索框（tokens.css .search：玻璃加强底 + 圆角 12）。 */
    private fun searchInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(color("on_surface"))
        setHintTextColor(color("on_surface_variant"))
        background = Tokens.alpha(color("glass_strong"), if (AppStyle.isNight) Tokens.GLASS_STRONG_ALPHA_DARK else Tokens.GLASS_STRONG_ALPHA)
            .let { GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(it); cornerRadius = dp(Tokens.RADIUS_CONTROL).toFloat() } }
            .let { d -> d.apply { setStroke(dp(1), Tokens.alpha(color("glass_line"), if (AppStyle.isNight) Tokens.GLASS_LINE_ALPHA_DARK else Tokens.GLASS_LINE_ALPHA)) } }
        minHeight = dp(Tokens.MIN_TOUCH)
        setPadding(dp(13), dp(12), dp(13), dp(12))
    }

    /**
     * 主/次按钮（tokens.css .btn.primary / .btn.ghost / .btn.danger）。默认高度 44dp。
     *
     * [compact] = 行内小按钮（自适应宽度）；缺省撑满（block）。
     * [heightDp] / [textSize] 供"行内小按钮"用：44dp 高塞进地址行会把整行撑起来，
     * 文字被顶到行中央，看上去就是"地址和按钮对不齐"。
     */
    private fun glassButton(
        label: String,
        style: BtnStyle = BtnStyle.PRIMARY,
        full: Boolean = true,
        compact: Boolean = false,
        enabled: Boolean = true,
        heightDp: Int = 44,
        textSize: Float = Tokens.TEXT_MD,
        click: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        this.textSize = textSize
        gravity = Gravity.CENTER
        minHeight = dp(heightDp)
        // 左右必须对称。原先右 padding 写的是 0 —— 对撑满宽的 block 按钮没影响（宽度固定、
        // gravity=CENTER），但 wrap_content 的行内小按钮会把文字顶到最右侧：
        // 「复制」两个字看着不在按钮中间，而是贴右边缘。
        val padH = dp(if (compact || !full) 14 else 18)
        setPadding(padH, 0, padH, 0)
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
        background = when (style) {
            BtnStyle.PRIMARY -> shape("primary", Tokens.RADIUS_CONTROL)
            // 深色下玻璃底(#1D242D)与卡片(#141A22)只差 9/255 的亮度 —— 按钮看上去像一行纯文字，
            // 用户不知道能点。深色改用 surface_variant 垫底 + 半透明 outline 描边，边界才立得住。
            BtnStyle.GHOST -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(Tokens.RADIUS_CONTROL).toFloat()
                setColor(
                    if (AppStyle.isNight) color("surface_variant")
                    else Tokens.alpha(color("glass_strong"), Tokens.GLASS_STRONG_ALPHA)
                )
                setStroke(dp(1), if (AppStyle.isNight) Tokens.alpha(color("outline"), 0.55f)
                else Tokens.alpha(color("glass_line"), Tokens.GLASS_LINE_ALPHA))
            }
            BtnStyle.DANGER -> shape("error_container", Tokens.RADIUS_CONTROL)
        }
        setTextColor(color(when (style) {
            BtnStyle.PRIMARY -> "on_primary"
            BtnStyle.GHOST -> "on_surface"
            BtnStyle.DANGER -> "error"
        }))
        setOnClickListener { if (isEnabled) click() }
        // 按压反馈：统一走 Motion（按下 0.98 / 70ms，抬起 120ms），不再在这里单写一套
        // 0.92 + 2dp 下沉 —— 那是全站最重的一处反馈，"点个复制像按了个实体键"。
        // 触摸监听返回 false：UP 照常分发 click，不加延迟、不拦长按。
        if (enabled) Motion.installPressFeedback(this)
        layoutParams = lp(if (full) -1 else -2, dp(44))
    }

    private enum class BtnStyle { PRIMARY, GHOST, DANGER }

    /** 次按钮（.btn.ghost 的 compact 版本，行内使用、自适应宽）。 */
    private fun ghostBtn(
        label: String,
        full: Boolean = false,
        danger: Boolean = false,
        heightDp: Int = 44,
        textSize: Float = Tokens.TEXT_MD,
        click: () -> Unit,
    ): TextView =
        glassButton(label, if (danger) BtnStyle.DANGER else BtnStyle.GHOST, full = full,
            heightDp = heightDp, textSize = textSize) { click() }

    /** 呼吸状态点（tokens.css .pulse：绿色圆点 + 光晕；停止时灰色实心）。 */
    private fun pulseDot(running: Boolean): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(if (running) "success" else "on_surface_variant"))
        }
        layoutParams = lp(-2, -2)
        contentDescription = if (running) "运行中" else "已停止"
    }

    /**
     * 底层胶囊（tokens.css .chip）。语义场景请用 chipOk / chipWarn / chipBad / chipMute / chipStatic。
     */
    private fun chip(value: String, backgroundName: String, textName: String, withDot: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(26)
            setPadding(dp(9), 0, dp(9), 0)
            background = shape(backgroundName, Tokens.RADIUS_PILL)
            if (withDot) addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(textName)) }
            }, lp(dp(6), dp(6), end = 5))
            addView(text(value, Tokens.TEXT_XS, true, textName))
            layoutParams = lp(-2, -2)   // 显式 wrap：否则在竖直容器里会被拉成整条横幅
        }

    /** 静态文字 chip（POST/GET 标记这类，指定 tone 即可）。 */
    private fun chipStatic(value: String, tone: String): LinearLayout = when (tone) {
        "info" -> chip(value, "accent_container", "accent_on_soft")
        else -> chip(value, "surface_container", "on_surface_variant")
    }

    private fun chipOk(value: String) = chip(value, "success_container", "success")
    private fun chipWarn(value: String) = chip(value, "warning_container", "warning")
    private fun chipBad(value: String) = chip(value, "error_container", "error")
    private fun chipMute(value: String) = chip(value, "surface_container", "on_surface_variant")
    private fun chipWithDot(value: String) = chip(value, "success_container", "success", withDot = true)

    /** 圆形头像（首字 + primary_soft 底；tokens.css .acct .av / .av.mute）。 */
    private fun avatar(name: String?, mute: Boolean = false): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(if (mute) "surface_container" else "primary_container"))
        }
        addView(text((name?.trim()?.take(1) ?: "T").uppercase(), Tokens.TEXT_MD, true,
            if (mute) "on_surface_variant" else "primary_on_soft").apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    }

    private fun avatarInitial(name: String): String = (name.trim().take(1) ?: "T").uppercase()

    /** 图标圆徽（list-item 里的 li-icon 等价物：30dp 圆角方块 + 矢量图标）。 */
    private fun iconBadge(icon: String, bg: String, tint: String): View {
        val res = when (icon) {
            "key" -> R.drawable.ic_key; "user" -> R.drawable.ic_account
            "gift" -> R.drawable.ic_gift; "moon" -> R.drawable.ic_moon
            "shield" -> R.drawable.ic_shield; "trash" -> R.drawable.ic_trash
            "info" -> R.drawable.ic_info
            else -> R.drawable.ic_more
        }
        return FrameLayout(this).apply {
            background = shape(bg, Tokens.RADIUS_SMALL)
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(res)
                setColorFilter(color(tint))
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }
    }

    /** 二级页入口行（设计稿 .list-item：图标 + 标题/副标题 + 箭头，整行可点）。 */
    private fun subPageRow(title: String, sub: String, icon: String = "more", onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(Tokens.CARD_PAD), dp(11), dp(Tokens.CARD_PAD), dp(11))
            isClickable = true
            isFocusable = true
            Motion.installPressFeedback(this)
            setOnClickListener { onClick() }
            addView(iconBadge(icon, "surface_container", "on_surface"), lp(dp(30), dp(30)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, Tokens.TEXT_MD, false))
                addView(captionText(sub).let { it.setPadding(0, dp(1), 0, 0); it })
            }, weighted(start = 12))
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(color("on_surface_variant"))
            }, lp(dp(15), dp(15), start = 6))
        }

    /** 所有背景的唯一来源。 */
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
    /**
     * 轻提示：贴导航胶囊**上方**弹出。
     *
     * 为什么不用系统 `Toast`：Android 11+ 的文本 Toast 固定在屏幕底部（setGravity 被忽略），
     * 而本项目的底部胶囊是自绘的悬浮层 —— 两者叠在一起，提示会压住「模型/记录」标签
     * （真机每种提示都复现）。自绘提示条放在导航栏上方，不遮任何控件，也顺带能换主题色。
     */
    private fun toast(value: String) {
        val host = findViewById<ViewGroup>(android.R.id.content) ?: return
        // 新的顶掉旧的：先 cancel 旧动画再 removeView —— 只 removeView 的话，
        // 旧 View 上还在跑的透明度动画会把回调打到已移除的控件上（重影/空指针都出现过）。
        toastView?.let {
            it.animate().cancel()
            runCatching { host.removeView(it) }
        }
        toastHide?.let { host.removeCallbacks(it) }
        val play = Motion.animationsEnabled(this)
        val tv = TextView(this).apply {
            text = value
            textSize = Tokens.TEXT_MD
            setTextColor(color("on_primary_container"))
            background = shape("primary_container", Tokens.RADIUS_PILL)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            gravity = Gravity.CENTER
            maxLines = 3
            alpha = if (play) 0f else 1f
            translationY = if (play) dp(8).toFloat() else 0f
        }
        host.addView(tv, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(Tokens.NAV_HEIGHT + Tokens.NAV_BOTTOM_MARGIN + 12)
            marginStart = dp(24)
            marginEnd = dp(24)
        })
        if (play) {
            tv.animate().alpha(1f).translationY(0f)
                .setDuration(140).setInterpolator(Motion.ease()).start()
        }
        toastView = tv
        val hide = Runnable {
            tv.animate().cancel()
            if (Motion.animationsEnabled(this)) {
                tv.animate().alpha(0f).translationY(dp(4).toFloat())
                    .setDuration(120).setInterpolator(Motion.ease())
                    .withEndAction {
                        runCatching { host.removeView(tv) }
                        if (toastView === tv) toastView = null
                    }.start()
            } else {
                runCatching { host.removeView(tv) }
                if (toastView === tv) toastView = null
            }
        }
        toastHide = hide
        host.postDelayed(hide, 2300)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(width: Int, height: Int, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }
    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(start) }
    private fun top(value: Int) = lp(-1, -2, top = value)

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
        val radius = minOf(Tokens.RADIUS_CONTROL, 6f)
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
            textSize = Tokens.TEXT_XS
            typeface = Typeface.DEFAULT_BOLD
        }
        paintCheckBox(box, on)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(
                item.title, Tokens.TEXT_XS, true,
                if (item.selectable) "on_surface" else "on_surface_variant",
            ).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(captionText(item.subtitle).apply {
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

        val countLabel = text("", Tokens.TEXT_XS, true, "on_surface_variant")
        var allBtn: TextView? = null

        fun refreshCount() {
            countLabel.text = if (selected.isEmpty()) "已选 0 个 · 至少选 1 个" else "已选 ${selected.size} 个"
            countLabel.setTextColor(color(if (selected.isEmpty()) "warning" else "on_surface_variant"))
            allBtn?.text = if (selectableCount > 0 && selected.size == selectableCount) "清空" else "全选"
        }

        allBtn = TextAction(this@MainActivity, "全选") {
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
            if (i < items.lastIndex) list.addView(rowDivider())
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
}

/**
 * 嵌套在外层 `ScrollView`（整页 `scroll {}`）里的列表滚动容器。
 *
 * **为什么需要它**：Android 的 `ScrollView` 在竖直滑动时会在 `onInterceptTouchEvent`
 * 抢走事件，内层 `ScrollView` 收不到 MOVE → 卡片里滚不动。用 `setOnTouchListener`
 * 也不行：外层在更早阶段就 intercept，listener 根本不会被调（实测日志为空）。
 *
 * 做法：在**事件必经**的 `onInterceptTouchEvent` 里，按"当前滑动方向上内层还能不能滚"
 * 动态 `requestDisallowInterceptTouchEvent`——
 * - 内层还能朝该方向滚 → disallow，事件留在内层；
 * - 内层已到边界 → 放行，继续滑带动整页。
 *
 * 这样卡片内滚动和整页滚动各自可用，且到边界能自然衔接，不出现"卡住"。
 */
private class NestedListScroll @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : ScrollView(context, attrs) {

    private var lastY = 0f

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastY = ev.y
                // 内层只要可滚就先把事件留住（canScrollVertically 任一方向为 true）
                val scrollable = canScrollVertically(1) || canScrollVertically(-1)
                parent?.requestDisallowInterceptTouchEvent(scrollable)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY
                lastY = ev.y
                // dy < 0 = 手指上滑 = 内容下移 = 需要"还能往下滚"(1)
                val canGo = if (dy < 0) canScrollVertically(1) else canScrollVertically(-1)
                parent?.requestDisallowInterceptTouchEvent(canGo)
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(ev)
    }
}
