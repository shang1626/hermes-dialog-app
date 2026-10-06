# 变更记录

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