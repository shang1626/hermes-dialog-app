# 运行时机制与踩坑记录

DESIGN.md 讲「有哪些文件」，这份讲「运行时到底怎么跑、哪些地方踩过坑」。改相关代码前先看这里。

## run 生命周期

一次对话 = 一次 run。客户端不做任何本地推理，全在服务端跑：

1. `POST /v1/runs`（body: `input` / `session_id?` / `images?`）→ 返回 `run_id`
2. `run_id` 存进 `Prefs.activeRunId`，`GET /v1/runs/{id}/events` 开 SSE 收流
3. 收到 `run.completed` / `run.failed` / `run.cancelled` 结束；`doneOk()` 清 activeRunId、停前台服务
4. App 被杀重开：`resumeActiveRun()` 查 `/v1/runs/{id}` 状态——`started/running/waiting_for_approval/queued` 就续接事件流，否则清标记并 `refreshFromServer()` 拉回产出

`currentRunId` 是内存变量，`activeRunId` 是持久化的，两者配合做「重开恢复」。

## SSE 事件类型（客户端认这些）

`streamRun()` 里 switch 的事件名（`ev.event` 或 `ev.data.event`）：

| event | 处理 |
|---|---|
| `message.delta` | 追加到当前 assistant 气泡正文（`appendDelta`） |
| `message.interim` | 忽略 |
| `tool.started` | 忽略（只在完成时记一行） |
| `tool.completed` | 追加工具轨迹行到 `trace`（界面默认折叠） |
| `tool.failed` | 同上，前缀 ✗ |
| `run.completed` | 用 `output` 定稿正文，`doneOk()`，后台则弹通知 |
| `run.failed` | 追加 `[失败]`，`finishPending()`，触发 `maybeContinue()` |
| `run.cancelled` / `run.interrupted` | 追加 `[已中断]`，`doneOk()` |

`Last-Event-ID` 头带 `lastSeq` 做断点续传；每个事件有 `id` 就更新 `lastSeq`。

## 断线续接（v2.7 的核心修复）

**铁律：SSE 提前断开时，绝不伪造用户消息。**

老版本在 `onClosed`/`onError` 里直接 `startRunWith(a, "继续")`，把「继续」当成用户输入写进会话——用户会看到自己没发过的消息。现在 `maybeContinue()` 的做法：

- 最多自动重连 3 次（`autoContinue >= 3` 就报「已自动重连 3 次」并 `failPending`）
- 每次：延迟 1.2s → 查 `/v1/runs/{id}` 状态
  - 还在跑（含 `stopping`）→ `streamRun()` 续接**同一个 run**，不新增任何用户消息
  - 已结束 → `finishPending()` + `refreshFromServer()` 拉回服务端产出

`runFinished` 是幂等闸：一旦置位，后续 onClosed/onError 都不再触发续接。

## 图片链路

1. 系统 Photo Picker（`PickMultipleVisualMedia(5)`）选图，无存储权限
2. `addImage()`：拷进 App 沙盒 `filesDir/outbox/`，单张上限 10MB，最多 5 张
3. 发送时 `startRunWith` 逐张 `POST /v1/artifacts/upload` 拿 `artifact_id`，再随 `images` 数组传给 `/v1/runs`
4. 气泡回显用本地 `uri`（`coil AsyncImage`），不依赖服务端

`fetchCapabilities()` 拉 `/v1/capabilities` 的 `features.supports_vision`：支持就走原生附图，不支持则按 `Prefs.visionAutoText` 策略转文字。

## 本地会话存储（SessionStore）

App 私有目录 `filesDir`，纯 JSON，不碰服务端库：

- `sessions_<profile>.json`：会话索引（id/title/updatedAt/archived）
- `chat_<profile>_<sessionId>.json`：单会话消息
- `chat_<profile>.json`：1.4 老格式，`migrateLegacy()` 首启迁移成第一个会话后删掉

- 单会话最多存 300 条（`saveMessages` 截尾）
- 空 pending 消息（无 text 无 trace）不落盘
- `sessionId` 同时是网关的 `session_id`，两边对齐

## 前台服务与通知

- `RunService`（`foregroundServiceType=dataSync`）：任务期间保 SSE 连接，`Prefs.keepAlive` 控制起不起
- 关掉 keepAlive → 不起服务、**彻底无通知**，任务仍在服务端跑，重开 App 拉结果
- 两条通知渠道要分清：
  - `Notifier.CHANNEL_ID = hermes_msg`：**IMPORTANCE_HIGH**，后台收到回复时提醒（提示音+震动），v2.2 起的「降噪」指的是另一条
  - 常驻通知（前台服务）：`IMPORTANCE_MIN` 静默，无正文/无提示音/无震动，Android 强制必须有
