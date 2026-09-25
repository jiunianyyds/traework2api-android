# 变更记录（CHANGELOG）

本文件**只追加**，记「改了哪个文件 / 改了什么 / 为什么」。
项目全貌看 `交接文档.md`，UI 规范看 `docs/UI现状清单.md`，本文件只是改动流水。

格式：[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号跟 `app/build.gradle.kts` 的 `versionName`。

---

## [0.4.0] - 2026-09-25

「一键签到」+ **修掉「今日签到」一直显示 `—`**。`versionName` 0.3.0 → **0.4.0**、`versionCode` 3 → **4**。

### 背景：先探测，结果推翻了 GitHub 参考实现

用户要"软件内一键签到"。动手前先用真账号探测（**只读 status + 一次无损 claim**），
结论与网上最有名的参考 `L0NE-6/Trae-AutoCheckin` **对不上**，幸好没照抄：

| 项 | 该参考实现说 | **实测（本账号）** |
|---|---|---|
| 已签时 claim 返回 | `9095` | **`9004`**（"order parameters incorrect"） |
| body 必须带 `req_source` | "空体会被拒成 9074" | **三种 body 返回一字不差，`req_source` 无影响** |
| 必须带设备头 | "缺了会被拒成 9074" | **无影响，不需要** |
| status 字段 | `checked_in`/`credits`/`enable` | 另有 **`did_checked_in`** 与 **`extra_credits`** |

→ 因为实测 `req_source`/设备头无效，**原计划"改 `ug()` 加设备号"整个不用做**，
签到就是复用现成的 `ug()`（它本来就 POST `{}`）。**需求因此变简单了，而不是变复杂。**

### Added
- **`TraeUpstream.EP_CLAIM`** + **`claimCheckin()`**：打 `checkin_credits/claim`，返回业务 code。
- **`TraeUpstream.CheckinStatus`**（`checkedIn`/`credits`/`enable`）+ **`parseCheckin()`**：
  把 `fetchCheckin()` 的返回从 `Pair<Boolean,Long>` 换成带 `enable` 的数据类 ——
  **`enable` 是"该账号是否开放签到"，`false` 时不该发 claim**（reference `scheduler.go:104` 同款判断）。
- **`parseCheckin` 自检**：fixture 直接用**真账号 dump 的原文 JSON**，钉住三个字段独立解析、
  以及"字段缺失要回落而不是抛异常"（上游多给/少给字段不能把页面打崩）。
- **账号页「积分与用量」卡的「签到」按钮**（放在「当前可用积分」标题行，挨着「刷新」）：
  文案随状态变 —— `签到` / `签到中…` / `今日已签到` / `未开放`；
  后三种**置灰禁用**（`isEnabled=false` + alpha 0.5）。
- **签到成功后自动重查积分与状态**：用户最想看的就是"签完变成多少"。

### Fixed
- **「今日签到」永远显示 `—`**（用户报的 bug）。**根因不是解析错，而是从来不自动加载**：
  `accountBalance`/`accountCap`/`accountCheckin` 初始化为 `null`，只有手动点「刷新」才填充，
  所以一进账号页三个数值位都是 `—`（「权益额度」「当前可用积分」同样中招）。
  修法：账号页首次渲染时 `contentHost.post { refreshQuota() }` 自动拉一次；
  用"三个缓存都为 null"做闸，**不会每次切页都打上游**。
  ✅ 实测：进页面直接显示 `2578` / `8350` / `已签 · +150/日`。

### Removed
- **「更多」页的「账号区域」卡**（含「登录国际服（Trae SG · 未开发）」按钮）：用户反馈用不到。
  国际服登录入口在**账号 Tab** 上本来就有（「复制国际服登录链接（未开发）」），
  删掉的是一处**重复入口**，能力没丢。随之删掉只为它存在的 `openLoginSg()`（已无引用）。
  ⚠️ `TraeRegion.SG` / `openLogin(SG)` / 账号页的国际服链接**仍保留** —— 国际服并未移除。

### 硬约束（违反即 bug）
1. **已签状态必须从 UI 上堵住**：实测重复 claim **不扣积分、不改坏状态**（已复测），
   但会白发一次请求并回 `9004`。所以 `checkedIn==true` 时按钮**置灰**，`claimCheckin()` 里也有第二道闸。
2. **先看状态再决定发不发 claim**（reference `scheduler.go:100` 的顺序）：
   已签 / `enable=false` 都不发请求。**绝不盲发 claim。**
3. **上游 message 直接展示会看不懂**：`9004` 的原文是英文
   `"The submitted order parameters are incorrect. Please try placing the order again"`，
   文案由本地映射成「今日已签到」，**不透传上游英文**。
4. **只有国服能签到**（`region.canQueryQuota`）：国际服账号点了永远失败，属已知边界。

### 验证（模拟器实测）
- 构建通过；装机 `logcat -b crash -d` **空**。
- **status 真实字段**（只读）：`checked_in=true, code=0, credits=150, enable=true, extra_credits=50`。
- **claim 真实返回**：`code=9004`（今天已签状态下试发，与探测一致）→ 文案映射为「今日已签到」。
- **无害性已复测**：claim 试发后 status 仍 `checked_in=true`、`credits=150`，未扣分未改状态。
- **已签路径**：按钮显示「今日已签到」、`enabled=false`；**硬点它无任何反应、不发请求**。
- **未签路径**（临时把 `checkedIn` 强制为 false 模拟，验完已删）：按钮变「签到」且 `enabled=true`、
  标签「未签」；点击后真实打到上游、`claim code=9004` 回来 —— 主路径端到端跑通。
- **自动加载**：杀进程重进账号页，无需点刷新即显示 `2578` / `8350` / `已签 · +150/日`。
- 临时代码（探测函数、探测按钮、模拟开关、日志）**全部已删并 grep 确认无残留**。

### 已知限制（明确，别当 bug）
- **只做手动签到**：不做定时、不做多账号批量、不做 9074 限流重试
  （那是有 cron 的桌面工具 `TraeWorkAssistant` 的活）。撞上限流会显示失败码。
- 签到成功与否依赖上游 `code==0`，**未做"积分真的到账"的二次断言**（怕一次签到打三次请求）。
- `enable=false` 的具体触发条件未探明（本账号一直是 `true`），只按上游字段如实反映。

---

## [0.3.0] - 2026-09-25

「模型 Tab 增加刷新模型按钮」+ **换上游 function 解锁新模型**。`versionName` 0.2.0 → **0.3.0**、`versionCode` 2 → **3**。

### 背景：任务书 §1.2 的悲观推断被实测推翻

任务书交接时实测「新模型（`DeepSeek-V4.1-Flash` / `GLM-5.3`）在当前 function 下一律 `4001`」，
据此警告「光加刷新按钮，很可能刷出来了也调不通」。本次按要求**先探测再动手**，探测结论是：

- 4001 的**真因不是"模型不可用"**，而是**模型挂在别的 function 下** —— 老代码发的是
  `function=solo_work_lite`，而新模型只在 **`solo_work_remote`** 下存在。
- 实测同一 token 下逐个 function 拉表：`solo_work_lite` 47 个（无新模型）、
  `solo_work_remote` **49 个且包含 `deepseek-v4.1-flash` / `glm-5.3` / `step-5-preview`**。
- 换成 `solo_work_remote` 后**新模型真能聊天**（中文正常、`done` 正常、无 error），
  且**旧 12 个模型逐个实测全部正常、工具调用无回归** —— 所以换过来是"白捡"。

### Added
- **`TraeUpstream.fetchModels()`（新）**：打 `POST {agentHost}/api/ide/v1/get_detail_param` 拉真实模型表
  （请求体照抄 reference `FetchModels`），复用既有 `Builder.solo()` 头（**一行都不用加**）。
- **`TraeUpstream.parseModels()`（新）**：`config_info_list` → `/v1/models` 形状；
  `name` 取 `display_config.display_name`（空回落 id）、`context_length` 取
  `model_detail_list[0].max_input_tokens`（取不到回落 131072）。
- **`TraeUpstream.isChatModel()`（新）**：过滤上游表里的**非聊天项**。
  上游表混着 `custom_model_*` 占位、`summary` / `file_search_agent` / `browser_use_subagent`
  等内部 agent，它们当 config_name 发过去会 4001，摆在模型 Tab 里只会让用户选错。
  **判据全部改用上游自己的字段**（见下方 Fixed），过滤后 **15 个**。

### Fixed（用户实测反馈后修正，2026-09-25）
用户指出**「我自己接入的第三方 API 模型为什么出现在 Trae 内置模型列表里」** —— 真 bug，已修。

- **根因**：第一版 `isChatModel()` **只用名字黑名单猜**，而第三方模型的名字
  （`mistral-small-4` / `glm-4.5-air` / `gemini-3.1-pro` / `oc/muse-spark-…`）
  **长得跟官方模型一样**，黑名单根本挡不住 → 6 个第三方模型混进了列表：
  `mistral-small-4`、`oc/muse-spark-1.3-contributor`、`gemini-3.1-pro`（显示名「肖恩ai-…」）、
  `agnes-2.5-flash`、`deepseek-v4-flash-0731`（显示名「ZZ …」）、`glm-4.5-air`，
  外加两个 `TRAE-DeepSeek-V4-Flash`（也是用户自己接的）。
- **真因排查**：把上游 49 项的字段全 dump 出来，发现上游**本来就有权威标记**：
  `config_source`（**1=官方内置 / 3=用户接入第三方**）与
  `display_config.is_custom_model`。第三方那批**全是 `config_source=3` + `is_custom_model=true`**，
  两个字段完全一致地分开了两批 —— 之前完全没用上。
- **修复**：判据改为读上游字段（`config_source==1` 且 `is_custom_model!=true`），
  名字黑名单只留作兜底。
- **顺带修掉另一个真 bug**：`is_invisible_to_user` 之前也被忽略 ——
  上游自己标"不对用户展示"的项（`glm-5` / `DeepSeek-V4-Flash` / `DeepSeek-V4-Pro` /
  `summary` / `sagitta`…）会露出来。**这正是之前列表里出现两个同名
  `DeepSeek-V4-Flash` 的原因**（一条 invisible、一条可见，只按名字过滤会把两条都留下）。
- ⚠️ 注意取舍：`glm-5` / `glm-5-turbo` / `DeepSeek-V4-Flash` / `DeepSeek-V4-Pro` 这 4 个
  **旧静态清单里有、但上游标了"不可见"**，因此刷新后**会从列表消失**。
  这是**跟着上游走的正确结果**（它们在新 function 下已不是给终端用户选的），
  但**重启回静态表时会又出现** —— 这是"静态兜底"与"上游真表"的固有差异，已知、可接受。
  `DEFAULT_MODE = "DeepSeek-V4-Flash"` 不受影响（它不查列表有效性）。
- 结果：**49 → 15 个**，全是 Trae 官方内置且可直呼的模型。
- **`TraeUpstream.selfCheck()`（新）**：钉住解析（空 `config_name` 跳过、name/context 回落、
  空表返回 null）**+ 过滤器两侧边界**（真模型不许被误过滤、内部项必须挡住）。
- **`ModelsRepo.selfCheck()`（新）**：表非空 + **空表不许覆盖静态表**。
- **模型 Tab「刷新模型」按钮**：手动拉取并**原地更新**（`modelsRefreshApply` 句柄），
  旁边一个计数标签（「内置清单 · 12 个模型」/「已刷新 · 15 个模型」）让用户确信按钮真起作用了。

### Changed
- **`TraeUpstream.FUNCTION`：`solo_work_lite` → `solo_work_remote`**（本次核心改动）。
  这是全局生效的转发常量，**旧模型实测无回归**（见验证）。
- **`ModelsRepo`：由「不可变静态清单」改为「静态兜底 + 可选动态表」** ——
  把阶段6 砍掉的拉取能力捡回来。`current()` 永远非空：
  `private var dynamic` 为 null（没刷过/刷失败/未登录）时回落静态表。
  **`DEFAULT_MODE` 绝不跟着动态表变**（仍是 `DeepSeek-V4-Flash`，用户要求"别默认用贵的"）。
- `ModelsRepo` / `MainActivity` 的注释同步更新（原文写着"此清单固定不变，不做真模型拉取替换"，
  已过时）。

### 硬约束（违反即 bug，都已在代码里注释）
1. **静态表必须保留兜底**：`/v1/models` 与模型 Tab 都读 `current()`，
   返回空数组会让**所有客户端看不到任何模型**（比少一个新模型严重得多）。
2. **空表/解析失败一律拒绝覆盖**（`applyFetched` 的 `length() > 0` 闸）。
3. **第一版不做持久化**：重启回静态表（用户要的是"刷最新"，不是"离线缓存"）。

### Fixed / 顺手守住的坑
- 刷新**不能用 `render()`/`redraw()`**：那会 `removeAllViews()` 重建内容区，
  **搜索框内容与滚动位置全丢**（项目踩过，见 MEMORY「积分刷新」）。
  做法是把卡片容器的重建登记成 `modelsRefreshApply` 句柄，只清空重塞卡片容器。
- 重塞后**必须同步 TextWatcher 的 `cards` map**：否则刷新后搜索过滤会作用在已失效的旧 View 上
  （表现：搜索框像坏了）。已把过滤收成 `applyFilter()`，重塞后重跑一次。

### 验证（模拟器实测，全部真跑）
- `.\gradlew.bat assembleDebug --offline` 通过；装机 `logcat -b crash -d` **为空**
  （10 个启动自检全过：原 8 个 + `TraeUpstream.selfCheck` + `ModelsRepo.selfCheck`）。
- **刷新真拉到数据**：`/v1/models` 与 UI 计数一致（`uiautomator dump` 读到「已刷新 · 15 个模型」）。
  初版过滤有 bug 时是 29；用户指出第三方混入后改用上游字段过滤，现为 **15**（见 Fixed）。
- **`/v1/models` 跟着变是零改动**：它读的就是 `ModelsRepo.current()`（任务书预判正确）。
- **新模型刷新后真能聊天**（这是本任务的目的）：`deepseek-v4.1-flash` 经网关返回
  「我是由深度求索公司创造的AI助手，乐于用中文为你解答问题并提供帮助。」+ `[DONE]`、无 error。
- **无回归**：`DeepSeek-V4-Flash` 中文流式正常（「1+1等于2。」）；工具调用仍
  `finish_reason:"tool_calls"`、`get_weather` 保留、`reasoning_content:null` 污染 0 次；
  旧 12 个模型在新 function 下逐个实测全部 OK。
- **刷新后搜索仍有效**：输 `zzzz` → 空态卡出现、无模型卡可见；清空 → 卡片回来。
- **重启回静态表**（非持久化符合预期）：重启 App 后显示「内置清单 · 12 个模型」。
- **失败不清空**：断网时点刷新 → 日志 `拉模型表异常: UnknownHostException`、
  清单仍是 12 个（**没被清空**）、系统日志确认 toast 弹出（非静默失败）。
- **自检真的能抓错**（不只验证"通过"）：故意把 `applyFetched` 的 `length() > 0` 闸改坏 →
  启动即崩，崩溃日志给出精确消息 `自检失败：空表覆盖了静态表`（`ModelsRepo.kt:70`）；改回后正常。
- ⚠️ 探测过程中的一个坑：新模型一度回**英文**，我差点误判成"新 function 有问题" ——
  真因是 **PowerShell 管道把中文 body 变成了问号**（§4.10 记过的老坑）。用 UTF-8 文件
  push 后中文正常。**测中文一定要先写文件再 push。**

### 已知限制（明确，别当 bug）
- **模型表过滤用的是黑名单**：上游若新增词形不在模式里的内部 agent，会漏进列表。
  代价可接受（多一项而已，不炸），升级路径见 `isChatModel` 注释。
- 静默失败已排除，但**拉取失败只 toast 一次**，不重试、不自动重拉（用户要的是手动刷新）。
- 上游表里有**重名项**（两个 `DeepSeek-V4-Flash`，display_name 不同）：UI 的 `cards` map 以 id 为键，
  重名会被后一个覆盖 → 该类模型在**搜索过滤**时以最后一条为准。实测不影响刷新与聊天。

---

## [0.2.0] - 2026-09-24

首个「国服可用 + UI 完整」的版本。`versionName` 0.1.0 → **0.2.0**、`versionCode` 1 → **2**
（本版之前那批 `[未发布]` 条目一并归到这里）。

### 版本号与打包（本次改动）

#### Added
- **`build-apk.ps1`（新，工程根）**：构建 debug APK 并按版本号命名到 `out\`，
  产出 `out\TraeWork2API-<versionName>-debug.apk`（**`out/` 已加入 `.gitignore`**）。
  - **版本号只有一个来源**：脚本用正则从 `app\build.gradle.kts` 读 `versionName`，
    不在脚本里写死 —— 两处写死早晚会对不上。
  - 拷贝前先删同名旧文件：APK 被占用时 `Copy-Item` 会**静默失败**，
    留下一个旧 APK 冒充新产物（比报错更糟）。
  - ⚠️ **脚本刻意全用英文**：本机 `powershell.exe` 是 **5.1**，它按 **GBK** 读 `.ps1`，
    UTF-8 中文注释会被拆成能破坏字符串引号的字节 → 直接**语法报错、脚本跑不起来**
    （本次实测踩到）。中文说明放在 `docs\` 与本文件里。

#### Changed
- `app/build.gradle.kts`：`versionName = "0.1.0"` → `"0.2.0"`，`versionCode = 1` → `2`。
- `docs\真机保活验证清单.md`：APK 名同步为 `out\TraeWork2API-0.2.0-debug.apk`
  （原文写的 `0.1.0` 且那个 `out\` 当时**并不存在**，现在脚本能真产出），并说明用 `build-apk.ps1` 生成。
- 界面版本号无需改代码：`MainActivity` 的 `versionName` 是 `by lazy { PackageManager... }`，
  自动跟着 `versionName` 走（**这处 plumbing 早就有了，本次只是让值不再是 0.1.0**）。

#### 验证（模拟器实测）
- `.\build-apk.ps1` → `BUILD SUCCESSFUL`，产出 `out\TraeWork2API-0.2.0-debug.apk`（1.5 MB）。
- **版本号真的进了 APK**（不只看脚本输出）：`aapt2 dump badging` →
  `versionCode='2' versionName='0.2.0'`。
- 装机后 `logcat -b crash -d` 为空（8 个启动自检全过）。
- **界面显示正确**：`uiautomator dump` 的「更多 → 关于」卡里读到文案顺序
  `应用说明 | … | 版本 | 0.2.0 | 致谢`。
- **端到端无回归**：点「启动服务」→ `healthz=200` / `models=200` →
  真实流式聊天请求返回 `"content":"版本验证通过"`、`[DONE]` ×1、**error 帧 0**。

---

## [0.2.0] - 2026-09-24

### UI 全面重做：多风格（4 套）× 独立亮暗，运行期可切换

用户要求：不再模仿 WorkBuddy，换一套自己的设计语言；并在「更多」里加**外观风格**二级页，
可选**多套风格**（配色 + 圆角/间距形态都不一样）与**独立的亮/暗**（跟随系统 / 浅色 / 深色），
选择持久化、切换立刻生效。

#### Added
- **`AppStyle.kt`（新）**：风格表 + 运行期取色 + 亮暗落地 + 启动自检。
  - `Style` = 中文名 + 圆角三档（卡片/控件/胶囊）+ 间距节奏（页面/卡片内边距 + 卡片间距
    + 顶栏内边距 + 内容最大宽度）+ 高低两种圆角的每套 **亮/暗两份色表**（19 个语义键）。
  - 4 套风格：**靛蓝**（默认，沿用原 `wb_*` 色板 / 中圆角 / 标准间距）、
    **石墨**（冷灰中性 / 方正小圆角 6dp / 紧凑间距 / 无阴影）、
    **苔绿**（自然绿 / 大圆角 24dp / 舒展间距）、
    **落日**（暖橙 / 大圆角 20dp / 宽松内边距）。
  - `color(name)`：按“当前风格 + 当前亮暗”查表取色；**取不到回退 `on_surface`**（绝不返回透明）。
  - `Mode` = 跟随系统 / 浅色 / 深色；`applyNightOverride(ctx)` 用
    `createConfigurationContext` 覆写 `uiMode`，使 `values-night`、平台对话框、光标一起跟。
  - `selfCheck()`：钉住①每套风格亮/暗色表齐全 ②色值必须是 `#RRGGBB` 且不透明
    ③11 组对比度 ≥ 4.5:1（正文/说明/主色/4 个语义 chip）。
- **「外观风格」二级页**（`更多` → 「打开外观风格」）：4 张风格卡（左侧色样预览用**该风格自己**
  的主色与圆角画，右侧选中项 = 主色 2dp 描边 + 「✓ 使用中」）+ 亮暗三选一（圆形单选标记）。
- **「更多」页新增「外观」入口卡**，显示当前风格与亮暗（不进二级页也知道现在在用什么）。

#### Changed
- **取色从「资源 id」改成「语义名运行期查表」**（本轮核心）：
  `color(R.color.wb_primary)` → `color("primary")`，全站 33 处。
  原因：`R.color.*` 是编译期常量，只能区分 `values/` 与 `values-night/`，**换不了风格**。
- **圆角/间距 token 从 companion 常量改成跟随风格的属性**（`RADIUS_*` / `GAP` / `PAGE_PAD_*` /
  `CARD_PAD` / `CONTENT_MAX_DP` 现在读 `AppStyle.current`），否则切风格只有颜色变、形态不变。
- `shape()` 新增 `strokeDp` 参数（选中卡要 2dp 描边）；`action()` 描边态改用语义名。
- `linkAction` 触控高度 40dp → **48dp**（无障碍最低要求）。
- 新增 `redraw()` 并在 token 加载、服务启停后调用它，替代 `render(currentPage)`：
  **修掉“停在二级页时被后台回调踢回 Tab 页”的 bug**（实测：切完风格重建后，token 加载完成
  又 render 一次，人就被送回「更多」）。
- `onSaveInstanceState` 记当前 Tab + 二级页；`attachBaseContext` 在建控件前加载外观偏好。
  切风格/切亮暗走 `recreate()` —— 颜色/圆角/间距散在几十个控件（含 chip、图标 tint），
  逐个回刷既长又易漏，重建一定是完整正确的一套。

#### 保留（未动）
- 业务逻辑、5 Tab 与二级页结构、7 个原启动自检、`res/values/colors.xml` +
  `values-night/colors.xml`（现在只代表**默认风格**，供启动主题与 6 个矢量图标使用）。

#### 验证（模拟器实测，非推断）
- `gradlew.bat assembleDebug --offline` 通过；`logcat -b crash -d` 为空（含新增风格表自检）。
- 平板横屏（3440×1440）+ 手机尺寸窗口（411dp 宽 × 867dp）各一轮；截图在 `docs/ui-shots/`。
- 4 套风格 × 亮/暗 的概览页各一张；苔绿浅色下 5 个 Tab + 二级页全套。
- 持久化：`am force-stop` 后重开仍是所选风格与亮暗（用「更多」页「当前：X · Y」文案断言）。
- 跟随系统：`cmd uimode night yes/no` 切换系统夜间，App 跟着变。
- ⚠️ 模拟器被厂商锁死横屏（`wm size` 与 `user_rotation` 均无法转成真竖屏），
  “手机竖屏”用等效的 411dp 宽窗口代替，**未在真机上验证**。

### 视觉语言深化：卡片层次 / 状态双编码 / 页面框架 / 图标重画

上一轮把"多风格"的骨架搭好了，但页面的视觉语言还是旧骨架（大白条顶栏 + 同质卡片），
看起来像"四套配色"而不是"一套新语言"。本轮补上这一层。

#### Added
- **卡片三层次 `CardTone`**（`NORMAL` / `SOFT` / `EMPHASIS`）：
  - `NORMAL` = `surface` + 1dp 描边 + 风格 elevation（常规内容卡）；
  - `SOFT` = `surface_container` 无描边无阴影（说明 / 空态：服务未启动·后台常驻中、使用引导、
    未登录、暂无记录）；
  - `EMPHASIS` = `primary_container` 无描边无阴影（本页最该看的那块：概览的**服务地址卡**）。
  - 有底色的面板不加阴影 —— 靠颜色分层，再叠阴影会显脏。
- **状态双编码**：`dot(tone, on)` + `statusChip(value, tone, on)`，
  圆点**实心 = 成立 / 空心 = 未成立**，用在概览服务状态、账号转发状态、记录 OK/ERR 上。
  （满足任务书 §5.2「色彩 + 图标/形状双重编码」，色盲也能分辨。）
- `AppStyle.CONTRAST_PAIRS` 新增 5 组：`on_surface` / `on_surface_variant` 压在
  `primary_container`、`surface_container` 上（强调卡/柔和卡里的文字），以及 `primary` 压
  `surface_container` —— 底色不是 `surface` 的卡单独钉一遍对比度。

#### Changed
- **顶栏与底栏底色从 `surface` 改为 `background`**（与页面同色），1dp 分隔线保留。
  这样"凸起"的只有卡片，层次由卡片承载；旧版三处同色导致整屏一样平。
- `chip()` 从 `TextView` 改为**横向 `LinearLayout`**（要容纳状态点），
  `lp(-2,-2)` 的 wrap 钉在容器上（横幅 bug 的修复不能丢）。
- `linkAction` 新增 `overContainer` 参数：强调卡内部（卡底本身是 `primary_container`）时
  按钮底改 `surface`，否则和卡同色、只剩一行字飘着。
- 删掉已无引用的 `chipDanger`（概览的"服务已停止"改用 `statusChip` 带空心点）。
- **落日·暗色的 `primary_container` 由 `#7A2E10` 调深到 `#6E2607`**：
  原值下"说明文字 / 强调卡底"只有 4.53:1，太贴下限；改后 5.19:1，其余组合都 >6。

#### 重画图标（矢量，仍无位图）
- `ic_dashboard`（2×2 圆角方块）、`ic_account`（头+肩）、`ic_models`（数据圆柱）、
  `ic_history`（表盘+指针）、`ic_more`（三点）：统一 **24dp 视口 + 1.7dp 描边 + 圆头圆角**，
  颜色仍是占位资源、实际由 `setTint()` 按当前风格刷。

#### 桌面图标改成自适应图标（用户拍板：双向箭头 + 靛蓝底）
原图标是 108dp 满幅方块（硬编码 `#4F46E5` + 一个「W」形标记），且**没做自适应图标** ——
Android 8+ 会当 legacy 图标塞进遮罩里再缩小一圈，看起来总比别人的图标小。
- `mipmap-anydpi-v26/ic_launcher.xml`（新）：adaptive-icon = background + foreground + monochrome；
- `drawable/ic_launcher_background.xml`（新）：满幅纯靛蓝；
- `drawable/ic_launcher_foreground.xml`（新）：白色「双向箭头」（请求出去 / 响应回来），
  图形宽约 54/108 = 画布 50%，**完全落在中间 72×72 安全区内**，任何遮罩都不会被切；
- `mipmap-anydpi/ic_launcher.xml`（新）：API 24–25 回落用的满幅方形（图形更大）；
- 删除 `drawable/ic_launcher.xml`（旧的「W」标记）；manifest 改 `@mipmap/ic_launcher`。
- `monochrome` 复用前景层，支持 Android 13+ 的「主题图标」（系统取 alpha 按壁纸配色）。
- ⚠️ 桌面图标**不能跟着 App 内风格切**（要挂多个 `activity-alias` 动态启用，属黑魔法），
  所以固定靛蓝当招牌色。

#### 验证（模拟器实测 + 像素取样）
- `gradlew.bat assembleDebug --offline` 通过；`logcat -b crash -d` 为空。
- 4 套风格 × 亮/暗 的概览页各一张；靛蓝深色下另截账号页 / 更多页；截图 `docs/ui-shots/v2-*.png`。
- **像素取样确认**（不靠肉眼，缩略图分不出 `#F7F7FC` 与 `#FFFFFF`）：
  顶栏 `#F7F7FC`（=background）、柔和卡 `#F0F0F8`（=surface_container）、
  强调卡 `#E6E7FF`（=primary_container）、状态点为"红环 + 白心"（空心生效）。
- **桌面图标**：`aapt2 dump badging` 显示 icon 解析到 `res/mipmap-anydpi-v26/ic_launcher.xml`
  （真的用上了自适应图标，没退回 legacy）；再从系统「应用信息」页截图，确认渲染为
  **靛蓝圆角方形 + 白色双向箭头**，图形大小正常（没被 legacy 那样缩小）。
  ⚠️ API 24–25 的回落分支（`mipmap-anydpi/`）**没法验证** —— 手上只有 API 32 的模拟器，
  且 MuMu 的桌面是它自己的精简桌面、不显示本应用，最终呈现仍以真机为准。

### 收尾四件：二级页返回 / 搜索空态 / 弹窗配色 / 更多页层次

多风格与视觉语言做完后巡了一遍还剩什么，挑出四处「现在的界面确实有小毛病」的补掉。

#### Added
- **二级页顶栏的「← 返回」**（`backAction`）：二级页原来**只有**系统返回键这一个出口，
  纯小白不一定知道那个键，容易卡在「保活设置 / 外观风格」页上。
  返回键放在标题**上方**（不是左侧）—— 左侧会把标题挤得与内容区左边缘错位。
- **模型页搜索无结果时的空态卡**（`SOFT`）：原来过滤只是把卡片 `GONE`，
  用户打错一个字就是**整页空白**，会以为 App 坏了。
- **`confirmDialog()`：统一风格的确认弹窗**。原来 4 个弹窗（国际服登录 / 切未开发区域账号 /
  删除账号 / 粘贴回调输入）都用 `AlertDialog.Builder(this).setTitle(...)`，走的是**平台主题**，
  配色来自 `colorAccent`（写死在 `values/colors.xml` 的靛蓝）—— 切到苔绿/落日/石墨之后，
  弹窗还是靛蓝高亮 + 系统灰底，和 App 完全脱节。
  现在标题/正文/按钮全部自己画，窗口底也换成当前风格的 `surface` + 圆角。
- `action()` 新增 `danger` 参数：删除账号的确定键走 `error` 色描边
  （破坏性操作不该长得和普通"确定"一样）。不给实心红底 —— 色板没有 `on_error`，
  浅色的 error 配白字在深色风格下会看不清。

#### Changed
- 「更多」页的应用说明 / 致谢 / 版权与免责声明改用 `CardTone.SOFT`（说明性质），
  与「外观」「保活」两个带操作的入口卡（`NORMAL`）区分开 —— 原来同一个 App 里两套判断标准。
- 粘贴回调的输入框补上 `surface_container` 底 + `RADIUS_CONTROL` 圆角，
  不再用平台那根下划线。

#### 验证（模拟器实测）
- 构建 `assembleDebug --offline` 通过；连续操作后 `logcat -b crash -d` 为空。
- 「← 返回」：二级页可见，点它回到「更多」✓（截图 `check-01-subpage-back.png`）。
- 搜索空态：输入 `zzzz` → 空态卡出现 ✓（`check-02-search-empty.png`）。
- 弹窗配色：切到「落日」后弹窗的描边键与实心键都是**落日橙**、窗口底是暖白圆角 ✓
  （`check-03-dialog-sunset.png`）。
- 更多页层次：应用说明卡变柔和层、外观卡仍是描边卡 ✓（`check-04-more-soft-cards.png`）。
- 另外实测排掉两个怀疑点：模型页搜索框高 **49dp**（达标）；
  系统字号 **1.3 倍**下文字正常换行、没被固定高度控件裁切（`check-font13-overview.png`）。

### 再加三件：占比条 / 端点复制 / 加载态

#### Added
- **记录页「模型消耗汇总」每行加占比条**（`shareBar`）：原来只有「总 N · 输出 M」两列数字，
  扫不出谁吃得多。条形按**相对最大的那组**画（不是占总量）—— 模型一多，占总量的话每条都只剩一小截，
  反而看不出差别。用 weight 分两段实现，不引任何依赖；`max<=0` 有除零保护。
- **概览「使用引导」的 3 个端点各配「复制」**：复制的是**完整 URL**
  （`http://127.0.0.1:8788/healthz` 这种，可直接贴浏览器/curl），提示里也会把 URL 念出来。
- **两处反馈空档**：
  - 「刷新积分」时数值位显示「…」、按钮变「刷新中…」；
  - 启停服务时状态 chip 与按钮同时变「启动中…/停止中…」，按钮置灰禁用（防连点）。

#### Changed
- 积分刷新从"重建页面"改为**原地更新**（新增 `quotaApply` 句柄）：
  `render()` 会 `removeAllViews()` 重建内容区，**滚动位置被重置到页首** ——
  实测后果是"积分与用量"卡在视口外，加载态的「…」一次都没被看见。
  现在只改那几个 TextView，页面不重建 → 滚动位置不丢、也不闪。
- `metric(label, value)` → `metricOf(label, valueView)`：值控件改由调用方持有，
  否则没法原地改数值（`metric` 已无引用，直接替换）。
- `render()` 里顺手清掉 `quotaApply`，避免旧页面的控件被句柄一直持有。

#### 验证（模拟器实测）
- 端点复制：点「使用引导」最后一行的复制，去模型页搜索框粘贴 → 得到
  `http://127.0.0.1:8788/healthz` ✓（`check-06-endpoints.png`）。
- 启停加载态：点「启动服务」的瞬间截到 chip 与按钮同时显示「启动中…」、按钮置灰；
  3 秒后 chip 变「服务运行中」；收尾点「停止服务」→「服务已停止」✓（`check-07a-*.png`）。
- 积分刷新：截到「刷新中…」+ 三个「…」，且**页面仍停在「积分与用量」卡上**；
  完成后原地变成 2436 / 8200 / 已签·+150/日，滚动位置依旧没丢 ✓（`check-07b-*.png`）。
- **记录页占比条（真实数据验证）**：原本以为 `UsageStore` 是空的、还打算请用户发一次请求去造数据，
  结果打开记录页发现**历史记录一直都在**（glm-5.2 总 248627 / DeepSeek-V4-Flash 总 1696）。
  上一轮我直接下了"没数据"的结论、没去看页面 —— 记录在这里更正。
  实测两组量级差 150 倍：长条铺满、短条缩成一个小点，一眼看出谁吃得多 ✓
  （`check-05-records-before.png`）。**最终没花用户任何额度。**
- 短条**不做最小宽度钳制**：那会把"极小"画得比实际大；具体数字就在同一行，够用了。
- ⚠️ 仍**没验证**的是**空态**（无记录时那句「暂无记录」）—— 现在存储里有数据，
  要测它就得先清掉用户的用量记录，不做。

---

## [0.2.0] - 2026-09-23

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