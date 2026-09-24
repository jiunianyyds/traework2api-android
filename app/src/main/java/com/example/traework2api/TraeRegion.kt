package com.example.traework2api

/**
 * 区域（Region）常量集中点。
 *
 * 目的：把"哪些 host/端点/版本号随区域变化"收在一处，避免散落硬编码（需求 §6.3）。
 * 国内版（CN）是**已实测**的；国际版（SG）目前只拿到上游 host（来源见下），
 * 其余字段**未经实测**，缺失的一律留空并在运行时显式报错——
 * 宁可明确说"没配"，也不要偷偷用一个错的值去连上游。
 *
 * ⚠️ **国际服（SG）已冻结为「未开发」**（2026-09-23 用户决定，见 [isSupported]）：
 * 登录链路是通的，但聊天不支持多轮上下文、外部工具调不了，且免费额度约 $1/月。
 * **代码保留但不再投入**；UI 上明确标注并引导用户用国服。要恢复就把 [isSupported] 改回去。
 *
 * 来源：
 * - CN：reference/traework2api（Go，已实测）+ 本工程模拟器实测。
 * - SG 的 Agent host：github.com/Casually/dsh-trae-api（README 支持版本表）。
 *   ⚠️ 其 OAuth/登录协议该工程未实现（它直接解桌面端 storage.json），故 SG 的
 *   OAuth host / ClientID / 登录 host 目前**未知**，留空待实测补齐。
 */
