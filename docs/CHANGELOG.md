## 2.158 — versionCode 169

修「停止后紧接着再发」时，旧的那一轮会抢回会话（第三方深度复评 F04）。

- **问题**：一轮发送的收尾状态（已收尾 / 已请求停止 / 当前 run）过去是「每会话一份」的共享变量，发起下一条时会被重置；而建 run 是同步 HTTP、取消协程拦不住它。于是「停止 A → 立刻发 B → A 的响应迟到」时，A 回来看见「没有停止标记」，误以为自己还是当前轮，把 B 的 runId 顶掉并重新接流——两条流抢同一个会话，「停止」不是真停。
- **改法**：给每次发送发一个「轮次号」（发起发送、停止各 +1）。迟到的响应先对号：号对不上（已被新发送或停止顶掉）就只补发一次 stop 把自己那条 run 收掉，**绝不写共享状态、绝不接流**。判断抽成纯函数 `ownsTurn` 并加单测（6 项）。
- **验证**：在第三方那份复现程序里跑同一条探针——修复前是「A 抢回会话」（`streamed=[run-A], runId=run-A`），修复后变为「A 不接流、被补发 stop，只有 B 在跑」（`streamed=[run-B], runId=run-B`）；窄场景与另一条探针行为不变，无回归。

（改 ChatViewModel.kt / RuntimeHub.kt；新增 TurnOwnership.kt + TurnOwnershipTest.kt / app/build.gradle.kts）

---

## 2.157 — versionCode 168

任务卡片去掉英文「标识」行，并给新增的峰谷密钥任务补上中文说明。

- **任务卡片不再显示英文标识**：`JobCard` 底部那行「标识  <原始任务名>」（形如 `标识  nightly-memory-refactor`）整行去掉，任务卡片上不再出现英文任务标识。
- **新增任务「峰谷密钥自动切换」补说明**：新增对照条目，卡片附一句「这任务是干嘛的」——按峰谷时间自动切换 FreeLLMAPI 的密钥。

（改 JobFormat.kt / Screens.kt / app/build.gradle.kts；无新增文件）

---

## 2.156 — versionCode 167

定时任务页与通知里的任务名全部中文化，并给认不出的任务兜底。

- **新任务名显示英文**：`patch-registry-audit` 等没进中英对照表的任务，任务卡片直接显示英文原名、且没有「这任务是干嘛的」说明。补进对照表（补丁登记巡检 / 记忆库夜间验收 / 重启后自检）并各附一句说明；关键词兜底再加 `audit=巡检`，以后新加同类任务自动出中文。
- **收件箱与通知的任务名一直是英文**：收件箱行、产出弹窗、「定时任务完成/失败」系统通知一直在用服务端原始名（如 ds-upstream-watch），与任务卡片的中文不一致，用户在通知栏看到英文看不懂。统一走 `jobDisplayName()` 翻译，认不出才回落原名。

（改 JobFormat.kt / Screens.kt / ChatViewModel.kt / app/build.gradle.kts；无新增文件）

---

## 2.145 — versionCode 156

修同事评估报告里实测仍有影响的 2 条（归档界面入口、大附件内存），其余 3 条实测判为「单人自用不触发」继续保留。

- **归档历史可浏览（P2-4 完整形态）**：超 300 条被裁掉的老消息此前只存不读，界面上翻不到、搜不到、导不出。聊天页顶部新增「更早 N 条已归档 · 查看」入口，点开是全屏只读视图：支持在归档里搜索、一键导出为 Markdown 分享。归档不并回主文件（不破坏 300 条裁剪逻辑）。
- **大附件不再整包读内存（P2-9）**：`downloadMedia` 原用 `body.bytes()` 把整个文件读进堆，超大附件可能 OOM 闪退且给不出提示。改为先看声明长度、超 100MB 直接拒绝；长度未知则边读边累计、超限即中止。打开/分享/保存三处失败提示统一（含「文件太大」这类明确原因），不再静默。

（改 ChatViewModel.kt / Screens.kt / net/HermesApi.kt / Attachment.kt / Markdown.kt / app/build.gradle.kts；无新增文件）

---

## 2.144 — versionCode 155

按同事评估报告核实后，把「属实且用户可感知」的 5 条缺陷修完（结构性大工程另开专题）。

- **历史归档重复膨胀（P2-4）**：超 300 条时被裁掉的老消息每次保存都重复追加进 `.archive.json`，归档无限膨胀。改为按 role+ts+正文签名去重，归档只提交新增消息；归档改为保留完整字段（轨迹/附件/引用/runId），并新增 `loadArchiveMessages`/`archiveCount` 读回入口。
- **残缺回复遮完整回复（P2-5）**：断线重连后，本地被截断的回复会一直盖住服务端完整版。合并时命中服务端行且本地正文更短被包含时，只把正文换成服务端完整版，本地 trace/runId/计时原样保留。新增 3 条回归测试。
- **序号领先落盘（P2-6）**：SSE 恢复序号可能领先于已落盘的消息，进程被杀会跳过未保存内容。改为「先取 seq 再取 msgs」的快照顺序，且 `saveMessages` 返回 Boolean——只有写盘成功才推进 lastSeq。
- **停止竞态（P1-2）**：run 还没建好时点停止，服务端任务仍会继续跑。`SessionRuntime` 加 `stopRequested` 标记，startRun 返回后若已请求停止就补发 stopRun 并收尾。
- **语音竞态（P2-8）**：点重播后立即停止、或 A→B 快速切换，晚到的下载线程仍会起播。`VoiceReplayPlayer`/`VoicePlayer` 各加播放代际号，stop/切换时代际 +1，旧线程不再起播。
- **文档矛盾（P2-10）**：`docs/README.md` 第 37 行 `./gradlew` 改成真实 release 命令；修正「配置在 Keys.kt」的过时描述（实际走 BuildConfig/local.properties）。

未修（保留）：P1-1 身份隔离、P1-3 一包两套密钥、P2-7 退出后旧通知——属「多人分发才触发」，继续自用不值得现在动；P2-9 大附件 OOM 低概率。

（改 ChatMerge.kt / ChatViewModel.kt / RuntimeHub.kt / SessionStore.kt / VoicePlayer.kt / VoiceReplayPlayer.kt / docs/README.md / app/build.gradle.kts；新增 ChatMergeTest 3 例）

---

## 2.143 — versionCode 154

按同事评估报告核实后，把剩余 7 条小项全部修完（结构性大工程另开专题）。

- **后台轮询不停（P1-3）**：全仓 0 个 `collectAsStateWithLifecycle`，状态页 5 秒、日志 2 秒、侧栏心跳的轮询切后台照跑。改为生命周期感知（`isResumedState()` + 55 处 `collectAsStateWithLifecycle`），退后台即停，省电省包。
- **网络层残留（P1-4）**：四个 `OkHttpClient` 各自独立连接池/线程池；改为共享同一 `ConnectionPool` + `Dispatcher`（OkHttp 官方推荐派生共享）。
- **本地存储三处（P1-8）**：① 300 条上限硬丢 → 被裁的历史归档进 `.archive.json`，不再永久丢失；② 索引加 `schema` 版本字段（兼容老裸数组格式）；③ 老格式迁移改为「确认索引落盘后才删老文件」，失败不再丢历史。
- **明文流量（P2）**：`usesCleartextTraffic` 关掉，登录页要求 `https://`，填 http 会提示（密钥不再明文上网）。
- **目录只增不减（P2）**：`voice_replay`、`exports` 纳入 `CacheUtil` 清理与统计（此前只清 outbox/apk/image_cache/attachments）。
- **无音频焦点（P2）**：新增 `AudioFocus`，三个播放器起播申请 `TRANSIENT_MAY_DUCK`、收尾/停止释放，播语音时系统会压低别的 App。
- **ticker 泄漏（P1-7）**：`ChatViewModel` 补 `onCleared()` 取消挂在进程级 scope 上的 `runFlagsTicker`，不再每次界面重建泄漏一条。

未采纳（实测判错/高估）：P0-3 语音队列（3 秒自愈）、P0-4（已有 finished 复位）、SSE 多行拼接（服务端单行 JSON）。结构性大工程（P1-1 跨线程状态、P1-2 上帝类、零测试、零 CI）另开专题。

（改 Screens.kt / App.kt / Markdown.kt / ChatViewModel.kt / SessionStore.kt / CacheUtil.kt / VoicePlayer.kt / VoiceReplayPlayer.kt / StreamVoicePlayer.kt / net/HermesApi.kt / AndroidManifest.xml / build.gradle.kts；新增 AudioFocus.kt）

---

## 2.142 — versionCode 153

按同事评估报告核实后，继续修 6 处（都是实测属实的）。

- **P1-7 恢复探测单飞位泄漏**：`resumeActiveRun` 的协程没有 `try/finally`，只在末尾 `resumingSids.remove`。`probeRun`/`ensureLoaded` 一旦抛异常，该 sid 永久留在集合里，该会话再也无法恢复探测。改为 `try/finally`。
- **AppLog 并发丢行**：`ERROR` 行当场调 `drainToFile`，与 400ms 写线程并发 `appendText`/`trim` 同一文件；且 `flush()` 也在主线程调它。改为 ERROR 行只入队（统一走写线程），`drainToFile` 整段进锁。
- **decodeDataUrl 无上限**：在 Compose 合成期主线程解码内联 base64，超大体会 OOM。加 48MB 上限。
- **原子写缺 fsync**：`writeAtomic` 写 tmp 后未 fsync 就 rename，掉电可能留下「已改名但零长度」文件。改为写后用 `fd.sync()`。
- **附件目录只增不减**：`filesDir/attachments/` 每次打开/分享/保存都写一份，`CacheUtil.clear` 没清它。加进清理。
- **Markdown 围栏状态**：`parseMdBlocks` 无 fence 跟踪，代码块里的 `|` 表被当表格渲染。加 `inFence` 状态。

未采纳（实测判错/高估）：P0-4 confirmReceipt（已有 finished 复位）、P0-3 语音队列（3 秒自愈）、SSE 解析器多行拼接（服务端单行 JSON）。结构性大工程（P1-1 跨线程状态、P1-2 上帝类、零测试）另开专题。

（改 ChatViewModel.kt / AppLog.kt / Attachment.kt / SessionStore.kt / CacheUtil.kt / Markdown.kt）

---

## 2.141 — versionCode 152

按同事评估报告核实后，修 5 处（报告有 3 条判错/过时，已剔除，只做实测属实的）。

- **滚动抢滚动条**（报告 P0-7，属实）：`Screens.kt` 贴底逻辑只有「增量才动画」的优化，没有「用户是否在翻历史」的守卫——流式期间正文每个 token 变化都触发贴底，用户往上翻历史会被反复拽回底部。加 `listState.canScrollForward` 判断：还能往前滚 = 用户在翻历史，就不跟随。
- **主 client / h1Client 加 callTimeout(180s)**（报告 P1-4，属实）：两个 client 都是 `readTimeout(0)`（无限读）且无总超时，链路半死时建 run/上传/拉会话会永久挂起。加总超时兜底，超时抛错走重试；不动 readTimeout（大响应仍可慢读）。
- **onProfileChanged 主线程 IO 移到 IO 协程**（报告 P0-6，属实）：读索引/迁移旧格式/扫会话文件判空壳都是同步磁盘 IO，原来跑在主线程会卡冷启动。
- **关闭 allowBackup**（报告 P2，属实）：会话记录明文进云备份。
- **通知加 setVisibility(PRIVATE)**（报告 P2，属实）：锁屏通知不再直接显示正文。

报告判错/高估未采纳：SSE 解析器两条（服务端单行 `data: {json}`，多行拼接与 trim 在本架构不触发）、P0-3 语音队列「永久堵死」（数据源 3 秒超时自愈）、P0-4 confirmReceipt（代码已有 finished 复位）。

（改 Screens.kt / HermesApi.kt / ChatViewModel.kt / Notifier.kt / AndroidManifest.xml）

---

## 2.140 — versionCode 151

修同事评估报告点出的 3 个用户可见缺陷（P0-1 / P0-2 / P0-5）。

- **P0-1 失败后会话永久卡「执行中」**：`run.failed` 分支原来只调 `finishPending`（只定稿气泡、不碰 `busy`）+ `maybeContinue`，而后者第一行就是「finished 就返回」，于是失败后没有任何一处清 `busy`——会话永远显示执行中、前台服务不退、排队消息不发，只能手动点「停止」逃生。改为走 `doneOk(sid)`（清 busy + 推进队列 + 收尾补拉），与 `run.cancelled` 分支一致。
- **P0-2 附件卡片显示乱码 `\uD83D\uDCCE`**：`Markdown.kt` 里这是普通字符串而非正则，双反斜杠 `"\\u..."` 渲染出 12 个字面字符。改成单反斜杠，恢复正常显示 📎。
- **P0-5 上传途中点「停止」无效**：`startRunWith` 的发送协程没有句柄，`stopSession` 只能 cancel `r.call`，而此刻流还没起、`r.call` 是 null。加 `SessionRuntime.sendJob` 字段，起发送协程时绑上；`stopSession` 一并 `sendJob?.cancel()`；上传完成、建 run 前检查 `finished`，已停止就不再起流；发送协程里的取消单独 catch（`CancellationException`）不当失败处理。

（改 ChatViewModel.kt / Markdown.kt / RuntimeHub.kt）

---

## 2.139 — versionCode 150

根治「发文件永远失败」：把附件从被边缘拦截的上传接口，改走已证明能通的 /v1/runs。

根因（2.138 日志 + 服务端对照测试交叉确认）：App 单独 POST `/v1/artifacts/upload` 时，在到达服务端之前就被边缘 TCP RST（`连接级失败 原因=Connection reset`），两次重试全废；而**同一部手机的 `/v1/runs`、`/api/sessions`（连 164KB 响应）、`/api/applog`（19KB body）全部正常**——只有「上传」这个接口被按请求形状拦。服务端侧复刻同一条上传（同域名/同 42KB/同文件名/同 UA）连测 30 次 27 次成功（3 次仅限流 429），证明不是服务端、不是 MIME、不是 body 大小。

修法（两端一起）：
- **服务端**：`/v1/runs` 新增 `inline_files` 字段（每项 `{name, mime, data=gzip+base64}`，≤10 个），`_resolve_inline_files` 解开落盘到 `~/.hermes/uploads/<会话>/`（图片进 cache/images），其余处理与 artifact 路径完全一致。
- **App**：发附件不再 POST `/v1/artifacts/upload`，改成把文件 gzip+base64 塞进 `/v1/runs` 的 JSON 一起发。纯文字发送、收发消息、语音均不受影响。

（改 HermesApi.kt / ChatViewModel.kt；服务端 api_server_runs.py 补丁）

---

## 2.137 — versionCode 148

2.136 的兜底没生效，这次真正修掉「发文件被边缘重置」。

2.136 的失误：兜底 catch 只包住 `.execute()`，而实测 `stream was reset: INTERNAL_ERROR` 发生在**读响应体**阶段（已进 `.use{}` 内部），catch 根本够不到，日志里一次都没出现兜底行。

- **重试包住「执行 + 读 body」**：新 `callText()` 把二者当一个整体，连接级失败自动清连接池重试一次。
- **全 client 强制 HTTP/1.1**：实测主 client 的 HTTP/2 复用长连接被边缘反复 RST_STREAM，而 HTTP/1.1 新建连接连测 10/10 成功。主 client / probe / SSE 流三个 client 全部 `.protocols(listOf(Protocol.HTTP_1_1))`。

（改 net/HermesApi.kt）

同版另一改动（另一会话）：顶栏「在线/离线」去掉文字只留圆点，右侧新增服务器 CPU 使用率（每 5 秒随心跳刷新，低绿/中黄/高红三档上色）。（改 App.kt）

---

## 2.136 — versionCode 147

修「发文件时连接被边缘重置、上传永远失败」。

起因：用户上传 md 时 App 报 `stream was reset: INTERNAL_ERROR`（约 300ms 失败、重试一次同样失败），而服务端网关日志里**完全没有这条请求**——请求在到达服务端前就被对端发了 RST_STREAM。同一错误 15:46 在一次纯文字发送上也出现过，故不是附件专属，是 EdgeOne/阿里云盾（sl-antibot）对**复用的 HTTP/2 长连接**偶发重置（curl 每次新建连接不复现，真实入口连测 10 次全部 201）。

