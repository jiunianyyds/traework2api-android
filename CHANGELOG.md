# 变更记录（CHANGELOG）

本文件**只追加**，记「改了哪个文件 / 改了什么 / 为什么」。
项目全貌看 `交接文档.md`，UI 规范看 `docs/UI现状清单.md`，本文件只是改动流水。

格式：[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号跟 `app/build.gradle.kts` 的 `versionName`。

---

## [未发布] - 2026-09-23

### 新增 UI 全面重做任务书（仅文档，未改代码）

用户要求：UI 全面重做（不再模仿 WorkBuddy），但**UI 由另一个 AI 负责**，
本会话只负责**写需求与提示词**，不动 `MainActivity.kt`。

#### Added
- **`docs\UI重做任务书.md`（新）**：给 UI AI 的完整需求 + 可复制的提示词。
  - 硬约束：零新依赖（仅 okhttp 4.12.0）、零布局 XML、颜色必须走 `values`/`values-night` 资源。
  - 信息架构保持不变（5 Tab + 保活二级页），并列出**易被误删的功能性细节**：
    API Key 默认打码、服务默认关闭的空态说明、国际服「未开发」标注与确认弹窗、
    `chip()` 里的 `lp(-2,-2)`（修 chip 被拉满的 bug）、底栏点击区挂外层容器。
  - 设计要求：浅色/深色双份、手机竖屏+平板横屏、触控 ≥48dp、≥5 档字号、状态一眼可见、
    对比度 ≥4.5:1；鼓励重新定义色板与视觉语言。
  - 现成验证流程（构建/装机/`uiautomator dump`/截图/`cmd uimode night`/`wm size`）+ 6 条历史踩坑。

#### 依据（本会话实测核对，非照抄文档）
- 构建 `gradlew.bat assembleDebug --offline` **通过**；装机版 `versionName 0.1.0`。
- `logcat -b crash -d` 为空；`uiautomator dump` 确认概览页渲染正常（服务停止态正确）。
- 依赖确认：`app/build.gradle.kts` 只有 okhttp 4.12.0；`res\layout` 目录**确实不存在**。
- 启动自检**实为 7 个**（第 116–134 行），此前文档脚注里写的「六个」已过时，任务书中按 7 个写。
- 底栏坐标实测：y≈1243，中心 x≈344/1032/1720/2408/3096。

---

## [0.1.0] - 2026-09-23

### 保活设置独立成页 + 一键配置

用户要求：「保活功能单独弄个设置，最好有个按钮自动让用户设置保活」。

#### Added
- **`KeepAlive.kt`（新）**：保活体检 + 一键配置。
  - `check(ctx)`：逐项状态（电池优化白名单 / 通知权限 / 通知渠道 / 精确闹钟 / 两项需自查）
  - `rom()` / `romFor(...)`：ROM 识别，**先看 ROM 特征属性再回落品牌名**
  - `romGuide(rom)`：各 ROM 的实际菜单路径
  - `openBatteryWhitelist` / `openExactAlarm` / `openAppNotificationSettings`
- **保活设置二级页**（`更多` → 「打开保活设置」）：总状态卡 → 一键配置 → 逐项清单 →
  本机 ROM 引导（含「复制这些路径」）。

#### Changed
- 「更多」页新增**保活入口卡**（带 `N 项待开启` 提示）。
- 概览页原有「检查保活设置」按钮（弹对话框）改为**跳转到新的保活设置页**。

#### Removed
- `MainActivity` 里三个旧函数（被 `KeepAlive` + 新页面取代）：
  `keepAliveIssues()` / `showKeepAliveDialog()` / `requestIgnoreBatteryOptimizations()`。

#### 关键设计：为什么不叫「一键保活」
Android **没有**打开厂商私有页面（自启动/省电策略/后台弹出）的公开接口，任何 App 都做不到
替用户勾选。所以把项分成：
- **可一键**（有公开 Intent）：电池优化白名单、精确闹钟、通知权限
- **只能引导**（厂商私有）：自启动、省电策略 → 给本机 ROM 的路径让用户照着点

界面照这个分类展示，用户看到的是「能点的按钮 + 剩下几步照做」，而不是一个假装全能却
静默失败的按钮。**一次只跳一个系统页面**（连弹会互相顶掉）。

#### 改了哪些文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/traework2api/KeepAlive.kt` | 新增 |
| `app/src/main/java/com/example/traework2api/MainActivity.kt` | 新增 `keepAlivePage()` + 二级页路由（`showKeepAlive` / `onBackPressed`）；「更多」加入口；删三个旧函数 |

#### 验证（实测）
- 「更多」页显示「后台保活设置 · 2 项待开启」；进页后标准项自动检出
  电池优化白名单 ✓ / 通知权限 ✓，2 项厂商私有项显示「需手动」。
- ROM 识别在模拟器（Samsung、无厂商属性）**正确回落到「其它 Android」**，非盲目默认。
- **返回键**从保活页回「更多」而不退出 App。
- 回归：聊天流式、服务启停正常；`logcat -b crash` 为空。
- 自检新增 ROM 分支断言，含**一加跑 ColorOS 时靠属性纠正**这一交叉场景。

---

## [0.1.0] - 2026-09-23

### 支持 stream:false 非流式聚合

#### Added
- **`LocalFakeServer.aggregateChunks()`** + `stream:false` 分支支持。
  **为什么**：上游（Trae）只认流式、内部也只能流式拿数据，以前客户端发 `stream:false`
  拿到的**仍是 SSE**，严格按 OpenAI 规范解析的客户端会报错。现在聚合回一个标准
  `chat.completion` 一次性返回。

#### Changed
- `streamingChat()` 在**输出端**分叉：`stream:true` 照旧走 SSE（**行为完全没变**）；
  `stream:false` 不写 SSE 头，帧先攒着、结束后聚合一次性写 JSON。
- ⚠️ 关键顺序：`wantsStream` 必须在 **`prepareBody` 之前**读 ——
  `prepareBody` 会把 `stream` **原地改成 true**（上游只认流式），改完就看不到客户端原意了。

#### 聚合规则（每条都有踩坑理由）
- `delta.content` / `delta.reasoning_content` **逐帧拼接**（只取最后一帧会丢字）
- `delta.tool_calls` 按 `index` 累积：`name` 取首个非空、`arguments` **逐帧拼接**
  （OpenAI 分片发送；只取最后一帧会得到半截 JSON）
- `finish_reason` 取**最后一个非空**值（中途帧是空串）
- 有 `tool_calls` 时补 `content:""` 并把 `finish_reason` 归一为 `tool_calls`

#### Added（自检）
- `LocalFakeServer.selfCheck()` 覆盖聚合：逐帧拼接、`reasoning_content`、
  `finish_reason` 取值、`usage` 带出、**工具调用分片合并**
  （钉住"必须合并成 1 条且参数拼全"，防止退化成覆盖）。

#### 改了哪些文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/traework2api/LocalFakeServer.kt` | 新增 `aggregateChunks()`；`streamingChat()` 加非流式分支；自检 |

#### 验证（实测）
- 普通非流式 → 完整 `chat.completion`：`content:"非流式"`（逐帧拼对）、
  `reasoning_content` 保留、`usage` 带出、`object` 是 `chat.completion`。
- 非流式 + 工具调用 → `arguments` 拼成**合法 JSON** `{"city": "北京"}`、
  `finish_reason:"tool_calls"`、`content:""`。
- **回归**：流式（`delta.content` + `[DONE]`）、chunked（Chatbox 方式）、
  `/v1/models` 免鉴权、启动自检全部正常，`logcat -b crash` 为空。

---

## [0.1.0] - 2026-09-23

### 修复 chunked 请求体（Chatbox 空气泡）+ 移除内置 WebView 登录

#### Fixed
- **Chatbox 发消息只得到空气泡（严重，等于用不了）**。真凶：**Chatbox 用
  `Transfer-Encoding: chunked` 发请求**（不带 `Content-Length`），而网关只读 `Content-Length`
  → body 读成空串 → 上游没有 `messages`、不生成任何内容 → 客户端空气泡。
  实测日志铁证：`POST /v1/chat/completions (len=0, chunked=true, TE=chunked, …)`。
  **为什么之前没发现**：`curl` 默认发 `Content-Length`，永远踩不到这条路径 ——
  教训是**别只用 curl 验收 HTTP 服务**。修复：新增 `readChunkedBody()` 解析分块体
  （按字节拼接，中文一字多字节不能按字符切；兼容 chunk 扩展与末块）。

#### Removed
- **`LoginWebActivity.kt` 整个文件** + manifest 声明 + `openLogin()` 开窗逻辑
  + `REQ_LOGIN` + `onActivityResult()`（用户要求"只要链接登录就行"）。
  **为什么**：它依赖页面里的 **JS 请求回调**（不是导航），`WebViewClient` 拦不到；
  那条 JS 还会被页面第三方 SDK 超时打断 → 部分网络下必失败。

#### Changed
- `openLogin()` 改为**跳账号页 + 复制登录链接**（三个登录入口共用）。
- 登录卡标题：「登录（推荐：浏览器 + 粘贴回调）」→ **「登录（浏览器 + 粘贴回调）」**
  —— 不再是"推荐"，而是唯一方式。
- `TraeAuthApi` 类注释同步（回调不再由 WebView 拦截）。

#### Added
- `LocalFakeServer.selfCheck()` 覆盖 `readChunkedBody`：分块解析 + 中文多字节切块。
- `TraeAuthApi.selfCheck()` 覆盖**回调链接解析**：登录现在只有这一条路，
  链接解析坏了 = 用户彻底进不去。钉住：无 `refreshToken` 不解析、有则解出、
  **非本机地址不算合法回调**。

#### 改了哪些文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/traework2api/LocalFakeServer.kt` | 新增 `readChunkedBody()`；识别 `Transfer-Encoding: chunked`；自检 |
| `app/src/main/java/com/example/traework2api/MainActivity.kt` | 删 `openLogin` 开窗/`REQ_LOGIN`/`onActivityResult`；改跳账号页+复制链接；登录卡文案 |
| `app/src/main/java/com/example/traework2api/TraeAuthApi.kt` | 注释同步；自检补回调链接解析 |
| `app/src/main/java/com/example/traework2api/LoginWebActivity.kt` | **删除** |
| `app/src/main/AndroidManifest.xml` | 删 `.LoginWebActivity` 声明 |
| `交接文档.md` | 新增 §5.16；§2 文件表标注删除 |

#### 验证（全部实测）
- 原始 socket 发 chunked 请求 → 正常返回 `delta.content`（修前是空气泡）。
- **Chatbox 实机发消息正常回复**（用户确认）。
- `dumpsys package` 的 Activity Resolver Table **只剩 `MainActivity` 一个 Activity**
  → WebView 登录窗真的没了（不只是没入口）。
- 登录卡三按钮正常渲染；主链路（chunked 聊天）无回归；`logcat -b crash` 为空。

#### 已知未修
- **`max_tokens` 仍未处理**：实测传 `22` 上游照样跑 128 token。
  与本次空气泡**无关**（用户未设上限），但客户端设的输出上限目前不生效 —— 待定。

---

### UI 视觉重构（不碰业务逻辑）

**范围**：只动视觉层与资源。`render()` 的 Tab 路由、各页数据读取
（`TraeAuthStore` / `TraeUpstream` / `UsageStore` / `ModelsRepo`）一行未动。
未引入任何依赖（项目仍只有 okhttp 4.12.0）、未引入任何布局 XML。

#### Added
- **design token 收口到 `MainActivity.companion`**：圆角三档（`RADIUS_CARD` 16 / `RADIUS_CONTROL` 12 /
  `RADIUS_PILL`）、间距（`GAP` 12、`PAGE_PAD_*`、`CARD_PAD`）、字号（`FS_TITLE` 22 / `FS_METRIC` 24 /
  `FS_CARD_TITLE` 17 / `FS_BODY` 15 / `FS_VALUE` 14 / `FS_CAPTION` 13 / `FS_CHIP` 12）、
  内容限宽 `CONTENT_MAX_DP = 640`。页面里不再写尺寸字面量。
- **内容限宽居中**：`scroll()` 与顶部栏内层统一走 `contentWidth()`。
  平板横屏（3440×1312）下内容限 640dp 居中，不再让一行文字横跨整个屏幕；手机竖屏（屏宽 < 640dp）不受影响。
- 新增 helper：`mono()`（等宽：服务地址 / 模型 id / 时间戳）、`metric()`（指标块：标签在上数值在下）、
  `divider()`（1dp 分隔线）、`searchInput()`。
- 新增 chip 语义封装：`chipOk` / `chipWarn` / `chipDanger` / `chipInfo`，替代调用处手配颜色对。
- 顶栏、底栏各加 1dp 分隔线（原来只靠背景色差，分层不清）。
- 概览页「服务地址」升级为强调卡：等宽字体 + 主色 + 一键复制。
- 记录页时间戳改等宽字体并右对齐，一列扫下来是对齐的。
- 更多页「应用说明」卡显示版本号（取自 `PackageManager`，不依赖 AGP 的 BuildConfig 开关）。

#### Changed
- **底部导航选中态重做**：由「1/5 屏宽的整块色板」改为
  「外层 `LinearLayout` 撑满 1/5 宽（保证点击区）+ 内层 `TextView` 包住图标与文字，胶囊贴在内层」。
  胶囊宽度由内容决定，手机竖屏与平板横屏都合适。点击监听挂在外层容器上。
- **本地 API Key 默认打码**，点「显示 / 隐藏」切换（防截图与旁人泄露），复制按钮保留。
- `modelsPage()` 收口到 `scroll {}`（原先自己抄了一遍 `20,16,20,24` 内边距，改 `scroll` 时会漏掉它）。
- `labelValue()` 左列由死宽 `96dp` 改 `minWidth = 96dp`，长标签自动撑开不再被挤。
- `section()` 由 16sp 深色改 14sp 灰字加字距 —— 原先与卡片标题（17sp）几乎分不出层级。
- 「当前可用积分」由 caption 小字改为 24sp 主色数值；权益额度 / 今日签到改为并排指标块。
- 卡片间距统一走 `card()` 默认 `GAP = 12`（原先调用处手传 8 / 10 / 12 混用）。
- 三处重复的 `getSystemService(CLIPBOARD_SERVICE)` + `setPrimaryClip` 收口到 `copyToClipboard()`。

#### Fixed
- **chip 在竖直容器里被拉成整条横幅**：实测 `bounds=[43,246][3397,314]`，横跨整屏。
  在 `chip()` 内显式 `layoutParams = lp(-2, -2)` 钉死为 wrap。
- **改色需要改两处**：`tw_*` 与 `wb_*` 两套色值完全相同，改主题色时漏一处就会主题与界面不同步。
  现已合并为 `wb_*` 一套，主题 `Theme.TraeWork2API` 也改读 `wb_*`。
- 顶部栏与内容区在限宽后左边缘错位（顶栏未限宽）——已让顶栏内层同样走 `contentWidth()` 对齐。
- `onCreate` 底栏注释写着「4 Tab」，实际由 `Page.entries` 驱动共 5 项 —— 注释已更正。

#### Removed
- `values/colors.xml` 与 `values-night/colors.xml` 中的 `tw_*` 共 7 个重复色值。
- `values/styles.xml` 中三条零引用死样式（已 grep 确认全工程零引用）：
  `WbText.Caption` / `WbBottomNavItem` / `WbStatusChip`。
- `WbText.Title` 从 XML 搬进代码（`FS_TITLE` + `sans-serif-medium`），
  现 XML 只剩主题样式，字号只有一个来源。

#### 改了哪些文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/traework2api/MainActivity.kt` | design token、helper 组件库、五个页面视觉结构 |
| `app/src/main/res/values/colors.xml` | 删 `tw_*`，只留 `wb_*` |
| `app/src/main/res/values-night/colors.xml` | 同上（深色值） |
| `app/src/main/res/values/styles.xml` | 删三条死样式，主题改读 `wb_*` |
| `app/src/main/res/values-night/styles.xml` | 主题改读 `wb_*` |
| `docs/UI现状清单.md` | 重写为「设计规范 + 原 11 条不一致逐条处置对照表」 |

#### 验证（全部实测，非推断）
- 离线构建通过：`.\gradlew.bat assembleDebug --offline`。
- 装机后 `logcat -b crash -d -t 20` 输出为空（五个启动自检全过）。
- **浅色 5 页**逐页截图确认；**深色**实测概览页 / 账号页（卡片、chip、胶囊、主色文字均正常）。
- **手机竖屏**（`wm size 1080x2340` + `wm density 420`）与**平板横屏**（3440×1312）各跑一轮，
  两种屏幕下底栏胶囊、内容限宽、chip 宽度均正确。

### 服务默认关闭（安全默认值）

**范围**：只改「本地服务什么时候开始监听」。转发逻辑、鉴权、账号、UI 组件库均未动。

#### Changed
- **删掉 `onCreate` 里的自动启动**：原来 `LocalFakeServer` 构造完直接 `it.start()`，
  等于**装上并每次启动就监听 8788**（`0.0.0.0`）。现在只构造、不启动。
  **为什么**：这是个长期驻留的本地端口，默认不监听 = 用户没主动开时不会有任何端口暴露；
  也让"暴露网关"这件事变成用户**显式**的选择。
- **概览页新增「服务未启动」空态卡**（仅未运行时显示）：写清"默认关闭、点「启动服务」才监听、
  不开启时客户端连不上"。**为什么**：否则用户看到服务地址却连不上，会以为坏了。
- **每次启动 App 都是关闭态**（不持久化"上次是开的"）。**为什么**：用户要求"必须手动开启"；
  持久化会把"上次开过"变成隐式自动启动，与意图相反。

#### Added
- `MainActivity.kt` 中针对该行为的注释（`onCreate` 与概览页），
  明确写**不要**把 `it.start()` 加回去 —— 那是本设计的反方向。

#### Fixed
- 无（本次不是修 bug，是改默认值）。

#### 改了哪些文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/traework2api/MainActivity.kt` | 删 `onCreate` 的 `it.start()`；概览页加未启动空态卡 |
| `docs/UI现状清单.md` | 概览页结构补「服务未启动」空态卡 |
| `交接文档.md` | 新增 §5.14（含实测证据与排查坑） |

#### 验证（全部实测，非推断）
- 装机后**不点启动**：`curl /healthz` → `curl_exit=7`（连接被拒）；
  `/proc/net/tcp6` 中 `:2254` **无 LISTEN（st=0A）**。
- 点「启动服务」→ `http=200`；`/proc/net/tcp6` 出现 `[::]:2254 st=0A LISTEN`（uid 10034 = 本 App）。
- 点「停止服务」→ 再次连不上；**再点启动正常**（端口可复用，无 EADDRINUSE）。
- **杀进程重开 → 回到关闭态**，概览页正确显示「服务未启动」。
- 手动开启后 **CN 聊天无回归**（流式 `delta.content` + `[DONE]` 正常）。
- `logcat -b crash -d -t 15` 为空；五个启动自检全过。

### 保活（前台服务）+ 默认模型改便宜

用户要求：「要加保活功能」+「不要用太贵的模型，要积分便宜的模型」。

#### Added
- **`Gateway.kt`（新）** —— 网关业务持有者：构建 `LocalFakeServer`、装配 cred/failover provider、
  持有 `tokenLock`。**只用 `applicationContext`**（provider 捕获 Activity 会变成长驻内存泄漏）。
- **`GatewayService.kt`（新）** —— Android 保活机制，**两层共 9 项**（见下）。
- `Gateway.selfCheck()`：钉住**本地 API Key 稳定且非空**（一旦变成"每次取值都变"，
  所有已配置的客户端全 401，且现象是"昨天能用今天不能"，极难定位）。

**保活第一层（标准 Android，原生系统够用）**：前台服务 + 常驻通知、`PARTIAL_WAKE_LOCK`、
`START_STICKY`、`stopWithTask="false"`。

**保活第二层（国产 ROM 适配，真机命门）** —— 参考 `reference/WorkBuddy2API-Android` 全套对齐。
**为什么必须加**：厂商省电策略会**无视 START_STICKY 直接强杀**前台服务，只有技术层不够。
- **`onTaskRemoved`**：划掉最近任务时立刻拉回；Android 12+ 禁止后台启动前台服务，
  被拦时立刻挂 **5 秒精确闹钟**兜底（闹钟唤醒属系统回调，不受该限制）。
- **AlarmManager 看门狗（15 分钟）**：只有 `setExactAndAllowWhileIdle` 能在 Doze 下唤醒进程。
  间隔**别调小** —— 更密会被 ColorOS 判为异常唤醒而拦截，反而更不可靠。
- **`should_run` 持久化用户意图**：区分"用户关了"vs"被杀了"，
  否则看门狗/onTaskRemoved 会把服务拉回来 → "我都关了它怎么又自己开了"。
- **电池优化白名单引导** + **保活体检面板**（界面侧）：品牌 ROM 的正门是白名单，
  WorkBuddy 原话"真正的保命线是电池优化白名单"，比所有技术手段都管用。
- 唤醒锁从**无限期**改为 **10 分钟超时 + 看门狗续期**：长时间死锁会被 ROM 判为
  "异常耗电目标"**优先清理**，与保活初衷相反。

#### Changed
- **server 不再归 `MainActivity`**：`onDestroy` 不再 `stop()`（并加注释说明别补回去）。
  **为什么**：保活的全部意义就是"Activity 没了它还得活"。
- `tokenLock` 由 Activity 字段改为 `Gateway.locked { }`，MainActivity 的主动续期**共用同一把锁**
  （refresh token 是轮换的，两个来源并发续期会让先换到的那个失效）。
- **`TraeUpstream.DEFAULT_MODE`：`glm-5.2` → `DeepSeek-V4-Flash`**（用户要求"别默认用贵的"）。
  只影响**请求没带 model** 的情况；客户端显式指定不受影响。
- 概览页：运行时新增「后台常驻中」说明卡（停止路径：点「停止服务」或通知栏「停止」）。

#### Removed
- `MainActivity.getOrCreateLocalApiKey()`（挪进 `Gateway`，避免两处各存一份 key 逻辑）。

#### 改了哪些文件
| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/example/traework2api/Gateway.kt` | 新增 |
| `app/src/main/java/com/example/traework2api/GatewayService.kt` | 新增 |
| `app/src/main/java/com/example/traework2api/MainActivity.kt` | 去掉 server/tokenLock，改走 Gateway + GatewayService；概览页空态/常驻态 |
| `app/src/main/java/com/example/traework2api/TraeUpstream.kt` | `DEFAULT_MODE` 改 `DeepSeek-V4-Flash` |
| `app/src/main/AndroidManifest.xml` | 7 个权限 + `GatewayService` 声明（`specialUse` / `stopWithTask=false`） |
| `交接文档.md` | 新增 §5.15（含实测证据、未验到项、已知取舍） |
| `docs/UI现状清单.md` | 概览页结构补「后台常驻中」卡 +「检查保活设置」入口 |

#### 验证（全部实测）
- 启动后 `isForeground=true foregroundId=8788`；`dumpsys power` 看到
  `PARTIAL_WAKE_LOCK 'traework2api:gateway-wakelock'`；通知 id=8788 在列。
- **看门狗确实排上了**：`dumpsys alarm` 可见
  `tag=*walarm*:com.example.traework2api.WATCHDOG`、`type=ELAPSED_WAKEUP`、
  `exactAllowReason=permission`、`whenElapsed≈+15min`，并带
  `temporaryAppAllowlistDuration=10000`（系统给的 10 秒唤醒白名单 —— 正是 Doze 下能拉起服务的原因）。
- **按 Home** → `healthz` 200。
- **`am kill` 杀不掉它**（前台服务把进程提到不可回收优先级 —— 这本身就是保活生效的证据）。
- **`kill -9 <pid>`（真杀）→ 约 5 秒后系统重建**（pid 26030→26356、`healthz` 200），
  日志可见新进程打出 `GatewayService: 唤醒锁已持有` → START_STICKY 的 null-intent 重启；
  重建后看门狗**重新排上**，且 pending 只有 1 条（`FLAG_UPDATE_CURRENT` 正确替换，**无重复闹钟**）。
- **点「停止服务」**：`curl_exit=7`；service records=0；通知 0；`should_run=false`；
  日志 `看门狗已取消`，`dumpsys alarm` 中该条只剩 `reason=alarm_cancelled` 的**历史**记录、
  **pending 列表已无 WATCHDOG** → **不会被"关了又自己开"**。
  唤醒锁：`dumpsys power` **活跃**列表 size=1（只剩系统锁）→ **无泄漏**。
- **`should_run` 默认 false**（装机后该 prefs 文件根本不存在）→ 杀进程重开仍是关闭态。
- **保活体检闭环**：对话框显示 `✗ 未加入电池优化白名单` + 3 项 ✓ →
  点「去开白名单」跳到系统 `RequestIgnoreBatteryOptimizations` 弹窗 → 点「允许」→
  `dumpsys deviceidle whitelist` 出现 `user,com.example.traework2api,10034` →
  回 App 按钮变成 **「保活设置：全部正常」**。
- `model:""` 的空请求实测落到 `DeepSeek-V4-Flash`（记录页 `in 7 · out 245`）；
  `model:"DeepSeek-V4-Flash"` 显式请求也正常返回。
- `logcat -b crash` 为空。

#### 没验到的（如实说）
- `stopWithTask="false"` + `onTaskRemoved` 的「划掉最近任务」路径**没能用 adb 稳定驱动**
  （`am` 无 `task remove`；`input keyevent 187` 在该模拟器上打不开最近任务）。
  代码与声明（含精确闹钟兜底）都已就位，但属**未实测** ——
  **真机首次使用请自己划一次最近任务确认**。

#### 已知取舍
- **保活强度 = 耗电**：第二层（唤醒锁 + 看门狗）本质是拿续航换存活率。
  用户明确要求"真机长期跑"，故按 WorkBuddy 全套实现；若日后嫌耗电，
  最小回退是把看门狗间隔调大、或去掉唤醒锁（代价是 MIUI 上可能被冻结）。
- `Notification.Builder.addAction/setPriority` 有弃用告警：本项目**无 androidx**，
  只能用平台 API（与 WorkBuddy 一致），警告无害。
- **`/v1/models` 列表没删贵模型**（仍是"菜单"，选哪个由客户端决定；用户没要求删）。
- **未做悬浮球**（WorkBuddy 的可选加固）：其源码自己写明"挡不住厂商强杀"，
  收益最低而代码量最大（500 行自绘悬浮窗 + 悬浮窗权限），故跳过。

---

## 记录约定

- 每次动代码后**在本文件顶部对应版本下追加**，不覆盖历史。
- 只用 Added / Changed / Fixed / Removed 四类，写清「为什么」而不只是「做了什么」。
- 有实测证据的写进「验证」小节；只是推断的**别写成已验证**。
- 顺手把 `versionName` / `versionCode` 一起改了的话，记得同步本文件的标题。