enum class TraeRegion(
    /** 内部标识，用于日志与持久化。 */
    val id: String,

    /** 聊天/模型接口 host（SOLO Agent）。 */
    val agentHost: String,

    /** 额度/签到接口 host（User Gateway）。 */
    val ugHost: String,

    /** 额度/签到请求头里的区域标识；为空则不发该头。 */
    val userRegionHeader: String,

    /** OAuth（ExchangeToken / GetUserInfo）host。空=该区域尚未支持。 */
    val oauthHost: String,

    /** 登录页 host（构造登录 URL 用）。空=该区域尚未支持。 */
    val consoleHost: String,

    /** OAuth ClientID。空=该区域尚未支持。 */
    val clientId: String,
) {
    CN(
        id = "cn",
        agentHost = "https://trae-api-cn.mchost.guru",
        ugHost = "https://api.trae.cn",
        userRegionHeader = "CN",
        oauthHost = "https://api.trae.com.cn",
        consoleHost = "https://www.trae.cn",
        clientId = "en1oxy7wnw8j9n", // SOLO stable（实测）
    ),

    SG(
        id = "sg",
        /**
         * 国际服的**聊天 host**。注意它与国内服是**两套完全不同的协议**，
         * 不能拿这个 host 去 POST /api/agent/v3/llm_utils_chat。
         *
         * ✅ 2026-09-23 实测确定：国际服聊天走 `core-normal.trae.ai/api/remote/v1`，
         * 三段式（POST /chat_sessions → POST /{id}/commit → GET /{id}/events），
         * 实现见 `TraeIntlUpstream.kt`（协议来自浏览器 Network 抓包，非第三方文档）。
         *
         * ⚠️ 曾经这里填的是 `a0ai-api-sg.byteintlapi.com`（来自某个项目的 README 版本表），
         * 实测它是**错误入口**：把国际服 token 发过去只会得到 `4001 param is invalid`。
         * 那个域名在权威逆向项目里零出现，别再填回来。
         */
        agentHost = "https://core-normal.trae.ai",
        // 以下三项为 2026-09-23 实测得出（模拟器内 curl 验证）：
        // ExchangeToken 在多个 trae.ai host 上都返回与国内服**同构**的错误结构
        // （ResponseMetadata.Error.Code=10101 / StandardCode=040004，仅文案中英差异），
        // 说明国际服与国内服的 OAuth 是同一套协议，本工程登录代码可直接复用。
        ugHost = "https://ug-normal.trae.ai",
        userRegionHeader = "", // 国际服未观测到该头；不发明
        oauthHost = "https://ug-normal.trae.ai",
        consoleHost = "https://www.trae.ai",
        clientId = "ono9krqynydwx5",
    );

    /** 该区域是否已具备完整可用配置（缺 OAuth 就只能转发、不能登录）。 */
    val canLogin: Boolean get() = oauthHost.isNotBlank() && consoleHost.isNotBlank() && clientId.isNotBlank()

    /** 额度/签到接口是否可用。 */
    val canQueryQuota: Boolean get() = ugHost.isNotBlank()

    // 注：曾有 canChat 能力位用于拦截"国际服聊天未实现"。现已两区域都实现（协议不同，
    // 由 TraeUpstream.chatStream 按区域分派），无调用者，故删除以免留下恒真死代码。
    // 将来若某区域聊天确实不可用，在 chatStream 的分派处直接给一句人话错误即可。

    /**
     * 该区域是否视为**已开发/受支持**。SG（国际服）**冻结为未开发**（2026-09-23 用户决定）：
     * 登录能通，但聊天半残（无多轮上下文、外部工具调不了），且免费额度极少（约 $1/月），
     * 不值得继续投入。代码保留（以后额度够/拿到协议还能捡回来），但 UI 明确标注、引导到国服。
     *
     * 判据收在这**一处**，UI/日志/守卫都读它 —— 免得"未开发"这个状态散落在多个 if 里各写各的。
     */
    val isSupported: Boolean get() = this == CN

    /** 未开发时给用户看的一句话（已开发则为空）。 */
    val unsupportedNote: String
        get() = if (isSupported) "" else "未开发：登录可通，但聊天不支持多轮上下文与外部工具调用；" +
            "且免费额度极少（约 \$1/月）。建议使用国服。"

    companion object {
        /**
         * IDE 版本号：`User-Agent: Trae/<v>`、`X-Ide-Version` 等都用它。
         * 与区域无关（国内实测量），故放在 companion 而不是每个 region 上。
         */
        const val IDE_VERSION = "0.1.43"

        /** 当前生效区域。默认 CN（本工程已实测的那条链路）。 */
        @Volatile
        var current: TraeRegion = CN

        private const val PREF_NAME = "traework2api_prefs"
        private const val KEY_REGION = "trae_region"

        /**
         * 从磁盘恢复区域。必须在启动时调用一次：
         * 区域不持久化的话，重启后会回落成 CN，导致**国际服 token 被发到国内服 host**，
         * 表现为 401/1001 或 4001（实测踩过）。
         */
        fun load(context: android.content.Context) {
            val id = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_REGION, null)
            current = fromId(id)
        }

        /** 切换并持久化区域。 */
        fun set(context: android.content.Context, region: TraeRegion) {
            current = region
            context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_REGION, region.id).apply()
        }

        /** 按 id 查（持久化读回用）；未知/空白回退 CN。 */
        fun fromId(id: String?): TraeRegion =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: CN

        /** 取当前区域，并断言它支持登录；不支持则给出人话错误。 */
        fun requireLogin(region: TraeRegion = current): TraeRegion {
            require(region.canLogin) {
                "区域 ${region.id} 的登录协议尚未接入（缺 OAuth host / ClientID），" +
                    "目前仅支持 ${CN.id}"
            }
            return region
        }

        /**
         * 自检区域不变式：
         * 1) CN 必须完整可登录（已实测链路，不能被后续改动悄悄弄坏）；
         * 2) SG 的常量必须齐全到"能发起登录"（否则 requireLogin 会报错，
         *    用户点了国际服登录却什么都不发生）；
         * 3) 两区域的 host / ClientID 必须不同——包括 consoleHost 与 clientId：
         *    曾出过"复制登录链接"没带区域、永远生成国服链接的 bug，
         *    所以这里把"按区域切 current 能改变生成的链接"也钉住。
         */
        fun selfCheck() {
            check(CN.canLogin && CN.canQueryQuota) { "自检失败：CN 区域应完整可用" }
            check(CN.isSupported) { "自检失败：国服必须受支持（它是主链路）" }
            check(!SG.isSupported) { "自检失败：国际服当前应标为未开发" }
            check(CN.unsupportedNote.isEmpty() && SG.unsupportedNote.isNotEmpty()) {
                "自检失败：未开发说明应只在未开发区域出现"
            }
            check(CN.agentHost.startsWith("https://")) { "自检失败：CN agentHost 应为 https" }
            check(CN.userRegionHeader == "CN") { "自检失败：CN 的 X-User-Region 应为 CN" }
            check(SG.canLogin) { "自检失败：SG 常量应齐全（oauthHost/consoleHost/clientId 都不能为空）" }
            check(SG.agentHost.startsWith("https://")) { "自检失败：SG agentHost 应为 https" }
            check(CN.agentHost != SG.agentHost) { "自检失败：CN/SG 的 agentHost 不应相同" }
            check(CN.oauthHost != SG.oauthHost) { "自检失败：CN/SG 的 oauthHost 不应相同" }
            check(CN.consoleHost != SG.consoleHost) { "自检失败：CN/SG 的 consoleHost 不应相同" }
            check(CN.clientId != SG.clientId) { "自检失败：CN/SG 的 clientId 不应相同" }
            check(CN.consoleHost.contains("trae.cn")) { "自检失败：CN 登录页应在 trae.cn" }
            check(SG.consoleHost.contains("trae.ai")) { "自检失败：SG 登录页应在 trae.ai" }
            check(fromId("cn") == CN && fromId("SG") == SG) { "自检失败：fromId 应大小写无关地命中" }
            check(fromId("unknown-region") == CN) { "自检失败：未知区域应回退 CN" }
            check(fromId(null) == CN) { "自检失败：空区域应回退 CN" }
        }
    }
}