- **连接级失败自动兜底**：新增强制 HTTP/1.1 的兜底 client。请求抛「非 HTTP 回执」的 IOException 时，先 `connectionPool.evictAll()` 丢掉被掐死的复用长连接，再用 HTTP/1.1 重试一次（HTTP/1.1 没有 RST_STREAM 帧，绕开此类重置）。覆盖 `sync`（建 run 等）与 `uploadImage`（附件上传）。
- 自动重试间隔 2s → 3.5s，与兜底重试不再挤在一起。

（改 net/HermesApi.kt / ChatViewModel.kt）

---

## 2.135 — versionCode 146

补齐附件上传链路的日志与失败反馈，让「传不上去」不再无声无息。

起因：用户上传一份 md 文档后，服务端只收到占位词「（文件）」、文件本体没到，而 App 日志里 0 条 attach/artifacts/上传记录——**上传失败连失没失败都查不出来**，因为成功路径全程不打日志，只有抛异常时才写一行。

- **附件链路补日志**（标签 `attach`，只记文件名/大小/类型/状态，不记内容）：选图/选文件成功、超 20MB(图)/50MB(文件)、超 10 个上限、读不到内容、移除附件、落盘退回、发送时本轮附件清单、逐个上传的进度与 mime。
- **「上报诊断」改成真反馈**：原来不管成败都弹同一句 Toast「已上报」，失败也这么弹，属于骗人。现改为按钮下方显示真实结果：「上报中…」→「已上报 ✓ HH:mm:ss（日志 N 字）」或「上报失败：原因」，不再闪一下就没。

（改 ChatViewModel.kt / Screens.kt）

---

## 2.134 — versionCode 145

审批完成后顶部提示条卡住不消的修复：确认提示改为「闪一下即清」，不再长时间挂在顶部。

（改 ChatViewModel.kt / Screens.kt）

---

## 2.133 — versionCode 144

界面可读性打磨：三套主题里 12 个颜色的对比度提到 WCAG AA（4.5:1）。

用 impeccable 的设计检测器扫三套主题的真实配色（导出成样本跑客观计算，对比度是纯数学值），发现正文合格但**次要小字与彩色语义色**在浅底/有色气泡上普遍不达标。本轮只改色值常量，不动布局、字号、逻辑。

- **深色**：`dim` 灰字 3.73:1 → 4.54；`accent` 蓝 4.31 → 4.50；`bad` 红 3.43 → 4.53（原来红字压在深蓝用户气泡上几乎看不清）。
- **浅色**：`accent`/`ok`/`warn`/`bad` 四色在用户气泡上 4.0~4.4，各压暗一档到 ≥4.5；`dim` 本就合格（4.85）不动。
- **护眼**：问题最集中，五个语义色（`dim`/`accent`/`ok`/`warn`/`bad`）落在卡片与气泡上只有 3.5~4.4，逐个加深到 ≥4.5。整体观感会明显扎实一点（原来偏「淡、费眼」）。

调整原则：保持原色调不变，只压/提明度，尽量不影响观感。12 个颜色全部是前景（文字/图标/边框），没有当实心按钮底色的，故不会牵连按钮上的字。

（改 App.kt / build.gradle.kts）

---

## 2.126 — versionCode 137

修上一版存储加固自身留下的三处缺陷。

- **删失败不再连累备份**：`deleteMessages` 原来不管主文件删没删成都清掉 `.bak`/`.tmp`——删失败（文件被占用 / 权限）时把唯一退路也断了。现在只在主文件真的删掉后才清备份，失败时保留并留一行错误日志。
- **保存路径补 `dead` 守卫**：`saveRuntime`（去抖写盘）原来没有删除守卫，`deleteSession` 的 `cancel` 一旦错过那 400ms 窗口就无效，已删会话的消息文件会被写复活。入口加 `if (r.dead) return`，与拉取路径的守卫成对。
- **体检数字不再虚高**：`diagSummary` 把原子写留下的 `.bak`/`.tmp` 也算进了「消息文件 N 个 / X 字节」，判「历史对话没了」时数字近一倍失真。过滤时排除这两个后缀。

（改 SessionStore.kt / ChatViewModel.kt / build.gradle.kts）

---

## 2.125 — versionCode 136

会话存储可靠性修复（四条）+ 多助手行轮次的最终回复回填。

- **本地存储改为原子写**：会话索引与消息文件原来都是 `writeText` 一次性覆盖，进程在写盘途中被杀 / 磁盘满就留下半截 JSON；`loadIndex` 把解析异常吞掉返回空列表，界面表现正是「历史对话整片没了」。现在先写 `.tmp` 再改名覆盖（同目录改名是原子的），并保留一份 `.bak`，读回失败自动回退备份。
- **发消息落盘时间戳**：`touchSession` 原来只改内存里的 `updatedAt`，重启后本地时间戳停在旧值，回前台对齐会一直判「服务端更新」，同一批会话每 30 秒被重拉一次。补一次去抖落盘。
- **消除同一会话的并发写**：`refreshFromServerFor` 直接赋值 + 另开一次写盘，与 `saveRuntime` 并发写同一个消息文件，谁后落盘谁赢、会丢正文。改为统一走 `setMsgs`（唯一写入口 + 去抖落盘），并在合并后把本地基线 `updatedAt` 推进到服务端那一份。
- **删除竞态**：`deleteSession` 只取消了去抖保存任务，`refreshFromServerFor` 那次未被追踪的写可能在其后落盘、把已删会话写复活。拉取入口与返回处各加一道 `dead` 守卫，删会话时一并清掉 `.bak`/`.tmp`。
- **多助手行轮次的最终回复回填**：一轮里出现多条助手消息时，中途被杀后最终回复只存在于服务端；`mergeTail` 现在会把服务端独有的助手正文回填进对应轮次，不再丢最后一条。

（改 SessionStore.kt / ChatViewModel.kt / build.gradle.kts）

---

## 2.124 — versionCode 135

重开 App 后会话正文冻结在退出前那一帧（拉到却丢掉）。

- **根因**：`refreshFromServerFor` 的两道「正在跑就跳过」守卫都是静默 `return`，且不落 `needSync`。冷启动时 `onProfileChanged` 先发起正文拉取、再恢复活跃 run——请求回来时 run 已恢复成 busy，撞上第二道守卫被丢掉；收尾时 `doneOk` 看 `needSync=false` 就不补拉，于是服务端 1.9MB 的记录拉回来了却被扔掉，界面停在退出前那一帧。
- **修法**：忙 = 推迟不是丢弃。两道守卫命中时都落 `needSync=true`，交给本轮收尾补拉一次。

（改 ChatViewModel.kt / build.gradle.kts）

---

## 2.123 — versionCode 134

接管别的端轮次时补全跳过分支日志（诊断「接管从不触发」）。

- 在 `reconcileServerRuns` 的每个跳过分支（不在本地列表、本地已在跑、接管位被占、探测已收尾或探不出）各留一行日志。此前只有「接管成功」有日志，失败路径一片空白，无法从日志判断到底是哪一步被挡下。
- 纯诊断，行为不变。

（改 ChatViewModel.kt / build.gradle.kts）

---

## 2.122 — versionCode 133

会话同步补全：切到哪就对齐哪，回前台把落后的会话一并追平，别的端起的轮次也能接管。

- **切会话即对齐**：以前 `switchSession` 只从本地磁盘读正文，一条服务端请求都不发，于是别的端（微信 / CLI / 桌面）在会话里跑过的新轮次，切过去看到的还是本地旧内容，除非重启 App。现在切过去同时拉一次服务端正文（正在跑的会话内部会自己跳过，不会把半截内容合进来）。
- **回前台对齐陈旧会话**：用服务端会话列表的 `last_active` 与本地 `updatedAt` 比对，只挑真的落后的那几条（最近 3 条、节流 30 秒）拉正文。以前只对齐当前那一个，其它会话的内容一直漂着，直到用户手动切过去。
- **接管别的端发起的轮次**：读 `GET /api/sessions` 返回的 `active_run`，本地没有对应运行时（轮次是别的端起的、或本进程发起后立刻被杀还没落盘）就接管过来接流。此前这类轮次在侧边栏一条提示都没有，跑完了也不通知。
  - 只处理本地已有列表行的会话，绝不为服务端独有的会话建本地行（否则会把别的端几十条会话灌进手机列表）。
  - 本地已在跑的会话不动，避免同一条 run 挂两条流、事件与语音收两遍（2.81 踩过的重复播报）。
  - 接管前先探一次 run 状态，已收尾或探不出来的直接放弃，不在本地瞎标活跃。
- 气泡策略与冷启动恢复一致：本地已有这一轮的空气泡就复用标成进行中，否则新起一个，避免同一轮正文被追到旧回复上或显示两遍。

（改 ChatViewModel.kt / App.kt / build.gradle.kts）

---

## 2.121 — versionCode 132

状态页重构：顶部加概览卡，下面加会平滑推进的资源进度条，底部加自动刷新心跳。

- **顶部概览卡**：运行状态徽标 + 模型 + 已运行时长 + 进程 PID + 活跃任务与子任务数，一眼看完「活着没、跑什么模型、跑了多久、在干几件事」。
- **资源进度条**：CPU、内存、Swap、磁盘、系统负载五条；用量 ≥60% 变黄、≥85% 变红；系统负载按核数折算，超核满条。数值变化时条平滑推进，不是硬跳。
- **刷新心跳**：底部一颗心跳点，每 5 秒刷新时闪一下，旁边显示「上次 HH:mm:ss」，一眼看出自动刷新真的在跑。
- 只在服务端回了对应字段时才出条（老网关没打 sysinfo swap 补丁时不显示一排 0% 的假条）。原明细分组保留。
- 纯客户端，服务端零改动。

（改 ChatViewModel.kt / Screens.kt / TimeFmt.kt）

---

## 2.120 — versionCode 131

事件流撞 404 不再无限重连；404 视为「服务端缓冲已回收」，直接转去服务端取结果。

- **根因**：服务端 `_run_streams` 缓冲 TTL(300s) 短于状态记录 TTL(3600s)，清理器又不管任务死活就删缓冲 —— 出现「`GET /v1/runs/{id}` 说 running，但 `/events` 立刻 404」的自相矛盾。客户端把 404 当普通断流去退避重连，于是「连接中断，N 秒后重试」反复刷、停不下来（实测一条 run 连撞 24 次，用户得手动点停止）。
- **修法**：新增 `RunStreamGoneException` 把「流没了」与「网络抖了」分开；SSE 订阅遇 404 直接转翻历史等答案落盘，跳过退避重起流。服务端清理器同步修（任务活着不删缓冲）。

（改 ChatViewModel.kt / HermesApi.kt；服务端补丁）

---

## 2.119 — versionCode 130

IPv4 优先 DNS + 探针客户端打开重试，修「一直显示离线 / 反复重连」。

- **根因**：用户所在移动网络到 EdgeOne 的 IPv6 路由是黑洞（SYN 发出去无回应），内核按 TCP 重传退避死等（1+2+4+8≈15s、再加 16≈31s、再加 32≈63s），普通请求因此「200 但要 14/28/67 秒」。主客户端开着 `retryOnConnectionFailure` 还能换地址重连，而 `probeClient` 关着重试，一撞上就立刻抛错 —— 而「右上角在线/离线」和「连接中断重连」全由它驱动，于是界面全程报离线。
- **修法**：新增 `IPv4FirstDns` 交给全部 OkHttp 客户端（IPv4 优先、IPv6 作后备，不彻底禁 IPv6）；`probeClient` 打开 `retryOnConnectionFailure`。

（改 HermesApi.kt）

---

## 2.118 — versionCode 129

在线探针放宽 + 通知点得进对应会话 + 审批前台也提醒。

- **在线探针**：超时 5s→15s，连续失败阈值 2→3 次，且最近 30 秒内成功过就仍算在线 —— 单次抖动不再把角标打红（用户实测「一直显示离线」而服务端 6195 次心跳全 200）。
- **通知跳转**：原来通知收了 sessionId 却从没 `putExtra`，点「任务完成/新消息」只是把 App 拉到前台、停在原会话。与 `notifyAction` 对齐带上会话 id，点通知直接进对应会话。
- **审批/澄清提醒**：原来「App 在前台就一律不弹」，用户开着 App 在看别的页面/别的会话时审批来了完全没提示。新判据 = 只有「就在当前这个会话」才不弹，其余情况前台也弹。
- **待处理置顶条**：审批/澄清卡片原来嵌在助手气泡里，长对话时埋在中间、滚半天看不到。对话窗口顶部钉一条醒目提示（「需要你确认」/「需要你选一下」+ 摘要），点「查看」跳到那张卡片；无待处理时不占高度。

（改 ChatViewModel.kt / Notifier.kt / Screens.kt / HermesApi.kt）

---

## 2.117 — versionCode 128

修：点别的任务的播放按钮时「当前这条停不掉」，以及「听不出正在播谁」。

- **根因（停不掉）**：2.116 的点重播路径在后台线程里又调了一次会推进队列的 `stop()`——队列把刚让位的语音又从头拉起，与手动重播叠着响。
- **修法**：外部播放器（重播 / 老附件）起播前只调 `yieldAndDrop()`——停掉正在播的那条**并把它从队列摘掉**，不再自动重播；改用独立的 `releaseCurrent()` 释放自己的旧播放器，绝不触发队列推进。用户想再听那条，点它自己的播放按钮即可。
- **加提示**：正在播的那条消息时间行旁边显示一个小喇叭（`Icons.Rounded.VolumeUp`）；若正在播的语音属于**别的会话**，聊天页顶部出现提示条「正在播放：X 的语音」，带「进入」「跳过」两个出口。
- 「停止」语义不变：停全部并清空队列。
- 服务端、网关零改动；补丁清单不变。

（改 StreamVoicePlayer.kt / VoicePlayer.kt / VoiceReplayPlayer.kt / ChatViewModel.kt / Screens.kt / Markdown.kt）

## 2.116 — versionCode 127

新：语音播放改成串行队列，不再互相插队打断。

- **主诉**：一条任务的语音正在播时，别的任务一完成就立刻抢麦播它的语音，把当前这条拦腰打断。
- **根因**：流式播放器是「单条」结构——只有一个文件句柄、一个播放器；B 的语音块一到就复用这份唯一资源，于是 A 被掐掉。
- **修法**：流式播放器改成「每个任务各一条」+ 到达顺序队列。B 的音频照常收、照常落盘（数据不丢），只是先不出声；A 播完自动接着播 B，依次排下去。
- 手动点某条消息的播放按钮仍是立即播这条（打断正在播的自动播报），并取消它自己的排队项（避免播两遍）；播完队列继续。
- 「停止」= 停全部并清空队列，不再接着播后面的。
- 服务端、网关零改动；补丁清单不变。

（改 StreamVoicePlayer.kt 重写 / VoicePlayer.kt / VoiceReplayPlayer.kt / ChatViewModel.kt）

## 2.115 — versionCode 126

修：点播放按钮出现两条语音同时播、且互相停不掉。

- **根因**：App 里三个播放器（自动播报 `StreamVoicePlayer`、气泡重播 `VoiceReplayPlayer`、老的整段附件 `VoicePlayer`）各持一份状态，起播前谁都不停别人——点重播只 `stop()` 自己，正在响的流式播报照旧；反过来按停止也只停自己那条。
- **修法**：三个播放器起播前一律先掐掉另外两个（互斥），保证同一时刻只有一条语音在响；「停止」语义随之变成停全部。
- 同一条消息不再同时渲染两个语音按钮（附件按钮与重播按钮）：优先按 `runId` 的重播按钮，没有 runId 的老消息才回落正文里的内联附件按钮。
- 服务端、网关零改动；补丁清单不变。

（改 VoicePlayer.kt / StreamVoicePlayer.kt / VoiceReplayPlayer.kt / Screens.kt）

## 2.114 — versionCode 125

修：设置页的「播报语速」对自动播报与重播不生效。

