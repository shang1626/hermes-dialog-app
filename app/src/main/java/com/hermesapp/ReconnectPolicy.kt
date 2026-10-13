package com.hermesapp

/**
 * 重连退避与「服务端还在跑」的状态集合。
 *
 * 抽成无 Android 依赖的纯对象，便于单元测试（app/src/test/.../ReconnectPolicyTest.kt）。
 * 这是阶段2「关键路径单测」的第一步：把纯逻辑从 ChatViewModel 里挪出来，测得到，才谈得上后续重构。
 */
object ReconnectPolicy {

    /** SSE 断流后的最大自动重连次数。 */
    const val MAX_RECONNECT_ATTEMPTS = 8

    /**
     * 「服务端还在跑」的状态集合：探测到这些状态就续接事件流，不判结束。
     * 必须与 api_server 的 run 状态机逐字对齐（含 stopping——已请求停止但还没收尾）。
     */
    val RUNNING_STATES = setOf(
        "started", "running", "waiting_for_approval", "waiting_for_clarify", "queued", "stopping",
    )

    /** 退避等待：第 n 次重试 2^(n-1) 秒（首次 1 秒），封顶 30 秒。 */
    fun backoffDelayMs(attempt: Int): Long {
        val base = 1000L shl (attempt - 1).coerceIn(0, 5)   // 1,2,4,8,16,32...
        return base.coerceAtMost(30_000L)
    }

    /**
     * 一条流多久没有任何一行（含服务端每 10 秒的心跳帧）就算死了。
     * 45 秒 = 连丢四拍心跳，正常的长工具执行（期间只有心跳）绝不会被误判。
     */
    const val STREAM_DEAD_AFTER_MS = 45_000L

    /**
     * 会话列表（`GET /api/sessions`）的轮询节拍，单位是 2 秒心跳的 tick 数。
     * 有任务在跑时 30 秒一轮（在跑的状态不能滞后），空闲时 120 秒一轮。
     * 2026-10-13 省电：这一项原来固定 30 秒一轮、响应 167 KB，实测 11.5 MB/小时
     * （手机上第二大的一笔）；响应侧另有 compact=1 补丁把它压到约 17 KB。
     */
    const val SESSIONS_SYNC_TICKS_ACTIVE = 15
    const val SESSIONS_SYNC_TICKS_IDLE = 60

    fun sessionsSyncTicks(hasActiveRuns: Boolean): Int =
        if (hasActiveRuns) SESSIONS_SYNC_TICKS_ACTIVE else SESSIONS_SYNC_TICKS_IDLE

    /** 增量窗口的常规大小（服务端原始行，含工具行）。 */
    const val SYNC_TAIL_ROWS = 40

    /** 本地落后服务端超过这么多行时，不再拼窗口、直接整份拉。 */
    const val SYNC_MAX_TAIL_ROWS = 400

    /**
     * 这次同步该向服务端要多少**原始行**；返回 0 = 整份拉。
     *
     * 铁律（2026-10-13 用户明确要求）：**本地绝不能比服务端少**。所以窗口不是固定 40 行，
     * 而是「常规 40 行 + 本地落后服务端的行数」—— 落后多少就把多少补进窗口里，本地缺、
     * 只在服务端的内容一定落在窗口内；落后超过 [SYNC_MAX_TAIL_ROWS] 就直接整份拉。
     *
     * @param serverRows 服务端该会话的 message_count（含工具行）；<0 = 未知（那就整份拉）
     * @param localRows  本地原始行代理，见 [localRawRows]
     * @param localEmpty 本地这个会话一条内容都没有（首次加载）
     */
    fun syncFetchRows(serverRows: Int, localRows: Int, localEmpty: Boolean): Int {
        if (localEmpty || serverRows < 0) return 0
        val gap = (serverRows - localRows).coerceAtLeast(0)
        if (gap > SYNC_MAX_TAIL_ROWS) return 0
        return SYNC_TAIL_ROWS + gap
    }

    /** 本地原始行代理：服务端把每个工具调用也存成一行，App 侧对应的是过程轨迹的行数。 */
    fun localRawRows(messageCount: Int, traceLines: Int): Int = messageCount + traceLines.coerceAtLeast(0)

    /**
     * 看门狗重起流的节流间隔：同一会话最多这么久重起一次。
     * 重起本身是幂等的（新流会掐掉旧流并按代际号作废旧回调），节流只为别刷日志、别白打请求。
     */
    const val STREAM_RESTART_THROTTLE_MS = 15_000L

