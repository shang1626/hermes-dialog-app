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
| `hermes.status` | 服务端 agent 的 lifecycle 状态（补丁 `apply_run_status_stream_patch` 转发）：召回指示 → 写到 `Msg.recall`（界面显式一行，正文之前）；其余状态 → 追加到 `trace`，前缀「· 服务端：」 |
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

- 最多自动重连 8 次（`autoContinue >= MAX_RECONNECT_ATTEMPTS`），退避 1→2→4→8→16→30 秒封顶；用尽后不判死，转状态轮询 / 翻历史取回
- 每次：退避等待 → 查 `/v1/runs/{id}` 状态
  - 还在跑（含 `stopping` / 等审批 / 等澄清）→ `streamRun()` 续接**同一个 run**，不新增任何用户消息
  - 已结束 → `finishRecoveredTurn()` 收尾 + 拉回服务端产出

`runFinished` 是幂等闸：一旦置位，后续 onClosed/onError 都不再触发续接。

**兜底：断流自愈看门狗（v2.199）**。上面这条退避链有三个已知的静默出口——流代际号把回调作废、
同会话探测的单飞位没释放、退避用尽转翻历史后不起任何任务；任何一种命中都会让界面上的
「正在执行」永久停住。真机现场（10-13 01:44–01:50）还多一种：**半死的流**——连接对象还在、
既没有事件也没有 onClosed/onError 回调，只有切前后台时的 25 秒体检才会去掐它。

`startRunFlagsTicker` 里那个每 2 秒的扫描因此多了一件事：对「本轮还在跑、却没有活流」的会话
（判据 `ReconnectPolicy.streamNeedsRestart`：没有流 / 流已取消 / 静默超过 `STREAM_DEAD_AFTER_MS`=45 秒，
服务端每 10 秒必发心跳帧所以正常空闲不会误判）立刻重起流，退避归零、作废历史轮询。
纯读内存、零新增请求；同一会话 15 秒内最多重起一次；正在跑状态轮询/翻历史的会话跳过。

**重连提示（`retryNote`，气泡上方那行）必须自己消失（v2.202）**。真机现场（10-13 06:13–06:14）：
App 切到后台被系统速冻 68 秒，回前台那一下看门狗按 45 秒静默判流死 → 重起流 + 写下
「连接中断，正在重连…」。流随后正常恢复（事件、进度一路刷新），但那行提示**再也消失不了**——
清理条件原来是 `autoContinue != 0`，而看门狗恰好把 `autoContinue` 归零了，条件永远不成立。

规则：**重连类提示的清理判据看「提示本身」而不看退避计数**（`ReconnectPolicy.shouldClearNoteOnEvent`：
`autoContinue != 0` 或 `note` 命中重连前缀）。所有重连提示一律经
`ReconnectPolicy.noteReconnecting(...)` / `noteForegroundReconnect()` 构造——手写文案就会逃出清理。
**终态提示故意不用这个前缀**（「本轮连接中断：重试 8 次仍未取回结果」），它的语义是「本轮结束」，
不该被下一个事件抹掉；其余语义的提示（回执、排队、本地拒绝、插话回执）同理，不许顺手清。

**「还在服务器上跑」这句提示：先探测，再下结论（v2.206）**。真机现场（10-14 00:50 与 01:04 两次）：
这一轮其实**早已跑完**（服务端连收尾语音都推完了），用户切回 App 时新起的 `/events` 拿到 404，界面却报
「这一轮还在服务器上跑（已 26 分钟），跑完自动取回」，且要再等一个轮询间隔（20 秒）才把结果取回。
错在「先写提示、后做核实」：404 的成因**最常见的就是「这一轮刚结束」**——服务端清扫器回收的正是
已结束那条 run 的 SSE 传输层（任务活着时缓冲保留、可续接；本机机制级复现脚本可证）。

`waitLiveRunByStatus()` 的规则：① 进函数**先探一次**（不等间隔），探测结果出来之前只显示
`ReconnectPolicy.LIVE_RUN_CONFIRMING_NOTE`（只讲正在做什么，不含任何结论）；② 服务端确认 running 才写
`liveRunNote(...)`，并把**服务端最后一次动静**一起带上（`updated_at` 是秒；它只在工具 / 生命周期事件上
刷新，流式正文不刷新，所以只说得清「最后一个工具步骤在 N 分钟前」，不能把长回答的静默写成「卡住」）；
③ 探不出来写 `liveRunUnreachableNote(...)`（问不到 ≠ 已结束，别再留一句「还在跑」在屏幕上）。

这三句都登记在 `ReconnectPolicy.STALE_WHEN_STREAM_RECOVERS`。原因同上一条：回前台会把 `autoContinue`
归零，不在这个家族里的提示，新接上的流收到真实事件也清不掉，会永久钉在会话上方。**新增任何状态提示，
一律用该家族的构造入口（别手写文案），并同时登记进那张表。**


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

- `AppColors` 数据类 + `NightColors` / `LightColors` 两套常量（`NightColors` = 夜间/跟随系统深色，2.196 起是灰底黑字），`LocalAppColors` 是 CompositionLocal
- 三种模式：`system` / `day` / `night`（`Prefs.themeMode`），`isDarkMode()` 判当前
- MainActivity `onCreate` 里**先定窗口底色再 setContent**，避免浅色模式闪一下黑
- v1.3 修过「黑背景黑字」：浅色模式必须整套换色，不能只换背景

## 自更新链路

1. `checkUpdate()` 拉 `Keys.UPDATE_URL`（`https://gx.99801886.xyz/update/version.json`）
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

服务端两处补丁（**升级 Hermes 会丢，需重打**，脚本在 `/root/hermes-patches/apply_media_file_patch.py`）：

- `gateway/platforms/api_server.py`：`_resolve_media_to_data_urls()` 支持非图片扩展名（`_MEDIA_FILE_MIME`），
  上限 5MB → 12MB
- `gateway/platforms/api_server_runs.py`：`/v1/runs` 的 `_finish(..., output=...)` 也过一遍解析
  （原来只有 chat-completions 那两条路走解析，App 用的 SSE 通道漏了）

## 全局踩坑清单

- **不要伪造用户消息**：任何断线/重试逻辑都不许往会话里塞用户输入（v2.7 教训）
- **输入状态别放回 ChatScreen**：放回去就退回 v2.6 的卡顿
- **浅色模式别只换背景色**：会黑字黑底
- **改 CSS/主题/资源后记得升 versionCode**：客户端靠它判断更新，且手机有缓存
- **release 签 debug 密钥**：换机器构建必须带同一份 `/root/.android/debug.keystore`，否则装不上
- **`Keys.kt` 里有真实密钥**：文档和提交信息里都不要抄，要看直接开文件
- **`local.properties` / `dist/` / `build/` 都在 .gitignore**：不是漏提交，是故意的
- **每条「状态提示」的写入者与清理者必须是同一个判据**：提示描述的是一种状态（「正在重连」「这条语音在排队等」），只要写它的条件和清它的条件不是同一处算出来的，状态不成立时就没人清，提示会永久钉在屏幕上。两次真实故障都是这一条：重连提示靠退避计数清（写它的路径把它归零了，2.202 修）、语音排队提示只在「轮到自己起播」与「按停止」两处清（队列播空走的是另一条 return，2.205 修）。做法：把一个纯函数（`ReconnectPolicy.shouldClearNoteOnEvent` / `VoiceQueueTip.shouldTrackWaiting`）同时用于写入与清理，重算点挂在**唯一的推进函数**上（如 pump 的 wrapper），别散在各条 return 里；并且提示涉及「排队」时，正在播的那条**不算在等**。