- **根因**：App 里有三个播放器，各自持有一份 `rate` 字段、互不同步——自动播报走 `StreamVoicePlayer`，气泡重播走 `VoiceReplayPlayer`，老的整段附件走 `VoicePlayer`。而 `setVoiceRate()` 只灌了 `VoicePlayer` 一个，于是设置页调完语速，流式自动播报和点重播都还用旧速度（重播那个更是只在启动 App 时读过一次）。
- **修法**：`setVoiceRate()` 改为三个播放器一起灌；并新增 `StreamVoicePlayer.applyRateNow()`，正在播的流式语音也立刻变速（ExoPlayer 支持播放中改速）。
- 服务端、网关、其它功能零改动；补丁清单不变。

（改 ChatViewModel.kt / StreamVoicePlayer.kt / app/build.gradle.kts）

## 2.113 — versionCode 124

新：语音可随时重播（点消息气泡里的播放按钮）。

- **背景**：流式语音为了不播两遍，回复正文里不再带音频附件，于是气泡里那个「播放」按钮失去了显示依据——旧消息再也点不开。自动播报本身没坏，坏的是重播入口。
- **做法**：服务端流式合成时顺手把整段 mp3 长期留档（`tts_voice/<run_id>.mp3`，总容量 2GB、超出按最旧淘汰），并新增 `GET /v1/voice/{run_id}` 取件；App 给消息记上 `runId` 并落盘，气泡按钮改看它——本机有留档就即时播，没有就按 runId 取回、缓存后再播（首次约 0.3~1 秒，之后即时）。
- 自动播报逻辑一行未动，仍是流式首块 1 秒级起播。
- 容量：按每条 0.1~1MB 算可存 2000~20000 条，多年到不了上限。
- 服务端配套补丁 `apply_voice_replay_patch.py`，已按铁律三处登记（脚本 / README-patches.md / verify_patches.py CHECKS）。

（改 VoiceReplayPlayer.kt（新增）/ ChatViewModel.kt / SessionStore.kt / Screens.kt / Markdown.kt / StreamVoicePlayer.kt / net/HermesApi.kt / app/build.gradle.kts）

## 2.112 — versionCode 123

修：流式语音第二个缺陷——stop() 的清理动作被延后到主线程，反把 begin() 刚设好的状态清空。

- **根因**：2.111 修线程约束时，把 `releaseQuietly()` 整个搬进主线程。但 `begin()` 是「先 `stop()`、再设 `nowPlaying`/`raf`」，两步在 SSE 线程顺序执行；`stop()` 里的清空被 post 到主线程**延后**执行，于是它在 `begin()` 设好新 key 之后才跑，把 `nowPlaying` 清空、`raf` 置空 → 后续 `audio.delta` 全被 `isNotEmpty()` 判假丢弃、`append()` 因 raf 为空直接 return → 又是一个字都不播。
- **修法**：`releaseQuietly()` 改为先在**调用线程**把 `player`/`raf` 引用摘下来（局部变量），只把「旧对象」交给主线程 `stop/release/close`；`nowPlaying` 的置空移到 `stop()` 里同步执行，绝不在主线程块里改共享状态。两条路径（线程约束 + 清空时序）现已同时正确。
- 服务端、网关、其它功能零改动。

（改 StreamVoicePlayer.kt / app/build.gradle.kts）

## 2.111 — versionCode 122

修：2.110 流式语音在真机上完全无声。

- **根因**：ExoPlayer 硬性要求「创建 / prepare / play / release」都发生在带 Looper 的线程（主线程）。`StreamVoicePlayer` 是从 SSE 回调线程（OkHttp 线程池，无 Looper）直接调进来的，`ExoPlayer.Builder(ctx).build()` 抛异常后被 catch 吞掉 → 播放器从未起播 → `nowPlaying` 被清空 → 后续 `audio.delta` 因「nowPlaying 为空」全被忽略 → 全程无声。
- **修法**：`StreamVoicePlayer` 内新增主线程 Handler，所有 ExoPlayer 操作（建、prepare、play、setPlaybackSpeed、stop、release）统一 post 到主线程；文件读写仍在原线程（文件 IO 无所谓线程）。
- 服务端、网关、其它功能零改动；`api_server_tts_stream` 关掉仍回落原整段附件路径。

（改 StreamVoicePlayer.kt / app/build.gradle.kts）

## 2.110 — versionCode 121

语音播报提速：合成前剥 markdown（乙）+ 流式边合成边播（丙）。

- **原来的问题**：run 收尾时同步把整段回复合成 mp3，合成完才发 `run.completed`，所以正文和语音一起被拖住。实测 300 字 4.3 秒、1000 字 14.2 秒、3000 字 21.4 秒才开始出声。
- **乙（剥 markdown）**：合成前去掉代码块、表格、链接、加粗、标题等，只念纯文字。实测「表格+代码」类回复字数省 77%，纯正文只省 4%（不伤正文）。带代码的回复总时长直接减半。
- **丙（流式）**：服务端改用 edge_tts 的流式接口，边合成边把 mp3 块通过新事件 `audio.start` / `audio.delta` / `audio.end` 推进同一条 SSE 流；App 用 media3 播放「边写边读的本地文件」，首块到达即起播。实测首块 1.05 秒就绪（1000 字全量要 7 秒）→ **出声从十几秒降到 1 秒级**。
- run 收尾不再等语音：`run.completed` 立即下发，正文秒出，音频随后在同一流上继续推。
- **整体可回退**：服务端 `voice.api_server_tts_stream` 关掉即回落原来的整段 MEDIA 附件路径，App 对 audio.* 事件是纯增量、不认识就忽略。
- 服务端配套补丁 `apply_tts_stream_patch.py`，已按铁律三处登记（脚本 / README-patches.md / verify_patches.py CHECKS）。

（改 StreamVoicePlayer.kt（新增）/ ChatViewModel.kt / app/build.gradle.kts）

## 2.109 — versionCode 120

新：收件箱支持删除。

- **单条删除**：长按收件箱某条产出 → 弹二次确认 → 删除（服务端 `POST /api/inbox/delete`）。也可在打开的全文弹窗底部点「删除」。
- **清空全部**：标题行新增红色「清空」入口 → 二次确认 → 整箱清空。
- 删完本地列表与未读数即时更新，不用等重拉；删掉的正是当前打开的那条时自动收起弹窗。
- 服务端配套补丁 `apply_app_inbox_delete_patch.py`（`cron/app_inbox.py` 加 `delete()`、`api_server.py` 加路由 `POST /api/inbox/delete`），已归档并登记总清单。

（改 net/HermesApi.kt / ChatViewModel.kt / Screens.kt / app/build.gradle.kts）

## 2.108 — versionCode 119

定时任务页的中文说明补齐（含朋友那边）。

- 新增 5 条任务的精确中文名与说明：`apk-keep-30`（安装包只留 30 个）、`gradle-idle-reaper`（编译进程空闲回收）、`mem0-upgrade-postcheck`（记忆库升级检查）、`friend-nightly-memory-refactor`（夜间记忆整理）、`ds-upstream-watch`（上游巡检）。
- 关键词兜底补 `upstream` / `apk` / `keep` / `postcheck`，以后新加任务不必再改代码；`watchdog` 仍排在 `watch` 之前。
- 两个档案（default / friend）共用同一份翻译表，朋友那边的任务名一并出中文。

（改 ChatViewModel.kt / app/build.gradle.kts）

## 2.107 — versionCode 118

调：历史对话的手动排序不再常驻显示，收进「排序模式」。

- 上版每行右侧常驻一对 ▲▼ 箭头，列表一长就是最密集的视觉噪声。现在**默认不显示**：标题行「历史对话」旁新增「排序」入口，点它才在每行右侧露出上/下移箭头，标题行同时把「搜索/已归档」换成「完成」。
- 点「完成」、或点任意会话进入，都退出排序模式，箭头收回，回到干净视图。
- 排序逻辑、`order` 落盘、新会话恒排第一等全部不变，只是露出方式改了。

（改 App.kt / app/build.gradle.kts）

## 2.106 — versionCode 117

新：历史对话支持手动排序。

- 会话行右侧（「⋯」左边）加一对迷你箭头 ▲▼：点 ▲ 上移一位、点 ▼ 下移一位，顶行▲、底行▼置灰。用图标而非长按——长按会和行的点击、菜单抢手势，容易误触。
- 排序键存在本地索引（`SessionMeta.order`）。**关键：只有你真正点过箭头才进入「手动顺序」模式**，之后列表不再按最近消息自动重排；没点过就照旧按更新时间排，行为不变。
- 新会话（点「+ 新对话」或空态直接输入）永远排第一：手动模式下取「最小 order − 1」并插到列表最前，不破坏你已经排好的其他行。
- 归档态内移动：只在未归档之间 / 已归档之间换位（跨态移动没有视觉意义）。
- 兼容：老索引没有 order 字段 → 读成 0 → 回落按更新时间排；老版本 App 读新索引忽略 order，不会崩。

（改 SessionStore.kt / ChatViewModel.kt / App.kt / app/build.gradle.kts）

## 2.105 — versionCode 116

2.104 的三处收尾（发布前自检抓到，2.104 的包不含这些）：

- **`newConversation()` 还在同步写盘**：它是切会话的兄弟路径（点「+ 新对话」），漏改成异步。不修的话新建对话照样卡一下。改成 `saveCurrentAsync()`。
- **删掉已死的 `refreshSessions()`**：2.104 把它改成异步后已无人调用，留着是死代码（而且它引用的 `_sessions.value` 赋值逻辑已由 bootstrap/落盘路径覆盖）。
- **补回一条启动日志**：`refreshSessions` 一去，启动分支那行「刷新列表 条数=…」就没了；把等价的日志挪进 `bootstrapSessions` 的「恢复列表」分支，启动体检信息不丢。
- 顺手把 `send()` 空态新建会话、`cleanupShellSessionsOnce()` 的索引写盘也统一走 `saveIndexAsync(0)`。

（改 ChatViewModel.kt / app/build.gradle.kts）

## 2.104 — versionCode 115

修「重启后侧边栏切换会话卡顿」。六项一起做（切会话路径全异步化）：

1. **切会话不再在 UI 线程做磁盘 I/O**。原来一次点击同步做 3 次写（上一会话正文 + 索引）+ 2 次读并全量 JSON 解析（新会话正文 + 索引），重启后没有内存缓存，每次都等磁盘 —— 这就是卡顿来源。现在写盘异步（复用 400ms 去抖的 saveJob）、读盘走 `Dispatchers.IO`，主线程只改状态。
2. **抽屉列表改 LazyColumn**（会话列表带稳定 key、全局搜索结果也惰性化）。原来整个列表一次性铺出来，切会话会整体重组，会话越多越卡。
3. **切会话不再整体重读索引、整体换列表**。`refreshSessions()` 改异步，且内容没变（`l == _sessions.value`）就不赋值，避免无谓重组。
4. **日志改异步批写**。原来每条日志都在调用线程上 `appendText`（一次 open/write/close），而日志遍布每个关键路径（流式期间每条事件一条）。现在入队 + 单写线程每 400ms 批量落盘；`ERROR` 行仍立即落盘（崩溃前那几行必须在文件里）。`AppLog.flush()` 供退后台时手动落盘。
5. **启动预热**：启动 1.2 秒后、IO 线程上预读最近 3 个未归档会话的正文，首次切换不用等磁盘。
6. **退后台立即落盘**（`onStop` → `vm.flushSaves()`）：写盘全异步化后进程随时可能被回收，这一步保证最后一段不丢。

数据安全：读盘用「用户消息锚点合并」——读盘期间若有推送写进了内存，按锚点合并而非整份覆盖；删除会话先把运行态标死、取消待执行的保存任务再删文件（否则刚挂上的异步保存会把文件写回来，会话复活）。

顺带加了耗时打点（`perf` 标签：切会话主线程耗时 / 读会话正文读盘耗时与「点击到就绪」总耗时），改前改后可对比实测毫秒数，而不是凭感觉。

（改 ChatViewModel.kt / App.kt / AppLog.kt / RuntimeHub.kt / app/build.gradle.kts）

## 2.103 — versionCode 114

修「通知栏左侧还是系统占位图（机器人＋网格），不是应用图标」。用户截图逐像素比对确认：那张卡片确实是 Hermes 的通知（标题以 H 开头），但左侧图标与 App 图标相关系数 −0.20 —— 系统没用我们给的图，退回了它自己的占位图。

两处按 Android 官方形式改：

- **小图标从 PNG 位图改成矢量图**（`res/drawable/ic_stat_hermes.xml`，24dp 圆角方块路径）。原来的位图是灰度+alpha 的 PNG（aapt2 会把白+透明的图优化成 LA 格式），这类位图在部分 ROM 的通知图标路径上会加载失败，系统就退回占位图。矢量图是官方推荐的小图标形式，任何 ROM 都能解析。
- **自适应图标补上 `monochrome` 单色层**（Android 13+ 主题图标用）。缺这一层时，部分 ROM 在通知/状态栏会用自家占位图顶替。

（改 res/drawable/ic_stat_hermes.xml（新增）/ mipmap-anydpi-v26/ic_launcher.xml、ic_launcher_round.xml / 删除 res/drawable-nodpi/ic_stat_hermes.png / app/build.gradle.kts）

## 2.102 — versionCode 113

通知栏挂上 App 自己的图标：

- **原因**：三条通知的小图标全用的系统 drawable（`stat_notify_chat` / `stat_sys_warning` / `stat_notify_sync`），而且从没调用过 `setLargeIcon`。所以状态栏顶着系统那个通用气泡，通知栏里右侧一片空白 —— 看着不像这个软件发来的消息。
- **改法**：新增 `Notifier.applyAppIcon()`，三处通知统一走它：
  - 小图标换成 `ic_stat_hermes`（从 App 自己的图标取的白剪影）—— 状态栏小图标会被系统强制染成单色，彩色图会被压成一块实心色，所以这里只能是单色剪影；
  - `setLargeIcon(ic_notify_app)` 给位图 —— 通知栏里显示**彩色的** App 图标走的是这条。
- 两份素材都从 App 现有的图标图派生（`res/drawable-nodpi/`），没有另画图案。

（改 Notifier.kt / RunService.kt / 新增 res/drawable-nodpi/ic_stat_hermes.png、ic_notify_app.png / app/build.gradle.kts）

## 2.101 — versionCode 112

修「定时任务界面显示：收件箱获取失败」：

- **根因**：`refreshInbox()` / `ackInbox()` 用 `RuntimeHub.scope.launch { }` 起协程，而 `RuntimeHub.scope` 是 `Dispatchers.Main.immediate`，里面调 `a.inbox()` / `a.ackInbox()` 是**阻塞式** HTTP —— 在建立连接那一刻就抛 `NetworkOnMainThreadException`，请求根本没发出去（服务端访问日志里一条都没有，隔壁 `/health` 照常打点，这就是佐证）。同类的坑之前只修了子任务轮询那两处，收件箱这两处漏了。
- 两处都改成 `launch(Dispatchers.IO)`。
- 顺带修「错误只显示一个问号」：`NetworkOnMainThreadException` 的 `message` 是 null，老写法 `e.message ?: "?"` 把真实原因吞掉了。新增 `diagText(e)`，报「类名: message」，以后再出问题一眼能看出是什么异常。
- 全项目扫了一遍其余 `launch` 点：没有别的「主线程做阻塞 HTTP」了（扫描脚本拿修复前的版本验证过，恰好只命中这两处）。

（改 ChatViewModel.kt / app/build.gradle.kts）

## 2.100 — versionCode 111

子任务入口挤进顶栏那一行：

- 顶栏改成 `☰ 对话 [子任务 N · M 跑 ▾] …… 搜索 ●在线`。小标插在标题右侧，右边靠 `Spacer(weight)` 顶住，**搜索与在线原位不动**；没有子任务时小标整个不出现，顶栏与从前完全一样。
- 点小标在那一行下面展开明细（列表属于 TopBar 组件自身），再点收起。默认折叠。
- 明细里不再重复放「收起」按钮（点顶栏小标即可收），只保留条目超过 5 条时的「看全部 / 只显示最近」。

（改 Screens.kt / App.kt / app/build.gradle.kts）

## 2.99 — versionCode 110

子任务面板四件事：

