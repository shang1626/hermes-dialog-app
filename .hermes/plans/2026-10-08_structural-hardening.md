# Hermes App 结构性加固专题

> 来源：2026-10-08 对同事评估报告「剩余条目」的实测评估。4 条全部属结构性工程，需单开专题、按序推进。

**目标：** 把「跨线程共享可变状态无锁」这个反复制造 bug 的根因收敛掉，并在此之前先建立起能兜住重构的测试与 CI 网。

**架构：** 纯客户端改动，不碰服务端、不改协议。分四阶段，顺序不可颠倒（后一阶段以前一阶段为前置条件）。

**技术栈：** Kotlin / Compose / OkHttp / JUnit + kotlinx-coroutines-test / GitHub Actions（或 Hermes cron 纯脚本兜底）。

**基线：** 2.143 (154)，commit 42578c1，工作树干净。

---

## 实测量出的规模（决策依据）

| 指标 | 实测值 |
|---|---|
| ChatViewModel.kt | 4626 行 / 172 函数 / 222 个类级字段 |
| SessionRuntime 可变 var | 25 个，@Volatile 0 个，Atomic 1 个 |
| HermesApi.kt | 767 行 / 0 个 suspend / 13 处阻塞 .execute() |
| 测试文件 | 0 |
| CI | 无 .github |
| 生命周期收集 | 58 处 collectAsStateWithLifecycle（2.143 已清） |

---

## 阶段 1：CI 门禁（最便宜、无风险、不碰主逻辑）

**目标：** 每次 push/PR 自动 `assembleRelease` + lint，防「编不过的代码进主干」。

**为什么先做：** 成本极低（一天内），且是所有后续重构的安全底线——重构第一步先要能证明「没把东西改坏」。

**Files:**
- Create: `.github/workflows/android-ci.yml`

**要点：**
- 本仓无 gradlew，用系统 gradle（CI 里 `gradle/actions/setup-gradle` 装 8.9）。
- JDK 21、Android SDK 34（`android-actions/setup-android`）。
- `lint` 不设 `--exit-zero`，作为软门禁先只警告不阻断，观察一轮再收紧。
- **无密钥 CI 要能过**：`local.properties` 里 buildConfigField 的 key/域名本机有、CI 没有 → 让 build.gradle.kts 对缺省值容忍（空串），不阻断编译。

**验证：** 推一个空提交触发 workflow，看 Actions 绿。

**风险/回滚：** 极低。删掉 yml 即回滚。

---

## 阶段 2：关键路径单测（重构的前置条件，可分次攒）

**目标：** 给「无 UI 依赖的纯逻辑」建测试网，每修一个模块补一组，不是一次性。

**可测且值的四块（按优先级）：**
1. **网络重试/退避链**：`backoffDelayMs`、`callText` 的重试语义（幂等、连接级 vs HTTP 级）、`RUNNING_STATES` 状态集合与 api_server 逐字对齐。
2. **存储层**：`writeAtomic`（fsync 后 rename）、`readTextOrBackup` 回退、`loadMessages`/`saveMessages` 丢弃条件、归档不丢、schema 版本兼容老裸数组。
3. **SSE 解析**：单行 `data: {json}` 解析、`Last-Event-ID`、`replay.truncated`。
4. **Markdown fence**：`inFence` 状态下代码块里的 `|` 不当表格。

**Files:**
- Create: `app/src/test/java/com/hermesapp/...`（逐模块）
- Modify: `app/build.gradle.kts` 加 `testImplementation`（junit、kotlinx-coroutines-test）

**难点：** 现有代码几乎无纯函数边界 → 每测一块先抽一个纯函数/纯类出来（这一步本身就是去耦合的开始）。

**验证：** `./gradlew test`（本机手写 gradle test 命令）全绿；CI 里也跑。

**风险/回滚：** 低，纯增量。

---

## 阶段 3：P1-1 跨线程共享可变状态收敛（唯一真会继续出事的）

**目标：** 把网络回调（OkHttp 线程）对 `SessionRuntime` 的写，改成「结果 post 回单一 dispatcher 再写」。

**实锤热点：**
- OkHttp `onResponse`/`onFailure` 跑在 OkHttp 线程池，直接写 `r.streamGen`/`r.lastEventAt`/消息列表；主线程同时写。
- `streamGen` 是读-改-写（3818: `val gen = r.streamGen + 1; r.streamGen = gen`）。
- 25 个 var 字段绝大多数无同步。

**危害形态：** 偶发消息重复/丢失、gen 判错 → 重复播报/重复通知（历史 2.81 栽过）。

**改动面：** 18 处 RuntimeHub.scope.launch + 27 处 viewModelScope.launch + OkHttp 回调。动的是主状态机。

**前置：** 阶段 2 的测试网必须已覆盖流/收尾/重连路径。

**验证：** 单测覆盖并发场景 + 真机回归（切后台回前台、断流重连、多任务并发）。

**风险/回滚：** 高。每个子步骤一小提交，可逐个 revert。

---

## 阶段 4：P1-2 上帝类拆解（最贵最险，纯维护性）

**目标：** ChatViewModel（4626 行）按职责拆成若干 manager（会话/发送/流/同步/存储协调）。

**为什么最后：** 无行为收益、用户不可感知；无测试网做等于闭眼大手术。

**前置：** 阶段 3 完成（状态已收敛，拆解时接口更清晰）。

**验证：** 全量单测 + 真机回归（每拆一块发一版或攒一批发）。

**风险/回滚：** 最高。按文件/职责分批，每批一提交。

---

## 开放问题

1. CI 用 GitHub Actions 还是 Hermes cron 纯脚本？GitHub Actions 更标准但需联网跑 SDK；本机 cron 零 token 但只覆盖本机。**建议：Actions 为主（本仓已推公开仓库），cron 兜底。**
2. 阶段 3 的「单一 dispatcher」用 `Dispatchers.Main.immediate` 还是专用单线程 dispatcher？**建议：专用单线程 dispatcher**，与主线程解耦。
3. 拆解粒度：先拆「存储协调」还是「网络/流」？**建议：先拆无状态工具（网络重试、存储）再到有状态机。**
