# Hermes 对话 App

**中文** | [English](README.en.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个自托管的安卓客户端，连接你自己搭建的 [Hermes Agent](https://github.com/NousResearch/Hermes) 网关，在手机上跟 AI 助手对话。

纯安卓端工程，**不含服务端代码**——服务端就用 Hermes 网关自带的 `api_server` 平台，不需要另外写后端。

> 本仓库是脱敏后的通用版本：不含任何真实密钥、密码、域名或内网地址。
> 部署者只需在 `local.properties` 里填两个地址（更新分发地址、历史域名迁移表，见下文「配置」）；
> **登录凭据不进包**——App 首次打开时输入「账号 + 密码」，由你自己的网关核对。

---

## 目录

- [这个 App 能做什么](#这个-app-能做什么)
- [整体架构](#整体架构)
- [技术栈](#技术栈)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [用你的 Hermes 编译（推荐）](#用你的-hermes-编译推荐)
- [手动构建](#手动构建)
- [编译踩坑大全](#编译踩坑大全)
- [签名](#签名)
- [服务端准备](#服务端准备)
- [服务端接口契约](#服务端接口契约)
- [发布与更新流程](#发布与更新流程)
- [常见问题排查](#常见问题排查)
- [安全说明](#安全说明)
- [许可](#许可)

---

## 这个 App 能做什么

### 对话

- **流式对话**：基于 SSE 的流式输出，边生成边显示，不用等整段生成完
- **多会话管理**：新建、切换、重命名、删除会话；会话列表按最近更新排序
- **会话搜索**：在历史会话与消息里搜关键词
- **中途插话（steering）**：任务跑到一半可以再发一句，改变它的方向
- **中止运行**：随时停掉正在跑的任务

### 双 Profile

- 可在两个不同的 Hermes 机器人（profile，例如 `default` 与 `friend`）之间切换
- 两个 profile 各自独立的账号、独立的会话列表、独立的本地存储目录，互不干扰
- **账号决定身份**：登录时输入「账号 + 密码」，App 直接拿它当令牌（`Authorization: Bearer 账号:密码`），网关按 URL 前缀 + 账号核对；**包里不含任何密钥**
- 已登录过的账号在登录页一键进入，也可在设置页切换（只列已登录的账号）

### 任务可视化

- **实时用量统计**：每轮对话显示 token 用量（输入 / 缓存读 / 缓存写 / 输出 / 合计）与耗时、速度（tok/s）
- **子任务进度**：多步任务实时显示进度行（已几步、跑了多久、多久前取的进度），可点开看子代理的步骤流水（第几步 + 工具名 + 参数 + 结果摘要）
- **工具轨迹**：工具调用记录默认折叠在气泡里，需要时展开
- **状态页**：把网关的 `/health/sysinfo` 整理成卡片——网关状态、CPU、内存、磁盘、负载、运行时长、今日 API 调用量、活跃 run / 子代理数、队列深度、最后心跳，每 5 秒自动刷新

### 多模态

- **图片发送**：系统 Photo Picker 选图（无存储权限），最多 5 张、单张上限 10MB
- **文件 / 图片接收**：服务端下发的 `MEDIA:` 内容自动渲染成图片或附件卡片，点击可保存并调用系统应用打开
- **能力探测**：先问服务端支不支持视觉，不支持则按策略把图片转成文字描述

### 语音

- **完成播报**：任务跑完时自动语音通知（走服务端 TTS）
- **语音播放**：流式语音边生成边播，历史消息可回放；同一条消息只显示一个语音按钮，播放互斥

### 其它

- **应用内更新**：检查更新 → 下载 APK（带进度）→ MD5 校验 → 拉起系统安装器
- **本地持久化**：会话、消息、用量数据全部落盘，重开 App 不丢；单会话保留最近 300 条
- **后台运行开关**：开着则起前台服务保住连接、后台收到回复弹通知；关掉则彻底无通知，任务照样在服务端跑，重开 App 拉结果
- **昼夜配色**：跟随系统 / 强制日间 / 强制夜间三种模式
- **崩溃日志**：内置崩溃捕获，便于自己排查

---

## 整体架构

单 Activity + Jetpack Compose，状态集中在一个 ViewModel，网络层一个类，本地持久化两个类。

```
MainActivity (App.kt)
  └─ HermesApp : 主题 / 昼夜色 → 未登录走 LoginScreen，已登录走 MainScaffold
       └─ MainScaffold : 抽屉 + 顶部栏 + 三个页面
            ├─ ChatScreen    ← ChatViewModel（消息、SSE 流、run 生命周期）
            ├─ StatusScreen  ← /health/*（状态卡片）
            └─ SettingsScreen（主题、服务器、已登录账号、缓存清理等）
```

核心数据流（一次对话 = 一次 run，全部推理在服务端）：

```
POST /v1/runs  ──►  run_id  ──►  GET /v1/runs/{id}/events（SSE 收流）
                                      │
                                      ├─ message.delta    → 追加到当前气泡
                                      ├─ tool.completed   → 追加工具轨迹行
                                      └─ run.completed    → 定稿正文、收尾
```

App 被杀重开时，用持久化的 `activeRunId` 去查服务端 run 状态：还在跑就续接事件流，已结束就把产出拉回来。

---

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose（Material3） |
| 网络 | OkHttp 4.12（REST + 手写 SSE 解析） |
| 图片 | Coil 2.6 |
| 本地存储 | SharedPreferences（配置）+ 应用私有目录 JSON（会话） |
| 最低版本 | minSdk 26（Android 8.0） |
| 目标版本 | targetSdk 34 / compileSdk 34 |
| JDK | 17+（实测用 JDK 21 构建通过） |
| 构建 | Gradle 8.9 + Android Gradle Plugin 8.5.2 + Kotlin 1.9.24 |

服务端要求：Hermes 网关（自带 `api_server` 平台），默认监听 `127.0.0.1:8642`。

---

## 目录结构

```
app/src/main/java/com/hermesapp/    Kotlin 源码
  App.kt                            主题 / 昼夜配色 / MainActivity / 登录页 / MainScaffold
  Screens.kt                        Compose 界面（聊天、消息列表、气泡、状态页、设置页）
  ChatViewModel.kt                  核心：会话状态、run 生命周期、SSE 解析、消息管理
  net/HermesApi.kt                  HTTP / SSE 接口层
  Markdown.kt                       轻量 Markdown 渲染（块解析 + 行内链接 + 表格）
  SessionStore.kt                   会话本地持久化（JSON）
  Prefs.kt                          SharedPreferences 封装
  Keys.kt                           构建期注入的配置读取（源码内无真值）
  RunService.kt                     前台服务（任务期间保 SSE 连接）
  Notifier.kt                       通知渠道与前后台标记
  VoicePlayer.kt                    语音播放（流式 / 回放）
  StreamVoicePlayer.kt              流式语音播放器
  VoiceReplayPlayer.kt              历史语音回放
  Attachment.kt                     附件 / data URL 解码
  CacheUtil.kt                      缓存统计与清理
  CrashLog.kt                       崩溃捕获
  AppLog.kt / TimeFmt.kt / ...      日志与格式化工具
app/src/main/res/                   图标 / 主题 / 字符串 / FileProvider 路径
app/build.gradle.kts                应用配置（版本号、签名、BuildConfig 注入）
build.gradle.kts                    根构建脚本
settings.gradle.kts                 仓库（阿里云镜像优先）与模块配置
gradle.properties                   Gradle 参数（堆内存、并行、缓存、daemon 超时）
build.sh                            一键构建脚本
docs/                               设计与开发文档
```

文档索引：

| 文件 | 内容 |
|---|---|
| `docs/DESIGN.md` | 架构分层、文件职责、接口契约、关键设计决定 |
| `docs/INTERNALS.md` | 运行时机制：run 生命周期、SSE 事件表、断线续接、图片链路、存储格式、踩坑清单 |
| `docs/BUILD.md` | 工具链位置、环境搭建、依赖版本、报错对照表 |
| `docs/DEPLOYMENT-GUIDE.md` | 从零部署与二次开发指南 |
| `docs/CHANGELOG.md` | 完整变更历史 |
| `docs/COLLAB.md` | 多人协作规范（提交格式、发布检查单、回滚） |

---

## 快速开始

```bash
# 1. 克隆
git clone https://github.com/<你的账号>/hermes-dialog-app.git
cd hermes-dialog-app

# 2. 配置（见下节，至少要填 sdk.dir 和服务地址）
cp local.properties.example local.properties   # 若无模板，手动新建
$EDITOR local.properties

# 3. 构建
./build.sh release

# 4. 产物
ls -l app/build/outputs/apk/release/app-release.apk
```

安装到手机：`adb install -r app/build/outputs/apk/release/app-release.apk`，或直接把这个 APK 拷进手机点开装。

> 不会配 Android 环境？见下节 [用你的 Hermes 编译](#用你的-hermes-编译推荐)，把整件事交给 AI 智能体。

---

## 配置说明

真值**不写进源码**。在工程根目录建一个 `local.properties`（已被 `.gitignore` 排除，不会进仓库），填入你的信息：

```properties
# Android SDK 路径（必须，否则构建报 SDK location not found）
sdk.dir=/你的/Android/SDK/路径

# ===== 构建期注入的地址（只有这两项） =====

# 登录凭据**不在这里**：App 用「账号 + 密码」登录、由网关核对，包里零密钥；
# 服务器地址在 App 登录页里填（也可随时改）。服务端只保留一份账号表（账号 + PBKDF2 指纹）。

# 应用内更新的 version.json 地址
HERMES_UPDATE_URL=https://你的更新分发域名/update/version.json

# 历史域名迁移（可选）：旧域名=新域名，App 启动时把存量的旧地址自动改写
HERMES_LEGACY_HOSTS=旧域名=新域名
```

当前构建期只注入两项：`UPDATE_URL`（应用内更新地址）与 `LEGACY_HOSTS`（历史域名迁移表）；源码里只有 `BuildConfig.XXX` 引用、没有任何真值。服务器地址与登录凭据都在 App 里填，不经过构建。**换机器构建时，`local.properties` 要自己补一份。**

---

## 用你的 Hermes 编译（推荐）

**不会配 Android 环境也没关系——把这件事交给 Hermes。** 本工程是纯命令行构建、不依赖 Android Studio，非常适合让 AI 智能体代劳：它自己装工具链、填配置、编包，把 APK 交给你，中途的坑也能自己查文档解决。

### 给 Hermes 的指令（复制即用）

把下面这段，连同你的 `local.properties` 真值一起发给你的 Hermes：

> 克隆 `<仓库地址>` 到本地，按仓库里的 README 与 `docs/BUILD.md`，把它编译成 **release** APK。
> 构建配置我已从 `local.properties.example` 复制出 `local.properties` 并填好（只有更新地址与域名迁移表，没有密钥）。
> 环境缺什么（JDK / Android SDK / Gradle）你自己装。
> 编完把 APK 的绝对路径、大小和 md5 告诉我。**不要把任何密钥写进源码、提交信息或公开场合。**

### Hermes 会替你做的四步（也是它最容易踩的坑）

| 步骤 | 关键点 |
|---|---|
| 1. 装工具链 | JDK 17+、Android SDK（platform 34 + build-tools 34.0.0 + platform-tools）、Gradle 8.9。**必须 `sdkmanager --licenses` 接受许可**，否则编译期卡 license 校验 |
| 2. 建配置 | `cp local.properties.example local.properties` 再填真值。漏了会报 `SDK location not found` |
| 3. 跑构建 | `./build.sh release`。脚本会自动探测 JDK / SDK / Gradle 位置，探测结果打印在开头，缺哪样直接报错 |
| 4. 交产物 | `app/build/outputs/apk/release/app-release.apk` |

> 安全提醒：登录凭据只在你手机上的 App 里输入，**不要贴进公开 issue、群聊或提交信息**。本仓库本身不含任何真值。

---

## 手动构建

### 前置条件

- JDK 17 或更高
- Android SDK：platform 34 + build-tools 34.0.0 + platform-tools
- Gradle 8.9

> ⚠️ 本工程**没有 gradle wrapper**（没有 `gradlew`，也没有 `gradle/wrapper/`）。
> 别照着网上教程敲 `./gradlew`，会 `command not found`。请用系统安装的 `gradle`，或直接用 `build.sh`。

### 一键构建

```bash
./build.sh            # 编 debug 包
./build.sh release    # 编 release 包（开 R8，体积小一半，给手机装用这个）
./build.sh clean      # 先 clean 再编（可组合：./build.sh clean release）
```

脚本开头会打印探测到的工具链位置，例如：

```
JDK    = /usr/lib/jvm/java-21-openjdk-amd64
SDK    = /home/you/Android/Sdk
GRADLE = /opt/gradle/bin/gradle
TASK   = assembleRelease
```

产物：
- debug → `app/build/outputs/apk/debug/app-debug.apk`
- release → `app/build/outputs/apk/release/app-release.apk`

日志写进 `build.log`（在 `.gitignore` 里，不入库）。构建结束看日志里的 `BUILD SUCCESSFUL` 与 `EXIT=0`。一次全量构建约半分钟；release 带 R8 约 2～2.5 分钟。

**探测规则**（每一项都能用环境变量强行覆盖）：

| 组件 | 探测顺序 |
|---|---|
| JDK | `$JAVA_HOME` → `~/.sdkman/candidates/java/current` → `/usr/lib/jvm/java-*-openjdk-*` → 从 `which javac` 反推 |
| Android SDK | `$ANDROID_SDK_ROOT` → `local.properties` 的 `sdk.dir` → `~/Android/Sdk` / `~/Library/Android/sdk` 等常见目录 |
| Gradle | `$GRADLE` → PATH 里的 `gradle` → `~/.sdkman/.../gradle/current` → 常见目录 → `./gradlew`（若有） |

找不到就在命令前加变量，例如 `JAVA_HOME=/your/jdk ./build.sh release`。

### 手动构建

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_SDK_ROOT=/path/to/android/sdk
export ANDROID_HOME=/path/to/android/sdk
gradle assembleDebug
```

三个环境变量是必须的：`JAVA_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_HOME`。少 `ANDROID_HOME` 会报找不到 SDK。

### 依赖与仓库

`settings.gradle.kts` 里仓库顺序是**国内镜像优先**（阿里云 gradle-plugin / google / public），再回落到 `google()` / `mavenCentral()`。
如果你在墙外且直连顺畅，可以删掉镜像；在国内建议保留，否则拉依赖会很慢。

核心依赖版本：AGP 8.5.2、Kotlin 1.9.24、Compose BOM 2024.06.00、compose-compiler 1.5.14、OkHttp 4.12.0、Coil 2.6.0。

---

## 签名

默认用 **debug 签名**（`~/.android/debug.keystore`），`app/build.gradle.kts` 里 release 构建也指向它：

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

含义与注意：

- 换机器构建**必须带上同一份 `debug.keystore`**，否则新旧 APK 签名不一致，手机上装不上（提示「应用未安装 / 签名冲突」）。
- 自用场景够用，不追求上架。要独立签名就自己建 keystore 并改 `build.gradle.kts`。
- 别人的机器上如果 `~/.android/debug.keystore` 不存在，Android 工具链会自动生成一份新的——所以两台机器编出来的包签名不同，这是最常见的坑。

---

## 服务端准备

App 只是客户端，服务端要你自己搭：

1. 部署并运行 [Hermes Agent](https://github.com/NousResearch/Hermes)，确认 `api_server` 平台已启用。
2. 确认网关健康：`curl http://127.0.0.1:8642/health` 应返回正常。
3. 拿到两个 profile（如 `default` / `friend`）的 API key，填进 `local.properties`。
4. 若要让手机在外网访问，用反向代理或反向隧道把 `8642` 暴露出去，并配好 HTTPS 证书。

> 注意：`api_server` 默认只监听 `127.0.0.1`，不要直接裸奔到公网。建议前面挂一层带鉴权/限流的反向代理。

---

## 服务端接口契约

App 用到的 `api_server` 接口（改服务端时对照这张表）：

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v1/runs` | 起一次 run（body: `input` / `session_id?` / `images?`）→ 返回 `run_id` |
| GET | `/v1/runs/{id}` | 查 run 状态与结果（重开 App 恢复用） |
| GET | `/v1/runs/{id}/events` | SSE 事件流（带 `Last-Event-ID` 断点续传） |
| POST | `/v1/runs/{id}/stop` | 中止运行 |
| POST | `/v1/runs/{id}/steer` | 中途插话 |
| GET | `/v1/capabilities` | 能力探测（如 `features.supports_vision`） |
| POST | `/v1/artifacts/upload` | 上传图片，返回 `artifact_id` |
| GET | `/api/sessions/{id}/messages` | 拉服务端会话消息（补回后台产出） |
| GET | `/health` `/health/sysinfo` `/health/detailed` | 在线探测与状态页数据 |

鉴权：每个请求带 `Authorization: Bearer 账号:密码`（App 用账号密码当令牌，包里零密钥）；网关按 URL 前缀选身份（`/api/...`=主 profile，`/p/<名>/api/...`=该 profile），再与账号表核对。

### SSE 事件类型（客户端认这些）

| event | 客户端处理 |
|---|---|
| `message.delta` | 追加到当前助手气泡正文 |
| `message.interim` | 忽略 |
| `tool.started` | 忽略（只在完成时记一行） |
| `tool.completed` | 追加工具轨迹行 |
| `tool.failed` | 追加工具轨迹行，前缀 ✗ |
| `run.completed` | 用 `output` 定稿正文并收尾；后台则弹通知 |
| `run.failed` | 标记失败，触发自动续接判断 |
| `run.cancelled` / `run.interrupted` | 标记已中断并收尾 |

### 服务端需要打的补丁（可选，用于接收非图片附件）

Hermes 网关默认只把图片类的 `MEDIA:` 转成内联 data URL。要支持接收普通文件附件，需要在服务端打两处补丁（**升级 Hermes 会丢，需重打**）：

- `gateway/platforms/api_server.py`：让 `_resolve_media_to_data_urls()` 支持非图片扩展名，并把大小上限从 5MB 提到 12MB。
- `gateway/platforms/api_server_runs.py`：让 `/v1/runs` 的收尾也走一遍 media 解析（原来只有 chat-completions 两条路走）。

不打补丁的话，App 收文件会退化成只看到文件名链接。

---

## 发布与更新流程

App 自带更新检查，流程如下：

1. 递增 `app/build.gradle.kts` 里的 `versionCode` 和 `versionName`（**versionCode 必须递增**，客户端靠它判断有没有新版本）。
2. 构建出 APK。
3. 把 APK 放进你的更新分发目录，命名建议 `<名>-<版本>-<md5前8>.apk`。
4. 更新同目录下的 `version.json`：

```json
{"versionCode":19,"versionName":"2.8",
 "url":"https://你的域名/update/<文件名>.apk",
 "notes":"这一版改了什么",
 "size":12345678,
 "md5":"<文件的 md5>"}
```

5. 客户端下次启动检查更新时就能发现，弹框确认后带进度下载、校验 MD5、拉起安装器。

要点：

- `size` 和 `md5` 必须与实际 APK 一致，否则下载校验不过。
- 如果更新分发走了 CDN（Cloudflare / EdgeOne 等），**CDN 会缓存 `version.json`**，发了新版记得去控制台刷新缓存，否则客户端拿到的是旧版本号。App 侧已在请求上拼时间戳绕缓存，但不能完全替代手动刷。
- 旧版本的 APK 建议保留在分发目录里，方便回滚（把 `version.json` 指回旧包即可）。

---

## 编译踩坑大全

按「现象 → 原因 → 处置」排。这些都是本工程实际踩过的，不是网上抄的通用清单。

### 一、环境 / 工具链

| 现象 | 原因 | 处置 |
|---|---|---|
| `gradle: command not found` | Gradle 没装或不在 PATH | 跑 `./build.sh`（自带探测）；或 `export GRADLE=/path/to/gradle` |
| `SDK location not found` | 没导 `ANDROID_HOME`，或 `local.properties` 里没有 `sdk.dir` | 建 `local.properties` 并填 `sdk.dir`（见「配置说明」） |
| `Failed to install ... licenses not accepted` | SDK 许可没接受 | `yes \| sdkmanager --licenses`（sdkmanager 在 `cmdline-tools/latest/bin/`） |
| `Unsupported class file major version` | JDK 版本不对（用了 11 或更低） | 用 JDK 17 或 21：`export JAVA_HOME=/path/to/jdk-17+` |
| `command not found: javac` | 只装了 JRE，没装 JDK | 装完整 JDK，不只要 JRE |

### 二、依赖下载

| 现象 | 原因 | 处置 |
|---|---|---|
| 编译卡在 `Downloading ...` 很久 | 直连 Google Maven 慢 | `settings.gradle.kts` 里**阿里云镜像在最前**是刻意配的，别删；在国内务必保留 |
| `Could not resolve ...` / `Connection timed out` | 网络不通 | 配代理：`export https_proxy=http://你的代理:端口` 后再跑；或改用国内镜像 |
| `Could not find com.android.tools.build:gradle` | 仓库顺序被改 | 恢复 `settings.gradle.kts` 的镜像列表 |

### 三、构建过程

| 现象 | 原因 | 处置 |
|---|---|---|
| 越编越慢 | Gradle / Kotlin 守护进程占内存、反复重启 | 正常现象；闲时会自己退出（Gradle 3 小时、Kotlin 2 小时），急就 `gradle --stop` |
| `Unclosed comment` + `Missing '}'` | Kotlin 块注释可嵌套，正文里写了 `/*`（如 `image/*`）会再开一层 | 注释里改用 `//`，或避开通配符 |
| 报一堆 `Unresolved reference` | 通常是**先报错的那个文件**结构断了，其余是连锁 | 只修第一个报错的文件，别追连锁 |
| `@Composable invocations can only happen...` | 函数上方的 `@Composable` 注解漂移或丢失 | 检查注解是否归位到正确函数正上方 |
| 构建「超时」但没报错 | 全量构建超了工具等待窗口，进程其实还在跑 | 先看产物 mtime 和 `build.log` 尾部，**别盲目重跑** |

### 四、配置注入

| 现象 | 原因 | 处置 |
|---|---|---|
| 登录提示账号或密码错误 | 账号/密码与网关那份账号表不一致（服务端只存 PBKDF2 指纹） | 改账号表**不用重启网关**（按 mtime 热读）；App 端重新登录一次 |
| App 一直「离线」 | `HERMES_DEFAULT_URL` 不可达 | 检查域名、证书、`api_server` 是否在跑 |
| 改了 `local.properties` 没反应 | 值在**构建期**注入，不是运行时读 | 改完重编 |

### 五、签名 / 安装

| 现象 | 原因 | 处置 |
|---|---|---|
| 手机提示「应用未安装 / 签名冲突」 | 换了构建机、或 `~/.android/debug.keystore` 丢了 | 用**同一份** `debug.keystore` 重编；新机器上它不存在时工具链会自动生成新的，两份签名就不一致了 |
| 装了新版但版本号没变 | 忘了 bump `versionCode` | `app/build.gradle.kts` 里 `versionCode` 必须递增（客户端就比它） |

### 六、产物 / 发布

| 现象 | 原因 | 处置 |
|---|---|---|
| release 包比 debug 小很多 | 正常，R8 压缩 + 资源裁剪 | 无需处理，release 才是给手机装的 |
| 更新检查拿到旧版本号 | 分发链路有 CDN 缓存 | 去 CDN 控制台刷新该路径；App 已带时间戳参数绕过，但替代不了手动刷新 |
| `version.json` 的 md5 对不上 | 文件被改过 / 拷错 | 用 `md5sum` 重算，`size` 用 `stat -c%s`，两者必须与实际文件一致 |

---

## 常见问题排查

| 现象 | 原因 / 处置 |
|---|---|
| `gradle: command not found` | 没用全路径。跑 `./build.sh`，或导出工具链路径 |
| `SDK location not found` | `ANDROID_HOME` 没导，或 `local.properties` 里没有 `sdk.dir` |
| `licenses not accepted` | 重跑 `sdkmanager --licenses`，`yes \| sdkmanager --licenses` |
| 手机装新包提示签名冲突 | 构建机换了 / `debug.keystore` 丢了。用同一份 keystore 重编 |
| 编译卡在下载依赖 | 网络问题。确认镜像仓库还在 `settings.gradle.kts` 里、没被改掉 |
| App 一直「离线」 | 检查 `HERMES_DEFAULT_URL` 是否可达；服务端 `api_server` 是否在跑；HTTPS 证书是否有效 |
| 登录提示账号或密码错误 | 账号或密码不对（账号决定身份，别拿 A 的账号连 B 的身份路径）；连续失败会被限速 | 核对账号表；等限速窗口过后重试 |
| 收不到文件附件 | 服务端没打 media 补丁（见上节），或文件超过 12MB 上限 |
| 语音播报没声音 | 服务端 TTS 未启用；或音色不受支持；或语音代理不可达 |
| 更新检查拿到旧版本号 | 分发链路有 CDN 缓存，去控制台刷新 |
| 深色/浅色下文字看不见 | 主题色必须整套替换，不能只换背景色 |

---

## 安全说明

- 本仓库**不含任何真实密钥、密码、域名或内网 IP**，可安全公开。
- **包里零密钥**：App 用「账号 + 密码」登录（服务端只存 PBKDF2 指纹 + 失败限速）；历史版本里被明文编进包的那几把密钥，已从源码与包里全部移除。
- 构建期只注入两个地址（`UPDATE_URL` / `LEGACY_HOSTS`），`local.properties` 已被 `.gitignore` 排除。
- 不要把真实凭据写进源码、文档、提交信息或 issue。
- `.gitignore` 排除的还有 `build/`、`dist/`、`build.log`、`.gradle/`、`.idea/` —— 不是漏提交，是故意的。
- 部署到公网时，务必在网关前面加反向代理 + HTTPS + 鉴权，不要直接把 `api_server` 暴露出去。

---

## 许可

自用项目，供学习与二次开发参考。