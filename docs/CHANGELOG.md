# 变更记录

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