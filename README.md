# TraeWork2API · Android 原生版

> 把你的 **Trae（国内版 trae.cn）账号额度**，变成手机本地一个标准的 **OpenAI 兼容 API**。
> 纯原生 Android 实现（Kotlin），**无需 Python、无需 Docker、无需电脑**——装到手机上就能跑。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/version-0.4.22-green.svg)](CHANGELOG.md)

---

## 这是什么

一个跑在 Android 手机上的本地 API 网关。启动后，你手机/电脑上的其它 App（Chatbox、
沉浸式翻译、Cherry Studio、OpenAI SDK……任何支持 OpenAI 协议的客户端）都能连接它，
用**你自己的 Trae 账号**正常聊天：

```
手机/电脑上的 OpenAI 兼容客户端
        │  http://127.0.0.1:8787  +  本地 API Key
        ▼
┌──────────────────────────────────────────────┐
│  TraeWork2API（本 App）                       │
│  ├─ OpenAI 协议适配（流式/非流式/工具调用）      │
│  ├─ 多账号保存 / 故障自动换号                   │
│  ├─ Token 自动续期（14 天免登录）               │
│  ├─ 批量签到 / 每日定时签到                     │
│  ├─ 模型表持久化 + 静默补新                     │
│  ├─ 上下文预算（实测 1M，超了丢最老的消息）      │
│  └─ 保活：前台服务 + 广播看门狗 + 精确闹钟       │
│            + 悬浮窗 + 开机自恢复                │
└──────────────────────────────────────────────┘
        │  Trae SOLO 国服上游
        ▼
     Trae 官方服务器
```

**消耗的是你 Trae 账号自己的额度**，不需要向任何 API 中间商付费，数据也不经过第三方。

## 功能一览

- **OpenAI 兼容**：`/v1/chat/completions`（流式 SSE / 非流式 JSON / 工具调用）、`/v1/models`、`/healthz`
- **多账号**：同时保存多个 Trae 国服账号，一键切换；当前账号 token 失效或限流时**自动换号重试**
- **Token 自动续期**：refresh token 轮换续期，长期使用不需要反复登录
- **签到与积分**：一键签到**全部账号**（逐个签，不只当前那个）；可开每日定时签到（默认关，
  到点自动签，没跑成则下次打开 App 自动补签，成功有通知）；积分与用量按模型统计
- **积分监测 · 自动换号**：服务运行中每 30 秒查当前账号剩余积分，低于你设的阈值就自动切到
  余额最多的另一个账号（阈值可填任意数字，总开关默认开）
- **账号备份**：全部账号导出成一个 JSON（**导出前可勾选**带哪几个），换设备后在新机导入即可，
  不用重新登录；导入会先列「新增 / 更新 / 跳过」再让你确认，不会覆盖本机更新过的凭据
- **模型表**：从上游拉真实模型清单，本地持久化，重启不丢，客户端随时可拉
- **上下文预算**：客户端声明 **1M**，网关按实测出的真实上限转发，超了从最老的非 system
  消息开始丢并记日志（见下节「上下文到底有多大」）
- **保活**：前台服务 + 常驻通知 + 唤醒锁 + **独立广播看门狗** + 精确闹钟（不允许时降级为
  不精确）+ 划掉任务自动拉回 + **悬浮窗小圆点** + **开机自恢复（可选）**；有保活体检面板
- **省心**：打开 App 自动启动服务（手动停止过则尊重你的选择）；改了亮暗主题与多配色风格
- **局域网可用**：服务监听 `0.0.0.0`，同一 WiFi 下的其它设备也能连（概览页直接给出该填的地址）

## 上下文到底有多大：实测 1M

`/v1/models` 里的 `context_length` 是**声明值**（客户端拿它决定何时压缩历史），
网关侧还有一个**真实预算**（转发前裁剪的上限）。两个数分开管，理由见下。

这个 1M 不是抄的，是灌出来的：往 `/v1/chat/completions` 塞等长填充，开头埋一个暗号，
结尾问「最开始那个暗号是什么」，读上游回来的 `usage.prompt_tokens` 校准字符/token 比
（英文约 5.17 字符/token、中文约 1.77），再逐步加压卡边界：

| 实际 `prompt_tokens` | 暗号还在吗 |
|---|---|
| 42,026 / 147,394 / 262,256 / 393,354 | 记得 |
| 786,648 / 917,744 / 1,022,623 | 记得 |
| 1,045,074 | 记得 |
| **1,048,075** | **记得** |
| **1,048,600** | **返回空 content** |

边界正好卡在 `1,048,576`。超限的表现不是 4xx、不是报错，是 **HTTP 200 但 `content`
是空串、`usage` 整个字段消失**——这就是「模型突然不说话」的真相。

**为什么还要裁剪**：1M 是上限，不是永远装得下。多轮对话、贴大文件、`tool_calls` 堆叠都能超。
`ContextBudget.trim()` 从最老的非 system 消息开始丢（等价滑动窗口），保留 system 人设与最新
消息，并打一行日志：

```
ContextBudget: 上下文裁剪：丢 3 条老消息，估算 168420 → 122880 tokens（预算 1048576）
```

有这行日志 = 网关在替你丢；没有 = 原样转发，上游自己吃满 1M 才闭嘴。

