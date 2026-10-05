# 构建环境（本机怎么把 APK 编出来）

工程**没有 gradle wrapper**（没有 `gradlew` / `gradle/wrapper/`），别照着网上教程写 `./gradlew`。
本机用 `~/android-tools` 下预装的一套工具链，入口是仓库根的 `build.sh`。

## 一键构建

```bash
cd ~/hermes-app
./build.sh            # 编译 debug 包
./build.sh clean      # 先 clean 再编
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，日志写进 `build.log`（该文件在 `.gitignore` 里，不入库）。
本机实测一次全量构建约 29 秒（守护进程冷启动更久，看日志里 `BUILD SUCCESSFUL` 与 `EXIT=0`）。

## 工具链位置（build.sh 已固化，改环境变量前先看这里）

| 组件 | 路径 / 版本 |
|---|---|
| JDK | `/usr/lib/jvm/java-21-openjdk-amd64`（系统 `java` 是 openjdk 21） |
| Gradle | `~/android-tools/gradle-8.9/bin/gradle`（**不在 PATH**，必须全路径） |
| Android SDK | `~/android-tools/sdk` |
| build-tools | `34.0.0` |
| platforms | `android-34` |
| platform-tools | 已装 |
| cmdline-tools | `latest`（sdkmanager 用） |

`build.sh` 里导出的三个变量是必须的：`JAVA_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_HOME`。
直接敲 `gradle` 会 `command not found`；不带 `ANDROID_HOME` 会报找不到 SDK。

## 环境是怎么装的（setup_sdk.sh，要重建时用）

`~/android-tools/setup_sdk.sh` 记录了当年搭环境的全过程，可重放：

1. 解压 `cmdline-tools.zip` → `sdk/cmdline-tools/latest`
2. `yes | sdkmanager --licenses` 接受许可
3. `sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"`
4. gradle 8.9 是另外解压 `gradle.zip` 得到

**坑**：
- `sdkmanager` 首次要联网下 platform/build-tools，本机 IPv6 到部分 CDN 慢，必要时先确认走 IPv4。
- 许可没接受会卡在编译期的 license 校验，`yes |` 那一行不能省。
- `local.properties` 里写死 `sdk.dir=~/android-tools/sdk`；这个文件在 `.gitignore` 里，**不入库**，换机器要自己补。

## 签名

自用包，release 也直接签 debug 密钥：`app/build.gradle.kts` 里
`signingConfig = signingConfigs.getByName("debug")`，用的是 `~/.android/debug.keystore`。

含义：
- 换机器构建**必须带上同一个 debug.keystore**，否则新旧包签名不一致，手机装不上（会提示「应用未安装 / 签名冲突」）。
- 自用场景够用，不追求上架；真要独立签名再建 keystore 并改 `build.gradle.kts`。

## 依赖与仓库

`settings.gradle.kts` 里仓库顺序是**阿里云镜像优先**（gradle-plugin / google / public），再回落到 `google()` / `mavenCentral()`。
国内直连 google maven 慢，镜像优先是刻意配的，别删。依赖已全量缓存在 `~/.gradle`（caches + android）。

核心依赖版本：AGP 8.5.2、Kotlin 1.9.24、Compose BOM 2024.06.00、compose-compiler 1.5.14、OkHttp 4.12.0、Coil 2.6.0。

## 常见报错对照

| 现象 | 原因 / 处置 |
|---|---|
| `gradle: command not found` | 没用全路径，跑 `./build.sh` |
| `SDK location not found` | `ANDROID_HOME` 没导，或 `local.properties` 缺失 |
| `Failed to install ... licenses not accepted` | 重跑 `setup_sdk.sh` 里的 `sdkmanager --licenses` |
| 手机装新包提示签名冲突 | 构建机换了 / debug.keystore 丢了，用同一份 keystore 重编 |
| 编译卡在下载依赖 | 网络问题，确认阿里云镜像仓库在 `settings.gradle.kts` 里且没被改掉 |
"}}