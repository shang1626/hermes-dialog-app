# A11 / A13 通知与更新审计

日期：2026-10-22。未提交、未发布、未 push、未重启；未修改版本与签名配置。

## 测试方式与证据

直接编译当前生产 Kotlin，测试调用 `Notifier.notifyDone` / `notifyAction` / `HermesApi.downloadApk`；没有复制旧/新实现来冒充生产调用。

- Android 行为：Robolectric 4.14.1，API 28；拒绝 POST_NOTIFICATIONS 用 API 33。
- 下载：本机 ServerSocket 提供真实 HTTP，生产 OkHttp 发起连接；覆盖 Content-Length、chunked、截断、101 MiB 响应。
- 独立测试源码：`app/src/auditTest/java/com/hermesapp/NotificationUpdateAuditTest.kt`。
- Gradle init：`scripts/audit-notification-update.init.gradle`，仅审计运行时注入 Robolectric 与测试源码目录，不修改正式依赖或签名。构建目录隔离在 `/root/.hermes/cache/scratch/hermes-notification-update-build`，避免共用工作树构建产物冲突。

执行：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/root/android-tools/gradle-8.9/bin/gradle \
-I scripts/audit-notification-update.init.gradle \
:app:testDebugUnitTest --tests com.hermesapp.NotificationUpdateAuditTest --console=plain
```

原实现（修改生产代码前真实执行）：11 tests / 6 failures / 0 errors。

- 禁用 App 通知：实际触发 direct vibrator。
- 完成渠道无声音且无震动：实际触发 direct vibrator。
- 完成渠道 IMPORTANCE_LOW：实际触发 direct vibrator。
- 空摘要且 HTTP 长度正确：仍返回安装文件。
- chunked 101 MiB：仍返回安装文件。
- progress 回调抛异常：`.part` 遗留。

原始证据：`/root/.hermes/cache/scratch/audit-notification-before.log`、`/root/.hermes/cache/scratch/audit-notification-before.xml`（XML timestamp 2026-10-22T15:32:26 UTC）。

修复后定向测试：18 tests / 0 failures / 0 errors，BUILD SUCCESSFUL。
再跑全部 JVM 单测（移除命令的 `--tests ...`）：225 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL in 8s。
日志：`/root/.hermes/cache/scratch/audit-notification-after.log`、`/root/.hermes/cache/scratch/audit-notification-full.log`。
XML：`/root/.hermes/cache/scratch/hermes-notification-update-build/test-results/testDebugUnitTest/`。

## A11 修复

`Notifier.kt` 移除绕开通知策略的直接 Vibrator / Ringtone 兜底。仍通过既有渠道发送通知，正常通知仍有原图标、动作、点击 PendingIntent 和完成渠道；渠道配置与系统勿扰规则交给 Android 执行。

`notifyMessage` / `notifyAction` 真正检查 Android 13+ POST_NOTIFICATIONS 权限；拒绝时不投递。两处发送均明确捕获 SecurityException 并记录日志，不使用 SuppressLint。

覆盖禁用、静音、低重要性不直接震动，正常完成仍生成通知，API 33 拒权时完成与审批通知都不投递。

## A13 修复与待接线

`net/HermesApi.kt`：

- 保留旧 downloadApk 签名；所有下载强制合法 32 位十六进制 MD5，空/非法摘要拒绝。
- HTTP 声明长度及实际流量均限制 100 MiB；chunked 也受限，写盘前判超限。
- 空文件、长度不符、摘要不符拒绝交付；finally 清理 `.part`，含读流异常、progress 异常和取消。
- 校验前不删除既有最终包，只有新包校验成功才提升、清理旧包。
- 新增 `downloadApk(info: UpdateInfo, ctx: Context, onProgress)`；要求清单 size 在 1..100 MiB，核对清单/HTTP/实际流量三者，chunked 同样检查实际大小。

新增测试覆盖合法大小/摘要（含大写摘要）、合法 chunked、摘要缺失/非法/不符、HTTP 声明超限、chunked 实际超限、清单过大/过小不符、截断清理和 progress 异常清理。

**尚需父代理接线：** VM 属其他并行人员负责，本批没有修改。当前 `ChatViewModel.kt` 更新下载入口在 `fun confirmUpdate` 内（本次定位约第 4982 行，行号随并行修改变化）：

```kotlin
// 原调用
val f = a.downloadApk(info.url, ctx, info.versionName, info.md5) { done, total ->
// 改为
val f = a.downloadApk(info, ctx) { done, total ->
```

进度回调与后续安装逻辑原样保留。接线前：摘要与硬上限已生效，但生产 VM 不会传清单 size，不能宣称清单大小核对已端到端完成。新重载本身已由真实 HTTP 调用测试。

## 边界与问题

- 初次共用 app/build 构建遇 Kotlin class 缺失的并发冲突，已改独立构建目录；未重置他人文件。
- Android Kotlin 测试编译环境无 JDK HttpServer，测试服务改为 ServerSocket 后成功运行。
- 本批未执行 lint（由父代理最终统一执行），未出 APK。父代理要求的 MissingPermission 两处已用真实权限检查与 SecurityException 处理修正。
- Robolectric 不等同手机硬件实测：已证明生产不再调用直接震动兜底，未声称手机实际铃声/勿扰硬件验证。
- MD5 是发布协议既有摘要，防残包/误下载，不提供发布者密码学认证；没有把它宣传成签名认证，也未更换 APK 签名。
- 全树 diff --check 发现并行 Prefs.kt 第 22 行尾空白；本批不修改他人负责文件。
