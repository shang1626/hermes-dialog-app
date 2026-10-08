# 构建说明（怎么把 APK 编出来）

工程**没有 gradle wrapper**（没有 `gradlew` / `gradle/wrapper/`），别照着网上教程敲 `./gradlew`。
入口是仓库根的 `build.sh`——它会自动探测 JDK / Android SDK / Gradle 的位置，探测结果打印在开头，缺哪样直接报错。

## 一键构建

```bash
cd <仓库目录>
./build.sh            # 编 debug 包
./build.sh release    # 编 release 包（开 R8，体积小一半，给手机装用这个）
./build.sh clean      # 先 clean 再编（可组合：./build.sh clean release）
```

产物：
- debug → `app/build/outputs/apk/debug/app-debug.apk`
- release → `app/build/outputs/apk/release/app-release.apk`

日志写进 `build.log`（在 `.gitignore` 里，不入库）。构建结束看日志里同时出现 `BUILD SUCCESSFUL` 与 `EXIT=0` 才算过。全量构建约半分钟；release 带 R8 约 2～2.5 分钟。

## 工具链探测规则

每一项都能用环境变量强行覆盖：

| 组件 | 探测顺序 | 版本要求 |
|---|---|---|
| JDK | `$JAVA_HOME` → `~/.sdkman/candidates/java/current` → `/usr/lib/jvm/java-*-openjdk-*` → 从 `which javac` 反推 | 17 或更高 |
| Android SDK | `$ANDROID_SDK_ROOT` → `local.properties` 的 `sdk.dir` → `~/Android/Sdk` / `~/Library/Android/sdk` 等常见目录 | platform 34 + build-tools 34.0.0 + platform-tools |
| Gradle | `$GRADLE` → PATH 里的 `gradle` → `~/.sdkman/.../gradle/current` → 常见目录 → `./gradlew`（若有） | 8.9 |

探测不到就在命令前加变量，例如 `JAVA_HOME=/your/jdk ./build.sh release`。

## 从零装工具链

```bash
# 1) JDK 17+（Debian/Ubuntu）
apt-get install -y openjdk-21-jdk

# 2) Android SDK 命令行工具：下载 cmdline-tools.zip 解压到 <sdk>/cmdline-tools/latest
export ANDROID_SDK_ROOT=/path/to/sdk
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 3) Gradle 8.9：下载 gradle-8.9-bin.zip 解压，把 bin 加进 PATH（或 export GRADLE=...）
```

**坑**：
- `sdkmanager` 首次要联网下 platform/build-tools，必要时确认走 IPv4。
- 许可没接受会卡在编译期的 license 校验，`yes |` 那一行不能省。
- `local.properties` 里 `sdk.dir` 指向你的 SDK 路径；该文件在 `.gitignore` 里，**不入库**，换机器要自己补（可 `cp local.properties.example local.properties`）。

## 签名

自用包，release 也直接签 debug 密钥：`app/build.gradle.kts` 里
`signingConfig = signingConfigs.getByName("debug")`，用的是 `~/.android/debug.keystore`。

含义：
- 换机器构建**必须带上同一个 debug.keystore**，否则新旧包签名不一致，手机装不上（会提示「应用未安装 / 签名冲突」）。
- 自用场景够用，不追求上架；真要独立签名再建 keystore 并改 `build.gradle.kts`。

## 依赖与仓库

`settings.gradle.kts` 里仓库顺序是**阿里云镜像优先**（gradle-plugin / google / public），再回落到 `google()` / `mavenCentral()`。
国内直连 google maven 慢，镜像优先是刻意配的，别删。首次构建会自动下载依赖并缓存到 `~/.gradle`。

核心依赖版本：AGP 8.5.2、Kotlin 1.9.24、Compose BOM 2024.06.00、compose-compiler 1.5.14、OkHttp 4.12.0、Coil 2.6.0、media3 1.4.1。

## 常见报错对照

| 现象 | 原因 / 处置 |
|---|---|
| `gradle: command not found` | 没用全路径，跑 `./build.sh`（自带探测） |
| `SDK location not found` | `ANDROID_HOME` 没导，或 `local.properties` 缺 `sdk.dir` |
| `Failed to install ... licenses not accepted` | 重跑 `yes | sdkmanager --licenses` |
| `Unsupported class file major version` | JDK 版本过低，换 17 或 21 |
| 手机装新包提示签名冲突 | 构建机换了 / debug.keystore 丢了，用同一份 keystore 重编 |
| 编译卡在下载依赖 | 网络问题，确认阿里云镜像在 `settings.gradle.kts` 里且没被改掉 |
| 构建「超时」但没报错 | 全量构建超了等待窗口，进程还在跑；先看产物 mtime 与 `build.log` 尾部，别盲目重跑 |

更全的踩坑清单（含配置注入、产物发布）见仓库根 README 的「编译踩坑大全」。
