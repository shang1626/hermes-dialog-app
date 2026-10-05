# 变更记录

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