    /**
     * 看门狗判据：这一轮**还在跑**，但手上没有一条活着的流 → 必须重起流。
     *
     * 为什么要有这条（2026-10-13 真机现场）：断流后的重连链有三个已知的静默出口 ——
     * ① 流代际号把这条流的回调作废；② 同会话探测的单飞位没释放；③ 退避 8 次用尽转「翻历史」后，
     * 那条分支只留一行提示、不起任何任务。
     * 任何一条命中，界面上「正在执行」就永久停住（实测：01:33:37 起流的任务，流 01:45:59
     * 断掉，之后十分钟 App 一次都没再连）。
     *
     * 判据只看**事实**（有没有流、流多久没动静），不看是哪条链挂了 —— 这样以后新增的
     * 静默出口也自动被兜住。纯函数，便于单测钉住。
     */
    fun streamNeedsRestart(
        busy: Boolean,
        finished: Boolean,
        hasRunId: Boolean,
        hasCall: Boolean,
        callCanceled: Boolean,
        msSinceLastEvent: Long,
    ): Boolean {
        if (!busy || finished || !hasRunId) return false
        if (!hasCall || callCanceled) return true
        return msSinceLastEvent > STREAM_DEAD_AFTER_MS
    }

    // ---- 重连提示的自动清理（2026-10-13 现场：那行提示一直钉在会话上方）--------------------

    /**
     * 「正在重连 / 正在取回」这类提示的统一前缀。
     *
     * 为什么清理判据要看**提示本身的内容**、不能只看退避计数：看门狗重起流时会把
     * `autoContinue` 归零并写下「连接中断，正在重连…」，而清理条件原来是 `autoContinue != 0`
     * —— 于是流早已恢复、进度也在正常刷新，那行提示却再也不消失（用户报「一直显示连接中断，
     * 正在重连」）。规则：**这行提示描述的是「当下的连接状态」，状态一不成立它就必须自己消失**。
     *
     * 所有重连类提示都必须经 [noteReconnecting] / [noteForegroundReconnect] 构造 ——
     * 在别处手写这个前缀的字符串会让它逃出自动清理，这正是本次缺陷的成因。
     */
    const val RECONNECTING_NOTE_PREFIX = "连接中断"

    /** 回前台时那条「回到前台，正在重连…」的前缀。 */
    const val FOREGROUND_RECONNECT_NOTE_PREFIX = "回到前台，正在重连"

    /** 构造「连接中断…」提示（唯一入口）。 */
    fun noteReconnecting(suffix: String = "，正在重连…"): String = RECONNECTING_NOTE_PREFIX + suffix

    /** 构造「回到前台，正在重连…」。 */
    fun noteForegroundReconnect(): String = FOREGROUND_RECONNECT_NOTE_PREFIX + "…"

    /**
     * 状态轮询兜底那三句提示的定稿文案 / 公共前缀。
     *
     * 三句都要登记进 [STALE_WHEN_STREAM_RECOVERS] 才清得掉，所以**构造文案只走本节的函数**，
     * 别在别处手写这几个字（手写就等于让它逃出自动清理——重连提示就是这么出过一次缺陷）。
     * 声明位置必须在 [STALE_WHEN_STREAM_RECOVERS] **之前**：`const val` 参与对象初始化顺序，
     * 写在后面会让那张表报「Variable must be initialized」。
     *
     * `LIVE_RUN_CONFIRMING_NOTE` 是「探测结果还没出来」那一刻显示的：只描述正在做什么，
     * **不含任何结论**——404 的成因不止一种，其中最常见的一种恰恰是「这一轮刚跑完」。
     */
    const val LIVE_RUN_CONFIRMING_NOTE = "正在向服务器确认这一轮的状态…"
    const val LIVE_RUN_RUNNING_NOTE_PREFIX = "这一轮还在服务器上跑"
    const val LIVE_RUN_UNREACHABLE_NOTE_PREFIX = "暂时问不到这一轮的状态"

    /** 这行提示是不是在讲「当下正在重连」——流一恢复它就不成立。 */
    fun isReconnectingNote(note: String): Boolean = STALE_WHEN_STREAM_RECOVERS.any { note.startsWith(it) }