1. **默认折叠**。进会话时只露一行「子任务（N） · M 个在跑」，点「展开」才列出各行——此前默认展开，有子任务的会话一进去就被面板吃掉一截屏幕。
2. **能停止**。运行中的那一行右下角多一个「停止」；走服务端新开的 `POST /api/subagents/{id}/stop`（对子代理对象发协作式中断），不是复用父 run 的 stop——子代理可能比父轮次活得久，父 run 管不到它。语义是到下一个步骤边界就停，不是立即杀进程；已经跑完的会回「已经不在跑了」。
3. **位置挪进顶部栏本身**。原来面板是对话区里的第一个元素（视觉上贴在顶栏下面）；现在 TopBar 改成上下两层，上面那行照旧（菜单/标题/搜索/在线），面板直接挂在它下面，属于顶部栏组件自身。
4. **修打开卡顿**。根因是子任务进度轮询挂在 `RuntimeHub.scope`（`Dispatchers.Main.immediate`）上，而 `sessionDetail()` 是阻塞式 HTTP——打开有子任务的会话时第一次拉进度就把 UI 线程堵住。轮询与进度面板的刷新都显式切到 `Dispatchers.IO`。

（改 ChatViewModel.kt / Screens.kt / App.kt / net/HermesApi.kt / app/build.gradle.kts；服务端 gateway/platforms/api_server.py 加 /api/subagents/{id}/stop）

## 2.98 — versionCode 109

两处布局调整：

1. **子任务汇总到对话窗口顶部**。原来子任务只在它那一条气泡里显示，对话一长就得往回翻半天才能找到「那个子任务跑到哪了」。现在跨气泡汇总成一块面板，贴在顶栏下面（`子任务（N） · M 个在跑`），带实时进度行，点某条照样打开「子任务进度」面板看它每一步；面板可收起/展开。数据走新加的 `sessionSubagents` 派生流（跨气泡按 id 去重取最新状态，正在跑的排前面），值没变不推给界面。气泡里那份逐条显示已撤掉——不然两处重复。
2. **身份标识从顶栏挪到侧边栏**。顶栏原来写着「对话 friend 搜索 在线」，那个 `friend` 挪到抽屉顶部「Hermes」右边；顶栏留给标题和搜索/在线状态。

（改 ChatViewModel.kt / Screens.kt / App.kt / app/build.gradle.kts）

## 2.97 — versionCode 108

样式：插话消息的「插话」小标不再独占气泡顶部一行，挪到底部跟发送时间并排。

原来它占在气泡正文上面一整行（还要外带 4dp 间距），消息本身只有一行时尤其浪费高度。现在挪到底部那行，样式不变（淡底小胶囊），只是位置改成 `插话 14:32` 这样跟时间同排；底部行的显示条件也补上了 `m.steer`，保证插话那条的标记不会因为没时间/没回执角标而消失。

（改 Screens.kt / app/build.gradle.kts）

## 2.96 — versionCode 107

修：定时任务完成的通知每次重启 App 都重复弹。

根因：防重复弹通知的那个集合只存在内存里，App 一重启就空了。而「弹过通知」和「已读」是两件事——用户不进定时任务页把那条点成已读的话，它一直是未读，于是每次开机都把同一条未读再响一遍。

改法：去重集合落盘（Prefs，键 `notified_reports`，只留最近 100 个条目 id）。规则不变，仍是「每次只弹最新一条未读、同一条只弹一次」，只是「只弹一次」现在跨重启也成立——通知的语义是提醒一次，不是每次开机提醒。

另外：弹过通知不等于已读，未读红点仍留着，进定时任务页照样能看到、点开看全文。

（改 Prefs.kt / ChatViewModel.kt / app/build.gradle.kts）

## 2.95 — versionCode 106

新：会话里有子任务在跑时，侧边栏那条会话也会亮起状态提示。

1. **原来为什么看不到**：侧边栏的状态点只由「本地正跑着一轮」（busy）驱动。但子任务是后台子代理，可能比父轮次活得久——父 run 早就结束了，子代理还在干，列表上却一片安静。实际就是「明明有活，看不出哪个会话在忙」。
2. **状态来源扩成三样**：本地 busy、正在跑的子任务数、待发队列条数，任一为真就在会话标题前亮一个点（busy 用琥珀色，仅子任务/排队用强调色），后面跟文字：`执行中` / `子任务 2` / `排队 1`，多个用 ` · ` 连起来。
3. **用 2 秒一次的内存扫描而不是各处打点**：状态有三个来源、子任务还会跨轮次存活，逐处打点必然漏；扫描只读内存（不发请求），值没变就不推给界面，不引起重组。
4. 「执行中」只在本轮真的在跑时显示；子任务单独在跑时显示「子任务 N」，不冒充「执行中」。

（改 ChatViewModel.kt / App.kt / app/build.gradle.kts）

## 2.94 — versionCode 105

新：定时任务跑完后，产出能进 App 了（配服务端新增的收件箱）。

为什么以前收不到：App 走网关的 api_server 通道，而那条适配器在服务端被标成「不能推送」（`supports_async_delivery = False`），投递目标解析里也把 api_server 排除在 origin 之外——定时任务的结果**没法主动推进 App**，只能判成投递失败。所以这一版是「服务端加收件箱 + App 来拉」两半一起上：

1. **服务端新增 `/api/inbox` 与 `/api/inbox/ack`**（见 cron/app_inbox.py）：cron 投递时把产出留一份带未读标记的档，App 拉取、点开即确认已读。
2. **`deliver: api`（别名 `app`）成为合法的投递目标**：写了它、或任务本来就是从 App 建的（origin = api_server），产出就进收件箱。收件箱收下即算投递成功——不再因为微信会话过期之类的旁路问题把任务标成 `delivery_failed`；旁路的失败照实留在 `last_delivery_unverified` 里，不隐瞒。
3. **App 侧「定时任务」页顶部多一个收件箱**：未读带红点、顶部显示「N 条未读」与「全部已读」；点某条弹全文（Markdown 渲染），点开即标已读。
4. **回前台/启动时自动拉一次**：拉到新的未读就弹一条本机通知（只弹最新一条，不连响一串；同一条只响一次），失败只记错误文案不打扰。

（改 net/HermesApi.kt / ChatViewModel.kt / Screens.kt / app/build.gradle.kts；服务端改 cron/app_inbox.py、cron/scheduler_delivery.py、cron/scheduler_preflight.py、gateway/platforms/api_server.py）

## 2.93 — versionCode 104

新：子任务（delegate_task 派出的子代理）运行时能看它的实时进度了，点一行还能看它每一步在干什么。

1. **根因（两处叠加）**：① 网关那条 run 事件流只转发 `subagent.start` / `subagent.complete`，中间的 `subagent.tool`（子代理每调一次工具）和 `subagent.progress`（每满 5 次一批）在 `api_server_runs.py` 里被当「界面噪音」直接丢掉，推送里根本没有运行中的进度；② App 侧也只记了 goal + 状态，且找不到「进行中气泡」就整条事件 return。结果：子任务跑着的时候，界面上永远只有一行「▶ 目标」。
2. **进度行**：子任务那行下面多一行实时进度——「已 N 步 · 已跑 X 分钟 · Y 秒前取的进度」，收工后显示「耗时 X · 子代理 N tokens」。
3. **进度哪来的**：子代理不是黑盒，它有自己的会话（`source=subagent`），跑的过程中就在实时落库。`subagent.start` 事件里带着 `child_session_id`，App 拿它去读 `GET /api/sessions/{id}` 的 `tool_call_count` / `ended_at` 就够出进度——**全程只读，服务端零改动**。本会话有子任务在跑才挂轮询，全部收工自动退出（有进度 4 秒一次，没进度 8 秒一次）。
4. **点一行看详情**：点子任务那行打开「子任务进度」面板，列这个子代理的步骤流水（第几步 + 工具名 + 参数 + 结果一句话），运行中每 3 秒刷新，收工即停。面板顶部是目标、状态、已跑多久、子代理自己的 token。
5. **完成判定不能只靠推送**：后台子代理常常跑得比父 run 还久，父 run 的事件流早就断了，`subagent.complete` 收不到。所以轮询时看子代理会话的 `ended_at`：非空就按 `end_reason` 标「已完成」或「已结束（没等到完成事件）」——不一律当成功，也不假装还在跑。
6. 找不到「进行中气泡」时改挂到最后一条助手气泡上（原来直接丢事件）；进度字段一并落盘，跑一半重开 App 会把轮询接回去。

（改 ChatViewModel.kt / Screens.kt / RuntimeHub.kt / SessionStore.kt / net/HermesApi.kt）

## 2.92 — versionCode 103

新：补齐运行日志的最后几处盲区（设置页、附件、消息落盘、语音、定时任务、服务端合并）。

1. **消息落盘失败不再静默**：`SessionStore.saveMessages` 以前整段 runCatching 吞掉，写盘失败一行日志都没有——表现是「消息看着发了、重开就没了」却查不到线索。现在失败记 ERROR（含条数、目标路径），裁剪超限也留痕。
2. **附件「打开/分享/保存」失败全部留痕并给提示**：原来打开与分享整段静默，点一下毫无反应，用户分不清「没点到」还是「打不开」；网关托管附件下载不到、内容解析不出，也各给一行提示。
3. **语音失败带原因**：取字节失败原来只写「取语音字节失败」，现在记来源（内联/网关托管）、长度与异常摘要；播放器 onError 记 what/extra。
4. **定时任务页全程留痕**：列表加载条数、每次操作（暂停/恢复/立即执行）、执行完成/失败结果。
5. **服务端记录合并对账留痕**：合并时记「本地 N / 服务端 M / 合并后 K」，服务端返回空也留一行——历史错位这类问题以后能看出是哪一步没对上。
6. **界面层补打点**：切页、登录完成、点删除（待确认）。

（AppLog 容量沿用 2.91 的 8000 行/4MB）

## 2.91 — versionCode 102

新：把「历史对话列表」这一层以前完全没打点的地方全部留痕——下次列表再出问题，日志里直接能看到是哪一步、哪条数据。

1. 启动体检：启动时打一行本地存储体检（索引文件在不在/多大、索引文件有几个、消息文件几个共多少字节、目录文件总数）+ 读索引条数 + 走的是哪个分支（空态 / 迁移旧格式 / 恢复列表，恢复时带条数与已归档数）。
2. 索引读写留痕：loadIndex 解析失败不再被静默吞掉（原来 runCatching 吞掉后当空列表返回——界面表现正是「历史对话全部没了」，日志里却一行都没有）；saveIndex 每次写盘记「写了几条、几条归档」。
3. 列表刷新留痕：refreshSessions 记条数/已归档数/当前会话；syncFromServer 记服务端返回条数与本地条数。
4. 消息文件留痕：读消息为空时记「文件存在与否 + 字节数」（区分文件丢了与文件在但读不出）；判断有无消息失败、删除消息文件都记结果。
5. 归档操作留痕。
6. 容量：落盘 5000 行/2MB 到 8000 行/4MB，内存 800 到 1200 行。
7. 纯客户端，不碰服务端、不重启网关。

（AppLog.kt、SessionStore.kt、ChatViewModel.kt）


修：切后台后任务实时进度仍中断（realme 等机型的「应用速冻」把整个进程挂起，唤醒锁管不了这一层）。

1. **新增 Wi-Fi 高性能锁**：与 CPU 唤醒锁一起，在有任务跑时持有、无任务立刻释放。防省电模式把 Wi-Fi 收包节流/掐断。
2. **两把锁的获取/释放全部落日志**：`唤醒锁 held=true/false`、`WifiLock held=...`、`释放锁 wake=... wifi=...`。以后「锁到底拿没拿到」是日志里的事实，不用猜。
3. **onStartCommand 落 active 与 intentNull**：确认保活请求真的带上了「有任务在跑」这个标记。
4. **诊断结论（本次未改）**：实测后台期间线程被系统冻结（3 分钟零日志、连 31 秒心跳都停），这是厂商后台管控，需在手机侧把 App 加进「允许完全后台行为」并把「应用速冻」关闭。进度轮询方案经核实无数据源（服务端运行中的 run 状态不带部分正文，也不增量写消息），需改服务端才能做，本轮不做。

（改 RunService.kt / AndroidManifest.xml）

## 2.89 — versionCode 100

修：切后台/息屏后任务不再汇报进度、跑完也不通知不播报。

1. **根因一（缺唤醒锁）**：前台服务只保证「进程不被杀」，不阻止 CPU 休眠。息屏/切后台后 CPU 睡了，TCP 连接还挂着却收不到字节，SSE 静默断掉——实测后台 61 秒 0 事件，切回来显示「重新连接」。修法：`RunService` 在有任务在跑时持 `PARTIAL_WAKE_LOCK`，无任务立刻释放（不空转耗电），清单加 `WAKE_LOCK` 权限。
2. **根因二（通知/语音只在流里触发）**：完成通知与语音只写在 `run.completed` 分支，而流被掐时收不到该事件，只能靠探测「已结束」收尾，于是任务跑完了手机不响。修法：探测兜底的两个收尾分支（服务端答已结束 / run 已不存在）也调通知+语音，正文取服务端返回的 output，没有就回落本地最后一条助手正文。
3. 纯客户端，不碰服务端、不重启网关。

（改 AndroidManifest.xml / RunService.kt / ChatViewModel.kt）

## 2.88 — versionCode 99

新：把运行日志范围大幅扩大，下次出 bug 直接发日志就能排查。

1. **网络层统一留痕**：HermesApi 所有请求出口都记「方法 路径 状态码 耗时 响应摘要」，失败另记 ERROR 行（含异常摘要）。覆盖建 run / 探状态 / 上传 / 下载媒体 / 审批 / 澄清 / 插话 / 停止 / 定时任务 / 能力与健康探测 / 检查更新。
2. **静默异常全部留痕**：ChatViewModel 各 catch / runCatching 补一行日志，不再「失败无声」。
3. **生命周期**：切会话、新建、删除、切身份、进前台、启动前台服务/停止，各记一行。
4. **回执五档**：sending/accepted/uncertain/failed/acked 每次转变留痕。
5. **排队/插话/停止/审批/澄清**：请求到达、你的选择、POST 结果全过程留痕。
6. **附件**：加图片/加文件的成败与体积上限拒绝留痕。
7. **头部环境摘要**：日志开头写版本、机型、Android 版本、pid，发一段就能定位环境。
8. **容量**：落盘 2000 行/600KB → 5000 行/2MB，内存 400 → 800 行。
9. **分级**：ERROR 行加前缀，便于搜索定位。
10. **新增「导出日志文件」**：设置页除「复制全文」外可把日志落成文件走系统分享发出来，不占剪贴板、不截断。
11. **预览**：设置页日志预览 200 → 300 行。
12. 隐私：不记消息正文全文、不记 API key、不记密码，只记长度/状态码/路径/异常摘要。日志始终 App 私有。

（改 AppLog.kt / HermesApi.kt / ChatViewModel.kt / RunService.kt / Screens.kt）

## 2.87 — versionCode 98

新：加「本轮事件计数」诊断日志，用来一眼分清「过程不显示」是事件没到还是没渲染。

1. **背景**：用户报任务跑起来不再逐步显示过程、直接出最终结果。服务端实测事件照发（tool.started/completed 都在），链路也实时，但手机端日志里出现过 `lastSeq=-1`（整轮一个事件都没收到）。缺一个判据。
2. **加计数**：`SessionRuntime` 新增 `evCount` / `toolCount`，起流时清零；每收一个事件 +1，`tool.` 开头的事件另计。
3. **落日志**：流关闭与流出错时打「本轮共收事件=N 其中工具=M」；每往气泡加一行过程时打「过程行已加 工具=… 本轮事件=… 其中工具=…」。
4. **判读**：事件=0 且工具=0 → 事件根本没到手机（链路/中间层问题）；事件很大但过程区仍空 → 事件到了没渲染（App 侧问题）。
5. 纯加打印，不动任何业务逻辑。

（改 ChatViewModel.kt / RuntimeHub.kt）

## 2.86 — versionCode 97

修：输入框草稿没按会话隔离——在 A 会话打了字没发，切到 B 会话那串字还在。