界面上两个按钮可各自循环切换（128K → 256K → 512K → 1M）。模型卡显示三个数：
`context 1M（上游上报 X · 预算 1M）`——**「上游上报」若是「未上报」，说明上游接口没给这个字段，
不是上游只有 128K**。

## 快速开始（30 秒版）

1. 打开 **[Releases 下载页](https://github.com/jiunianyyds/traework2api-android/releases/latest)**
   （点开直接是当前最新版），下载 `TraeWork2API-x.y.z-debug.apk`，装到手机并打开；
2. 「账号」页 → 复制国服登录链接 → 浏览器登录 Trae → 把回调链接粘回 App；
3. 回「概览」页确认服务显示「运行中」（通常已自动启动）；
4. 把 `http://127.0.0.1:8787/v1`（同机客户端）或概览页显示的局域网地址
   `http://<本机IP>:8787/v1`（同 WiFi 的其它设备）和概览页的 API Key 填进你的客户端。

详细的手把手教程（含电脑连手机、Chatbox 逐字段填法、常见问题排查）见 **[traework2api使用说明.md](traework2api使用说明.md)**。

### 保活怎么配才真的不被杀

进「更多 → 保活」，按顺序做：

1. 确认顶部「后台保活」已启用，按需设时长（0/1/4/8/12/24 小时，到期自动收工）；
2. 点「开始自动配置」，逐项允许电池白名单 / 通知 / 精确闹钟；
3. 开「悬浮窗小圆点」→ 缺权限时点「去授予『显示在其他应用上层』」；
   **MIUI/HyperOS 还要额外开「后台弹出界面」**；
4. 需要重启自恢复再开「重启手机后自动恢复」（默认关）；
5. 卡住就点「直达厂商省电页」，进去把省电策略设成「无限制 / 允许完全后台行为」。

屏幕上的小圆点：**单击复活网关**（1.5 秒去抖，防撞 8787 端口），**拖动挪位置**（位置持久化）。

> 悬浮窗不是防杀魔法。它是「多一层可见窗口优先级 + 一个一键复活入口」。ColorOS 那种无视
> `START_STICKY` 的强杀，最终靠**电池白名单 + 自启动权限 + 精确闹钟**三件套。

真机验收顺序（能定位 90% 的「还是被杀」）：息屏 30 分钟 → 客户端仍能连；
划掉最近任务 → 30 秒内服务回来（通知还在）；锁屏过夜 → 早上直连 `http://手机IP:8787/v1/models` 有响应。

## 构建自己编译

### Windows + Android Studio（或仅命令行 SDK）

```powershell
.\build-apk.ps1          # 产物在 out\TraeWork2API-<版本>-debug.apk
# 或
.\gradlew.bat assembleDebug --offline
```

要求：JDK 17+、Android SDK 36、Build Tools 36.0.0。

### Linux / 无 Gradle 环境（手搓链路）

`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 若还是 `file:///C:/...`
需要先换成 `https://services.gradle.org/distributions/gradle-9.3.1-bin.zip`。不想装 Gradle
的话，`build-apk-linux.sh` 走的是六步手搓：

```
aapt2 → javac(R.java) → kotlinc(embeddable) → d8 → zipalign → apksigner
```

依赖 `libs/`：`okhttp-4.12.0`、`okio-jvm-3.6.0`、`kotlin-stdlib`、`kotlin-compiler-embeddable`、
`kotlin-reflect`、`kotlinx-coroutines-core-jvm`、`annotations-13.0`；`ANDROID_JAR` 指向
`platforms/android-33/android.jar`。

> aarch64 上注意：Google 只发 x86_64 的 build-tools，要用 Debian 的 arm64 `aapt2` 2.19；
> API 35 的 `android.jar` 会被 2.19 拒绝（`RES_TABLE_TYPE_TYPE entry offsets overlap`），
> 用 API 33 的。

## 已知差异

- `foregroundServiceType` 用 `dataSync` 而非 `specialUse`：`specialUse` 是 API 34 才有的 flag，
  compileSdk 33 的 aapt2 直接链接失败。`targetSdk=33` 时 Android 14/15 对 dataSync 的 6 小时
  上限不生效。换 SDK 36 工具链后按上游改回 `specialUse` + `<property>` 即可。
- `targetSdk=33`（上游 36）：Android 14+ 的前台服务类型校验与后台启动限制宽松一档，自用合适。
- 未做混淆 / 资源压缩，体积约 1.2 MB 略大于 release。
- 上游接口的 `model_detail_list` 里没有 `max_input_tokens` 字段，所以 `context_upstream`
  显示「未上报」；`context_length` 兜 131072 只是为了不认识该字段的老客户端能用。

## 致谢与许可

本项目站在两个项目的肩膀上：

- **[Sliverkiss/traework2api](https://github.com/Sliverkiss/traework2api)**（Go）——
  上游协议与业务逻辑的参考实现；
- **[WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)**——
  UI 资源骨架（主题/色板结构/图标）的参考，MIT 许可。本项目的保活结构
  （独立广播看门狗 + 精确闹钟 + 悬浮窗 + 开机广播）也按它的实现重构。

本项目其余代码以 **MIT** 发布，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 免责声明

本项目仅供**个人学习与研究**使用。使用本项目与 Trae 账号存在风险（包括但不限于账号限制），
请自行评估并遵守 Trae 相关服务条款；本项目与 Trae 官方无关，也不保证随上游协议变更长期可用。
使用本项目产生的任何后果由使用者自行承担。
