package com.hermesapp

data class Msg(
    val role: String,
    val text: String,
    var pending: Boolean = false,
    val ts: Long = 0L,
    /** 本气泡开始等回复的墙钟时间：进行中时界面实时显示耗时。0 表示不显示。 */
    val startedAt: Long = 0L,
    /** 本条消息附带的本地图片路径（用户发的图用于气泡回显缩略图）。 */
    val images: List<String> = emptyList(),
    /** 本条消息附带的非图片附件名（气泡里回显成文件卡片）。 */
    val files: List<String> = emptyList(),
    /**
     * 本条消息真正发出去时用的附件清单（文件**名**，按发送顺序）。全部文件都在 App 私有
     * 「已发送」目录 `filesDir/sent/` 下——图片与非图片都留了一份。
     *
     * 为什么要按顺序存这份清单（F07）：重发必须拼出与首次**一模一样**的请求体——
     * 服务端算指纹含请求体，附件内容或顺序变了就会被判「键相同、内容不同」而 409。
     * 只靠 images/files 两个展示字段拼不出原始顺序（一个是图片、一个是非图片），
     * 所以单存这一份有序清单作为「原载荷」的凭据。老消息没有这个键 → 为空，重发按旧逻辑。
     */
    val attachments: List<String> = emptyList(),
    /** 过程轨迹（工具调用等）。与正文分开存，界面上默认折叠，不占屏幕。 */
    val trace: String = "",
    /** 本轮 token 用量（run.completed 的 usage），仅助手消息有。 */
    val usage: Usage? = null,
    /** 审批卡片：服务端在等一个 choice 回执；空表示无待审批。 */
    val approval: ApprovalCard? = null,
    /** 澄清卡片：服务端在等我问你选项的回执。 */
    val clarify: ClarifyCard? = null,
    /** 子任务进度（delegate_task 派出的子代理），按开始顺序排列。 */
    val subagents: List<SubagentLine> = emptyList(),
    /** 稳定唯一 id：给 LazyColumn 做 key，避免滑动时整列重组。 */
    val id: Long = nextMsgId(),
    /** 投递状态：只有用户消息有；老消息/服务端拉回的消息为 null。 */
    val receipt: Receipt? = null,
    /** 引用回复：被引用的上一条消息正文片段（空表示不是引用发送）。 */
    val quote: String = "",
    /**
     * 本条的幂等键：发送时生成一次，重发时复用。服务端凭它保证同一句话只执行一次，
     * 重发不再有「可能发两遍」的风险，还能拿回原来那轮的 run_id 直接接上。
     */
    val idemKey: String = "",
    /** 插话气泡：用户在本轮运行中追加的一句，界面按用户气泡显示并标「插话」。 */
    val steer: Boolean = false,
    /**
     * 本轮 run_id（仅助手消息有）。语音重播按钮靠它去服务端取长期留档的 mp3：
     * 流式模式下回复正文里不再带音频附件，按钮不能再看正文，只能看这个键。
     */
    val runId: String = "",
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextMsgId(): Long = counter.incrementAndGet()
    }
}

/**
 * 一条用户消息的投递状态——「服务端到底收下这条没有」。
 *
 *   sending    POST 已发出，回执还没回来（气泡角标转灰点）
 *   accepted   服务端已收下并给了 run_id（角标打勾），本轮才能正常订阅流
 *   uncertain  POST 中途断了，收没收不知道（黄问号）——要用户点一下确认或重发
 *   failed     服务端明确拒绝（HTTP 4xx/5xx，有回执），或确认后发现记录里没有（红叹号）
 *
 * rawText / priorUserCount 是给「确认送达」「重新发送」复用的：
 * 拿原文与本条之前的用户消息条数去服务端记录里认领这条消息。
 */