1. **根因**：草稿全 App 共用一个键 `draft_input`，`MainScaffold` 的 `inputState` 只 `remember`（不带键）初始化一次，切会话时不重新取。于是 A 会话的内容原封不动留在输入框里，看着像「跟着人走」而不是「跟着会话走」。
2. **草稿按会话存**：`Prefs` 的 `draftInput` 换成 `draftFor(sessionId)` / `setDraft(sessionId, v)` / `clearDraft(sessionId)`，键是 `draft_input:<sessionId>`。
3. **切会话即切换输入框**：`inputState` 改成 `remember(currentId) { ... }`——会话一变就重新取该会话自己的草稿。
4. **去抖期间切走也不丢**：加 `pendingDraft` + `flushDraft()`，`LaunchedEffect(currentId)` 在切走时先把待落盘的草稿冲刷到旧会话的键上，再让新会话取自己的。
5. 删会话时一并清它的草稿；「插话没赶上放回输入框」也改成写当前会话自己的键。

（改 App.kt / Prefs.kt / ChatViewModel.kt）

## 2.85 — versionCode 96

新：从后台切回 App 时，自动与服务端同步一次会话正文（以前只同步标题）。

1. **根因**：`onAppForeground()` 只做三件事——补恢复探测、掐假死流重连、同步标题（30 秒节流），**从不重拉正文**；而真正重拉正文的 `refreshFromServerFor()` 开头有 `if (r.busy.value) return` 守卫，正在跑的会话每次都被挡下。于是「进行中的对话切后台再回来，信息就停在切出去那一刻」。
2. **回前台自动同步**：`syncOnForeground()` 拉当前会话的服务端消息并用既有的 `mergeByUserAnchor` 合并（按用户消息正文对齐，不丢本地内联图片，服务端压缩删行也不错位）。3 秒节流，防快速切前后台狂打接口。
3. **正在跑的不硬拉**：服务端记录此刻是半成品，合并会把半截内容写进气泡。改落 `SessionRuntime.needSync` 标记，本轮收尾（`doneOk` / `failPending`）时自动补拉一次；其它正在跑的会话同样各落各的标记。
4. **改挂进程级作用域**：`refreshFromServerFor` 原挂 `viewModelScope`，而回前台同步与收尾补拉都可能在 Activity 已被重建/销毁之后触发，挂旧实例的作用域会被一起取消 → 改挂 `RuntimeHub.scope`。

（改 ChatViewModel.kt / RuntimeHub.kt）

## 2.84 — versionCode 95

修：定时任务卡片上多出一行「原因 null」，以及真故障「投递失败」看不到。

1. **「原因 null」是误报**：org.json 的 `optString(key, "")` 只对**缺失**的键给默认值；键存在但值是 JSON null 时返回字面的 `"null"`。服务端 `latest_execution.error` 本来是 null（= 没出错），被 App 当成错误原因印了出来。新增 `jsonStr()` 统一判空，所有任务字段改走它。
2. **补显示「投递失败」**：任务本身跑成功、结果没送出去时（服务端 `last_delivery_error`，如微信 iLink 会话未就绪），以前 App 完全看不到这条真原因。现在单独一行红字显示。

（改 ChatViewModel.kt / Screens.kt）

## 2.83 — versionCode 94

治本：切后台回来丢进度 / 完成不弹通知的根因——运行态跟着 Activity 一起被销毁重建。

1. **运行态搬进进程级单例（RuntimeHub）**：原来 `runtimes` 挂在 ChatViewModel 里，而 ViewModel 会随 Activity 被系统销毁重建（息屏、内存紧张、从通知栏回 App 都可能触发）。新实例的代际号 `streamGen` 与续接计数 `autoContinue` 都从 0 重来，与旧实例那条仍在跑的流各执一份，同一个 run 挂上两条流：进度被互相搅乱（看着像「进度没了」），完成事件也可能落在已被作废的那条上，于是通知不弹。实测日志：02:45:04 起流 gen=2，02:45:37 又出现「第0次续接 gen=1」。
2. **长命任务改挂进程级作用域**：攒帧器、消息落盘、退避重连、翻历史兜底原来都挂 `viewModelScope`，旧 ViewModel 一死全部陪葬——表现是「进度卡住不动、跑完才一次性冒出来」。现在统一挂 `RuntimeHub.scope`（SupervisorJob + Main.immediate），跨 Activity 重建存活。
3. **前台服务保活判据改为「有任务在跑就举牌」**：不再只看「后台运行」开关，开关关着时任务在跑也保持进程不冻结（抄 relay 的活跃轮次登记思路）。
4. **重建留痕**：ChatViewModel 构造时打一行 `[vm] ChatViewModel 新建 pid=`，用于确认是否真的发生了重建。

（新增 RuntimeHub.kt；改 ChatViewModel.kt）

## 2.82 — versionCode 93

修：回前台时同一条任务被起了两条流，导致回复正文与完成语音都重复执行一遍。

1. **流代际号**：每起一条新流 `streamGen + 1`，旧流的回调（onEvent/onClosed/onError/onActivity）发现代际不符即整段作废，不再重复处理事件、不再重复触发续接。
2. **起新流前掐旧流**：`streamRun` 里先把 `r.call` 指向的旧流 `cancel()`，再挂新流——以前只是覆盖引用，旧流还活着。
3. **恢复探测加守卫**：`resumeActiveRun` 探测是异步的，等待期间用户可能刚发了新消息（busy 已置真 / runId 已换新）。探测完成后先复核这两点，命中就放弃本次恢复，不再对同一条 run 起第二条流。

（ChatViewModel.kt streamRun / resumeActiveRun / SessionRuntime）

## 2.81 — versionCode 92

修：App 切到后台再切回来，正在跑的任务不再继续汇报进度。

1. **回前台补一次恢复探测**：以前只有切身份（onProfileChanged）才会重试接回任务，回前台（onAppForeground）这条路径根本不存在——被系统在后台杀掉、重开成新进程的活跃任务，runtime 是全新的 busy=false，直接被跳过，进度永远不报。现在每次进前台都会补探一次（已在跑的会话自动跳过，不会重复接流）。
2. **回前台重置重连退避**：后台期间断流重试次数用满后，会话会掉进「每 5~30 秒轮询服务端记录、只等最终答案」的历史恢复模式，期间不推任何流式进度。现在回前台把退避计数归零并作废该轮询，让流式续接优先。
3. **探不出来不再判死**：重开 App 时网络未就绪、探测返回「未知」的活跃任务，以前只留个标记干等（而没有前台重试入口），现在登记成「进行中」并交给看门狗与退避链继续重连。

（ChatViewModel.kt onAppForeground / resumeActiveRun）

## 2.80 — versionCode 91

状态页「内存」区新增交换分区（Swap）用量。

1. **新增两行**：「Swap 使用率」（百分比）与「Swap 已用/总量」（MB / MB），排在网关进程下面。
2. **数据来源**：服务端 `/health/sysinfo` 新增 `swap_total_mb` / `swap_used_mb` / `swap_percent`（配套服务端补丁 apply_sysinfo_swap_patch.py）。
3. **兼容旧服务端**：字段缺失时两行自动不显示，不会报错也不会显示 0。

（ChatViewModel.kt buildStatus）

## 2.79 — versionCode 90

「定时任务」页的「已触发执行」不再是一句看不出结果的话。

1. **提示带上任务名**：原来点「立即执行」只在页顶写一行「已触发执行」，多任务时分不清点的是哪条。现在写「已触发执行：<任务名>」。
2. **卡片显示最近一次执行明细**：服务端列表接口每条任务本来就带 `latest_execution`（状态 / 起止时间 / 错误），App 以前整个丢掉。现在显示「最近执行：执行中 / 已完成 / 失败 · 耗时 Ns」，失败时另起一行显示原因。
3. **跑完回显产出摘要**：点「立即执行」后每 3 秒轮询一次执行状态，跑完（最多盯 2 分钟）把这次 cron 会话最后一条助手回复压成一行显示在提示里；超时未结束就如实说「仍在执行中」，不谎报完成。

（ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.78 — versionCode 89

修：切换主题时系统状态栏 / 导航栏颜色不跟着变。

`LaunchedEffect` 的键原为 `dark`，而「护眼」与「白天」两档的 `dark` 同为 false，在这两档之间互切时副作用不重跑，系统栏会停在上一档的颜色。键改为配色对象 `c`，配色一变即刷新。（App.kt）

## 2.77 — versionCode 88

外观新增第四档主题「护眼」：暖米黄纸感底色 + 暖灰文字，压低蓝光与对比度，长时间看不刺眼。设置页外观从三档变四档（跟随系统 / 白天 / 夜间 / 护眼）。（App.kt、Screens.kt、Prefs.kt）

## 2.76 — versionCode 87

下线「拉取历史对话」功能（历史对话太多）。

1. **去掉抽屉里的「拉取」按钮**及拉取结果提示行。
2. **去掉手动拉取 `pullFromServer()`**，并删除自动补行逻辑（原来本地列表为空时会补最近 7 天的会话）。此后 `syncFromServer` 只做一件事：把服务端生成的好标题覆盖到本地已有行上，**绝不往列表里增行、也不清理**。
3. **一次性清理历史空壳行**：上一版灌进来、本地无聊天记录且标题不是「新对话」的会话行会被移除（只动本地索引，不删消息文件，服务端数据保留）。靠 `prefs.shellCleanupDone` 保证只跑一次。

（App.kt、ChatViewModel.kt、Prefs.kt）

## 2.75 — versionCode 86

发布方式变更：改发 **release 包**（原来一直发 debug 包）。

1. **release 开启 R8 代码压缩 + 资源裁剪**：`app/build.gradle.kts` 的 release 从 `isMinifyEnabled = false` 改为 `true` + `isShrinkResources = true`，并启用 `proguard-rules.pro`。debug 包不做压缩/裁剪，方法数与体积都偏大，启动与滚动都吃这个亏。实测包体从 16,294,063 字节（debug）降到 1,576,296 字节，签同一份 debug 证书，可直接覆盖安装。
2. 新增 `app/proguard-rules.pro`：保住清单入口（MainActivity / RunService / ReplyReceiver / HermesApplication）、okhttp、coil 与 kotlin 元数据；**保留行号信息**（`-keepattributes SourceFile,LineNumberTable`），崩溃日志仍能定位到行。

（app/build.gradle.kts、app/proguard-rules.pro）

## 2.74 — versionCode 85

两项改进：

1. **语音播报可调语速**：设置页「完成语音播报」下方新增语速档位（0.75× / 正常 / 1.25× / 1.5×，平铺按钮，无下拉）。用 MediaPlayer 的 `PlaybackParams.setSpeed` 在播放层变速，不改语音文件；选择存 SharedPreferences，重启后仍生效，下次播报即用新速度。
2. **打开 App 不再卡顿**：长会话（几百条）打开或切会话时，原来对消息列表用 `animateScrollToItem`，会从第 0 项逐帧动画滚到末尾，明显卡。改为：仅「同一会话末尾追加一条」才平滑动画，其余（首次加载、切会话、批量合并）一律 `scrollToItem` 瞬间到底。

（Screens.kt、VoicePlayer.kt、Prefs.kt、ChatViewModel.kt、App.kt、app/build.gradle.kts）

## 2.73 — versionCode 84

一次收干净三处缺陷（都是审出来的）。

1. 审批/澄清回执改成「发成功才标已选择」：原来点一下先把卡片标成已选择、才去发请求，回执没送到时界面已无入口，提示里的「可重试」点不了。现在先发、成功才定下来，失败保持按钮可点。
2. 附件「分享」失败不再静默：落盘失败或手机没有可分享应用时给一句 Toast，与「保存」一致。
3. 子任务进度（delegate_task 派出的子代理）纳入落盘：长任务跑一半重开 App，进度行不再丢。

纯客户端改动。

## 2.72 — versionCode 83

审批 / 澄清回执失败不再静默吞掉。

原来点「允许一次」或某个澄清选项时，回执 POST 用 runCatching 包住、不看返回也不给界面反馈——如果这次没送到（断线、本轮已收尾、run 没了），界面照样把卡片标成「已选择」，用户以为送达了其实没有。现在两处回执都返回布尔值：成功/失败都写进气泡上方的提示行，失败显示「回执没送到：本轮可能已收尾，可重试」（与「插话」同一写法）。

纯客户端改动，不碰服务端。

## 2.71 — versionCode 82

收到的附件（文件卡片）新增「分享」「保存」两个动作。

原来附件卡片只有一个动作——点开（拉起系统应用打开）。想把 App 收到的文件转发到微信、QQ、邮件时没有出口，只能干看着。现在每张附件卡片右侧多了两个小图标：

- **分享**：落盘 → 换成 content:// 地址 → 拉起系统分享面板（微信、QQ、邮件都在里面）。
- **保存**：存进系统「下载」目录的 `Download/Hermes/`，安卓 10 以上走 MediaStore 不需要存储权限；安卓 9 及以下写应用外部目录后扫描入册。

网关托管的大文件（`hermes-media://` 那种）也能分享/保存：点图标时才按需下载，内联附件则直接用本地字节。图片不受影响，它走的是全屏查看 + 保存到相册那条路。

## 2.70 — versionCode 81


耗时改成以服务端为准，不再由 App 自己掐表。

原来的耗时是 App 从「按下发送」到「收到回复」的墙钟时间，把 App↔服务端的网络往返、服务端排队、断线重连的等待全算进去了，所以偏大、还不稳定（同一件事两次能差出十几秒）。参考两个热门上游客户端（Hy4ri/hermes-mobile、rusty4444/hermes-android）的做法——它们都不在客户端掐表，耗时一律读服务端下发的权威字段。

- 服务端在回复收尾时把本轮真实执行耗时随 usage 下发（它本来就算好了，只是没往外发）。
- App 收到后优先用这个值；服务端没给（老服务端）才回落到原来的本地掐表。
- 任务跑的时候那行实时跳动不变，回复到达后换成服务端权威值。

注意：服务端那一半要重启网关才生效，否则 App 拿不到新字段、自动回落本地掐表（不会出错，只是数字还是旧的算法）。

## 2.69 — versionCode 80

收紧 2.68 的历史列表回填，并加一个手动拉取入口。

2.68 每次启动/回前台都往列表里补服务端会话，实测补进 65 条，其中大量是测试会话（语音测试、只回复两字、友好问候）和四五十天前的老微信会话，列表一下变得很长。本版改成：

- 平时（本地列表非空）不再自动补行，一条都不灌；仅同步已有会话的标题。
- 只有本地列表整个为空（重装 / 清应用数据 / 换机导致索引丢失）才自动补，且只补最近 7 天、消息数 ≥6 的。
- 列表顶部加一个「拉取」按钮：平时不打扰，想看微信端 / 别的端建的会话时点一下才合并（不限时间，但只收有实际对话的）。
- 一次性清理：把 2.68 已灌进列表、又不符合新标准（无本地聊天记录的空壳行）删掉。

纯客户端。

## 2.68 — versionCode 79

修复 App「历史对话」列表整个变空后无法恢复，并让微信端、CLI 端建的会话也出现在列表里。

- 启动 / 切身份 / 回前台时从服务端拉一次会话列表，把本地缺失的会话补进本地索引。以前本地索引一被清（重装、清应用数据、换机）列表就整个空掉，服务端记录还在却回不来；现在能自动恢复。
- 只补「你真正聊过」的来源（App、微信、CLI）。定时任务、子智能体等机器会话不进历史列表。
- 只增不删：本地已有的会话（含还没落到服务端的新对话）一律保留；已有的只更新标题，不动排序。
- 纯客户端。

## 2.67 — versionCode 78

修复耗时统计在气泡被重建时漂移：本轮计时起点以前锚在助手气泡自己身上，一旦气泡重建（插话、澄清卡片、续接等）起点就被重设，实时计时和最终耗时看着忽大忽小、对不上。现在统一锚到整轮唯一的 `r.startedAt`（用户按下发送那一刻），气泡怎么重建都不再漂；界面实时计时与跑完的最终耗时仍同源。纯客户端。

## 2.66 — versionCode 77

设置页重新排版，按用途分五区（新增 `SectionTitle` 分组小标题）：

1. **服务器** —— 地址输入框 + 保存（保存改小按钮，不再占整行）
2. **通知与语音** —— 后台运行 / 其它会话完成也提醒 / 完成语音播报，三个开关聚在一起
3. **版本更新** —— 检查更新 + 进度 + 当前版本
4. **存储** —— 清理缓存
5. **排查诊断（默认折叠）** —— 运行日志 / 上次闪退记录 / 服务故障记录