    /**
     * 「流一接上就不该再留在屏幕上」的提示前缀。
     *
     * 除了两种重连文案，还包括状态轮询兜底那几句（正在确认 / 还在服务器上跑 / 暂时问不到状态）：
     * 它们同样只在「流接不上」时成立。**为什么必须并进来**——回前台会把 `autoContinue` 归零
     * （见 `onAppForeground`），此后新接上的流收到真实事件时 `shouldClearNoteOnEvent` 的两个
     * 条件（退避计数非零、提示属重连类）就都不成立，那行提示会永久钉在会话上方。本仓这类
     * 「写入者与清理者判据不一致」的残留已复发过两次（重连提示、语音排队提示），所以新写的
     * 每一句状态提示都要在这里登记。
     */
    private val STALE_WHEN_STREAM_RECOVERS = listOf(
        RECONNECTING_NOTE_PREFIX,
        FOREGROUND_RECONNECT_NOTE_PREFIX,
        LIVE_RUN_CONFIRMING_NOTE,
        LIVE_RUN_RUNNING_NOTE_PREFIX,
        LIVE_RUN_UNREACHABLE_NOTE_PREFIX,
    )

    /**
     * 收到一个真实事件后，这行提示该不该清掉。
     *
     * ① 退避链收到事件（`autoContinue != 0`）→ 清（既有行为，保持不动）；
     * ② 不经退避计数的恢复路径（看门狗重起流、回前台掐假死流）→ 只要提示本身是重连类也清。
     *
     * `replay.truncated` 不算「恢复」：它是服务端在说「断线期间的事件已经补不回来了」，
     * 此时清掉提示会把「正在取回」的语义抹掉（既有行为，别改）。
     */
    fun shouldClearNoteOnEvent(note: String, eventName: String, autoContinue: Int): Boolean {
        if (eventName == "replay.truncated") return false
        return autoContinue != 0 || isReconnectingNote(note)
    }

    // ---- 「这一轮还在服务端跑」这句提示（2026-10-14 现场：已完成被说成还在跑）------------------
    // 三句文案与其前缀常量声明在文件上方（[STALE_WHEN_STREAM_RECOVERS] 之前，初始化顺序所限）。

    /**
     * 状态轮询兜底时那句提示：把「已跑多久」和「服务端做到哪一步了」一起给出来。
     *
     * 两条口径写在文案里，因为用户对它们的读法完全不同：
     * ① 时长答「跑了多久」，只靠本地锚点；
     * ② **服务端最后一次有动静是什么时候**答「在算还是卡住了」——本地掐表答不了这个，
     *    只有服务端状态里的时间戳能答。
     *
     * @param elapsedMs      本轮已跑毫秒数（0 = 起点未知，只报「还在跑」）
     * @param lastEvent      服务端 run 状态的 `last_event`（事件名，可能为空）
     * @param lastEventAgeMs 现在距服务端 `updated_at` 的毫秒数；<0 = 不知道
     */
    fun liveRunNote(elapsedMs: Long, lastEvent: String, lastEventAgeMs: Long): String {
        val mins = elapsedMs / 60_000L
        val head = if (mins > 0) LIVE_RUN_RUNNING_NOTE_PREFIX + "（已 $mins 分钟）" else LIVE_RUN_RUNNING_NOTE_PREFIX
        return head + liveRunDetail(lastEvent, lastEventAgeMs) + "，跑完自动取回"
    }

    /**
     * 「做到哪一步了」这半句。
     *
     * 只用服务端真给得出的东西，且**宁可少说**：`updated_at` 只在工具事件与状态变更上刷新，
     * 流式正文（message.delta）不经状态写入，所以它代表的是「最后一个工具步骤」而不是
     * 「最后收到一个字」——长回答期间的静默是正常的，不能读成卡住，因此这里只讲工具步骤。
     * 认不出来（事件名不在表里 / 没有时间戳）就一个字都不加，别编。
     */
    fun liveRunDetail(lastEvent: String, lastEventAgeMs: Long): String = when {
        lastEvent == "approval.request" -> "，服务端在等你确认一条审批"
        lastEvent == "clarify.request" -> "，服务端在等你回答一个问题"
        lastEvent == "run.stopping" -> "，正在停止这一轮"
        lastEvent.startsWith("tool.") -> when {
            lastEventAgeMs < 0L -> ""
            lastEventAgeMs < 60_000L -> "，刚刚还有工具步骤在跑"
            else -> "，最后一个工具步骤在 " + (lastEventAgeMs / 60_000L) + " 分钟前"
        }
        else -> ""
    }

    /** 网络探不出状态时那句：说实话（问不到 ≠ 已结束），并给出已试次数。 */
    fun liveRunUnreachableNote(misses: Int, maxMisses: Int): String =
        LIVE_RUN_UNREACHABLE_NOTE_PREFIX + "（第 $misses/$maxMisses 次，多半是网络不通），继续试"
}