data class Receipt(
    val status: String,
    val runId: String = "",
    val note: String = "",
    val rawText: String = "",
    val priorUserCount: Int = -1,
    /**
     * 本条发送时用的幂等键，重发必须原样复用：服务端凭它认出「这是同一条」，
     * 只执行一次并把原来那轮的 run_id 还回来。
     */
    val idemKey: String = "",
    /**
     * 首次发送时上传附件拿到的 artifact id。重发必须复用这一份：服务端算指纹时
     * 把请求体一起算进去，附件 id 变了指纹就变了，会被判成「键相同、内容不同」而 409。
     */
    val artifactIds: List<String> = emptyList(),
) {
    companion object {
        const val SENDING = "sending"
        const val ACCEPTED = "accepted"
        const val UNCERTAIN = "uncertain"
        const val FAILED = "failed"
        /** 排队中：本会话还在跑上一轮，这条等它结束自动发（不是发送中，别转圈）。 */
        const val QUEUED = "queued"
        /** 用户已确认忽略「不确定」：收掉角标与提示，不再反复提醒，也不自动重发。 */
        const val ACKED = "acked"
        /**
         * 插话没送达：本轮没赶上下一次工具调用（服务端已收尾）。
         * 插话没有「重发」语义（它属于某一轮），所以这一档只用来如实标注，不给处置按钮（F13）。
         */
        const val STEER_FAILED = "steer_failed"
        /**
         * 未发送：入队时状态是 QUEUED，但 **App 重启后内存里的待发队列已丢**，这条永远不会
         * 自动发出去。读盘时把 QUEUED 归到这一档，别继续显示「排队中」（那是句假话），
         * 界面上给「重新发送 / 知道了」两个出口。
         */
        const val NOT_SENT = "not_sent"
    }
}

/** 审批卡片：服务端 approval.request 事件下发，用户选一个 choice 回执后置 resolved。 */
data class ApprovalCard(
    val requestId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
    val resolved: String = "",
)

/** 澄清卡片：服务端 clarify.request 事件下发（我问你「选 A 还是 B」），点选项回执。 */
data class ClarifyCard(
    val clarifyId: String,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean = false,
    val resolved: String = "",
)

/** 一轮对话的 token 用量（run.completed 的 usage 字段）。 */
data class Usage(
    val input: Int,
    val output: Int,
    val total: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    /** 本轮耗时（毫秒），用来算每秒出多少 token；0 表示没测到。 */
    val durationMs: Long = 0L,
)

/** 一条子任务进度（subagent.start / subagent.complete + 子代理会话轮询回来的进度）。 */
data class SubagentLine(
    val id: String,
    val goal: String,
    val status: String,
    val summary: String = "",
    /**
     * 子代理自己的会话 id（subagent.start 事件里带）。
     * 网关那条 run 事件流只转发 start/complete，中间的 subagent.tool / subagent.progress
     * 被当界面噪音丢了——所以运行中的进度只能靠这个 id 去读子代理自己的会话。
     */
    val childSessionId: String = "",
    /** 已执行步数（子代理会话的 tool_call_count）。 */
    val steps: Int = 0,
    /** 子代理开跑墙钟毫秒（算「已跑多久」用）。 */
    val startedAt: Long = 0L,
    /** 最近一次取到进度的时间（算「N 秒前更新」用）；0 表示还没取过。 */
    val seenAt: Long = 0L,
    /** 子代理会话结束时间（毫秒）；>0 表示这批已经收工。 */
    val endedAt: Long = 0L,
    /** 子代理自己的 token 用量（会话详情的输入+输出）。 */
    val tokens: Int = 0,
)

/** 子任务进度面板里的一步：子代理调了一次什么工具、参数是什么、结果一句话。 */
data class SubagentStep(
    val n: Int,
    val tool: String,
    val arg: String,
    val result: String,
)

/** 子任务进度面板的数据。 */
data class SubagentDetail(
    val key: String,
    val goal: String,
    val childSessionId: String,
    val status: String,
    val steps: List<SubagentStep> = emptyList(),
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val tokens: Int = 0,
    val updatedAt: Long = 0L,
    /** 拉不到（会话被清、网络断）时的说明，界面照实显示。 */
    val note: String = "",
)

/** 服务端会话记录的一行（翻历史兜底时用来认锚点、认答案）。 */
internal data class HistRow(val role: String, val text: String, val id: String)

/** 待发送的附件：uri 用于展示（图片缩略图），file 是拷进沙盒后的真实文件。 */
data class PendingImage(
    val id: Long,
    val uri: String,
    val file: java.io.File,
    /** 是否图片：决定待发区显示缩略图还是文件卡片。 */
    val isImage: Boolean = true,
)

/**
 * 排队待发的一条消息（本会话正在跑任务时用户又发的那条）。
 * 用户消息此刻已经进了气泡，只是 POST 还没发出去；等本轮结束自动发。
 * text 是发往服务端的完整正文（含引用片段），files 是已拷进沙盒的附件。
 */
data class QueuedSend(
    val text: String,
    val files: List<java.io.File>,
    /** 对应的用户消息 id：发送时按它更新投递回执。 */
    val msgId: Long,
    /** 入队时该会话已有的用户消息条数：翻历史认锚点要用。 */
    val priorUserCount: Int,
    /** 入队时生成的幂等键：真正发出去时用它，重发也复用，保证只执行一次。 */
    val idemKey: String = "",
)

/** 状态页的一行：标签 + 值。value 为空则该行不显示。 */
data class StatusItem(val label: String, val value: String)

