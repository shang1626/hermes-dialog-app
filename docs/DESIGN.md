# 架构与模块设计

客户端是单 Activity + Compose，状态集中在 `ChatViewModel`，网络层 `HermesApi`，本地持久化 `SessionStore` + `Prefs`。

## 分层

```
MainActivity (App.kt)
  └─ HermesApp : 主题/昼夜色 → 未登录走 LoginScreen，已登录走 MainScaffold
       └─ MainScaffold : 抽屉 + 顶部栏 + 三个页面
            ├─ ChatScreen   (Screens.kt)  ← ChatViewModel
            ├─ StatusScreen (Screens.kt)  ← /health/*
            └─ SettingsScreen (Screens.kt)
```

## 文件职责

| 文件 | 行数级 | 职责 |
|---|---|---|
| `App.kt` | ~540 | 昼夜配色（DarkColors/LightColors + LocalAppColors）、主题模式 system/day/night、MainActivity、登录页（服务器地址+密码+选 profile）、MainScaffold（抽屉/顶部栏/返回键处理） |
| `Screens.kt` | ~650 | ChatScreen（消息列表+输入栏+图片待发区+全屏输入）、MessageList、Bubble、StatusScreen（状态卡片）、SettingsScreen、NativeChatInput（原生 EditText 桥） |
| `ChatViewModel.kt` | ~830 | 唯一状态源：消息列表、会话索引、run 生命周期、SSE 流、工具调用行、图片上传、状态页数据；输入草稿去抖落盘 |
| `net/HermesApi.kt` | ~190 | OkHttp 封装：REST 同步调用 + SSE 流式读取（手写 event-stream 解析） |
| `SessionStore.kt` | ~144 | 本地会话：索引 `sessions_<profile>.json`，消息 `chat_<profile>_<id>.json`，旧版单文件迁移 |
| `Markdown.kt` | ~170 | 轻量 Markdown 渲染：块解析 + 行内链接可点 + 表格 |
| `Prefs.kt` | ~52 | SharedPreferences：登录态、profile、服务器地址、主题、草稿、activeRunId、视觉策略、后台运行开关 |
| `Keys.kt` | ~19 | 固定密钥 / URL / UpdateInfo 数据类 |
| `RunService.kt` | ~71 | 前台服务（任务期间保 SSE 连接），`dataSync` 类型 |
| `Notifier.kt` | ~63 | 通知渠道（IMPORTANCE_MIN 静默）+ AppForeground 前后台标记 |
| `CacheUtil.kt` | ~36 | 缓存统计与清理（outbox / apk / image_cache） |
| `TimeFmt.kt` | ~27 | 时间格式化（本地毫秒、ISO→北京时间） |

## 服务端接口契约（api_server）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v1/runs` | 起一次 run（body: input / session_id? / images?）→ run_id |
| GET | `/v1/runs/{id}` | 查 run 状态与结果（重开 App 恢复用） |
| GET | `/v1/runs/{id}/events` | SSE 流（`Last-Event-ID` 续传）；event: delta/tool/trace/done… |
| POST | `/v1/runs/{id}/stop` | 中止 |
| POST | `/v1/runs/{id}/steer` | 中途插话（out-of-band steering） |
| GET | `/v1/capabilities` | 能力探测（如 `features.supports_vision` 决定图片原生/转文字） |
| POST | `/v1/artifacts/upload` | 上传图片，返回 artifact_id（绑本 profile 密钥作用域） |
| GET | `/api/sessions/{id}/messages` | 拉服务端会话消息（补回后台产出） |
| GET | `/health` `/health/sysinfo` `/health/detailed` | 在线探测与状态页 |

鉴权：`Authorization: Bearer <profile key>`（Keys.kt 里 default/friend 两把）。

## 几个设计决定（踩过坑才这么写的）

- **输入状态提到 MainScaffold**：`inputState` 是 `MutableState<String>`，打字只让输入栏重组，消息列表完全不动 —— 这是修 v2.6 输入卡顿的核心手段。
- **草稿去抖落盘**：停手 600ms 才写 SharedPreferences；清空时立即写。长文本每次按键写盘会反复整串序列化。
- **断线不伪造用户消息**：v2.7 起连接中断改为查同 run 状态后续接流 / 拉回结果，不再往会话里塞一条「继续」。
- **后台运行可关**：关闭则不起前台服务、彻底无通知，任务仍在服务端跑，重开 App 拉结果。
- **原生 EditText 桥**：中文输入法符号问题用 AndroidView 包原生 EditText 解决，不用 Compose 的 TextField 直接收中文。

## 数据安全

纯客户端，不碰服务端数据库。本地数据在 App 私有目录（SharedPreferences + filesDir），卸载即清。