原来的问题是三个开关被压在 260dp 高的运行日志下面、每次要滑半天；排障内容夹在中间割裂。折叠区展开时才起日志刷新循环（收起停掉，反而省电）。功能一个不少，只是归位。纯客户端。

## 2.65 — versionCode 76

修复更新弹窗里版本说明空白。发布文件里说明文字的键名历史上出现过两种（`notes` / `changelog`），App 以前只认 `notes`，最近两版被写成了 `changelog` 就读不到，弹窗只剩安装包大小。现在 App 两个键名都认（`notes` 优先），发布脚本也统一写 `notes`。纯客户端。

## 2.64 — versionCode 75

修复耗时统计不准：最终耗时改用该助手气泡自己的 `startedAt`（与界面实时计时同源），并去掉 `startRunWith` 里拿到 run_id 后重复设置的计时起点。以前「建 run + 上传附件」的网络往返被整段抹掉，显示 1.6~1.7 分时实际已 2 分。

## 2.63 — versionCode 74

- 插话改为在聊天界面直接显示：点「插话」后立刻插一条自己的气泡，左上角带「插话」小标，不再只塞进默认折叠的「过程」里（以前用户以为没发出去）。
- 插话气泡随会话落盘，重开 App / 切会话回来仍在。

## 2.62 — versionCode 73

修复中途插话「插不进去」，以及退出重进后耗时重新计时。

- 插话现在有明确反馈：服务端接受（200）就在气泡上方显示「插话已送达，本轮会读到」；
  被拒（如本轮已收尾）显示「插话没送达：本轮可能已收尾，这句话没赶上」——原来返回被静默吞掉，
  成功失败都看不到，才以为插不进去。
- 插话没赶上本轮时：服务端随结果下发的未送达插话会放回输入框并提示，点发送即可重发，不再静默丢。
- 耗时计时起点（startedAt）随消息落盘：App 退出重进、切会话回来，进行中的耗时都接着原起点算，
  不再从头开始。
- 纯客户端改动，不碰服务端。
（SessionStore.kt、net/HermesApi.kt、ChatViewModel.kt、app/build.gradle.kts）

## 2.61 — versionCode 72

耗时显示单位自适应。

- 1 分钟以内仍按秒显示（如「耗时 45.2s」）；
- 到 1 分钟起改按分钟显示（如「耗时 1.5分」「耗时 3.3分」）。
- 进行中的实时计时和跑完的最终耗时两处都改。
- 纯客户端改动，不碰服务端。
（Screens.kt、app/build.gradle.kts）

## 2.60 — versionCode 71

耗时改为任务执行中实时统计。

- 待回复的气泡一出现就开始计时，界面上每秒跳动显示「耗时 N.Ns」，不用等任务跑完才知道用了多久。
- 回复到达后停止跳动，最终耗时仍由原来那行用量显示（入/出/共 + tok/s）。
- 气泡中途断开续接、重发复用同一条时，计时不重置也不清零；卡片（审批/澄清）挂起期间同样在计时。
- 纯客户端改动，不碰服务端。
（ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.59 — versionCode 70

修复 token 用量与耗时在重开 App / 切会话后消失，并补上「耗时」显示。

- 根因一：`SessionStore` 从不落盘 usage，重开 App 或从服务端刷新历史就丢；
  现在 usage 随消息一起落盘、读回时还原。
- 根因二：`refreshFromServer` 的服务端消息没有 usage 字段（服务端只存正文），
  合并时不能覆盖本地已带回填的 usage；已加守卫保留本地那份。
- 耗时列改为直接显示秒数（如「耗时 12.3s」），速度仍按耗时算 tok/s。
- 纯客户端改动，不碰服务端。
（SessionStore.kt、ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.58 — versionCode 69

语音播报图标改为与时间并排。

- 迷你播放图标不再单独占一行，改到气泡底部时间行里，紧挨发送时间显示。
- 图标缩小到 16dp 纯图标（原来是 30dp 圆点），空闲是播放三角、正在播时变停止方块。
- 音频附件本身不再进正文块渲染，正文里不会再看到多余的占位行。
- 自动播报行为不变。纯客户端改动，不碰服务端。
（VoicePlayer.kt、Markdown.kt、Screens.kt、app/build.gradle.kts）

## 2.57 — versionCode 68

语音播报改成简约迷你图标。

- 之前的胶囊按钮「▶ 播放语音 / ■ 停止播放」太占地方，改成 30dp 的小圆点图标：
  空闲时是一个淡淡的播放三角，正在播时变成强调色的停止方块。
- 文件名不显示，点一下播、再点一下停，播完可反复重播；自动播报行为不变。
- 纯客户端改动，不碰服务端。
（Markdown.kt、app/build.gradle.kts）

## 2.56 — versionCode 67

完成语音不再显示成文件附件，改成播放按钮。

- 任务跑完那条语音，以前在气泡里是一张 `[📎 tts_reply_xxx.mp3]` 文件卡片（像要你下载个文件）。现在是一个「▶ 播放语音」按钮，点一下重播，正在播时变成「■ 停止播放」。
- 自动播报行为不变：开关开着时跑完仍自动念一遍；按钮是额外给你的重播入口，关掉开关也能点。
- 导出会话时这类音频标成「（语音）」，不再笼统写「（附件）」。
（VoicePlayer.kt、Markdown.kt、SessionStore.kt、app/build.gradle.kts）

## 2.55 — versionCode 66

定时任务页的翻译改成「精确表 + 关键词兜底」。

- 原来中文对照表只写了自己这边的任务名，另一个机器人（friend）的两个任务名不在表里，于是回落显示英文。现在任务名里带 memory-refactor 就翻「记忆整理」、带 watchdog 翻「看门狗」、带 watch 翻「上游巡检」、带 reaper 翻「空闲回收」——朋友的、以后新加的、两边任意档案的任务都能自动出中文名与说明，不必每加一个任务改一次代码。
- 排期补了对 cron 表达式的识别（如 0 9 * * * → 「每天 09:00」），不再依赖服务端那句英文 display（every day at 9am）。
（ChatViewModel.kt、app/build.gradle.kts）

## 2.54 — versionCode 65

定时任务页改中文。

- 任务名、说明、排期、状态全部显示中文：如「夜间记忆整理」「浏览器空闲回收」「记忆库看门狗」；
  排期由 cron 表达式转成「每天 03:30」「每 15 分钟」这类可读中文。
- 每条任务加一行说明，讲清它是干什么的（如「每 15 分钟检查记忆库：服务掉了就拉起」）。
- 保留原始英文标识（一行小字），方便对照排查；认不出的任务仍显示原始名，不会消失。
- 纯客户端改动，不碰服务端。
（ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.53 — versionCode 64

新增「定时任务」页。

- 抽屉导航新增「任务」入口，列出服务端 `/api/jobs` 的定时任务：名称、排期、上次运行结果、下次运行时间。
- 每条任务给「暂停 / 恢复」「立即执行」两个按钮，可直接在手机上管自己的定时任务。
- 默认只列启用的任务，顶部可切「显示已停用」；两个机器人各用各的密钥，服务端按档案隔离，只看到自己的任务。
- 纯客户端改动，不碰服务端、不重启网关；接口失败只提示，不影响其它页面。
（ChatViewModel.kt、Screens.kt、App.kt、net/HermesApi.kt、app/build.gradle.kts）

## 2.52 — versionCode 63

- **会话导出为 Markdown 并分享**：会话列表的「⋯」菜单新增「导出为 Markdown」——把整段对话（角色、北京时间、正文）写成一个 .md 文件，直接调系统分享面板发出去（发同事、存网盘、导入笔记都行）。只读本地记录，不碰服务端、不改会话内容。
  - 工具轨迹用可折叠块包起来，导出后想细看还能展开；正文里的内联图片/附件会换成一行占位（base64 塞进 md 没意义）。
  - 文件名取会话标题 + 导出时间，落在 App 私有 `exports/` 目录（FileProvider 已声明）。失败只记日志，不打扰对话。
  - （SessionStore.kt、ChatViewModel.kt、App.kt、res/xml/file_paths.xml、app/build.gradle.kts）

## 2.51 — versionCode 62

「完成语音播报」默认改为开启 + 补全另一个机器人的语音配置。

- 设置页开关默认由「关」改为「开」：装完即生效，不必先去设置页点一下。想静音仍可在设置页关掉。
- 服务端侧：给 friend（朋友的机器人）profile 补上 `tts` / `voice` 两段配置，与 default 对齐（中文晓晓音色 + 代理 + `api_server_tts: true`），两边行为一致。
（Prefs.kt；服务端配置不随包发布）

## 2.50 — versionCode 61

任务完成自动语音播报（整段回复念出来）。

- 设置页新增「任务完成后语音播报」开关（默认关）。打开后，一轮任务跑完会自动播放服务端合成的整段语音。
- 播放用安卓自带播放器（`VoicePlayer.kt`，MediaPlayer），不引第三方库、不加权限；只在播的那几秒占用内存。
- 语音随回复作为附件卡片下发（服务端 run 收尾时合成、走现成的网关托管媒体通道），App 收到即自动播一次；
  手动点卡片仍可重听。播报失败只影响声音，不影响消息本身。

## 2.48 — versionCode 59
两项纯客户端改进（对标上游 hermes-relay 的「排队消息可撤回/编辑」与「中途插话」）：
- **排队消息可撤回 / 编辑**：跑任务期间发的下一条会进队列、本轮结束自动发，此前入队后没有任何入口能把它收回来。现在点那条消息的「⋯」标（提示改为「点这里可撤回或编辑」）即可「撤回」或「编辑」；编辑把正文回填到输入框，改完重发（附件不回填，需重新选）。
- **停止会按住待发队列**：原来点「停止」只停当前这轮，排队的那条照样接着发，容易误以为全停了。现在停止会把待发队列一起按住，输入栏出现「继续」按钮，点它才接着发；无待发队列时行为不变。
- **新增「中途插话」**：任务在跑时输入栏除「排队」「停止」外新增「插话」——把这句话立刻注入本轮（走 `/v1/runs/{id}/steer`，仅 running 状态接受），用于中途纠正方向；与「排队」（下一轮才发）分工明确。
（ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.49 — versionCode 60
- **会话标题同步服务端正式标题**：本地标题只在「首条消息发出前」生成（首句截 20 字），重开 App 先拉回服务端消息、排队发送、别的端建的会话都会漏，标题永远停在「新对话」。现在从服务端 `/api/sessions` 把每个会话的正式标题（小模型生成的 3~7 词）同步回本地索引，启动 / 回前台 / 每轮结束各一次（带节流），历史会话一并修好。只改 title 字段，不动 updatedAt、不增删行。
（ChatViewModel.kt、net/HermesApi.kt、app/build.gradle.kts）

## 2.47 — versionCode 58
侧边栏把「状态」「设置」两个按钮从顶部移到底部（「外观」上方）。
（App.kt、app/build.gradle.kts）

## 2.46 — versionCode 57
删除会话前加二次确认：
- 原来会话列表点 ⋯ → 删除，点下去立刻删——本地记录（`chat_<profile>_<id>.json`）一并清掉，
  手一滑就没了，且不可恢复。归档是可逆的，删除不是，两者混在同一个下拉菜单里更容易误触。
- 现在点「删除」先弹确认框（「删除这个对话？本地记录会一并清掉，删了就找不回来了。」
  + 取消 / 删除），确认后才真删。
（App.kt、app/build.gradle.kts）

## 2.45 — versionCode 56
两项纯客户端改进（对标上游 #1491/#1494 与 #1401）：
- **修「长会话被服务端压缩后，翻历史错位/重复」**：服务端压缩会删/合并老记录、重发行 id，
  原来按「第几条用户消息」对齐两边记录，从删点起整段错位（回复贴到错误气泡、用户消息重复）。
  改成按「用户消息正文归一 + 时间接近（2 分钟窗口）」做顺序匹配：服务端被删的那条匹配不上就
  跳过，不影响其后各块；服务端独有的块（别的端发的轮次）按原顺序插回正确位置。
- **「其它会话完成也提醒」开关**：原来只在 App 退到后台时弹完成通知；开着 App 看别的会话时
  那边跑完不响。新增开关（在「后台运行」下方），打开后别的会话跑完也弹通知；当前会话不重复
  提醒（内容就在屏幕上）。因需一条活连接才能观察到别的会话收尾，打开它自动启用后台运行。

# 变更记录

## 2.44 — versionCode 55
修「重开 App 后，服务端还在等你点头的卡片（审批/澄清）会消失」——对齐上游 #1488/#1489：
- **断流恢复漏判「等你选一下」**：`RUNNING_STATES` 收了 `waiting_for_approval` 却漏了
  `waiting_for_clarify`。服务端在等澄清时断流，退避探测拿到该状态会被判成「任务已结束」，
  卡片挂不上、流也不再续接。
- **探测只读状态字符串、丢掉卡片载荷**：服务端 run 状态里本来就带着 `clarify` / `approval`
  事件（`_set_run_status(..., clarify=event)`），`probeRun` 只取了 `status` 就把它扔了。
  现在把整个状态对象带回，重开 App / 续接前据它把卡片重新挂回去。
- **待办卡片没落盘**：`SessionStore` 只存正文与工具轨迹，而待办卡片气泡正文常是空的——
  落盘丢弃条件（以及读回条件）会把它整条滤掉。现在卡片一并存/读，且「空正文+空轨迹+有卡片」
  的消息不再被丢。
- **卡片挂载位置保守化**：恢复时优先挂当前进行中的助手气泡，没有就挂最后一条助手消息，
  都没有才新建；同一 id 已挂且未作废的不重复挂。
- **过期卡片会清掉**：探测到 run 已结束 / 不存在，或本轮正常收尾（doneOk / failPending）时，
  清掉未作废的卡片（用户已点过的保留，留「已选择：…」痕迹），避免重启后读回的旧卡片赖着不走。
（ChatViewModel.kt、SessionStore.kt、net/HermesApi.kt、app/build.gradle.kts）

## 2.43 — versionCode 54
修「内置检查更新下载完，系统提示已安装相同版本」（手动从网址下载却能装上）：
- **旧包没被清掉**：内置下载固定写死一个名字 hermes-update.apk，而且装完不删。
  某次下载写到一半失败（EdgeOne 把连接掐了），那个残缺/旧的文件还在原地，
  点安装时安装器打开的就是它 —— 版本号不高于手机已装的，系统就提示「已安装相同版本」。
  现在按版本号命名（hermes-2.43.apk）、先写 .part 整段下完再改名，
  拉起安装器前把该目录里其它包全删掉，安装器只可能拿到刚下完的这一份。
- **加指纹校验**：下载完按 version.json 里登记的 md5 与大小核对，对不上直接丢弃并报
  「下载失败（校验没过）」，绝不把残包交给安装器。
- **检查更新加防缓存**：请求拼一个时间戳，绕开 EdgeOne 边缘缓存，免得拿到旧版本号/旧包地址。
- **留痕**：下载开始（期望大小/md5）、大小不符、md5 不符、拉起安装器，都写进运行日志，
  下次直接在设置页「运行日志」看卡在哪一跳。
（Keys.kt、net/HermesApi.kt、ChatViewModel.kt、app/build.gradle.kts）

## 2.42 — versionCode 53
设置页「当前版本」补上版本名：
- 原来只显示一个数字（版本号 52），看不出是哪个发布版本。现在显示「当前版本 2.42（53）」，版本名与版本号并列，与更新弹窗、version.json 里的版本号能一眼对上。
（Screens.kt、app/build.gradle.kts、docs/CHANGELOG.md）

## 2.41 — versionCode 52
修断流重连的四处并发/留痕缺陷（由一次真实日志暴露，收发消息本身没坏）：
- **探测循环被起了多个**：防重入用的是普通布尔，onProfileChanged 并发调用时
  两个线程都把自己当第一个，于是并存多个 5 秒轮询循环。症状是同一条「恢复在线」
  一次打好几遍、离线判定挤在同一毫秒、探测请求成倍。改用原子开关。
- **退避链静默退出**：退避到点后发现本轮已被别的路径收尾，原来一声不吭地 return，
  日志里只剩「第 N 次重试」没有「探测结果」，排查时被误读成重试卡死。现在补一行
  「退避作废」。