/** 状态页的一个分组：标题 + 若干行。 */
data class StatusSection(val title: String, val items: List<StatusItem>)

/**
 * 状态页的一根进度条（CPU / 内存 / Swap / 磁盘 / 负载）。
 * percent 0..100；valueText 是条右端的数值；subText 是条下方的小字说明。
 */
data class StatusMetric(
    val key: String,
    val label: String,
    val percent: Double,
    val valueText: String,
    val subText: String = "",
)

/** 状态页顶部的概览卡：一眼看完「活着没、跑什么模型、跑了多久、在干几件事」。 */
data class StatusHero(
    val ok: Boolean,
    val statusText: String,
    val version: String,
    val uptimeText: String,
    val pid: Int,
    val activeRuns: Int,
    val delegations: Int,
)

/** 定时任务页的一条（来自服务端 /api/jobs）。中文名/说明由本地映射表翻译。 */
data class JobItem(
    val id: String,
    val name: String,
    /** 中文名；不认识的返回空，界面回落显示原始名。 */
    val zhName: String,
    /** 这个任务是干什么的中文说明；不认识的为空。 */
    val note: String,
    val schedule: String,
    val enabled: Boolean,
    val state: String,
    val lastStatus: String,
    /** 上次运行是否成功（用于配色，不受翻译影响）。 */
    val lastOk: Boolean,
    val lastRun: String,
    val nextRun: String,
    // ---- 最近一次执行明细（服务端 /api/jobs 每条都带的 latest_execution）----
    /** 执行记录 id：用来判断「立即执行」后是否真的出现了新的一次执行。 */
    val execId: String = "",
    /** claimed / running / completed / failed / unknown。 */
    val execStatus: String = "",
    /** 执行耗时文案（已算好）；拿不到起止时间时为空。 */
    val execDuration: String = "",
    /** 失败原因（仅 failed 时有值）。 */
    val execError: String = "",
    /**
     * 投递失败原因：任务本身跑成功、但结果没送到用户手上时才有值。
     * 与 execError 分开——一个是「活儿干砸了」，一个是「干完了没送到你手上」。
     * 服务端字段 last_delivery_error，此前 App 完全没读，真故障被藏在日志里。
     */
    val deliveryError: String = "",
    // ---- 任务详情（展开「详情」时才显示；服务端 /api/jobs 本来就有这些字段）----
    /** 任务描述原文（服务端 prompt）：这条定时任务到底让 agent 干什么，全文可能几千字。 */
    val desc: String = "",
    /** 投递方式（api / bot-chat / local 等）。 */
    val deliver: String = "",
    /** 该任务使用的模型。 */
    val model: String = "",
    /** 创建时间（已格式化为本地时区）。 */
    val createdAt: String = "",
    /** 已执行次数（服务端 repeat.completed）。 */
    val repeatDone: Int = 0,
)

/**
 * 一个会话的运行态摘要（侧边栏显示「执行中 / 子任务 N / 排队 N」用）。
 * 三者任一为真就算「有动静」。
 */
data class SessionRunFlag(
    /** 本会话本地正跑着一轮。 */
    val busy: Boolean = false,
    /** 本会话里正在跑的子任务（delegate_task 子代理）个数。 */
    val subagents: Int = 0,
    /** 排队待发的条数。 */
    val queued: Int = 0,
    /**
     * 服务端说这个会话正在跑，而本地没有对应的运行时——轮次是别的端（微信 / CLI / 桌面）
     * 发起的。2026-10-08 加：此前 App 只认自己内存里的 runtimes，别的端起的轮次在侧边栏
     * 一条提示都没有，任务跑完了也不通知。
     */
    val remote: Boolean = false,
) {
    val active: Boolean get() = busy || subagents > 0 || queued > 0 || remote
}

/**
 * 收件箱里一条定时任务产出（服务端 GET /api/inbox 的一条）。
 * 为什么走收件箱而不是推送：App 走 api_server 通道，那条适配器声明不支持推送
 * （supports_async_delivery=False），定时任务结果没法主动送过来，只能 App 来拉。
 */
data class CronReport(
    /** 收件箱条目 id：确认已读按它标记。 */
    val id: String,
    /** 产出时刻（服务端 ISO 串，带时区）。 */
    val at: String,
    val jobId: String,
    val jobName: String,
    /** true = 这是「任务没跑成 / 配置有问题」的通知，不是正常产出。 */
    val failed: Boolean,
    /** 正文（服务端已脱敏、超长已截断）。 */
    val body: String,
    /** 还没确认已读。 */
    val unread: Boolean,
)

