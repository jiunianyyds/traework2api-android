# TraeWork2API · Android 原生版

> 把你的 **Trae（国内版 trae.cn）账号额度**，变成手机本地一个标准的 **OpenAI 兼容 API**。
> 纯原生 Android 实现（Kotlin），**无需 Python、无需 Docker、无需电脑**——装到手机上就能跑。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/version-0.4.14-green.svg)](CHANGELOG.md)

---

## 这是什么

一个跑在 Android 手机上的本地 API 网关。启动后，你手机/电脑上的其它 App（Chatbox、
沉浸式翻译、Cherry Studio、OpenAI SDK……任何支持 OpenAI 协议的客户端）都能连接它，
用**你自己的 Trae 账号**正常聊天：

```
手机/电脑上的 OpenAI 兼容客户端
        │  http://127.0.0.1:8787  +  本地 API Key
        ▼
┌─────────────────────────────────┐
│  TraeWork2API（本 App）          │
│  ├─ OpenAI 协议适配（流式/非流式）│
│  ├─ 多账号保存 / 故障自动换号     │
│  ├─ Token 自动续期（14 天免登录）│
│  ├─ 批量签到 / 每日定时签到       │
│  ├─ 模型表持久化 + 静默补新       │
│  └─ 前台服务保活（看门狗+唤醒锁） │
└─────────────────────────────────┘
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
- **保活**：前台服务 + 常驻通知 + 唤醒锁 + 看门狗闹钟 + 划掉任务自动拉回；有保活体检面板
- **省心**：打开 App 自动启动服务（手动停止过则尊重你的选择）；改了亮暗主题与多配色风格
- **局域网可用**：服务监听 `0.0.0.0`，同一 WiFi 下的其它设备也能连（概览页直接给出该填的地址）

## 快速开始（30 秒版）

1. 打开 **[Releases 下载页](https://github.com/jiunianyyds/traework2api-android/releases/latest)**
   （点开直接是当前最新版），下载 `TraeWork2API-x.y.z-debug.apk`，装到手机并打开；
2. 「账号」页 → 复制国服登录链接 → 浏览器登录 Trae → 把回调链接粘回 App；
3. 回「概览」页确认服务显示「运行中」（通常已自动启动）；
4. 把 `http://127.0.0.1:8787/v1`（同机客户端）或概览页显示的局域网地址
   `http://<本机IP>:8787/v1`（同 WiFi 的其它设备）和概览页的 API Key 填进你的客户端。

详细的手把手教程（含电脑连手机、Chatbox 逐字段填法、常见问题排查）见 **[traework2api使用说明.md](traework2api使用说明.md)**。

## 构建自己编译

```powershell
# Windows + Android Studio（或仅命令行 SDK）
.\build-apk.ps1          # 产物在 out\TraeWork2API-<版本>-debug.apk
# 或
.\gradlew.bat assembleDebug --offline
```

要求：JDK 17+、Android SDK 36、Build Tools 36.0.0。

## 致谢与许可

本项目站在两个项目的肩膀上：

- **[Sliverkiss/traework2api](https://github.com/Sliverkiss/traework2api)**（Go）——
  上游协议与业务逻辑的参考实现；
- **[WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)**——
  UI 资源骨架（主题/色板结构/图标）的参考，MIT 许可。

本项目其余代码以 **MIT** 发布，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 免责声明

本项目仅供**个人学习与研究**使用。使用本项目与 Trae 账号存在风险（包括但不限于账号限制），
请自行评估并遵守 Trae 相关服务条款；本项目与 Trae 官方无关，也不保证随上游协议变更长期可用。
使用本项目产生的任何后果由使用者自行承担。
