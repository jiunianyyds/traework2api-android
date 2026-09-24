package com.example.traework2api

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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

        // ---- design tokens：收口原先散在 helper 与页面里的圆角/间距/字号 ----
        /** 容器类（卡片）圆角。 */
        private const val RADIUS_CARD = 16f
        /** 控件类（按钮/输入框）圆角。 */
        private const val RADIUS_CONTROL = 12f
        /** 胶囊（chip / 导航指示器）圆角：给一个大值即为全圆角。 */
        private const val RADIUS_PILL = 100f

        /** 卡片之间的标准间距；页面里不再手传 8/10/12 混用。 */
        private const val GAP = 12
        /** 页面左右内边距。 */
        private const val PAGE_PAD_H = 20
        /** 页面上下内边距。 */
        private const val PAGE_PAD_TOP = 16
        private const val PAGE_PAD_BOTTOM = 24
        /** 卡片内边距。 */
        private const val CARD_PAD = 16

        /** 内容最大宽度（dp）：手机竖屏不受影响，平板/横屏下不再让一行文字横跨整屏。 */
        private const val CONTENT_MAX_DP = 640

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
    }

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
    private lateinit var navItems: Map<Page, TextView>
    private var currentPage = Page.OVERVIEW
    /**
     * 是否正停在二级页（保活设置）。
     *
     * 做成二级页而不是第 6 个 Tab：底部导航已有 5 项，再加会挤；且保活是"设置一次就不管"的
     * 低频页面，不值得占一个常驻 Tab。
     */
    private var showKeepAlive = false
    private val apiKey: String by lazy { Gateway.apiKey(this) }
    /** 账号页积分/额度缓存（null=未拉到），展示用，不存凭据。 */
    private var accountBalance: Long? = null   // 当前可用积分
    private var accountCap: Long? = null       // 权益额度(上限)
    private var accountCheckin: Pair<Boolean, Long>? = null // (今日已签, 每日可领)
    /** 本地 API Key 明文默认打码（防止截图/旁观泄露），点「显示」才展开。 */
    private var apiKeyVisible = false

    /** 版本号取自 PackageManager，避免依赖 AGP 的 BuildConfig 开关。 */
    private val versionName: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "-"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setBackgroundColor(color(R.color.wb_background))
            fitsSystemWindows = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // 顶部栏（标题 + 副标题 + 1dp 分隔线，把顶栏和内容区分开）
        // 外层全宽撑背景，内层限宽居中，这样平板/横屏下标题与内容左边缘是对齐的
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.wb_surface))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PAGE_PAD_H), dp(14), dp(PAGE_PAD_H), dp(12))
                addView(TextView(this@MainActivity).apply {
                    textSize = FS_TITLE
                    setTextColor(color(R.color.wb_on_surface))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }.also { pageTitle = it })
                addView(TextView(this@MainActivity).apply {
                    textSize = FS_CAPTION.toFloat()
                    setTextColor(color(R.color.wb_on_surface_variant))
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
            setBackgroundColor(color(R.color.wb_surface))
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
        render(Page.OVERVIEW)
        loadAndRefreshToken()
    }

    // ------------------------------------------------------------------
    // Tab 路由
    // ------------------------------------------------------------------

    private fun render(page: Page) {
        currentPage = page
        // 点任意底部 Tab 都算"退出二级页"，否则会卡在保活页上出不去。
        showKeepAlive = false
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
            item.setTextColor(color(if (active) R.color.wb_primary else R.color.wb_on_surface_variant))
            item.typeface = if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            // 选中态：贴在图标+文字上的胶囊（宽度由内容决定，见 onCreate 的 navBar 组装）
            item.background = if (active) shape(R.color.wb_primary_container, RADIUS_PILL) else null
            item.compoundDrawables.filterNotNull().forEach { it.mutate().setTint(color(if (active) R.color.wb_primary else R.color.wb_on_surface_variant)) }
        }
    }

    /** 打开保活设置二级页（顶栏换成"返回"，Tab 高亮保持在「更多」）。 */
    private fun openKeepAlive() {
        showKeepAlive = true
        pageTitle.text = "保活设置"
        pageSubtitle.text = "让服务在后台长期活着"
        contentHost.removeAllViews()
        contentHost.addView(keepAlivePage())
    }

    /** 二级页返回：回到「更多」。 */
    private fun closeKeepAlive() = render(Page.MORE)

    /** 系统返回键：在保活页时先退回「更多」，而不是直接退出 App。 */
    @Deprecated("Activity.onBackPressed 已废弃，但本项目未引 androidx（无 OnBackPressedDispatcher）")
    override fun onBackPressed() {
        if (showKeepAlive) closeKeepAlive() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // 概览
    // ------------------------------------------------------------------

    private fun overviewPage(): View = scroll {
        val running = GatewayService.running
        addView(if (running) chipOk("服务运行中") else chipDanger("服务已停止"))
        // 服务默认是**关闭**的（见 onCreate：不自动 start）。关闭时先说清"为什么连不上、
        // 该点哪里"，否则用户看到地址却连不上会以为坏了。
        if (!running) {
            addView(card {
                addView(text("服务未启动", FS_CARD_TITLE, true))
                addView(caption("本服务默认关闭，需要你手动开启后才会监听本地端口。\n" +
                    "点下面的「启动服务」即可；不开启时客户端连不上，也不会有任何端口在监听。"), top(8))
            }, top(GAP))
        } else {
            addView(card {
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
        // 服务地址是本页核心信息：做成强调卡（等宽地址 + 主色 + 一键复制）
        addView(card {
            addView(caption("服务地址"))
            addView(row(8) {
                addView(mono("http://127.0.0.1:${LocalFakeServer.PORT}", FS_BODY, true, R.color.wb_primary), weighted())
                addView(linkAction("复制") { copyServerUrl() })
            }, top(6))
            addView(caption("把它作为 base URL 填进任意 OpenAI 兼容客户端"), top(8))
        }, top(GAP))
        addView(action(if (running) "停止服务" else "启动服务", full = true, outlined = running) { toggleService() }, top(GAP))
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
        addView(card {
            addView(text("使用引导", FS_CARD_TITLE, true))
            addView(caption("将上面的地址与 API Key 填入任意 OpenAI 兼容客户端：\nPOST /v1/chat/completions\nGET /v1/models\nGET /healthz"), top(8))
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
            addView(if (hasToken) chipOk("转发 已启用") else chipWarn("转发 未配置"))
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
            addView(card {
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
            addView(row {
                addView(caption("当前可用积分"), weighted())
                addView(linkAction("刷新") { refreshQuota() })
            })
            // 主数值放大，一眼能读到；次要指标并排
            addView(text(accountBalance?.toString() ?: "—", FS_METRIC, true, R.color.wb_primary), top(2))
            addView(row(12) {
                addView(metric("权益额度(上限)", accountCap?.toString() ?: "—"), weighted())
                addView(metric("今日签到", accountCheckin?.let { if (it.first) "已签 · +${it.second}/日" else "未签" } ?: "—"), weighted(start = 12))
            }, top(14))
        })
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

        // 客户端过滤：改可见性而非重建容器，避免丢失输入框焦点
        val cards = buildModelCards(this)
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val f = edit.text.toString().trim()
                cards.forEach { (key, v) -> v.visibility = if (f.isBlank() || key.contains(f, true)) View.VISIBLE else View.GONE }
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
            addView(mono(id, FS_CHIP, colorRes = R.color.wb_on_surface_variant).apply {
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
            addView(card { addView(caption("暂无记录 —— 发一次聊天请求后这里会显示用量与汇总")) })
            return@scroll
        }
        addView(card {
            addView(text("模型消耗汇总", FS_CARD_TITLE, true))
            for (g in UsageStore.aggregate(this@MainActivity)) addView(row(8) {
                addView(text(g.model.ifBlank { "?" }, FS_VALUE, true), weighted())
                addView(mono("总 ${g.total} · 输出 ${g.output}", FS_CAPTION, colorRes = R.color.wb_on_surface_variant))
            }, top(8))
        })
        addView(section("调用记录（最近 ${records.size} 条）"))
        for (r in records.asReversed()) addView(recordCard(r))
    }

    private fun recordCard(r: UsageStore.UsageRecord): View = card {
        addView(row {
            addView(if (r.ok) chipOk("OK") else chipDanger("ERR"))
            addView(text(r.model.ifBlank { "?" }, FS_VALUE), weighted(start = 10))
            // 时间戳用等宽字体，一列扫下来是对齐的
            addView(mono(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.ts)), FS_CHIP,
                colorRes = R.color.wb_on_surface_variant))
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
        addView(section("关于"))
        addView(card {
            addView(text("应用说明", FS_CARD_TITLE, true))
            addView(caption("本应用针对 Trae 当前协议的个人学习自用适配，不保证长期兼容，不代表官方支持。"), top(8))
            addView(labelValue("版本", versionName), top(12))
        })
        addView(card {
            addView(text("致谢", FS_CARD_TITLE, true))
            addView(caption("参考与致谢：\n• traework2api（本项目）\n• WorkBuddy2API-Android（UI 风格与组件写法参考）"), top(8))
        })
        addView(card {
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
                        if (it.ok) R.color.wb_success else R.color.wb_warning), lp(-2, -2, end = 8))
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

    /**
     * 国际服（SG）登录：回调流与国内服相同，只是 host/ClientID 不同。
     * 该区域**已冻结为未开发**，所以先弹确认，避免用户白登一个用不上的账号。
     */
    private fun openLoginSg() {
        if (!TraeRegion.SG.canLogin) { toast("国际服常量未配置完整"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("国际服尚未开发")
            .setMessage(TraeRegion.SG.unsupportedNote + "\n\n仍然要登录吗？")
            .setPositiveButton("仍要登录") { _, _ -> openLogin(TraeRegion.SG) }
            .setNegativeButton("用国服", null)
            .show()
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
            android.app.AlertDialog.Builder(this)
                .setTitle("该账号属未开发区域")
                .setMessage("${meta.nickname.ifBlank { uid }}（${regionLabel(meta.region)}）\n\n" +
                    TraeRegion.SG.unsupportedNote + "\n\n仍然切换吗？")
                .setPositiveButton("仍要切换") { _, _ -> doSwitchAccount(meta) }
                .setNegativeButton("取消", null)
                .show()
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
        android.app.AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("将删除「${meta.nickname.ifBlank { meta.uid }}」的本地凭据（含加密 token）。\n" +
                "不影响 Trae 服务端的账号本身。")
            .setPositiveButton("删除") { _, _ ->
                TraeAuthStore.remove(this, meta.uid)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                render(currentPage)
            }
            .setNegativeButton("取消", null)
            .show()
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
            setTextColor(color(R.color.wb_on_surface))
            setHintTextColor(color(R.color.wb_on_surface_variant))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("粘贴回调链接")
            .setMessage("用系统浏览器打开登录链接登录后，把地址栏里那串 http://127.0.0.1:18080/authorize?... 完整粘进来。")
            .setView(edit)
            .setPositiveButton("登录") { _, _ ->
                val url = edit.text?.toString().orEmpty()
                if (url.isBlank()) { toast("没有粘贴内容"); return@setPositiveButton }
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
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyApiKey() = copyToClipboard("apiKey", apiKey, "已复制 API Key")

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
        toast("正在刷新积分…")
        thread(name = "trae-fetch-quota") {
            val u = TraeUpstream.fetchUsage(cred.accessToken)
            val c = TraeUpstream.fetchCheckin(cred.accessToken)
            runOnUiThread {
                accountBalance = u?.second
                accountCap = u?.first
                accountCheckin = c
                toast(if (u == null) "未获取到额度" else "已更新")
                if (currentPage == Page.ACCOUNT) render(currentPage)
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
        if (GatewayService.running) {
            GatewayService.stop(this)
            toast("服务已停止")
        } else {
            GatewayService.start(this)
            // Android 13+ 通知要运行时授权。被拒也能跑（前台服务照常），
            // 只是通知栏看不到"正在运行" —— 那是保活体验的一部分，所以该问就问。
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
            toast("服务已启动")
        }
        // 服务启停是异步的（onStartCommand 晚于 start 调用），稍等再重绘，否则会画成旧状态。
        contentHost.postDelayed({ render(currentPage) }, 400)
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
            runOnUiThread { render(currentPage) }
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
    // Wb 风格 View 组装 helper（这就是本项目的"组件库"）
    // 改样式只改这一段；圆角/间距/字号只看 companion 里的 design token。
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

    private fun card(topMargin: Int = GAP, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD), dp(CARD_PAD))
        background = shape(R.color.wb_surface, RADIUS_CARD, R.color.wb_outline_variant)
        elevation = dp(1).toFloat()
        build()
        layoutParams = lp(-1, -2, top = topMargin)
    }

    private fun row(top: Int = 0, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
        layoutParams = lp(-1, -2, top = top)
    }

    private fun text(value: String, size: Int = FS_BODY, bold: Boolean = false, colorRes: Int = R.color.wb_on_surface): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    /** 等宽文本：地址、模型 id、时间戳用它，数字和标识符能对齐。 */
    private fun mono(value: String, size: Int = FS_VALUE, bold: Boolean = false, colorRes: Int = R.color.wb_on_surface): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color(colorRes))
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(0f, 1.12f)
        }

    private fun caption(value: String): TextView = text(value, FS_CAPTION, false, R.color.wb_on_surface_variant)

    /** 区块标题：小号灰字；原先 16sp 深色，和卡片标题（17sp）几乎分不出层级。 */
    private fun section(value: String): TextView = text(value, FS_SECTION, true, R.color.wb_on_surface_variant).apply {
        letterSpacing = 0.04f
        setPadding(0, dp(18), 0, dp(8))
    }

    /** 1dp 分隔线：顶栏/底栏与内容区分层。 */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.wb_outline_variant))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    /** 底层胶囊。语义场景请用 chipOk / chipWarn / chipDanger / chipInfo，别在这里手配颜色对。 */
    private fun chip(value: String, backgroundRes: Int, textRes: Int): TextView = text(value, FS_CHIP, true, textRes).apply {
        gravity = Gravity.CENTER
        minHeight = dp(32)
        setPadding(dp(12), 0, dp(12), 0)
        background = shape(backgroundRes, RADIUS_PILL)
        layoutParams = lp(-2, -2)   // 显式 wrap：否则在竖直容器里会被拉成整条横幅
    }

    private fun chipOk(value: String) = chip(value, R.color.wb_success_container, R.color.wb_success)
    private fun chipWarn(value: String) = chip(value, R.color.wb_warning_container, R.color.wb_warning)
    private fun chipDanger(value: String) = chip(value, R.color.wb_error_container, R.color.wb_error)
    private fun chipInfo(value: String) = chip(value, R.color.wb_primary_container, R.color.wb_primary)

    /** 键值行：标签列只给最小宽度（不再是死 96dp，长标签能撑开），值可长按复制。 */
    private fun labelValue(label: String, value: String): View = row {
        addView(caption(label).apply { minWidth = dp(96) }, lp(-2, -2))
        addView(text(value, FS_VALUE).apply { setTextIsSelectable(true) }, weighted(start = 12))
    }

    /** 指标块：小标签在上、数值在下，并排展示两个次要指标用。 */
    private fun metric(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(caption(label))
        addView(text(value, FS_BODY, true), top(2))
    }

    /** 搜索框：与卡片同一套白底 + 描边。 */
    private fun searchInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(color(R.color.wb_on_surface))
        setHintTextColor(color(R.color.wb_on_surface_variant))
        background = shape(R.color.wb_surface, RADIUS_CARD, R.color.wb_outline_variant)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun linkAction(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = FS_CAPTION.toFloat()
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(R.color.wb_primary))
        gravity = Gravity.CENTER
        minHeight = dp(40)
        minWidth = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = shape(R.color.wb_primary_container, RADIUS_PILL)
        setOnClickListener { click() }
    }

    private fun action(label: String, outlined: Boolean = false, full: Boolean = false, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(18), 0, dp(18), 0)
        setTextColor(color(if (outlined) R.color.wb_primary else R.color.wb_on_primary))
        background = if (outlined) shape(R.color.wb_surface, RADIUS_CONTROL, R.color.wb_outline) else shape(R.color.wb_primary, RADIUS_CONTROL)
        setOnClickListener { click() }
        if (full) layoutParams = lp(-1, dp(48))
    }

    private fun shape(fillRes: Int, radiusDp: Float, strokeRes: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color(fillRes))
        cornerRadius = dp(radiusDp).toFloat()
        strokeRes?.let { setStroke(dp(1), color(it)) }
    }

    private fun color(res: Int) = getColor(res)
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(width: Int, height: Int, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }
    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(start) }
    private fun top(value: Int) = lp(-1, -2, top = value)
}