- **同一会话多条退避链并发探测**：onClosed / onError / 回前台体检都调 maybeContinue，
  各起一条链就会重复探测、重复加计数，严重时一边判「还在跑」一边判「已结束」。
  加单飞位，同一会话同时只允许一条退避链在等。
- **运行日志会丢行**：内存队列与文件追加没放同一把锁，多线程同时 appendText/trim
  会互相截断，正好丢掉排查断流最需要的那几行。现在整段进锁。
（ChatViewModel.kt、AppLog.kt、app/build.gradle.kts）

## 2.40 — versionCode 51
常驻通知改成真常驻（原来只在「有任务在跑」期间挂，跑完即撤）：
- 判据从「有任务才起前台服务、全部结束就停」改成「只要设置页『后台运行』开关开着，
  就一直保持前台服务与一条静默常驻通知」，跟有没有任务在跑无关；关掉开关才停、通知消失。
- 进主界面（MainScaffold 的 LaunchedEffect）补一次 `ensureRunService()`：开关开着但还没发过
  消息时也把常驻通知挂上。原来要等第一次发消息或回前台才起，期间切后台状态栏一条都不剩。
- 通知标题「Hermes」→「Hermes 在线」。
- 设置页「后台运行」说明文字改准（原来写「任务期间保持连接」）。
- 注：compileSdk=34 不支持 `Service.onTimeout`（API 35 才有），`dataSync` 的 6 小时/24 小时
  上限也只在 targetSdk≥35 才强制，本版 targetSdk=34 不受影响，故未加超时兜底。
  开机自启做不到（安卓禁止后台起前台服务），需手动打开一次 App 才会挂上。
（RunService.kt、ChatViewModel.kt、App.kt、Screens.kt、app/build.gradle.kts）

## 2.39 — versionCode 50
修「点通知栏常驻条目没反应」：
- 那条常驻条目是任务运行期间的前台服务通知（RunService，通知 id 1001，标题就一个
  「Hermes」）。它建通知时只设了标题与图标，**漏挂 contentIntent** —— 安卓里通知要点得动
  必须挂 PendingIntent 指明拉起谁，没挂就是点了完全没反应。
- 另两条通知（新消息 2001、审批/澄清 2002）都挂了 contentIntent，所以只有这条点不动。
- 改法：补上指向 MainActivity 的 PendingIntent（NEW_TASK + CLEAR_TOP，requestCode=3
  与另两条区分，避免互相覆盖）。点它即把 App 拉到前台。
（RunService.kt、app/build.gradle.kts）

## 2.38 — versionCode 49
新增**运行日志**，用来定位「一直重连、连不上」卡在哪一跳：
- 动机：报「连不上」时界面上只有一行 retryNote，看不出是 DNS 解析失败、TLS 握手失败、
  服务端 4xx/5xx、探测超时，还是 run 其实还在跑而流被系统掐了。手机没法抓包，只能让
  App 自己把每一跳的结果留痕。
- 落点：`AppLog`（filesDir/run.log，内存保 400 行、落盘保 2000 行，超长自动裁剪）。
  打点位置：发送建 run（含是否重放、附件数）、发送失败（区分服务端拒绝/网络中断）、
  起流（lastSeq + 第几次续接）、流关闭 / 流出错、重连退避（第几次 + 等多久）、
  状态探测结果（Known/Missing/Unknown）、run 完成 / 失败、恢复探测、在线状态翻转。
- 入口：设置页「运行日志」——每 2 秒自动刷新最近 200 行，可「复制全文」发出来。
- 保留崩溃留痕（上次闪退记录）与服务故障记录两处不动。
（新增 AppLog.kt；App.kt、ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.37 — versionCode 48
三项纯客户端改进（对标上游 v2.3.0 的三处可靠性修复）：
- **「不确定」可确认忽略**：投递状态卡在黄问号时，原来只有「确认送达/重新发送」两个出口，
  想收掉角标就只能选重发，等于赌会不会把同一句话发两遍。新增 acked 状态与「知道了」按钮：
  点一下收起角标与提示，不动网络、不重发；重启后仍保持收起。