- Android 13+ 要 `POST_NOTIFICATIONS`，拒绝也静默失败不崩（`runCatching` 包住 notify）

## 昼夜配色

- `AppColors` 数据类 + `DarkColors` / `LightColors` 两套常量，`LocalAppColors` 是 CompositionLocal
- 三种模式：`system` / `day` / `night`（`Prefs.themeMode`），`isDarkMode()` 判当前
- MainActivity `onCreate` 里**先定窗口底色再 setContent**，避免浅色模式闪一下黑
- v1.3 修过「黑背景黑字」：浅色模式必须整套换色，不能只换背景

## 自更新链路

1. `checkUpdate()` 拉 `Keys.UPDATE_URL`（`https://your-update.example.com/update/version.json`）
2. `versionCode > 当前` → `pendingUpdate`，界面弹确认框
3. `confirmUpdate()` 带进度下载 APK 到 `getExternalFilesDir/apk/`
4. `installApk()` 用 FileProvider（authority `com.hermesapp.fileprovider`）拉起系统安装，需 `REQUEST_INSTALL_PACKAGES` 权限

## 输入卡顿的根治（v2.6，改输入相关代码必读）

- `inputState` 是 `MutableState<String>`，**提到 MainScaffold**，打字只让输入栏重组，`MessageList` 完全不动
- 草稿去抖：停手 600ms 才写 SharedPreferences；清空时立即写（长文本每次按键写盘会反复整串序列化）
- 输入框配色只在变更时重建；`TextWatcher` 用 `rememberUpdatedState` 防陈旧闭包
- **中文输入法符号问题**用 `NativeChatInput`（AndroidView 包原生 EditText）解决，不用 Compose TextField 直接收中文

## 状态页字段映射

`buildStatus()` 把 `/health/sysinfo` 的扁平 JSON 整理成「分组 → 行」：
网关（status/pid/platform/python）、CPU（model/percent/count/freq）、内存（percent/used/total/proc）、
磁盘（total/used/free/percent）、运行（load_avg/uptime）、API 与任务（model/metrics_today/active_runs/active_delegations/process_queue_depth/last_heartbeat）。
字段名变更要同步改 `buildStatus()` 的 `optXxx` 取值。状态页每 5 秒自动刷新。

## 接收文件 / 图片（v2.9，改这块必读）

服务端（Hermes 网关）在 `run.completed` 的 `output` 字段里，会把回复中的 `MEDIA:<路径>` 标签
替换成内联 data URL（图片 `![image](data:image/...)`，其他文件 `[📎 名](data:<mime>;...)`）——
**App 侧不需要也不能下载服务端路径**（Hermes 自带 `/v1/artifacts/download/{id}` 是一次性的，第二次 404）。

客户端处理链：

1. `parseMdBlocks()` 按行识别：独占一行的图片 / 附件链接 → `MdBlock.Image` / `MdBlock.Attachment`
2. `decodeDataUrl()` 解出 mime + 字节（`Attachment.kt`）
3. 图片：`AsyncImage(model = 字节数组)` 内联渲染，点击 `openAttachment()`
4. 附件：渲染成卡片（📎 文件名 · 大小 · 点击打开），点击落盘 `filesDir/attachments/` 后
   FileProvider（authority `com.hermesapp.fileprovider`，`res/xml/file_paths.xml` 已声明 `attachments/`）
   + `ACTION_VIEW` 拉起系统应用

服务端两处补丁（**升级 Hermes 会丢，需重打**，脚本在 `~/hermes-patches/apply_media_file_patch.py`）：

- `gateway/platforms/api_server.py`：`_resolve_media_to_data_urls()` 支持非图片扩展名（`_MEDIA_FILE_MIME`），
  上限 5MB → 12MB
- `gateway/platforms/api_server_runs.py`：`/v1/runs` 的 `_finish(..., output=...)` 也过一遍解析
  （原来只有 chat-completions 那两条路走解析，App 用的 SSE 通道漏了）

## 全局踩坑清单

- **不要伪造用户消息**：任何断线/重试逻辑都不许往会话里塞用户输入（v2.7 教训）
- **输入状态别放回 ChatScreen**：放回去就退回 v2.6 的卡顿
- **浅色模式别只换背景色**：会黑字黑底
- **改 CSS/主题/资源后记得升 versionCode**：客户端靠它判断更新，且手机有缓存
- **release 签 debug 密钥**：换机器构建必须带同一份 `~/.android/debug.keystore`，否则装不上
- **`Keys.kt` 里有真实密钥**：文档和提交信息里都不要抄，要看直接开文件
- **`local.properties` / `dist/` / `build/` 都在 .gitignore**：不是漏提交，是故意的