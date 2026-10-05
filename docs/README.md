# Hermes 对话 App（hermes-app）

自用安卓客户端，连自建 Hermes 网关（api_server），在手机上跟 Hermes 对话。
两名开发者共享本仓库：裸仓库 `~/hermes-app-shared.git`，工作副本 `~/hermes-app`。

## 技术栈

- Kotlin + Jetpack Compose（Material3），minSdk 26 / targetSdk 34 / JDK 17
- OkHttp 4.12（REST + SSE 流式）、Coil（图片）
- 无后端代码：服务端是 Hermes 网关自带的 `api_server` 平台（监听 127.0.0.1:8642），本仓库只有安卓端

## 目录

```
app/src/main/java/com/hermesapp/   Kotlin 源码（见 DESIGN.md）
app/src/main/res/                  图标 / 主题 / 字符串
dist/update/                       发布产物：APK + version.json
build.sh                           一键构建脚本（本机无 gradlew，见 BUILD.md）
build.log                          最近一次构建日志（gitignore，不入库）
docs/                              本目录：设计、变更、协作记录
```

## 构建

```bash
cd ~/hermes-app
./build.sh                     # 产物 app/build/outputs/apk/debug/app-debug.apk
```

注意：本工程**没有 gradle wrapper**，别用 `./gradlew`（会 command not found）。`build.sh` 用
`~/android-tools` 下预装的 Gradle 8.9 + Android SDK 34。实测一次全量构建约 29 秒。
构建结束看 `BUILD SUCCESSFUL` 与 `EXIT=0`；`build.log` 是上一次的日志。工具链细节见 `docs/BUILD.md`。

## 发布流程

1. 改 `app/build.gradle.kts` 里的 `versionCode` / `versionName`（必须递增，客户端靠它判断更新）
2. `./gradlew assembleDebug` 出包
3. 把 APK 复制到 `dist/update/`，命名 `<名>-<版本>-<md5前8>.apk`
4. 更新 `dist/update/version.json`：

```json
{"versionCode":19,"versionName":"2.8","url":"https://your-gateway.example.com/update/<文件名>.apk",
 "notes":"这一版改了什么","size":<字节>,"md5":"<md5>"}
```

5. 不需要重启任何服务：`hermes-update-files.service` 是 `python3 -m http.server 8644 --directory ~/hermes-app/dist`，直接读盘

## 公网链路

- 更新分发：Cloudflare tunnel `hermes-app` → 本机 8644；对外 `https://your-gateway.example.com/update/version.json`
- 对话 API：Hermes 网关 api_server 监听 127.0.0.1:8642，经反 tunnel（HK → 本机）暴露，App 里 `Keys.DEFAULT_URL` 指向它
- 隧道配置在 `~/.cloudflared/hermes-app-*`，systemd 单元 `hermes-update-files.service` / `hermes-reverse-tunnel*.service`

## 客户端密钥

`app/src/main/java/com/hermesapp/Keys.kt` 里是自用固定值：App 登录密码、两个 profile 的 API key、服务器地址、更新地址。
**不要把真实密钥写进文档或提交信息**，需要时直接看该文件。

## 相关服务（本机）

| systemd 单元 | 作用 |
|---|---|
| `hermes-gateway.service` | Hermes 网关（含 api_server 8642） |
| `hermes-update-files.service` | 分发 dist/ 下的 APK（8644） |
| `hermes-reverse-tunnel.service` | HK ↔ 本机 8642 反向隧道 |
| `hermes-reverse-tunnel-8644.service` | HK ↔ 本机 8644 反向隧道 |