- **输入框支持粘贴图片**：复制一张图后长按输入框→粘贴，或输入法自带贴图（如 Gboard），
  都能直接进待发附件（走系统剪贴板 paste 分支 + InputConnection.commitContent 两条路，
  并在 onCreateInputConnection 里声明 contentMimeTypes=image/* 让输入法亮出贴图入口）。
- **图片解码失败给重试出口 + 诊断信息**：内联图解码失败原来只显示一行「[图片解析失败]」，
  现在显示失败卡片 +「点此重试」（真的重新解码）+ 数据字节数，方便判断是数据坏了还是没解出来。
（ChatViewModel.kt、Screens.kt、Markdown.kt、app/build.gradle.kts）

## 2.36 — versionCode 47
用户消息的投递状态标记与发送时间并排：
- 原来「✓/◌/⋯/?/!」状态一行、时间「HH:mm」又一行，各占一行还各带 4dp 间距，短消息下显得空。
- 改法：两者合到同一个 Row，顺序为「状态标记 + 时间」，仅在有状态或有时间时显示该行；
  排队/不确定/失败的文字说明接在时间后面（单行省略），保持告警可点。
（Screens.kt、app/build.gradle.kts）

## 2.35 — versionCode 46
修「软键盘收不起来」——把收键盘从 Compose 层挪到 Activity 触摸分发层：
- 根因一：输入框是原生 EditText（AndroidView 承载），View 体系「点外部不会失焦」，
  键盘自然不收。
- 根因二（关键）：消息气泡自己带 combinedClickable，点击被气泡先消费，父级 Compose 的
  detectTapGestures 根本收不到 → 点气泡（屏幕绝大部分区域）收不起键盘。
- 改法：MainActivity 重写 dispatchTouchEvent，在 ACTION_DOWN 阶段（任何子 View/Compose
  消费之前）取当前焦点控件，若它是 EditText 且落点不在其可见矩形内，就 clearFocus +
  hideSoftInputFromWindow。一次覆盖：点空白、点气泡、顶栏、抽屉、切 tab、切会话。
- 保留 Compose 侧原逻辑做兜底（两者不冲突；EditText 已失焦时 dispatch 分支不触发）。
（App.kt、app/build.gradle.kts）

## 2.34 — versionCode 45
用幂等键根治「发送结果不确定」——重发不可能再变成发两遍：
- 根因：POST /v1/runs 中途断网时收不到回执，客户端分不清「服务端收了没」。原实现
  只能提示「不确定，可能已收下」，让用户自己赌一把：不重发怕丢，重发怕发两遍。
- 服务端早就支持：请求带 Idempotency-Key 头时，服务端把 (作用域, 键) 写进
  runs_idempotency.db，24 小时内同一个键只真正执行一次；重复提交不重跑，把原来
  那轮的 run_id 原样还回来（响应头 Idempotency-Replayed: true）。请求体不同但键相同
  报 409 idempotency_key_conflict。
- 改法：每条用户消息发送前生成一个幂等键，随请求发出并落进回执（连附件 artifact id
  一起落盘）。重发复用同一键与同一份附件 id——服务端算指纹含请求体，附件 id 变了
  指纹就变了会被判冲突，所以必须原样复用。
- 收益一：网络中断时不再让用户赌，改为凭幂等键自动安全重试一次（首次其实已收下就
  接上原来那轮，没收下就是全新执行一次，绝不发两遍）；重试用尽仍失败才标「不确定」，
  此时手动重发同样安全。
- 收益二：手动「重新发送」不再有重复风险，且能直接接上原来那轮的产出。
（net/HermesApi.kt、ChatViewModel.kt、SessionStore.kt、app/build.gradle.kts）

## 2.33 — versionCode 44
修「后台任务跑完、重开 App 拉记录时回复贴错气泡」——合并按数组下标对齐：
- 根因：refreshFromServer 的合并是「本地第 i 条对服务端第 i 条」，但两边长度天然
  不等——服务端接口会滤掉「内容为空的助手行」与工具行，本地却保留带工具轨迹的空
  助手行（实测某会话差 236 行）。下标从第一处差异起整体错位，服务端的回复可能贴到
  错误的气泡上，或干脆不显示。
- 改法：改用两边都完整保留且有序的「用户消息」当锚点分段合并。段内本地原样保留
  （含 trace / 图片 / 回执 / 引用），仅当本地该条助手正文为空而服务端同段有内容时
  用服务端正文补齐（保留本地 trace）；本地没有的段整段取服务端。
- 对拍验证：本地「空助手行 + 服务端已补第三轮」的真实形态下，旧实现在同一数据上
  确实丢了第三轮答复（错位实锤），新实现三轮答复各归各位、trace 保留。
（ChatViewModel.kt、app/build.gradle.kts）
## 2.32 — versionCode 43
修「重开 App 后同一轮的『过程』重复显示」——续接序号没落盘，恢复时从 0 全量重放：
- 根因：重开 App 恢复进行中的任务时，续接序号 `lastSeq` 被重置成 -1，于是向服务端
  请求事件流时带上 `Last-Event-ID: -1` → 服务端从 seq 0 全量重放。重放出来的
  `tool.completed` 事件又被追加进工具轨迹（trace 是累加），同一轮的过程整段重复；
  正文因为按覆盖写所以看不出重复，只有「过程」露馅。
- 改法：把续接序号与消息一起落盘（`saveRuntime` 里写 `seq:<session>`），重开时按它
  续接，只补断线之后的事件；新 run 开始时重置该序号；run 结束清理时一并清掉。
- 配套修一处气泡归属：本地消息读回后 `pending` 一律是 false，恢复时必须把这一轮的
  助手气泡重新标成「进行中」，否则续接的增量会另起一条气泡、同一轮回复显示两次。
  判据用续接序号区分——断线前收到过事件（lastSeq>=0）就复用本地气泡，否则（本轮
  还一个字都没收到）新起空气泡，避免把新内容挂到上一轮的回复上。
（Prefs.kt、ChatViewModel.kt、app/build.gradle.kts）

## 2.31 — versionCode 42
修「前台服务启动超时」崩溃（ForegroundServiceDidNotStartInTimeException）：
- 根因：RunService.onStartCommand 里 `runCatching { startForeground(...) }` 把异常
  静默吞掉——startForeground 若失败（Android 12+ 从后台拉起前台服务、通知权限被禁、
  类型不符），进程既不退也不停，系统看到「喊了转前台却一直没转」，约 10 秒后判违约
  直接杀进程。机型 realme RMX3888 / Android 16 上必现。
- 改法三条：① startForeground 显式传前台服务类型 FOREGROUND_SERVICE_TYPE_DATA_SYNC，
  与清单一致；② 失败不再吞异常，改为 CrashLog.recordFault 留痕 + stopSelf 干净收场，
  从「崩溃」降级为「安静降级」；③ 返回 START_NOT_STICKY，避免进程被杀后系统在后台
  把服务拉回来、正好撞上「后台不许起前台服务」的限制而再次崩溃。
- 配套：调用方 updateRunService 仅在 App 处于前台时启动服务（Android 12+ 限制），
  回到前台由 onAppForeground 补启，任务在服务端照跑不受影响。
- 新增：设置页「服务故障记录」，把被 catch 住、进程不会死的非致命故障单独留一份
  （crash/service_fault.txt），可复制全文。
（RunService.kt、CrashLog.kt、ChatViewModel.kt、Screens.kt、app/build.gradle.kts）

## 2.30 — versionCode 41
修「长时间跑工具时一直重连、连不上」——心跳帧没被算作「流还活着」：
- 根因：服务端在两次事件之间每 10 秒必发一个 `: keepalive` 注释帧，但 SSE 解析器
  只认 `id:`/`event:`/`data:` 三种行、空行才派发事件，`:` 开头的注释帧被直接跳过，
  不触发 onEvent，上层的「最近活动时间」不刷新。
- 后果：任务长时间只跑工具（如终端命令一跑 180 秒）时，这期间服务端只在发心跳、
  没有真实事件；而「回到前台」的看门狗判据是「超过 25 秒没收到任何事件即认定流假死」，
  于是每次退出软件重新打开、onResume 一触发，就把一条本来健康的流当假死掐掉去重连，
  界面就一直停在「连接中断，N 秒后重试」。
- 改法：解析器新增 onActivity 回调，每读到任何一行（含心跳注释帧）就回调一次，
  上层据此刷新最近活动时间。心跳现在算「活着」，健康流不再被误判。
（net/HermesApi.kt、ChatViewModel.kt、app/build.gradle.kts）

## 2.29 — versionCode 40
修「只发图片/文件不打字，点发送报 HTTP 400」：
- 服务端对 input 有非空校验，而且是先查 input、后处理附件——正文为空时请求直接被
  400 挡回（Missing 'input' field），附件白选。现在只发附件时，发往服务端的正文补一个
  占位词（图片 / 文件 / 图片和文件），气泡里仍然一个字都不显示；图片本身走原生多模态
  附件，模型照样看得到图。引用与投递回执的位置锚点也用这一版正文，重发 / 确认送达才和
  服务端记录对得上。
- 修附件消息重开后消失：本地落盘与读回原先只认正文和工具轨迹，且没存非图片附件的
  文件名。于是「只发了图、没打字」的消息（正文为空）在重开 App 后被整条丢掉，附件卡片
  也不落盘。现在只要还有正文 / 轨迹 / 图片 / 附件就留住，非图片附件名一并落盘。
（ChatViewModel.kt、SessionStore.kt、app/build.gradle.kts）

## 2.28 — versionCode 39
修「断线后一直重连、连不上」——重连判据把「不知道」当成了「已结束」：
- 根因：退避到点后先探一次 run 状态决定「续接」还是「收尾」，但探测失败（网络没通、
  隧道未就绪）时旧代码把状态取成空串，空串不在「还在跑」集合里 → 直接判任务结束、
  关掉气泡、清掉活跃标记。8 次退避形同虚设，第一次探测失败就彻底放弃。
- 现在把状态探测结果分成三态：服务端明确回答（按状态续接或收尾）／明确说没有这个 run
  （404，判结束）／探不出来（网络未通，不知道 ≠ 已结束，继续退避重试）。
- 探测改用带硬超时的独立客户端：原来走 SSE 用的那个客户端读超时为 0（无限），
  隧道半死时会永久挂起；且 404 与网络异常抛成同一个异常，上层分不清。
- 重开 App 恢复活跃任务同改：开机网络常未就绪，探测失败不再删活跃标记，重试 3 次，
  仍探不通就保留标记、下次进前台再试，并拉一次服务端记录兜底。
- 历史域名静默迁移：服务器地址是登录时存进 prefs 的，就地升级不会改；域名一换
  （2026-10-06 .example-old.com → .example.com）老地址就成死链，正是「怎么都连不上」的成因。
  启动时自动把旧域名改写成当前域名，用户不用重新登录。
（ChatViewModel.kt、net/HermesApi.kt、app/build.gradle.kts）

## 2.27 — versionCode 38
修「带图片的消息发不出去」：
- 发图时会把图片从待发目录（outbox）挪进 App 私有的「已发送」目录（sent/，不参与清理、
  清缓存换机后仍在）。老实现只把「地址」改了，却把待发目录里的原文件删了，而随后上传
  仍然按老路径读字节 → 文件已经不存在，附件必然发送失败（表现为点了发送没反应 /
  回执转成「发送结果不确定」）。现在挪动后把新文件一路带到上传，附件只有一处真源。
- 引用条不再对可空状态做强制解包（!!）。委托属性不能被智能转换，老写法在状态变空的
  那一瞬间是空指针闪退点。

新增闪退留痕（为了能查清闪退）：
- 进程入口装上未捕获异常处理器，崩溃瞬间把堆栈（时间/线程/机型/系统/版本 + 完整堆栈）
  写进 App 私有目录 crash/last_crash.txt，并保留上一份 last_crash_prev.txt。
- 设置页新增「上次闪退记录」：显示堆栈，可「复制全文」直接发出、「清除」删掉。
  闪退是进程被直接杀掉，没有这个文件就只能靠猜。
（ChatViewModel.kt、Screens.kt、CrashLog.kt、App.kt、AndroidManifest.xml、app/build.gradle.kts）

## 2.26 — versionCode 37
敏感操作后台提醒：
- 任务在服务端停下来等你点头（命令审批 / 澄清提问），而 App 不在前台时，弹一条系统通知
  （「需要你确认」或「需要你选一下」，带待处理内容摘要）。原来只提醒「任务跑完」，任务中途
  卡在等确认时手机不响，你不知道它在等你。前台不弹——卡片就在屏幕上，再弹是骚扰。
- 点通知直接跳回那条会话，待处理的卡片就在眼前。会话 id 双保险：Intent extra（App 还活着）
  + 落盘 pendingOpenSession（App 被系统杀过，冷启动 extra 可能丢）；取到即清，回前台不重复跳。
- 用独立通知位（2002），不覆盖「新消息」那条。
（ChatViewModel.kt、Notifier.kt、Prefs.kt、App.kt、app/build.gradle.kts）

## 2.25 — versionCode 36
对话体验四项（参考 Codename-11/hermes-relay 的引用回复 / 富卡片 / 忙时排队 / 跨会话搜索，纯客户端，服务端零改动）：
- 引用回复：长按任意气泡弹菜单选「引用」，输入栏上方出现引用条（可点 × 取消）。
  发送时把被引片段压成一行「我：…」/「助手：…」拼在正文前（带 `> ` 前缀），模型据此知道在回应哪句；
  气泡内另起一块回显被引片段。片段落盘，重开仍在。
- 忙时排队：同一会话正在跑任务时不再把输入锁死——发送按钮变「排队」，消息先落进气泡（回执标
  「排队中，本轮结束后自动发送」），本轮一结束（正常完成 / 失败 / 手动停止 / 翻历史取回）自动按序发出。
  输入栏显示「排队 N 条」。排队态不落盘：重启后内存队列已丢，落盘会永远停在排队中。
- 跨会话搜索：抽屉顶部新增「搜索」，搜本地全部会话的正文与工具轨迹（去抖 200 毫秒、后台线程扫），
  结果显示「角色 · 会话标题 + 上下文片段 + 时间」；点一条跳到那个会话并定位到该条。
  原来 v2.23 只在当前会话内搜，跨会话不在。
- 富卡片：助手正文里独占一行的 `CARD:{json}` 渲染成结构化卡片——标题/副标题/正文/字段表/
  按钮行/页脚，左侧一条强调色竖条（info/success/warning/danger）。按钮动作三种：open_url
  （浏览器打开）、send_text（默认，当一条消息发出）、slash_command。JSON 坏了当普通文本，
  绝不吞正文；未知字段忽略，对方加字段不炸旧包。
（ChatViewModel.kt、Screens.kt、SessionStore.kt、Markdown.kt、App.kt、app/build.gradle.kts）

## 2.24 — versionCode 35
投递回执三项（参考 Hy4ri/hermes-mobile 的 WhatsApp 式投递勾 / 不确定回执确认 / 按身份对账）：
- 用户消息挂投递状态：发送中（灰圈）→ 已送达（单勾）→ 不确定（黄问号）或失败（红叹号）。
  状态绑在消息自己的 id 上，不靠位置；原来只有出没出 run_id 这一层，出错只能往助手气泡里塞
  一行「[请求失败]」，用户既看不懂也不知道该干嘛。
- 不确定消息给两个出口（点黄问号展开）：「确认送达」复用断流翻历史那套位置锚点+正文核对，
  在服务端记录里找到就转已送达并接着把答案等回来；找不到就转失败。「重新发送」拿原正文再 POST
  一次（图片能找回就一并带上）。不确定档绝不自作主张重发，避免同一句话发两遍。
- 明确失败（服务端返 4xx/5xx，有回执）直接标红叹号 + 显示原因，不再往气泡里塞文字；
  失败时顺手收掉那个空的助手占位气泡。
- 状态落盘：重开 App 后不确定/失败的消息仍能处置（发送中不落盘，避免重启后一直转圈）。
（ChatViewModel.kt、Screens.kt、SessionStore.kt、app/build.gradle.kts）

## 2.23 — versionCode 34
图片留存与查找三项（参考 Hy4ri/hermes-mobile 的 ReconciledImages / 图片失败重试 / ChatSearchDelegate）：
- 用户图可靠留存：发出去的图先落进 App 私有「已发送」目录（sent/）再进气泡。原来存的是相册给的
  content:// 地址，会被系统回收（换机/清数据/授权到期）；而备用副本放在 outbox，会被「清理缓存」
  一起删掉——两者都会让历史里的图变白框。sent/ 不参与清理，重开、清缓存后都还在。
- 图片加载三态：正文内联图与用户气泡里的图改成「加载中占位 / 失败可见可重试 / 成功渲染」。
  原来直接渲染，网络一抖或链接过期就是一片空白，分不清「在加载」还是「坏了」。本地图读不到字节时
  显示「图片不可用」小灰块，不再静默变白框。
- 会话内搜索：对话页顶栏新增「搜索」，展开后在当前会话的本地消息里搜（含正文与工具轨迹）。
  输入去抖 150 毫秒、后台线程匹配；显示「第几/共几」，上下箭头跳转并自动滚到该条；命中气泡加
  强调边框、命中词加黄底。只搜当前会话（本地上限 300 条），跨会话搜索不在此版。
- 新增文件 ChatSearch.kt。
（ChatViewModel.kt、ChatSearch.kt、Screens.kt、Markdown.kt、App.kt、app/build.gradle.kts）

## 2.22 — versionCode 33
断流兜底与流式观感两补（参考 Codename-11/hermes-relay 的 ChatStreamRecovery / StreamDeltaCoalescer）：
- 断流翻历史兜底：重连退避用尽（8 次、约 1 分钟）后不再把回合判死。手机 SSE 常被系统
  掐死而服务端仍在跑，跑完会把答案写进会话记录——改为轮询 `/api/sessions/{id}/messages`
  等答案落盘，首次 5 秒、逐次翻倍到 30 秒封顶，最多盯 30 分钟。认锚点靠位置不靠文字
  （发送前记下用户消息条数 N，第 N+1 条即本次发送，再用正文二次校验），答案须连续两次
  读到一致才算定（签名带记录总条数，服务端还在追加工具记录时会变）；认不出锚点连续两次
  即放弃，宁可报错不认错答案。
- 流式攒帧：收到的碎字先进缓冲，每 16 毫秒放一小段，放多少跟积压自适应（每帧 8~48 字），
  避免模型吐字「憋一下、然后一大块」地跳。切分时避开中文/emoji 半个字；工具事件、回合
  结束、中断等节点先 flush 再走，保证顺序与末段不丢。
- 新增文件 StreamDeltaCoalescer.kt。
（ChatViewModel.kt、StreamDeltaCoalescer.kt、app/build.gradle.kts）

## 2.21 — versionCode 32
断线恢复三个洞一起补（参考 Hy4ri/hermes-mobile 的重连+补播实现）：
- 隧道假死看门狗：SSE 事件流改用独立 client（读超时 30 秒）。原来那条流 readTimeout=0，
  隧道半死（连接在、不回包）时会永久挂着，表现是「发出去一直转圈、没反应」。服务端每 10 秒
  必发一个 keepalive 注释帧，30 秒 = 3 个心跳周期，正常空闲不会误杀。
- 退避重试：断流后的「固定 1.2 秒 × 3 次」改成 1→2→4→8→16→30 秒封顶、最多 8 次，覆盖约
  1 分钟窗口；首次仍只等 1 秒。收到任意正常事件即清零计数并清掉提示。
- 丢事件提示：接住服务端 replay.truncated 事件（断线期间事件超出保留窗口），在气泡里标一行
  「断线期间有内容未收到，已从服务端补拉最新结果」，并自动拉一次服务端消息兜底。
- 附带：回到前台立刻体检一次，超过 25 秒没收到事件的流主动断开重连，不必干等 30 秒。
- 不再往正文塞「[连接断开]」错行，断流提示统一走气泡上方一行。
（HermesApi.kt、ChatViewModel.kt、App.kt）

## 2.20 — versionCode 31
图片查看器升级：
- 全屏看图支持双指缩放（1~6 倍）+ 单指拖动，放大后拖拽查看细节。
- 保存改为显式的「保存到相册」按钮（顶部操作条），不再依赖长按；旁边有「关闭」按钮。
- 点空白不再直接关掉全屏（方便缩放时误触），关闭走按钮或系统返回。
（Markdown.kt）

## 2.19 — versionCode 30
更新提示更实时、且与设置页联动：
- 侧边栏「设置」的绿点原来只在启动/切身份时检查一次，现改为每 30 秒静默检查一次
  （挂在在线探针循环里），发新版后角标自动亮起，不必等下次启动。
- 设置页的「检查更新」按钮也加同款绿点，与抽屉角标共用同一个状态（updateBadge），
  两边实时联动。
（ChatViewModel.kt、Screens.kt）

## 2.18 — versionCode 29
修「用户自己发的图片点不开」：用户气泡里的图片原来只是 AsyncImage 缩略图，没有手势。
- 抽出一个可缩放图片组件（缩略图点击 → App 内全屏查看，点任意处关闭；长按 → 保存到相册），
  正文内联图与用户本地图共用。
- 用户本地图（content:// / file://）读字节后同样支持全屏与保存；读不到字节时仍能放大。
（Screens.kt、Markdown.kt、Attachment.kt）

## 2.17 — versionCode 28
三个问题：
- 更新后图片不见：重开 App 时 refreshFromServer 用服务端消息覆盖本地，服务端存的是原始
  MEDIA: 路径、不带内联图，一覆盖图片就没了。改为按位置合并——本地该条已有内容就保留本地
  （更完整、含图），只把本地没有的尾部（后台任务产出）按服务端补上。
- 图片改用 App 内全屏查看（不再甩给外部软件打开）：点一下在 App 内放大，点任意处关闭，
  长按保存到相册。
- 侧边栏会话列表：正在执行任务的会话显示「● 执行中」标识。
（ChatViewModel.kt、Markdown.kt、App.kt）

## 2.16 — versionCode 27
- 长按选中文本后可以取消选中了：点消息区空白处或点正文任意位置会重建 SelectionContainer，
  选中态随之清除（原来是选中后没法取消）。
- 有新版本时，抽屉里「设置」按钮右上角（边框内）显示一个小绿点，提示可更新；
  启动/切身份时静默检查，点「检查更新」仍会弹确认框。
（Screens.kt、Markdown.kt、ChatViewModel.kt、App.kt）

## 2.15 — versionCode 26
修两个渲染层问题：
- 消息里的图片可以长按保存到系统相册（Pictures/Hermes）。API 29+ 走 MediaStore 免存储权限；
  低版本写应用外部目录后扫描入册。点击仍是用系统应用打开。
- 恢复正文长按选择/复制：2.3 轮上 Markdown 渲染后正文改用 ClickableText（不支持长按选中），
  现把段落重新包进 SelectionContainer，长按可选中复制。

## 2.14 — versionCode 25
支持多会话并行执行：一个窗口在跑任务时切到另一个窗口发消息，原任务不再被中断。
- 运行态按会话隔离：每个会话各自持有消息、忙闲、当前 run、SSE 连接、续跑计数。
- 切换会话/新建对话不再 stop 正在跑的任务；后台会话的流照常接收并写进自己的缓冲，
  切回去看到的内容是完整的。
- 「停止」只停当前会话的任务（删除会话会先停它自己的任务）。
- 前台服务与通知按「是否有任意任务在跑」判断；重开 App 按会话逐个恢复还在跑的 run。
（ChatViewModel.kt、Prefs.kt）

## 2.13 — versionCode 24
修「掉线了还显示在线」：
- 在线探针改用独立 OkHttpClient（callTimeout 6s / read 5s）。原来复用 SSE 那个 readTimeout=0 的
  无限长 client，CF 隧道半开时 /health 永久挂起，pingLoop 卡死、状态冻结在「在线」。
- 探测间隔 10s→5s；连续 2 次失败才翻「离线」（防抖），一次成功立刻回「在线」。
（ChatViewModel.kt、net/HermesApi.kt）

## 2.12 — versionCode 23
修「切换对话内容不对」+ 图片上传限制对齐：
- 重开 App 恢复上次停留的会话（原来忽略 sessionId，总是跳到最近更新的那个）。
- 服务端拉取加会话守卫：切走后返回的旧会话数据不再覆盖当前对话内容。
- 图片单张上限 50MB→20MB（最多仍 10 张）；服务端修复大图上传被截断/被 413 挡掉
  （StreamReader 单次 read 截断 + 全局 10MB 请求上限提到 55MB）。
（ChatViewModel.kt、app/build.gradle.kts；服务端补丁见 hermes-patches）

## 2.11 — versionCode 22
对话体验增强四项：
- 审批卡片：服务端 `approval.request` 事件渲染成可点按钮（允许一次/本会话/始终/拒绝），
  点击回执 `POST /v1/runs/{id}/approval`，不再只在聊天里发文字问。
- token 用量与速度：轮末 usage 显示「入/缓存/出/共 + tok/s」（App 按耗时自算）。
- 子任务进度：`subagent.start/complete` 归并成一行条进度。
- 通知栏直接回复：通知自带输入框，打完直接发（RemoteInput + ReplyReceiver + 落盘暂存）。
（ChatViewModel.kt、Screens.kt、Notifier.kt、net/HermesApi.kt、Prefs.kt、AndroidManifest.xml）

## 2.10 — versionCode 21
新增文件上传：输入栏加「文件」按钮（系统文件选择器，任意类型、可多选，最多 10 个）。
图片上传逻辑泛化成通用附件上传，按真实扩展名给 MIME（不再硬填 image/jpeg）。
单附件上限 10MB→50MB，最多 5→10 个。待发区非图片显示文件卡片，气泡里回显附件名。
（Screens.kt、ChatViewModel.kt；服务端白名单扩到 31 种、上下行上限统一 50MB，
/v1/runs 支持非图片文件落盘到工作区并在消息附路径——服务端补丁见 hermes-patches）

## 2.9 — versionCode 20
支持接收文件与图片：服务端把回复里的 `MEDIA:` 标签内联成 data URL 后随消息下发，
App 新增内联图片渲染 + 附件卡片（点击落盘到 `filesDir/attachments/`，再经 FileProvider
拉起系统应用打开，HTML 走浏览器）。非图片文件（html/pdf/zip/md 等）不再只显示成一行路径文本。
（Markdown.kt 新增 MdBlock.Image / MdBlock.Attachment；新增 Attachment.kt）

按 versionCode 递增。发布时同步更新 `dist/update/version.json` 的 notes 字段。
（versionCode 12 / 14 未产生提交，编号有跳档属正常。）

## 2.8 — versionCode 19
新增应用图标：Hermes 原图标，传统 PNG + adaptive icon 全密度，含 round 版。

## 2.7 — versionCode 18
修自动续跑伪造用户消息：连接中断改为查同 run 状态后续接流 / 拉回结果，不再塞「继续」。
（ChatViewModel.kt）

## 2.6 — versionCode 17
修输入长文本卡顿：草稿去抖落盘、输入框配色只在变更时重建、TextWatcher 用 rememberUpdatedState。

## 2.5 — versionCode 16
输入框图标改单色描边；设置新增「清理缓存」；对话正文字号 14→16。

## 2.4 — versionCode 15
Markdown 表格渲染 + 链接可点；白天模式输入框白字修复；后台运行开关（关掉即无常驻通知）。

## 2.2 — versionCode 13
常驻通知降噪：IMPORTANCE_MIN 静默频道，无正文 / 无提示音 / 无震动。

## 2.0 — versionCode 11
输入框 2–8 行；过程折叠；全屏不截断；图片输入（上传 artifact + 气泡回显）；
重开恢复 run 状态；全屏输入 fill。

## 1.9 — versionCode 10
输入栏对齐；点空白收键盘；状态页细化；草稿持久化；全屏返回；输入卡顿优化；列表 key 稳定。

## 1.8 — versionCode 9
原生 EditText 输入修输入法符号；外观固定底部；空态新建会话；侧滑返回收抽屉；
重开拉取会话；移除设置里的对话身份项。

## 1.7 — versionCode 8（baseline）
中文标点修复；点空白收键盘；发送按钮缩小；服务器地址必填；侧滑返回；后台任务常驻。