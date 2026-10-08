package com.hermesapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesapp.net.HermesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 异常摘要：优先「类名: message」。
 *
 * 为什么要带类名：NetworkOnMainThreadException 这类异常的 message 是 null，
 * 老写法 `e.message ?: "?"` 会把真实原因吞成「?」，用户只看到「收件箱获取失败：?」，
 * 排查时等于没有信息。
 */
private fun diagText(e: Throwable): String {
    val m = e.message
    val n = e.javaClass.simpleName
    return if (m.isNullOrBlank()) n else n + ": " + m
}

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
private data class HistRow(val role: String, val text: String, val id: String)

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
    val model: String,
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

/** SSE 断流后的最大自动重连次数（退避等待，见 ChatViewModel.backoffDelayMs）。 */
private const val MAX_RECONNECT_ATTEMPTS = 8

/**
 * 回前台自动同步的节流窗口：来回快速切前后台时，别每次回前台都打一遍接口。
 * 3 秒足够挡住「切出去看一眼通知再切回来」这类连击。
 */
private const val FOREGROUND_SYNC_THROTTLE_MS = 3_000L

/**
 * 「服务端还在跑」的状态集合：探测到这些状态就续接事件流，不判结束。
 * 注意必须与 api_server 的 run 状态机一致（含 stopping——已请求停止但还没收尾）。
 */
private val RUNNING_STATES = setOf(
    "started", "running", "waiting_for_approval", "waiting_for_clarify", "queued", "stopping",
)

/** 重开 App 恢复活跃任务时，探测状态的尝试次数（开机网络未就绪时多试几次）。 */
private const val RESUME_PROBE_TRIES = 3

/**
 * 合并服务端与本地记录时，认定「同一条用户消息」的时间窗口。
 * 服务端压缩会重发行 id、改写老行，但保留原时间戳；手机与服务端钟差 + 发送延迟
 * 一般远小于这个窗口，超过即不认（避免把两句同文本的重复提问错配成一条）。
 */
private const val REWRITTEN_ROW_WINDOW_MS = 120_000L

/**
 * 历史域名 → 当前域名。App 把服务器地址存在 prefs 里（登录时填的那次），
 * 就地升级不会改；域名一换，老用户的地址就成了死链，表现正是「一直重连连不上」。
 * 命中即静默改写成新地址，用户不用重新登录。
 *
 * 具体域名对不写进源码：由 local.properties 的 HERMES_LEGACY_HOSTS
 * （形如 old.example.com=new.example.com，多个用逗号分隔）在构建期注入。
 */
private val LEGACY_HOSTS: Map<String, String> = BuildConfig.LEGACY_HOSTS
    .split(",")
    .mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }
    .toMap()

/** 断线丢事件时补进正文的提示行。 */
private const val TRUNCATED_NOTICE = "\n[提示] 断线期间有内容未收到，已从服务端补拉最新结果\n"

/**
 * 断流翻历史的盯梢窗口：重连退避用尽后，改为轮询服务端会话记录等答案落盘。
 * 首次等 5 秒，逐次翻倍到 30 秒封顶，最多盯 30 分钟。
 */
private const val HISTORY_RECOVERY_WINDOW_MS = 30L * 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private var store = SessionStore(app, prefs.profile)

    init {
        // 留一行证据：同一个进程里 ViewModel 重建会打多行，
        // 用于确认「切后台回来进度丢」是不是被系统重建引起。
        AppLog.log("vm", "ChatViewModel 新建 pid=" + android.os.Process.myPid())
    }

    // 会话运行态改放进程级单例（见 RuntimeHub.kt）：重建接回同一份，不再分叉。
    private val runtimes get() = RuntimeHub.runtimes
    private fun rt(id: String): SessionRuntime = RuntimeHub.rt(id)

    private val _currentId = MutableStateFlow("")
    val currentId = _currentId.asStateFlow()

    /** 当前会话的消息/忙闲/重连提示：随 _currentId 切换，后台会话互不影响。 */
    val messages: StateFlow<List<Msg>> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(emptyList<Msg>()) else rt(id).messages }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前会话里的全部子任务（跨气泡汇总、按 id 去重取最新状态），供对话窗口顶部的面板用。
     *
     * 为什么要在顶部汇总：子任务原来只在它那一条气泡里显示，对话一长就得往回翻半天才能找到
     * 「那个子任务跑到哪了」。汇总到顶部后，不管对话多长都在同一处看。
     * 排序：正在跑的排前面，同组内按开始时间倒序（新的在上）。
     */
    val sessionSubagents: StateFlow<List<SubagentLine>> = _currentId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(emptyList())
            else rt(id).messages.map { msgs ->
                val byId = LinkedHashMap<String, SubagentLine>()
                for (m in msgs) {
                    for (s in m.subagents) byId[s.id] = s   // 后出现的覆盖先前的 = 取最新状态
                }
                byId.values.sortedWith(
                    compareBy({ it.status != "running" }, { -it.startedAt })
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val busy: StateFlow<Boolean> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(false) else rt(id).busy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val retryNote: StateFlow<String> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf("") else rt(id).retryNote }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * 当前会话里「在等我点头」的卡片（审批或澄清），供对话窗口顶部一条醒目提示。
     *
     * 为什么要置顶：卡片原来是嵌在助手气泡里的（和正文同一块），对话一长就埋在中间，
     * 得滚半天才看得到 —— 用户报「长文本上面看不到」。置顶后不管滚到哪都在眼前，
     * 点一下直接跳到那张卡片。数据源就是本地已有的消息，不额外发请求。
     */
    data class PendingAction(val msgId: Long, val title: String, val summary: String, val isApproval: Boolean)

    val pendingAction: StateFlow<PendingAction?> = _currentId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(null)
            else rt(id).messages.map { msgs ->
                // 取最后一条未处理的卡片；审批优先（同会话极少两者同时有）。
                val ap = msgs.lastOrNull { it.approval?.resolved?.isEmpty() == true }
                val cl = msgs.lastOrNull { it.clarify?.resolved?.isEmpty() == true }
                when {
                    ap != null -> PendingAction(
                        ap.id, "需要你确认",
                        ap.approval?.description?.ifEmpty { ap.approval?.command.orEmpty() }.orEmpty(), true)
                    cl != null -> PendingAction(
                        cl.id, "需要你选一下", cl.clarify?.question.orEmpty(), false)
                    else -> null
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前会话排队待发的条数：输入栏显示「排队 N 条」。 */
    val queuedCount: StateFlow<Int> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(0) else rt(id).queued }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 当前会话的待发队列是否被「停止」按住（界面显示「继续」按钮）。 */
    val queuedPaused: StateFlow<Boolean> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(false) else rt(id).queuePaused }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online = _online.asStateFlow()

    private val _statusSections = MutableStateFlow<List<StatusSection>>(emptyList())
    val statusSections = _statusSections.asStateFlow()

    /** 顶部概览卡 + 进度条数据（与 _statusSections 同一次 /health/sysinfo 拉取产出）。 */
    private val _statusHero = MutableStateFlow<StatusHero?>(null)
    val statusHero = _statusHero.asStateFlow()

    private val _statusMetrics = MutableStateFlow<List<StatusMetric>>(emptyList())
    val statusMetrics = _statusMetrics.asStateFlow()

    private val _statusErr = MutableStateFlow("")
    val statusErr = _statusErr.asStateFlow()

    private val _updateNote = MutableStateFlow("")
    val updateNote = _updateNote.asStateFlow()

    /** 有待确认的新版本时为非空。 */
    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()

    /** 服务端存在比本机更新的版本时为 true：抽屉里「设置」右上角显示绿点。 */
    private val _updateBadge = MutableStateFlow(false)
    val updateBadge = _updateBadge.asStateFlow()

    /** 正在跑任务的会话 id 集合：会话列表里给它们显示「执行中」标识。 */
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds = _runningIds.asStateFlow()

    /**
     * 侧边栏每条会话的状态提示：跑着任务 / 几个子任务在跑 / 排队几条。
     *
     * 为什么单独一份（而不是只有一个 running 集合）：子任务是后台子代理，可能比父轮次活得久
     * （父 run 结束了子代理还在干），只按「本地 busy」打点会漏掉这种情况——用户看着会话列表
     * 一片安静，其实里面还有活。见 refreshRunFlags。
     */
    private val _runFlags = MutableStateFlow<Map<String, SessionRunFlag>>(emptyMap())
    val runFlags = _runFlags.asStateFlow()
    private var runFlagsTicker: Job? = null

    /**
     * 服务端说「这些会话正在跑」：sessionId -> runId（GET /api/sessions 的 active_run）。
     *
     * 为什么必须有：本地 runtimes 只记录本进程发起的轮次。别的端（微信 / CLI / 桌面）起
     * 的轮次，App 一无所知——侧边栏无提示、也不去接流、跑完不通知；App 被杀过再启动同样
     * 如此（发送后立刻被杀那一瞬还没落盘）。这份映射是唯一能覆盖这两种情况的信息源。
     */
    private val _serverRuns = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverRuns = _serverRuns.asStateFlow()

    /** 服务端每行 last_active（毫秒）。回前台挑「比本地新」的会话对齐时用。 */
    private var serverLastActive: Map<String, Long> = emptyMap()

    /** 上次回前台批量对齐其它会话的时间（节流 30 秒，别每次切前后台全拉一遍）。 */
    private var lastAlignAt = 0L

    /** -1 未下载；0..100 下载中百分比。 */
    private val _downloadPct = MutableStateFlow(-1)
    val downloadPct = _downloadPct.asStateFlow()

    private val _downloadText = MutableStateFlow("")
    val downloadText = _downloadText.asStateFlow()

    /** 待发送图片（选好未发送）。 */
    private val _pendingImages = MutableStateFlow<List<PendingImage>>(emptyList())
    val pendingImages = _pendingImages.asStateFlow()

    // ---------- 引用回复 ----------

    /** 当前待引用的一条消息（长按气泡选「引用」后非空，输入栏上方显示引用条）。 */
    private val _quoteTarget = MutableStateFlow<Msg?>(null)
    val quoteTarget = _quoteTarget.asStateFlow()

    /** 长按某条消息选「引用」：记下它，输入栏显示引用条，发送时把片段带上。 */
    fun setQuote(m: Msg) {
        _quoteTarget.value = m
    }

    /** 取消引用。 */
    fun clearQuote() {
        _quoteTarget.value = null
    }

    // ---------- 排队消息编辑回填 ----------

    /** 编辑排队消息时把正文回填到输入框；非空即取走。 */
    private val _queuedEdit = MutableStateFlow<String?>(null)
    val queuedEdit = _queuedEdit.asStateFlow()

    /** 被引正文压缩成一行片段（最多 80 字），服务端与气泡共用。 */
    private fun quoteSnippet(m: Msg): String {
        val one = m.text.replace(Regex("\\s+"), " ").trim()
        val who = if (m.role == "user") "我" else "助手"
        val body = if (one.isEmpty()) "[图片或附件]" else if (one.length > 80) one.take(80) + "…" else one
        return who + "：" + body
    }

    // ---------- 富卡片动作 ----------

    /**
     * 卡片按钮点击分发：
     *   open_url      用浏览器打开 value（不动会话）
     *   send_text     把 value 当一条消息发出去（默认）
     *   slash_command 同 send_text，但原样带上斜杠命令（不走引用拼装）
     */
    fun dispatchCardAction(a: CardAction, ctx: android.content.Context) {
        when (a.action) {
            "open_url" -> {
                if (a.value.isEmpty()) return
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(a.value)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            "slash_command" -> {
                if (a.value.isEmpty()) return
                send(a.value)
            }
            else -> {
                if (a.value.isEmpty()) return
                send(a.value)
            }
        }
    }

    /** 模型是否支持原生图片（/v1/capabilities features.supports_vision）；未知按 true。 */
    private val _supportsVision = MutableStateFlow(true)
    val supportsVision = _supportsVision.asStateFlow()

    /** 图片上传进度文案（空表示无进行中上传）。 */
    private val _imageNote = MutableStateFlow("")
    val imageNote = _imageNote.asStateFlow()

    // ---------- 会话内搜索（只搜当前会话的本地消息） ----------

    /** 搜索栏是否展开。 */
    private val _searchActive = MutableStateFlow(false)
    val searchActive = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    /** 命中的消息 id，按会话顺序。 */
    private val _searchIds = MutableStateFlow<List<Long>>(emptyList())
    val searchIds = _searchIds.asStateFlow()

    /** 当前跳到第几条（0 基）；无命中为 -1。 */
    private val _searchIdx = MutableStateFlow(-1)
    val searchIdx = _searchIdx.asStateFlow()

    private var searchJob: Job? = null

    /**
     * 「滚到某条消息」的一次性请求（待处理卡片置顶提示点「查看」用）。
     *
     * 为什么不复用搜索跳转：那条路径要 searchActive=true，会顺手弹出搜索栏。
     * 这里只要滚动，界面消费完立刻清掉，避免后续重组再滚一次。
     */
    private val _scrollToMsg = MutableStateFlow<Long?>(null)
    val scrollToMsg = _scrollToMsg.asStateFlow()

    fun requestScrollToMsg(msgId: Long) { if (msgId > 0) _scrollToMsg.value = msgId }
    fun consumeScrollToMsg() { _scrollToMsg.value = null }

    /** 打开/收起搜索栏。收起时清掉全部搜索态。 */
    fun toggleSearch() {
        if (_searchActive.value) clearSearch() else {
            _searchActive.value = true
            _searchQuery.value = ""
            _searchIds.value = emptyList()
            _searchIdx.value = -1
        }
    }

    /**
     * 输入即搜：去抖 150 毫秒（快速连打只算一次扫描），匹配放后台线程算，不卡界面。
     * 只搜当前会话的本地消息（最多 300 条）。
     */
    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        val sid = _currentId.value
        if (q.isBlank()) {
            _searchIds.value = emptyList()
            _searchIdx.value = -1
            return
        }
        searchJob = viewModelScope.launch {
            delay(150)
            val msgs = rt(sid).messages.value
            val ids = withContext(Dispatchers.Default) { ChatSearch.matchIds(msgs, q) }
            // 结果回来时用户可能已改词或切走会话：过期的结果不落地。
            if (_currentId.value != sid || _searchQuery.value != q) return@launch
            _searchIds.value = ids
            _searchIdx.value = if (ids.isEmpty()) -1 else 0
        }
    }

    /** 上下跳转：dir 为 +1 / -1，到头绕回。 */
    fun searchNavigate(dir: Int) {
        val n = _searchIds.value.size
        if (n == 0) return
        val cur = _searchIdx.value
        _searchIdx.value = (((cur + dir) % n) + n) % n
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchActive.value = false
        _searchQuery.value = ""
        _searchIds.value = emptyList()
        _searchIdx.value = -1
    }

    // ---------- 跨会话搜索（扫本地全部会话） ----------

    /** 全局搜索面板是否展开（抽屉里）。 */
    private val _globalActive = MutableStateFlow(false)
    val globalActive = _globalActive.asStateFlow()

    private val _globalQuery = MutableStateFlow("")
    val globalQuery = _globalQuery.asStateFlow()

    private val _globalHits = MutableStateFlow<List<GlobalHit>>(emptyList())
    val globalHits = _globalHits.asStateFlow()

    private var globalJob: Job? = null

    fun toggleGlobalSearch() {
        if (_globalActive.value) clearGlobalSearch() else {
            _globalActive.value = true
            _globalQuery.value = ""
            _globalHits.value = emptyList()
        }
    }

    fun clearGlobalSearch() {
        globalJob?.cancel()
        _globalActive.value = false
        _globalQuery.value = ""
        _globalHits.value = emptyList()
    }

    /** 输入即搜（去抖 200 毫秒），扫本地全部会话文件，放后台线程算。 */
    fun setGlobalQuery(q: String) {
        _globalQuery.value = q
        globalJob?.cancel()
        if (q.isBlank()) {
            _globalHits.value = emptyList()
            return
        }
        globalJob = viewModelScope.launch {
            delay(200)
            val hits = withContext(Dispatchers.IO) { store.searchAll(q) }
            if (_globalQuery.value != q) return@launch
            _globalHits.value = hits
        }
    }

    /**
     * 点一条跨会话命中：切到那个会话，并让本会话内的搜索栏定位到这条消息。
     * 命中靠正文认（本地消息 id 不落盘），取该会话里第一条正文一致的消息。
     */
    fun openGlobalHit(hit: GlobalHit) {
        clearGlobalSearch()
        if (hit.sessionId != _currentId.value) switchSession(hit.sessionId)
        ensureLoaded(hit.sessionId)
        viewModelScope.launch {
            delay(80)   // 等会话消息装载完
            val msgs = rt(hit.sessionId).messages.value
            val idx = msgs.indexOfFirst { it.text == hit.text }
            if (idx < 0) return@launch
            _searchActive.value = true
            _searchQuery.value = hit.text
            _searchIds.value = listOf(msgs[idx].id)
            _searchIdx.value = 0
        }
    }

    private var api: HermesApi? = null
    /**
     * 探测循环只允许起一个。
     *
     * 原来用普通 Boolean 做防重入，但 onProfileChanged 可能被并发调用两次
     * （登录回调 + MainScaffold 的 LaunchedEffect），两个线程都读到 false，
     * 各自把自己当成第一个 → 起了多个 5 秒轮询循环。症状：同一条「恢复在线」
     * 一次打好几遍、离线判定挤在同一毫秒、探测请求成倍（白耗流量与电）。
     * compareAndSet 是原子操作，只有一个线程能赢。
     */
    private val pingStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 正在恢复探测的会话 id：进前台会频繁调用 resumeActiveRun，防止同一会话叠起多条探测链。 */
    private val resumingSids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 本机版本号：静默检查更新时用来比较（免去每次从界面传进来）。 */
    private val myVersionCode: Int = runCatching {
        val app = getApplication<Application>()
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
    }.getOrDefault(1)

    init {
        // 通知栏直接回复：先取落盘的（App 被杀过），再收运行中的广播
        drainPendingReply()
        viewModelScope.launch {
            com.hermesapp.PendingReply.flow.collect { drainPendingReply() }
        }
    }

    /** 取出通知栏回复并作为用户消息发出（连接没建好或该会话正忙时留着，下次再取）。 */
    private fun drainPendingReply() {
        val raw = prefs.pendingReply
        if (raw.isEmpty()) return
        val sid = raw.substringBefore('\u0000')
        val text = raw.substringAfter('\u0000')
        if (text.isBlank()) { prefs.pendingReply = ""; return }
        if (api == null) return
        val target = if (sid.isNotEmpty()) sid else _currentId.value
        if (target.isEmpty()) return
        if (rt(target).busy.value) return
        prefs.pendingReply = ""
        if (target != _currentId.value) switchSession(target)
        send(text)
    }

    // ---------- 会话与本地持久化 ----------

    private val maxHistory = 300

    private fun stamp(): Long = System.currentTimeMillis()

    /** 新会话的排序键：取现有最小 order − 1，保证新会话永远排第一。 */
    private fun nextTopOrder(): Long {
        val min = _sessions.value.minOfOrNull { it.order } ?: 1L
        return min - 1L
    }

    /** 本轮计时起点：优先会话级的（整轮唯一、不会被气泡重建冲掉），没有才取当下。 */
    private fun turnStart(r: SessionRuntime): Long = if (r.startedAt > 0) r.startedAt else stamp()

    private fun setMsgs(r: SessionRuntime, list: List<Msg>) {
        r.messages.value = list
        scheduleSave(r)
    }

    /** 索引落盘的异步去抖任务：切会话/改标题连点也只写一次。 */
    private var indexSaveJob: Job? = null

    private fun scheduleSave(r: SessionRuntime) {
        if (r.dead) return          // 已删除的会话：别再安排写盘，否则文件会复活
        r.saveJob?.cancel()
        r.saveJob = RuntimeHub.scope.launch(Dispatchers.IO) {
            delay(400)
            saveRuntime(r)
        }
    }

    private fun saveRuntime(r: SessionRuntime) {
        store.saveMessages(r.id, r.messages.value, maxHistory)
        // 续接序号与消息一起落盘，保证两者永远一致：重开 App 时按它做
        // Last-Event-ID，只补断线之后的事件（消息也正好停在那一刻）——
        // 既不会重放已存过的工具轨迹（表现是「过程重复显示」），也不会漏事件。
        if (r.runId.isNotEmpty()) prefs.putLastSeq(r.id, r.lastSeq)
    }

    /**
     * 读会话正文（异步）。
     *
     * 为什么必须异步：正文最多 300 条含工具轨迹，readText + 全量 JSON 解析在主线程上就是
     * 一次几十到几百毫秒的卡顿；重启后每个会话都要现读一次，最明显。
     *
     * 数据安全：读盘期间若有推送/续接写进了 messages（非空），说明内存里那份更新，
     * 这时按用户消息锚点合并，绝不整份覆盖。
     */
    private fun ensureLoaded(id: String) {
        val r = rt(id)
        if (r.loaded || r.loading || r.dead) return
        r.loading = true
        val t0 = System.currentTimeMillis()
        RuntimeHub.scope.launch(Dispatchers.IO) {
            val msgs = runCatching { store.loadMessages(id) }.getOrDefault(emptyList())
            val cost = System.currentTimeMillis() - t0
            withContext(Dispatchers.Main.immediate) {
                if (r.dead) { r.loading = false; return@withContext }
                r.messages.value = if (r.messages.value.isEmpty()) msgs
                                   else mergeByUserAnchor(r.messages.value, msgs)
                r.loaded = true
                r.loading = false
                // 落盘里还有「运行中」的子任务（长任务跑一半重开 App）：把进度轮询接回去，
                // 否则界面上那行会一直停在重开前的步数。只对当前会话接线（预热其它会话时
                // 不该替用户去轮询）。
                if (id == _currentId.value &&
                    r.messages.value.any { it.subagents.any { s -> s.status == "running" && s.childSessionId.isNotEmpty() && s.endedAt == 0L } }) {
                    ensureSubagentSweep(id)
                }
                val total = if (r.switchStartedAt > 0) System.currentTimeMillis() - r.switchStartedAt else -1L
                AppLog.log("perf", "读会话正文 " + id.take(8) + " 条数=" + msgs.size + " 读盘=" + cost +
                    "ms" + (if (total >= 0) " 点击到就绪=" + total + "ms" else ""))
            }
        }
    }

    /**
     * 启动后预热：后台把最近几个会话的正文读进内存。
     *
     * 为什么需要：重启后除当前会话外一条都没加载，用户切到哪个都要现读现解析。
     * 放在启动 1.2 秒后、IO 线程上，不跟启动期的同步标题/收件箱/更新检查抢，也不挡首帧。
     */
    private fun prewarmRecentSessions() {
        RuntimeHub.scope.launch(Dispatchers.IO) {
            delay(1200L)
            val ids = _sessions.value.filter { !it.archived }.take(3).map { it.id }
            for (id in ids) ensureLoaded(id)   // 内部自带 loaded/loading 守卫与锚点合并
        }
    }

    

    /** 上次从服务端同步标题的时间：节流用，避免每个轮末都打一次接口。 */
    private var lastTitleSyncAt = 0L

    /**
     * 只把服务端生成的好标题同步回本地已有行，**不再往列表里增行、也不清理**。
     *
     * 2026-10-07：用户明确要求「历史对话不要再拉取」。此前有自动补行（列表为空时补最近
     * 7 天）与手动「拉取」两条路径，会把服务端几十上百条会话灌进手机列表，实测被反馈
     * 「历史对话太多了」。现两条路径全部下线，本函数只剩一个职责：给本地已有的会话行
     * 覆盖服务端小模型生成的正式标题（否则标题永远停在「新对话」或首句截断）。
     */
    fun syncFromServer(throttleMs: Long = 0L) {
        val a = api ?: return
        val now = System.currentTimeMillis()
        if (throttleMs > 0 && now - lastTitleSyncAt < throttleMs) return
        lastTitleSyncAt = now
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.listSessions()
                val arr = resp.optJSONArray("data") ?: return@launch
                val list = _sessions.value.toMutableList()
                var changed = false
                // 顺手把服务端的「正在跑」与 last_active 收下来：前者决定侧边栏角标与
                // 接流，后者决定回前台该对齐哪几条会话。
                val runs = LinkedHashMap<String, String>()
                val lastActive = HashMap<String, Long>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty() || o.optBoolean("is_internal_child", false)) continue
                    val ar = o.optString("active_run", "")
                    if (ar.isNotEmpty() && ar != "null") runs[id] = ar
                    val la = o.optDouble("last_active", 0.0)
                    if (la > 0) lastActive[id] = (la * 1000).toLong()
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx < 0) continue      // 本地没有这条：一律不补行
                    val title = o.optString("title", "").trim()
                    if (title.isNotEmpty() && list[idx].title != title) {
                        list[idx] = list[idx].copy(title = title); changed = true
                    }
                }
                serverLastActive = lastActive
                if (_serverRuns.value != runs) _serverRuns.value = runs
                AppLog.log("sync", "同步标题：服务端=" + arr.length() + " 条，本地=" + list.size +
                    " 条，覆盖=" + changed + " 服务端在跑=" + runs.size)
                if (changed) {
                    _sessions.value = list
                    store.saveIndex(list)
                }
                // 服务端说当前会话在跑而本地没有 → 别的端发起的（或本地标记丢了），接上去。
                reconcileServerRuns()
                refreshRunFlags()
            } catch (e: Exception) {
                AppLog.err("sync", "同步会话标题失败", e)
                // 离线 / 接口异常：保留本地列表，不影响使用
            }
        }
    }

    /**
     * 一次性清理历史遗留的空壳会话行（只跑一次，靠 prefs.shellCleanupDone 记）。
     *
     * 背景：上一版的「拉取」把服务端几十条会话灌进了本地索引，用户从没在这些会话里
     * 聊过、本地也没有聊天记录文件。判据 =「本地无聊天记录文件 且 标题不是『新对话』」：
     * - 本地新建、还没发过消息的会话标题就是「新对话」，保留；
     * - 发过消息的会话本地必有记录文件，保留；
     * - 剩下「无记录 + 非新对话」的，只可能是外部灌进来的空壳，移除。
     * 只动本地索引，不删任何消息文件；服务端数据原样保留，误判也不会丢数据。
     */
    private fun cleanupShellSessionsOnce() {
        if (prefs.shellCleanupDone) {
            AppLog.log("session-sync", "空壳清理：本机已执行过，跳过")
            return
        }
        try {
            val list = _sessions.value
            val kept = list.filter { meta ->
                store.hasMessages(meta.id) || meta.title == "新对话"
            }
            val removed = list.size - kept.size
            AppLog.log("session-sync", "空壳清理：清理前=" + list.size +
                " 保留=" + kept.size + " 移除=" + removed)
            if (removed > 0) {
                _sessions.value = kept
                saveIndexAsync(debounceMs = 0L)
                if (kept.none { it.id == _currentId.value }) selectNextOrEmpty()
            }
        } catch (e: Exception) {
            AppLog.err("session-sync", "空壳清理失败", e)
        }
        prefs.shellCleanupDone = true
    }
    /** 切到某个会话（网关 session_id 同步指过去）。不再停止任何正在跑的任务。 */
    fun switchSession(id: String) {
        if (id == _currentId.value) return
        val t0 = System.currentTimeMillis()
        AppLog.log("ui", "切会话 -> " + id.take(8))
        clearSearch()   // 搜索只作用于当前会话：切走即收起
        // 切会话必须立刻返回。原来这里在 UI 线程上同步做「写上一会话正文 + 写索引 +
        // 读新会话正文并全量解析 + 再读一次索引」：一次点击 3 写 2 读，重启后没有内存
        // 缓存，每次都等磁盘 —— 这就是侧边栏切换卡顿的来源。现在写盘异步、读盘走 IO，
        // 主线程只改状态（下面那行 perf 日志会把实际耗时记下来）。
        val r = rt(id)
        r.switchStartedAt = t0
        saveCurrentAsync()
        _currentId.value = id
        prefs.sessionId = id
        ensureLoaded(id)
        // 切到哪就与服务端对齐哪。以前这里只从本地磁盘读正文，一条服务端请求都不发，
        // 于是别的端（微信 / CLI / 桌面）在会话 X 里跑过的新轮次，切过去看到的是本地旧
        // 内容，永远对不上（除非重启 App）。已在跑的会话 refreshFromServerFor 内部会
        // 自己跳过（busy），不会把半截内容合进来。
        refreshFromServerFor(id)
        AppLog.log("perf", "切会话 " + id.take(8) + " 主线程耗时=" + (System.currentTimeMillis() - t0) + "ms")
    }

    /**
     * 落盘当前会话的正文与索引（全异步）。
     *
     * 顺序保证：每个会话自己有 saveJob（400ms 去抖）。这里先 cancel 掉待执行的去抖任务，
     * 再挂一个立刻执行的 IO 写 —— 同一会话任何时刻只有一个写在跑，不会互相覆盖。
     */
    private fun saveCurrentAsync() {
        val id = _currentId.value
        if (id.isNotEmpty()) {
            val r = runtimes[id]
            if (r != null && !r.dead) {
                r.saveJob?.cancel()
                r.saveJob = RuntimeHub.scope.launch(Dispatchers.IO) { saveRuntime(r) }
            }
        }
        saveIndexAsync()
    }

    /**
     * 索引落盘：异步 +（默认）300ms 去抖。
     * debounceMs = 0 用于「必须尽快落盘」的场合（新建会话、归档、删除）。
     */
    private fun saveIndexAsync(debounceMs: Long = 300L) {
        indexSaveJob?.cancel()
        indexSaveJob = RuntimeHub.scope.launch(Dispatchers.IO) {
            if (debounceMs > 0) delay(debounceMs)
            store.saveIndex(_sessions.value)
        }
    }

    /** 把待写的正文/索引立刻落盘（App 退到后台时调，避免进程被杀丢最后一段）。 */
    fun flushSaves() {
        saveCurrentAsync()
        AppLog.flush()
    }

    fun newConversation() {
        AppLog.log("ui", "新建会话")
        saveCurrentAsync()
        val id = UUID.randomUUID().toString()
        // 新会话永远排第一：手动模式给它最小 order − 1；非手动模式留 0（重启按更新时间排也在最前）。
        val nOrder = if (_sessions.value.any { it.order > 0L }) nextTopOrder() else 0L
        val meta = SessionMeta(id, "新对话", stamp(), false, nOrder)
        _sessions.value = listOf(meta) + _sessions.value
        saveIndexAsync(debounceMs = 0L)
        _currentId.value = id
        prefs.sessionId = id
        rt(id).loaded = true
        rt(id).loading = false
    }

    /**
     * 手动调整会话顺序：dir = -1 上移，+1 下移。
     * 列表按「归档态」过滤展示，跨态移动没有视觉意义，所以只在同态相邻项之间换位；
     * 换完把「位置」写回 order，保证落盘 / 重启后顺序不变。
     */
    fun moveSession(id: String, dir: Int) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val arch = list[i].archived
        var j = i + dir
        while (j >= 0 && j < list.size && list[j].archived != arch) j += dir
        if (j < 0 || j >= list.size) return
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        for (k in list.indices) list[k].order = k.toLong()
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
        AppLog.log("ui", "会话排序 sid=" + id.take(8) + " 方向=" + dir + " 新位置=" + j)
    }

    fun archiveSession(id: String, archived: Boolean) {
        AppLog.log("ui", "归档会话 sid=" + id.take(8) + " 归档=" + archived)
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(archived = archived)
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
        if (id == _currentId.value && archived) selectNextOrEmpty()
    }

    fun deleteSession(id: String) {
        AppLog.log("ui", "删除会话 sid=" + id.take(8))
        // 若该会话有正在跑的任务，先停掉（服务端一并停），再删本地记录。
        stopSession(id)
        // 关键顺序：先把该会话的运行态标死、取消待执行的保存任务，再删文件。
        // 反过来的话，刚挂上去的异步保存会在删除之后把文件又写回来（会话复活）。
        runtimes[id]?.let { r -> r.dead = true; r.saveJob?.cancel() }
        runtimes.remove(id)
        prefs.clearDraft(id)
        val list = _sessions.value.filter { it.id != id }.toMutableList()
        _sessions.value = list
        saveIndexAsync(debounceMs = 0L)
        RuntimeHub.scope.launch(Dispatchers.IO) { runCatching { store.deleteMessages(id) } }
        if (id == _currentId.value) selectNextOrEmpty()
    }

    /**
     * 把一个会话导出成 Markdown 文件并调系统分享面板发出去（发同事/存网盘）。
     *
     * 落在 App 私有目录 exports/（FileProvider 已声明），只读本地记录，不碰服务端。
     * 失败只在日志里记一行，不弹错——导出是附加功能，坏了不该打扰正常对话。
     */
    fun exportSession(ctx: Context, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meta = _sessions.value.firstOrNull { it.id == id }
                val title = meta?.title ?: "对话"
                val md = store.exportMarkdown(id, title)
                if (md.isBlank()) {
                    AppLog.log("export", "会话为空，跳过导出 id=" + id.take(8))
                    return@launch
                }
                val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
                val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "对话" }
                val f = File(dir, safe + "_" + TimeFmt.mdhm(System.currentTimeMillis())
                    .replace(Regex("[^0-9]"), "") + ".md")
                f.writeText(md)
                AppLog.log("export", "已导出 " + f.name + " " + md.length + " 字")
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
                    val it = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(Intent.createChooser(it, "导出对话").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                AppLog.err("export", "导出失败", e)
            }
        }
    }

    /** 当前会话被删/归档后：优先切到下一个未归档会话；没有则清空进入空态（输入即新建）。 */
    private fun selectNextOrEmpty() {
        val next = _sessions.value.firstOrNull { !it.archived }
        if (next != null) {
            _currentId.value = next.id
            prefs.sessionId = next.id
            ensureLoaded(next.id)
        } else {
            _currentId.value = ""
            prefs.sessionId = null
        }
    }

    private fun touchSession(firstUserText: String?) {
        val id = _currentId.value
        if (id.isEmpty()) return
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = list[i]
        val title = if (old.title == "新对话" && !firstUserText.isNullOrBlank()) {
            val t = firstUserText.replace(Regex("\\s+"), " ").trim()
            if (t.length > 20) t.take(20) + "…" else t
        } else old.title
        list[i] = old.copy(title = title, updatedAt = stamp())
        _sessions.value = list
        // 时间戳必须落盘：原来只改内存，重启后 updatedAt 停在旧值，回前台对齐会一直判
        // 「服务端更新」，同一批会话每 30 秒被重拉一次。去抖 300ms，连发也只写一次。
        saveIndexAsync()
    }

    private fun bootstrapSessions(profileSessionId: String?) {
        store = SessionStore(getApplication(), prefs.profile)
        // 启动体检：把本机到底存了哪些会话文件钉进日志。
        AppLog.log("store", "启动体检 profile=" + prefs.profile + " " + store.diagSummary() +
            " 上次会话=" + (profileSessionId ?: "无"))
        val idx = store.loadIndex()
        AppLog.log("store", "启动读索引 条数=" + idx.size)
        if (idx.isEmpty()) {
            val migrated = store.migrateLegacy(profileSessionId ?: "")
            if (migrated == null) {
                // 无历史会话：进入空态，首次输入再建会话
                AppLog.log("store", "启动分支=空态（索引为空且无旧格式文件可迁移）")
                _currentId.value = ""
                prefs.sessionId = null
                _sessions.value = emptyList()
                return
            }
            AppLog.log("store", "启动分支=迁移旧格式 会话=" + migrated.id.take(8))
            _currentId.value = migrated.id
            prefs.sessionId = migrated.id
            _sessions.value = listOf(migrated)
            ensureLoaded(migrated.id)
            return
        }
        // 排序：只要用户手动排过（存在非 0 的 order），就按 order 升序（手动顺序）；
        // 否则回落到按最近更新时间降序——同时把当前顺序钉成 order（1..N），
        // 作为手动排序的起点，免得第一次上/下移时整表乱跳。
        val manual = idx.any { it.order > 0L }
        val list = if (manual) idx.sortedBy { it.order } else idx.sortedByDescending { it.updatedAt }
        // 优先恢复上次停留的会话（prefs.sessionId）；找不到才回退到最近更新的未归档会话。
        val preferred = profileSessionId?.takeIf { it.isNotEmpty() }
            ?.let { pid -> list.firstOrNull { it.id == pid && !it.archived } }
        val target = preferred ?: list.firstOrNull { !it.archived } ?: list.first()
        AppLog.log("store", "启动分支=恢复列表 条数=" + list.size +
            " 已归档=" + list.count { it.archived } +
            " 命中上次会话=" + (preferred != null) + " 当前=" + target.id.take(8))
        _currentId.value = target.id
        prefs.sessionId = target.id
        _sessions.value = list
        AppLog.log("store", "刷新列表 条数=" + list.size + " 已归档=" + list.count { it.archived } +
            " 当前=" + target.id.take(8))
        ensureLoaded(target.id)
        prewarmRecentSessions()
    }

    // ---------- 连接 ----------

    fun onProfileChanged(p: Prefs) {
        AppLog.log("ui", "切身份 profile=" + p.profile + " 服务器=" + p.serverUrl)
        migrateLegacyHost(p)
        val key = if (p.profile == "default") Keys.DEFAULT_KEY else Keys.FRIEND_KEY
        val prefix = if (p.profile == "default") "" else "/p/friend"
        api = HermesApi(p.serverUrl, key, prefix)
        // 网关托管媒体：把带鉴权的取文件函数挂给 Markdown 附件卡片
        val a0 = api
        MediaFetch.handler = { token -> a0?.downloadMedia(token) }
        // 语音重播：点旧消息的播放按钮时按 runId 取长期留档的 mp3
        VoiceReplayPlayer.fetcher = { rid -> a0?.downloadVoice(rid) }
        VoiceReplayPlayer.rate = p.voiceRate
        bootstrapSessions(p.sessionId)
        cleanupShellSessionsOnce()
        syncFromServer()
        // 把设置里存的语速灌进播放器（播放器是单例，重启 App 后要重新初始化）
        VoicePlayer.rate = p.voiceRate
        StreamVoicePlayer.rate = p.voiceRate
        pingLoop()
        refreshStatus()
        refreshFromServer()
        fetchCapabilities()
        resumeActiveRun()
        // 启动就先看一眼定时任务收件箱：App 这个通道收不到推送，产出只能自己来拉。
        refreshInbox()
        // 侧边栏会话状态提示的心跳（子任务/排队/busy 都算）。
        startRunFlagsTicker()
        drainPendingReply()
        checkUpdateSilently()
    }

    /**
     * 老域名静默迁移：把 prefs 里存的历史域名换成当前域名。
     *
     * 为什么必须做：服务器地址是登录时写进 prefs 的，就地升级（不重新登录）
     * 时不会更新。域名一旦迁移，老用户的地址就指向死链，
     * 表现就是「一直重连连不上、怎么都连不上」。
     * 新老域名对照见 local.properties 的 HERMES_LEGACY_HOSTS（构建期注入，源码不留真值）。
     * 这里只做主机名替换，路径/端口/协议原样保留。
     */
    private fun migrateLegacyHost(p: Prefs) {
        val cur = p.serverUrl
        if (cur.isEmpty()) return
        val hit = LEGACY_HOSTS.entries.firstOrNull { cur.contains(it.key) } ?: return
        val fixed = cur.replace(hit.key, hit.value)
        if (fixed != cur) p.serverUrl = fixed
    }

    /**
     * 静默检查更新：只更新「设置」角标，不弹框（启动 / 切身份时调用）。
     * 用户主动点「检查更新」仍走 checkUpdate()，那时才弹确认框。
     */
    fun checkUpdateSilently() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate() ?: return@launch
                _updateBadge.value = info.versionCode > myVersionCode
                AppLog.log("update", "静默检查：服务端=" + info.versionName + "(" + info.versionCode + ") 本机=" + myVersionCode)
            } catch (e: Exception) {
                AppLog.err("update", "静默检查更新失败", e)
            }
        }
    }

    /**
     * 重开 App 时恢复「正在跑的任务」：本地按会话存了 run_id，逐个查状态——
     * 还在跑的就恢复「停止」按钮并继续接流（多会话可同时恢复），已结束的清标记。
     */
    fun resumeActiveRun() {
        val a = api ?: return
        val map = prefs.activeRunsMap()
        if (map.isEmpty()) return
        for ((sid, rid) in map) {
            // 已在跑的会话不重复探测：重复接流会把同一条 run 挂两条流、进度翻倍。
            if (rt(sid).busy.value) continue
            // 同一会话的探测不许叠（两个前台事件可能并发进来）。
            if (!resumingSids.add(sid)) continue
            RuntimeHub.scope.launch(Dispatchers.IO) {
                // 开机时网络往往还没就绪，探测失败不能当成「任务已结束」——
                // 那样会把活跃标记删掉，这条任务就永远回不来了。重试几次，
                // 实在探不出来就保留标记，等下次进前台/切身份再试。
                var last: HermesApi.RunStatus = HermesApi.RunStatus.Unknown
                for (i in 1..RESUME_PROBE_TRIES) {
                    last = a.probeRun(rid)
                    if (last !is HermesApi.RunStatus.Unknown) break
                    delay(if (i == 1) 1_000L else 3_000L)
                }
                // 探测是异步的：等待期间用户可能刚发了新消息（busy 已置真、runId 已换新），
                // 或另一条路径已把这条任务接上。此时绝不能按探测到的旧 run 再起一条流——
                // 同一 run 两条流会重复收事件，正文与语音都执行两遍（实测 2.81 回前台重复播报）。
                val rg = rt(sid)
                if (rg.busy.value || (rg.runId.isNotEmpty() && rg.runId != rid)) {
                    AppLog.log("resume", "恢复探测放弃 sid=" + sid.take(8) + " busy=" + rg.busy.value + " runId换=" + (rg.runId.isNotEmpty() && rg.runId != rid))
                    resumingSids.remove(sid)
                    return@launch
                }
                when (last) {
                    is HermesApi.RunStatus.Known -> {
                        AppLog.log("resume", "恢复探测 sid=" + sid.take(8) + " 状态=" + last.status)
                        if (last.status in RUNNING_STATES) {
                            val r = rt(sid)
                            ensureLoaded(sid)
                            r.runId = rid
                            r.finished = false
                            // 冷启动恢复：本地没有这轮的发送上下文（pendingSendText 不落盘），
                            // 翻历史兜底拿不到位置锚点，靠 resumed 标记走「保留气泡继续重连」。
                            r.resumed = true
                            // 续接序号从落盘恢复：只补断线之后的事件，避免从 0 全量重放
                            // 把已经存过的工具轨迹再追加一遍（用户报「过程重复显示」）。
                            r.lastSeq = prefs.lastSeq(sid)
                            // 服务端还在等审批/澄清：把那张卡片重新挂回去（续接流只补
                            // lastSeq 之后的事件，早于断点的 clarify.request 不会重放）。
                            restorePendingCard(r, last.payload)
                            // 本地落盘的消息读回来时 pending 一律是 false，而服务端这条 run
                            // 还没结束。若断线前已收到过事件（lastSeq>=0），本地就有这一轮的
                            // 气泡，把它恢复成「进行中」，续接来的增量才追加到同一条上，不会
                            // 再起一条（否则同一轮正文/轨迹会显示两次）；若还没收到过任何事件，
                            // 本地只有上一轮的回复，绝不能标它 pending（新内容会挂到旧回复上），
                            // 这时新起一个空气泡接续。
                            val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
                            val hasLocalTurnBubble = lastAssistant >= 0 &&
                                lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
                            if (hasLocalTurnBubble) {
                                val m = r.messages.value.toMutableList()
                                m[lastAssistant] = m[lastAssistant].copy(
                                    pending = true,
                                    startedAt = if (m[lastAssistant].startedAt > 0) m[lastAssistant].startedAt
                                    else if (r.startedAt > 0) r.startedAt else System.currentTimeMillis()
                                )
                                r.messages.value = m
                            } else {
                                r.messages.value = r.messages.value +
                                    Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r))
                            }
                            r.busy.value = true
                            updateRunService()
                            streamRun(a, sid)
                        } else {
                            // run 已结束：清掉落盘读回的过期待办卡片，再拉记录。
                            ensureLoaded(sid)
                            clearStaleCards(rt(sid))
                            prefs.removeActiveRun(sid)
                            if (sid == _currentId.value) refreshFromServer()
                        }
                    }
                    // 服务端明确说没这个 run：标记失效，清掉并拉一次记录。
                    HermesApi.RunStatus.Missing -> {
                        ensureLoaded(sid)
                        clearStaleCards(rt(sid))
                        prefs.removeActiveRun(sid)
                        if (sid == _currentId.value) refreshFromServer()
                    }
                    // 探不出来（网还没就绪）：不知道 ≠ 已结束。保留标记，并把它登记成
                    // 「进行中」（busy + 待接续气泡），交给看门狗与退避链继续重连——
                    // 只留个标记干等的话，回前台这条路径以前根本不存在，任务就永远不报了。
                    HermesApi.RunStatus.Unknown -> {
                        val r = rt(sid)
                        ensureLoaded(sid)
                        r.runId = rid
                        r.finished = false
                        r.resumed = true
                        r.lastSeq = prefs.lastSeq(sid)
                        val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
                        val hasLocalTurnBubble = lastAssistant >= 0 &&
                            lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
                        if (hasLocalTurnBubble) {
                            val m = r.messages.value.toMutableList()
                            m[lastAssistant] = m[lastAssistant].copy(pending = true)
                            r.messages.value = m
                        } else {
                            r.messages.value = r.messages.value +
                                Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r))
                        }
                        r.busy.value = true
                        r.lastEventAt = System.currentTimeMillis()
                        updateRunService()
                        maybeContinue(sid)
                        if (sid == _currentId.value) refreshFromServer()
                    }
                }
                resumingSids.remove(sid)
            }
        }
    }

    /**
     * 服务端说某些会话正在跑，而本地没有对应的活跃标记 —— 接上去。
     *
     * 覆盖两种此前完全看不见的情况：
     *   1) 轮次是别的端（微信 / CLI / 桌面）发起的，本进程从来没发过这条 run；
     *   2) 本进程发起后立刻被杀/断网，activeRunsMap 还没落盘就被系统收走了。
     *
     * 只处理「本地没在跑」的会话：本地已经在跑的由它自己的流负责，重复接流会把
     * 同一条 run 挂两条流、事件与语音都收两遍（实测过的 2.81 重复播报）。
     */
    private fun reconcileServerRuns() {
        val a = api ?: return
        val runs = _serverRuns.value
        if (runs.isEmpty()) return
        // 只处理本地已有的会话行：与「历史对话不要再拉取」一致，绝不为服务端独有的会话
        // 建本地行（那会把几十条别的端的会话灌进手机列表）。
        val known = _sessions.value.filter { !it.archived }.map { it.id }.toSet()
        AppLog.log("resume", "接管检查 服务端在跑=" + runs.size + " 本地会话=" + known.size)
        for ((sid, rid) in runs) {
            if (rid.isEmpty() || sid !in known) {
                AppLog.log("resume", "接管跳过 sid=" + sid.take(8) + " 不在本地列表")
                continue
            }
            val r = rt(sid)
            // 本地就在跑这一轮：不动（自己的流负责，重复接流会收两遍事件）。
            if (r.busy.value && r.runId == rid) continue
            // 本地在跑「别的」轮次：一个会话同时只该有一条 run，先信本地，但把分歧记下来
            // （2026-10-08：这里原来一句静默 continue，日志里只剩「服务端在跑=N」却看不到
            //  为什么没接管，排查时无从下手）。
            if (r.busy.value) {
                AppLog.log("resume", "接管跳过 sid=" + sid.take(8) + " 本地run=" + r.runId.take(12) +
                    " 服务端run=" + rid.take(12))
                continue
            }
            if (!resumingSids.add(sid)) {
                AppLog.log("resume", "接管跳过 sid=" + sid.take(8) + " 已在接流中")
                continue
            }
            AppLog.log("resume", "服务端报告活跃轮次，接管 sid=" + sid.take(8) + " run=" + rid.take(12))
            RuntimeHub.scope.launch(Dispatchers.IO) {
                try {
                    val st = a.probeRun(rid)
                    if (st !is HermesApi.RunStatus.Known || st.status !in RUNNING_STATES) {
                        // 探到已收尾或探不出来：不接管。收尾的交给下一次对齐拉正文，
                        // 探不出来的下一轮 syncFromServer 再说（不在这里瞎标活跃）。
                        AppLog.log("resume", "接管放弃 sid=" + sid.take(8) + " 状态=" +
                            if (st is HermesApi.RunStatus.Known) st.status else "Unknown/Missing")
                        resumingSids.remove(sid)
                        return@launch
                    }
                    withContext(Dispatchers.Main.immediate) { attachRemoteRun(sid, rid, st.payload) }
                } catch (e: Exception) {
                    AppLog.err("resume", "接管探测失败 sid=" + sid.take(8), e)
                } finally {
                    resumingSids.remove(sid)
                }
            }
        }
    }

    /**
     * 把一条「不是本进程发起」的活跃 run 挂进该会话的运行时，并接上事件流。
     *
     * 与 resumeActiveRun 的冷启动分支同构，区别只在来源：那条靠本地落盘的 run_id，
     * 这条靠服务端会话列表的 active_run。气泡策略一致 —— 本地若已有这一轮的空气泡
     * （lastSeq 有值、末尾就是助手行）就复用它标成进行中，否则新起一个，避免同一轮
     * 正文被追到旧回复上、或显示两遍。
     */
    private fun attachRemoteRun(sid: String, rid: String, payload: org.json.JSONObject?) {
        val r = rt(sid)
        if (r.busy.value) return
        ensureLoaded(sid)
        r.runId = rid
        r.finished = false
        r.resumed = true
        r.lastSeq = prefs.lastSeq(sid)
        prefs.putActiveRun(sid, rid)
        if (payload != null) restorePendingCard(r, payload)
        val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
        val hasLocalTurnBubble = lastAssistant >= 0 &&
            lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
        if (hasLocalTurnBubble) {
            val m = r.messages.value.toMutableList()
            m[lastAssistant] = m[lastAssistant].copy(
                pending = true,
                startedAt = if (m[lastAssistant].startedAt > 0) m[lastAssistant].startedAt
                else if (r.startedAt > 0) r.startedAt else System.currentTimeMillis()
            )
            r.messages.value = m
        } else {
            r.messages.value = r.messages.value +
                Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r))
        }
        r.busy.value = true
        r.lastEventAt = System.currentTimeMillis()
        updateRunService()
        AppLog.log("resume", "已接管运行中会话 sid=" + sid.take(8) + " 状态=" + (payload?.optString("status") ?: ""))
        streamRun(api ?: return, sid)
    }

    /**
     * 回到前台时的即时体检：对每个仍在跑、且超过 25 秒没收到任何事件（含心跳帧）的会话，
     * 判定为「流已被链路假死卡住」，主动断开并走一次重连续接。
     *
     * 阈值 25 秒的由来：服务端每 10 秒必发一个 keepalive，25 秒 ≈ 连丢两拍，
     * 正常空闲绝不会误判；不这样做的话，息屏期间假死的流要等 30 秒读超时才断开。
     */
    fun onAppForeground() {
        AppLog.log("ui", "回前台")
        // 回前台顺手看一眼定时任务收件箱：App 这个通道收不到推送（服务端
        // supports_async_delivery=False），任务产出只能自己来拉。
        refreshInbox()
        // 冷启动/探活失败遗留的活跃任务没有别的重试入口（onProfileChanged 只在切身份时跑），
        // 进前台先补一次恢复探测。已在跑的会话会被 resumeActiveRun 的守卫跳过，不会重复接流。
        resumeActiveRun()
        val now = System.currentTimeMillis()
        for (r in runtimes.values) {
            if (!r.busy.value || r.finished) continue
            // 回前台是「重新争取流式续接」的机会：把退避计数归零、作废正在跑的历史轮询，
            // 否则后台期间退避用尽的任务会停在「只等最终答案」的历史轮询里，界面不再刷流式进度。
            r.autoContinue = 0
            r.recoveryJob?.cancel()
            val last = r.lastEventAt
            if (last > 0 && now - last > 25_000L) {
                val c = r.call
                if (c != null && !c.isCanceled()) {
                    if (r.retryNote.value.isEmpty()) r.retryNote.value = "回到前台，正在重连…"
                    // 主动掐掉假死连接：onError 回调会走一次退避续接，这里不重复调，
                    // 避免同一次断流把重试计数加两回。
                    c.cancel()
                } else {
                    maybeContinue(r.id)
                }
            }
        }
        // 回前台补齐前台服务：后台期间被 Android 12+ 限制挡下的启动在这里补上，
        // 保证继续挂着的任务不再被系统冻结。
        updateRunService()
        // 顺带刷一次在线状态，别让角标停在离线
        viewModelScope.launch(Dispatchers.IO) { refreshStatus() }
        // 回前台顺带同步一次服务端标题（节流 30 秒，避免频繁切前后台狂打接口）
        // 同时把服务端的「正在跑」收下来（syncFromServer 内部会 reconcileServerRuns），
        // 这样别的端刚起的轮次也能被接管。
        syncFromServer(30_000L)
        // 回前台把当前会话与服务端对齐一次（正文，不只是标题）。
        syncOnForeground()
    }

    /**
     * 回前台自动同步：把当前会话与服务端对齐一次。
     *
     * 为什么需要它：后台期间 App 可能没收到任何事件（进程被冻结、流被掐），
     * 或者在别的端（微信 / CLI）产生了新轮次——本地的消息就停在了切出去那一刻。
     * 以前回前台只同步「标题」，正文永远不会补齐，表现就是「切回来信息不同步」。
     *
     * 正在跑的会话不能硬拉：服务端记录此刻是半成品，合并进去会把半截内容写进气泡。
     * 改落一个「待同步」标记，交给本轮收尾（doneOk / failPending）时补拉一次。
     */
    private fun syncOnForeground() {
        val now = System.currentTimeMillis()
        if (now - lastFgSyncAt < FOREGROUND_SYNC_THROTTLE_MS) return
        lastFgSyncAt = now
        val cur = _currentId.value
        if (cur.isNotEmpty()) {
            val r = rt(cur)
            if (r.busy.value) {
                r.needSync = true
                AppLog.log("sync", "回前台：会话在跑，落待同步标记 sid=" + cur.take(8))
            } else {
                AppLog.log("sync", "回前台：同步当前会话 sid=" + cur.take(8))
                refreshFromServerFor(cur)
            }
        }
        // 其它正在跑的会话也各落各的标记，各自收尾时补拉。
        for ((sid, other) in runtimes) {
            if (sid != cur && other.busy.value) other.needSync = true
        }
        alignStaleSessions(cur)
    }

    /**
     * 回前台把「服务端比本地新」的其它会话也对齐一遍（最近 3 条、节流 30 秒）。
     *
     * 为什么以前只对齐当前那一个不够：别的端在会话 X 里跑了新轮次，而 App 自己不知道 X 忙
     * （busy 只来自本进程发起的 run），于是既不对齐、也没有收尾补拉 —— 没被点到的会话内容
     * 就一直漂着，直到用户切过去才追平（切会话修好后又多了一条追平路径，但得先切）。
     * 这里用服务端返回的 last_active 与本地 updatedAt 比对，只挑真的落后了的会话拉。
     */
    private fun alignStaleSessions(cur: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlignAt < 30_000L) return
        lastAlignAt = now
        val la = serverLastActive
        if (la.isEmpty()) return
        val stale = _sessions.value
            .filter { !it.archived && it.id != cur }
            .filter { m ->
                val sv = la[m.id] ?: return@filter false
                sv > m.updatedAt + 2_000L   // 容 2 秒时钟/落盘误差，避免无谓重拉
            }
            .sortedByDescending { la[it.id] ?: 0L }
            .take(3)
        for (m in stale) {
            val r = rt(m.id)
            if (r.busy.value) { r.needSync = true; continue }
            AppLog.log("sync", "回前台对齐落后会话 sid=" + m.id.take(8))
            refreshFromServerFor(m.id)
        }
    }

    /** 上次回前台同步的时间：节流用。 */
    private var lastFgSyncAt = 0L

    /** 拉一次服务端能力：是否支持原生图片（决定图片是原生附图还是先转文字）。 */
    fun fetchCapabilities() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val caps = a.capabilities()
                val f = caps.optJSONObject("features")
                if (f != null) _supportsVision.value = f.optBoolean("supports_vision", true)
            } catch (_: Exception) {
            }
        }
    }

    /** 重开 App 时从服务端拉当前会话消息：后台跑完的任务产出据此补回。 */
    fun refreshFromServer() = refreshFromServerFor(_currentId.value)

    /** 指定会话的服务端消息拉取与合并（翻历史兜底也复用）。 */
    private fun refreshFromServerFor(id: String) {
        val a = api ?: return
        if (id.isEmpty()) return
        val r = rt(id)
        // 已删除的会话：拉回来的内容一个字都不能写，否则会把删掉的消息文件写复活。
        if (r.dead) return
        // 忙 = 推迟，不是丢弃。
        //
        // 2026-10-08：这里原来是静默 return，冷启动时被踩得很惨——onProfileChanged 先调
        // refreshFromServer()、再调 resumeActiveRun()，请求发出时还没忙，回来时 run 已经
        // 恢复成 busy，撞上函数内第二道守卫被丢掉；而两道守卫都不落 needSync，收尾时
        // doneOk 看 needSync=false 就不补拉。表现就是用户报的「执行中途杀掉 App，重开进
        // 该会话，内容停在退出前那一帧」——服务端 1.9MB 的记录拉回来了却被扔掉。
        if (r.busy.value) {
            r.needSync = true
            AppLog.log("sync", "会话在跑，落待同步标记（入口）sid=" + id.take(8))
            return
        }
        // 挂进程级作用域而不是 viewModelScope：回前台同步与「收尾补拉」都可能在
        // Activity 已被重建/销毁之后触发，挂 viewModelScope 会随旧实例一起被取消，
        // 表现就是「该同步的时候没同步」。
        RuntimeHub.scope.launch(Dispatchers.IO) {
            try {
                val resp = a.sessionMessages(id)
                val arr = resp.optJSONArray("data") ?: return@launch
                val list = mutableListOf<Msg>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role", "assistant")
                    if (role != "user" && role != "assistant") continue
                    val content = o.optString("content", "")
                    if (content.isEmpty()) continue
                    list.add(Msg(role, content, pending = false, ts = parseTs(o.optString("timestamp", ""))))
                }
                // 守卫：该会话此刻已在跑任务则不覆盖（切换会话不影响——只写它自己的缓冲）。
                // 但要落 needSync：请求往返期间 run 可能刚开始/被恢复（冷启动就是这么撞上的），
                // 直接丢就等于把服务端这一份记录白拉了。
                if (r.dead) {
                    // 请求往返期间会话被删了：整份丢掉，绝不落盘（否则删掉的文件复活）。
                    AppLog.log("sync", "会话已删除，丢弃拉回结果 sid=" + id.take(8))
                    return@launch
                }
                if (r.busy.value) {
                    r.needSync = true
                    AppLog.log("sync", "会话在跑，落待同步标记（返回时）sid=" + id.take(8))
                    return@launch
                }
                if (list.isEmpty()) {
                    AppLog.log("sync", "服务端会话无消息（本地保留 " + r.messages.value.size +
                        " 条）sid=" + id.take(8))
                    return@launch
                }
                // 合并而不是覆盖：本地消息正文里带内联图片（data URL），而服务端存的是
                // 原始 MEDIA: 路径——直接覆盖会把图片弄丢（用户报「更新后图片不见了」）。
                // 对齐方式不能用数组下标：服务端会滤掉「内容为空的助手行」与工具行，
                // 本地却保留带工具轨迹的空助手行，两边长度天然不等（实测某会话差 236 行），
                // 按下标对齐会从第一处差异起整体错位，把服务端回复贴到错误的气泡上。
                // 改用两边都完整保留、且有序的「用户消息」做锚点，见 mergeByUserAnchor。
                val local = r.messages.value
                val merged = mergeByUserAnchor(local, list)
                // 统一走 setMsgs（唯一写入口 + 去抖落盘）：原来这里直接赋值 + 另开一次写盘，
                // 与 saveRuntime 并发写同一个消息文件，谁后落盘谁赢，会丢正文。
                setMsgs(r, merged)
                r.loaded = true
                // 本地基线推进到服务端那一份：不回写的话回前台对齐永远判「服务端更新」，
                // 同一批会话每 30 秒被重拉一次。
                val sv = serverLastActive[id]
                if (sv != null && sv > 0) {
                    val sl = _sessions.value.toMutableList()
                    val si = sl.indexOfFirst { it.id == id }
                    if (si >= 0 && sv > sl[si].updatedAt) {
                        sl[si] = sl[si].copy(updatedAt = sv)
                        _sessions.value = sl
                        saveIndexAsync()
                    }
                }
                AppLog.log("sync", "合并服务端记录 sid=" + id.take(8) +
                    " 本地=" + local.size + " 服务端=" + list.size + " 合并后=" + merged.size)
            } catch (e: Exception) {
                AppLog.err("sync", "拉服务端消息失败 sid=" + id.take(8), e)
                // 服务端无此会话或网络异常：保留本地内容
            }
        }
    }

    /**
     * 服务端会话记录与本地消息的合并——按「用户消息内容」对齐，不按数组下标。
     *
     * 为什么不能按下标：/api/sessions/{id}/messages 会滤掉空内容的助手行（本地保留，
     * 它们带工具轨迹 trace），且**长会话被服务端压缩时老行会被删/合并**——两边用户消息
     * 条数天然不等，按「第几条」对齐会从第一处差异起整体错位（把服务端回复贴到错误的
     * 气泡上、用户消息重复出现）。用户消息正文两侧基本原样保留，所以拿「正文归一 + 时间
     * 接近」做顺序匹配：服务端被压缩删掉的那条匹配不上就跳过，不影响其后各块的对齐。
     */
    private fun mergeByUserAnchor(local: List<Msg>, srv: List<Msg>): List<Msg> {
        fun isUser(m: Msg) = m.role == "user"
        fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

        // 各自切块：head = 第一条用户消息之前的行；随后每个用户消息带一个 tail
        //（其后、下一条用户消息之前的助手/系统行）。
        fun blocks(ms: List<Msg>): Triple<MutableList<Msg>, MutableList<Msg>, MutableList<MutableList<Msg>>> {
            val head = mutableListOf<Msg>()
            val users = mutableListOf<Msg>()
            val tails = mutableListOf<MutableList<Msg>>()
            var started = false
            for (m in ms) {
                if (isUser(m)) { started = true; users.add(m); tails.add(mutableListOf()) }
                else if (started) tails.last().add(m) else head.add(m)
            }
            return Triple(head, users, tails)
        }
        val (lHead, lUsers, lTails) = blocks(local)
        val (sHead, sUsers, sTails) = blocks(srv)

        // 用户消息顺序匹配：正文归一相同（时间做二次确认，避免同文本误配）才认成同一条。
        // 服务端压缩删掉的老用户消息匹配不上 → 跳过，不影响其后各块。
        val l2s = IntArray(lUsers.size) { -1 }
        var sj = 0
        for (li in lUsers.indices) {
            val a = lUsers[li]
            val na = norm(a.text)
            var k = sj
            while (k < sUsers.size) {
                val b = sUsers[k]
                val sameText = na.isNotEmpty() && na == norm(b.text)
                val tsOk = a.ts <= 0 || b.ts <= 0 || kotlin.math.abs(a.ts - b.ts) <= REWRITTEN_ROW_WINDOW_MS
                if (sameText && tsOk) { l2s[li] = k; sj = k + 1; break }
                k++
            }
        }
        val srvMatched = BooleanArray(sUsers.size)
        for (m in l2s) if (m >= 0) srvMatched[m] = true

        fun mergeTail(lt: List<Msg>, st: List<Msg>): List<Msg> {
            if (lt.isEmpty()) return st
            if (st.isEmpty()) return lt
            val seg = lt.toMutableList()
            // 本地这一块里已有的助手正文（归一后），用来判重。
            val localAsst = seg.filter { it.role == "assistant" && it.text.isNotBlank() }
                .map { norm(it.text) }.toMutableList()
            // ① 本地那条「正文为空的进行中气泡」：服务端有正文就补上。
            val li = seg.indexOfLast { it.role == "assistant" }
            if (li >= 0 && seg[li].text.isEmpty()) {
                val sText = st.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
                if (!sText.isNullOrEmpty()) {
                    seg[li] = seg[li].copy(text = sText, pending = false)
                    localAsst.add(norm(sText))
                }
            }
            // ② 服务端有、本地没有的助手正文，补进这一块。
            //
            // 2026-10-08：以前这里只做①，本地助手气泡正文非空就原样返回 —— 于是一轮里
            // 服务端落多条助手消息（中途解说 + 最终答复）时，本地若在最终答复落库前断流/
            // 被杀，只收到中途解说，那条最终答复**永远补不回来**：合并后条数一条不涨，
            // 界面那轮只剩过程。用户实测「服务端跑完了，App 里没有同步过来」就是它。
            var added = 0
            for (s in st) {
                if (s.role != "assistant" || s.text.isBlank()) continue
                val ns = norm(s.text)
                // 判重放宽到「包含」：服务端压缩/改写会让同一段正文在两侧不完全等长，
                // 只比相等会把改写过的旧行当成新行，重复贴一条。
                val dup = localAsst.any { it == ns || it.contains(ns) || ns.contains(it) }
                if (!dup) {
                    seg.add(s.copy(pending = false))
                    localAsst.add(ns)
                    added++
                }
            }
            if (added > 0) {
                AppLog.log("sync", "合并补齐助手正文 本地块=" + lt.size + " 服务端块=" + st.size +
                    " 补入=" + added)
            }
            return seg
        }

        val out = mutableListOf<Msg>()
        out.addAll(if (lHead.isNotEmpty()) lHead else sHead)
        // 服务端独有的用户块（被压缩改写 / 别的端发的轮次）按其原顺序插回对应位置：
        // 用「下一个本地已匹配块的 srv 索引」当边界，把边界之前未匹配的服务端块补进去。
        var sIdx = 0
        for (li in lUsers.indices) {
            val target = l2s[li]
            if (target >= 0) {
                while (sIdx < target) {
                    if (!srvMatched[sIdx]) { out.add(sUsers[sIdx]); out.addAll(sTails[sIdx]) }
                    sIdx++
                }
                sIdx = target + 1
            }
            out.add(lUsers[li])
            out.addAll(mergeTail(lTails[li], if (target >= 0) sTails[target] else emptyList()))
        }
        // 本地所有块之后，剩余未匹配的服务端用户块照原顺序补上。
        while (sIdx < sUsers.size) {
            if (!srvMatched[sIdx]) { out.add(sUsers[sIdx]); out.addAll(sTails[sIdx]) }
            sIdx++
        }
        return out
    }

    private fun parseTs(s: String): Long = runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun pingLoop() {
        if (!pingStarted.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            var fails = 0
            var tick = 0
            // 最近一次探测成功的时刻：用于「30 秒内成功过就仍算在线」的兜底，
            // 避免单次超时（fails 未达 3 次阈值）也把角标打红。
            var lastOkAt = 0L
            while (true) {
                val ok = api?.ping() ?: false
                if (ok) {
                    if (!_online.value) AppLog.log("net", "恢复在线")
                    fails = 0
                    lastOkAt = System.currentTimeMillis()
                    _online.value = true   // 恢复立刻生效
                } else {
                    // 2026-10-08：连续 2 次失败就翻「离线」太敏感 —— 移动网络/服务端偶发一次
                    // 超时就闪红，用户实测「一直显示离线」而服务端 6195 次心跳全 200。
                    // 现在：连续 3 次失败才翻，且最近 30 秒内成功过就仍算在线（兜底）。
                    fails++
                    val recentlyOk = lastOkAt > 0 && System.currentTimeMillis() - lastOkAt < 30_000L
                    if (fails >= 3 && !recentlyOk) {
                        if (_online.value) AppLog.log("net", "连续 " + fails + " 次探测失败，标记离线")
                        _online.value = false
                    } else {
                        AppLog.log("net", "探测失败第 " + fails + " 次（最近成功=" + recentlyOk + "），暂不标离线")
                    }
                }
                // 每 30 秒静默查一次更新：发新版后角标自动亮起，不必等下次启动。
                tick++
                if (tick % 6 == 0) checkUpdateSilently()
                delay(5000)
            }
        }
    }

    // ---------- 定时任务（服务端 /api/jobs） ----------

    private val _jobs = MutableStateFlow<List<JobItem>>(emptyList())
    val jobs = _jobs.asStateFlow()

    private val _jobsErr = MutableStateFlow("")
    val jobsErr = _jobsErr.asStateFlow()

    private val _jobsNote = MutableStateFlow("")
    val jobsNote = _jobsNote.asStateFlow()

    /** App 收件箱：定时任务产出（api_server 通道不支持推送，见 HermesApi.inbox）。 */
    private val _inbox = MutableStateFlow<List<CronReport>>(emptyList())
    val inbox = _inbox.asStateFlow()
    private val _inboxUnread = MutableStateFlow(0)
    val inboxUnread = _inboxUnread.asStateFlow()
    private val _inboxErr = MutableStateFlow("")
    val inboxErr = _inboxErr.asStateFlow()

    /** 正在看的报告：非 null 时界面弹「定时任务产出」全文。 */
    private val _cronReport = MutableStateFlow<CronReport?>(null)
    val cronReport = _cronReport.asStateFlow()

    /**
     * 拉一次收件箱。App 打开 / 回前台 / 进定时任务页时调。
     *
     * 拉回来后：新的未读条目弹一条本机通知（App 开着也能看见「任务跑完了」），
     * 同一条只弹一次（按 id 记着）。失败只记错误文案，不打扰。
     */
    fun refreshInbox(notifyNew: Boolean = true) {
        // 必须显式切 IO：RuntimeHub.scope 是 Dispatchers.Main.immediate，而 a.inbox()
        // 是阻塞式 HTTP。挂在主线程上会抛 NetworkOnMainThreadException（在建立连接那一
        // 刻就抛，请求根本没发出去——服务端访问日志里一条都不会有），界面显示
        // 「收件箱获取失败」。而且该异常的 message 是 null，不带上异常类名就只剩一个「?」。
        RuntimeHub.scope.launch(Dispatchers.IO) {
            try {
                val a = api ?: return@launch
                val resp = a.inbox(limit = 50)
                val arr = resp.optJSONArray("items") ?: JSONArray()
                val out = mutableListOf<CronReport>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        CronReport(
                            id = o.optString("id", ""),
                            at = o.optString("at", ""),
                            jobId = o.optString("job_id", ""),
                            jobName = o.optString("job_name", ""),
                            failed = o.optString("status", "ok") == "failure",
                            body = o.optString("body", ""),
                            // 服务端「未读」是 JSON null（不是缺键）：Android 的 optString 会把
                            // JSON null 读成字符串 "null"（非空），所以这里必须先判 isNull。
                            unread = o.isNull("acked_at") || o.optString("acked_at", "").isEmpty(),
                        )
                    )
                }
                _inbox.value = out
                _inboxUnread.value = resp.optInt("unread", out.count { it.unread })
                _inboxErr.value = ""
                if (notifyNew) {
                    // 只弹最新一条未读（一次拉回好几条未读不该连响一串），且**跨重启**只弹一次：
                    // 去重集合落盘在 Prefs（原来只在内存里，重启就空 → 每次开机都把同一条未读
                    // 再响一遍；用户不进定时任务页点掉它就会一直响）。
                    val newestUnread = out.firstOrNull { it.unread }
                    if (newestUnread != null && newestUnread.id !in prefs.notifiedReportIds()) {
                        prefs.markReportNotified(newestUnread.id)
                        Notifier.notifyMessage(
                            getApplication(),
                            (if (newestUnread.failed) "定时任务失败：" else "定时任务完成：") +
                                (newestUnread.jobName.ifEmpty { newestUnread.jobId }),
                            newestUnread.body.replace(Regex("\\s+"), " ").trim().take(80),
                        )
                    }
                }
            } catch (e: Exception) {
                _inboxErr.value = "收件箱获取失败：" + diagText(e)
            }
        }
    }

    /** 打开一条报告看全文（未读则顺手标已读）。 */
    fun openCronReport(report: CronReport) {
        _cronReport.value = report
        if (report.unread) ackInbox(listOf(report.id))
    }

    fun closeCronReport() {
        _cronReport.value = null
    }

    /** 标记已读：ids 为空表示整箱已读。标记完本地状态与未读数一起更新。 */
    fun ackInbox(ids: List<String> = emptyList(), all: Boolean = false) {
        // 同上：a.ackInbox() 也是阻塞 HTTP，不能在主线程上跑。
        RuntimeHub.scope.launch(Dispatchers.IO) {
            try {
                val a = api ?: return@launch
                a.ackInbox(ids, all)
                _inbox.value = if (all) {
                    _inbox.value.map { it.copy(unread = false) }
                } else {
                    val set = ids.toSet()
                    _inbox.value.map { if (it.id in set) it.copy(unread = false) else it }
                }
                _inboxUnread.value = _inbox.value.count { it.unread }
            } catch (e: Exception) {
                _inboxErr.value = "标记已读失败：" + diagText(e)
            }
        }
    }

    /** 删除收件箱条目：ids 非空按 id 删；all=true 整箱清空。删完本地列表与未读数即时更新。 */
    fun deleteInbox(ids: List<String> = emptyList(), all: Boolean = false) {
        RuntimeHub.scope.launch(Dispatchers.IO) {
            try {
                val a = api ?: return@launch
                val resp = a.deleteInbox(ids, all)
                val removed = resp.optInt("removed", 0)
                val set = ids.toSet()
                _inbox.value = if (all) emptyList() else _inbox.value.filter { it.id !in set }
                _inboxUnread.value = _inbox.value.count { it.unread }
                // 删掉的正是当前打开的那条时，顺手收掉全文弹窗
                val cur = _cronReport.value
                if (cur != null && (all || cur.id in set)) _cronReport.value = null
                AppLog.log("job", "收件箱删除 请求=" + (if (all) "全部" else ids.size.toString()) + " 实际=" + removed)
            } catch (e: Exception) {
                _inboxErr.value = "删除失败：" + diagText(e)
            }
        }
    }

    /** 上次列表用的过滤口径：动作完成后按同一口径重拉，避免刚暂停的任务凭空消失。 */
    private var jobsIncludeDisabled = false

    /** 已知定时任务的中文名；不认识的返回空串，界面回落显示原始名。 */
    private fun jobZhName(name: String): String {
        when (name) {
            "nightly-memory-refactor" -> return "夜间记忆整理"
            "browser-idle-reaper" -> return "浏览器空闲回收"
            "mem0-watchdog" -> return "记忆库看门狗"
            "boot-verify-report" -> return "开机自检报告"
            "fix-dup-unit-report" -> return "重复服务修复报告"
            "boot-verify2-report" -> return "开机自检报告（二）"
            "mem0-upgrade-postcheck" -> return "记忆库升级检查"
            "gradle-idle-reaper" -> return "编译进程空闲回收"
            "apk-keep-30" -> return "安装包只留 30 个"
            "friend-nightly-memory-refactor" -> return "夜间记忆整理"
            "ds-upstream-watch" -> return "上游巡检（DeepSeek）"
        }
        // 精确表认不出时按关键词兜底：别的档案（friend）和以后新加的任务都能自动出中文，
        // 不用每加一个任务改一次代码。顺序有讲究：watchdog 必须排在 watch 前面。
        val n = name.lowercase()
        return when {
            n.contains("memory-refactor") || n.contains("memory_refactor") -> "记忆整理"
            n.contains("watchdog") -> "看门狗"
            n.contains("upstream") -> "上游巡检"
            n.contains("watch") -> "上游巡检"
            n.contains("reaper") -> "空闲回收"
            n.contains("apk") || n.contains("keep") -> "安装包清理"
            n.contains("postcheck") -> "升级检查"
            n.contains("backup") -> "备份"
            n.contains("report") -> "结果报告"
            n.contains("verify") || n.contains("check") -> "自检"
            else -> ""
        }
    }

    /** 这个任务是干什么的；不认识的返回空串。 */
    private fun jobZhNote(name: String): String {
        when (name) {
            "nightly-memory-refactor" ->
                return "每天凌晨自动整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
            "browser-idle-reaper" ->
                return "每 15 分钟收掉闲置的浏览器进程，回收内存；没有闲置进程时静默。"
            "mem0-watchdog" ->
                return "每 15 分钟检查记忆库：服务掉了就拉起，网关记忆后端初始化失败就重启网关。健康时静默。"
            "boot-verify-report" ->
                return "一次性任务：容器重启后把开机自检结果发给你，跑完自动删。"
            "fix-dup-unit-report" ->
                return "一次性任务：修复重复网关服务后把结果发给你，跑完自动删。"
            "boot-verify2-report" ->
                return "一次性任务：容器重启后的自检（含重启次数与重复服务检查），跑完自动删。"
            "mem0-upgrade-postcheck" ->
                return "一次性任务：记忆库升级后的检查报告，跑完自动删。"
            "gradle-idle-reaper" ->
                return "每 15 分钟收掉空闲的编译进程，回收内存；正在编译时不动。"
            "apk-keep-30" ->
                return "每 15 分钟清一次安装包：只保留最近 30 个，防止旧版本堆满磁盘。"
            "friend-nightly-memory-refactor" ->
                return "每天凌晨自动整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
            "ds-upstream-watch" ->
                return "每天巡检 DeepSeek 上游的状态变化，有变化才出报告；无变化时静默。"
        }
        val n = name.lowercase()
        return when {
            n.contains("memory-refactor") || n.contains("memory_refactor") ->
                "定时整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
            n.contains("watchdog") ->
                "定期检查记忆库：服务掉了就拉起，网关记忆后端初始化失败就重启网关。健康时静默。"
            n.contains("watch") ->
                "定期巡检上游页面的变化，有变化才出报告；无变化时静默。"
            n.contains("reaper") ->
                "定期收掉闲置的进程，回收内存；没有闲置时静默。"
            n.contains("apk") || n.contains("keep") ->
                "定期清理安装包，只保留最近 30 个，防止旧版本堆满磁盘。"
            n.contains("postcheck") ->
                "一次性任务：升级后的检查报告，跑完自动删。"
            n.contains("backup") ->
                "定时备份数据。"
            n.contains("report") ->
                "一次性任务：把结果发给你，跑完自动删。"
            n.contains("verify") || n.contains("check") ->
                "定时自检，结果有异常才提醒。"
            else -> ""
        }
    }

    /** 运行状态翻译。 */
    private fun jobZhState(s: String): String = when (s) {
        "scheduled" -> "已排期"
        "running" -> "执行中"
        "completed" -> "已完成"
        "paused" -> "已暂停"
        "failed" -> "失败"
        else -> s
    }

    /** 上次运行结果翻译。 */
    private fun jobZhStatus(s: String): String = when (s) {
        "ok" -> "正常"
        "failed", "error" -> "失败"
        else -> s
    }

    /** 执行记录状态翻译（latest_execution.status）。 */
    private fun jobZhExecStatus(s: String): String = when (s) {
        "claimed" -> "已排入队列"
        "running" -> "执行中"
        "completed" -> "已完成"
        "failed" -> "失败"
        "unknown" -> "状态未知"
        else -> s
    }

    /**
     * 执行耗时文案：优先 finished-claimed 的墙钟差，跑着就 started-claimed。
     * 时间戳是带时区的 ISO 串，用 OffsetDateTime 解析再相减；解析失败返回空串。
     */
    private fun execDurationText(ex: JSONObject?): String {
        val e = ex ?: return ""
        fun ts(k: String): java.time.OffsetDateTime? = runCatching {
            val v = e.optString(k, "")
            if (v.isEmpty()) null else java.time.OffsetDateTime.parse(v)
        }.getOrNull()
        val claimed = ts("claimed_at") ?: return ""
        val end = ts("finished_at") ?: ts("started_at") ?: return ""
        val ms = java.time.Duration.between(claimed, end).toMillis()
        if (ms < 0) return ""
        return when {
            ms < 1000 -> "不到 1 秒"
            ms < 60_000 -> String.format("%.1f 秒", ms / 1000.0)
            else -> String.format("%.1f 分", ms / 60_000.0)
        }
    }

    /** 排期翻译：interval / cron / once 三类分别转中文。 */
    private fun jobZhSchedule(sch: JSONObject?): String {
        if (sch == null) return ""
        return when (sch.optString("kind", "")) {
            "interval" -> {
                val m = sch.optInt("minutes", 0)
                when {
                    m <= 0 -> "定时"
                    m % 60 == 0 -> "每 " + (m / 60) + " 小时"
                    else -> "每 " + m + " 分钟"
                }
            }
            // 优先按 cron 表达式翻（服务端 display 常是「every day at 9am」这种英文，
            // 靠它翻不出来）；表达式翻不出再回落 display。
            "cron" -> cronZh(sch.optString("expr", "")).ifEmpty { sch.optString("display", "") }
            "once" -> "一次性"
            else -> sch.optString("display", "")
        }
    }

    /** 5 段 cron 转中文；认不出就原样返回。 */
    private fun cronZh(expr: String): String {
        val p = expr.trim().split(Regex("\\s+"))
        if (p.size != 5) return expr
        val mi = p[0]
        val ho = p[1]
        val dom = p[2]
        val mo = p[3]
        val dow = p[4]
        val everyMin = Regex("^\\*/(\\d+)$").find(mi)
        if (everyMin != null && ho == "*" && dom == "*" && mo == "*" && dow == "*") {
            return "每 " + everyMin.groupValues[1] + " 分钟"
        }
        if (mi == "*" && ho == "*") return "每分钟"
        val m = mi.toIntOrNull()
        val h = ho.toIntOrNull()
        if (m != null && h != null) {
            val hm = String.format("%02d:%02d", h, m)
            return when {
                dom == "*" && mo == "*" && dow == "*" -> "每天 " + hm
                dom == "*" && mo == "*" && dow == "1-5" -> "工作日 " + hm
                dom == "*" && mo == "*" -> "每周 " + hm
                else -> "每月 " + dom + " 日 " + hm
            }
        }
        return expr
    }

    fun refreshJobs(includeDisabled: Boolean = jobsIncludeDisabled) {
        jobsIncludeDisabled = includeDisabled
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = fetchJobs(includeDisabled) ?: return@launch
                _jobs.value = out
                _jobsErr.value = ""
                AppLog.log("job", "任务列表加载 " + out.size + " 条 includeDisabled=" + includeDisabled)
            } catch (e: Exception) {
                AppLog.err("job", "任务列表加载失败", e)
                _jobsErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    /**
     * 取字符串字段，把「JSON 空值」统一转成空串。
     *
     * 为什么必须单列：org.json 的 optString(key, "") 只对**缺失**的键返回默认值；
     * 键存在但值是 JSON null 时，它返回字面的四个字母 "null"。定时任务卡片上的
     * 「原因 null」就是这么来的——服务端的 latest_execution.error 本来是 null
     * （= 没出错），却被当成有错误原因印了出来。
     */
    private fun jsonStr(o: org.json.JSONObject?, key: String): String {
        val v = o?.opt(key) ?: return ""
        if (v === org.json.JSONObject.NULL) return ""
        val s = v.toString()
        return if (s == "null") "" else s
    }

    /** 拉一次任务列表并解析成 JobItem（纯读取，不写 _jobs）。失败抛异常，由调用方兜。 */
    private suspend fun fetchJobs(includeDisabled: Boolean): List<JobItem>? {
        val resp = api?.listJobs(includeDisabled) ?: return null
        val arr = resp.optJSONArray("jobs") ?: org.json.JSONArray()
        val out = mutableListOf<JobItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sch = o.optJSONObject("schedule")
            val rawName = jsonStr(o, "name").ifEmpty { "(未命名)" }
            val rawLast = jsonStr(o, "last_status")
            // 最近一次执行明细：服务端每条 job 都带 latest_execution，
            // 以前 App 整个丢掉，于是「已触发执行」之后看不出跑成没成。
            val ex = o.optJSONObject("latest_execution")
            out.add(
                JobItem(
                    id = jsonStr(o, "id"),
                    name = rawName,
                    zhName = jobZhName(rawName),
                    note = jobZhNote(rawName),
                    schedule = jobZhSchedule(sch),
                    enabled = o.optBoolean("enabled", true),
                    state = jobZhState(jsonStr(o, "state")),
                    lastStatus = jobZhStatus(rawLast),
                    lastOk = rawLast == "ok",
                    lastRun = TimeFmt.isoToBj(jsonStr(o, "last_run_at")),
                    nextRun = TimeFmt.isoToBj(jsonStr(o, "next_run_at")),
                    execId = jsonStr(ex, "id"),
                    execStatus = jsonStr(ex, "status"),
                    execDuration = execDurationText(ex),
                    execError = jsonStr(ex, "error"),
                    deliveryError = jsonStr(o, "last_delivery_error"),
                )
            )
        }
        return out
    }

    /** 定时任务动作：pause / resume / run。成功后按原过滤口径重拉列表。 */
    fun jobAction(jobId: String, action: String, jobLabel: String = "") {
        if (jobId.isEmpty()) return
        val who = jobLabel.ifEmpty { jobId }
        viewModelScope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            AppLog.log("job", "操作 " + action + " id=" + jobId.take(12) + " 名=" + who)
            try {
                when (action) {
                    "pause" -> { a.pauseJob(jobId); _jobsNote.value = "已暂停：" + who }
                    "resume" -> { a.resumeJob(jobId); _jobsNote.value = "已恢复：" + who }
                    "run" -> {
                        // 立即执行：POST 只是「排上队」，服务端返回 {"ok":true} 没有 run_id。
                        // 先记下当前这次执行记录 id，触发后轮询等它变成一次新的执行，
                        // 才能知道触发的是哪条、跑成没成、产出是什么。
                        val before = _jobs.value.firstOrNull { it.id == jobId }?.execId.orEmpty()
                        a.runJob(jobId)
                        _jobsNote.value = "已触发执行：" + who
                        _jobsErr.value = ""
                        followRunToCompletion(a, jobId, who, before)
                        delay(400)
                        refreshJobs()
                        return@launch
                    }
                    else -> _jobsNote.value = "完成"
                }
                _jobsErr.value = ""
            } catch (e: Exception) {
                AppLog.err("job", "操作失败 " + action + " id=" + jobId.take(12), e)
                _jobsNote.value = ""
                _jobsErr.value = "操作失败：" + (e.message ?: "?")
            }
            delay(400)
            refreshJobs()
        }
    }

    /**
     * 「立即执行」后盯住这次执行直到结束，并把产出摘要回显到提示行。
     *
     * 为什么要轮询：POST /api/jobs/{id}/run 的语义是「下一轮 tick 排上队」，返回体只有
     * {"ok":true}，没有 run_id，也没有产出。唯一能看结果的途径是列表里那条
     * latest_execution（claimed→running→completed/failed），所以轮询它。
     *
     * 产出摘要：cron 任务跑起来会在服务端建一个会话，id 形如
     * cron_<job.id>_<yyyymmdd_HHMMSS>（job.id 与 latest_execution.job_id 一致）。
     * 跑完去 /api/sessions 里找到这次新出现的那个会话，拉最后一条助手消息即可。
     * 拿不到就只报「已完成」，绝不编造。
     */
    private suspend fun followRunToCompletion(
        a: HermesApi, jobId: String, who: String, beforeExecId: String,
    ) {
        var lastStatus = ""
        for (i in 1..40) {   // 每 3 秒一轮，最多盯 2 分钟
            delay(3_000)
            val out = try { fetchJobs(true) } catch (_: Exception) { null } ?: continue
            _jobs.value = out
            val job = out.firstOrNull { it.id == jobId } ?: continue
            val ex = job.execId
            // 还没出现新的执行记录：可能还在等下一轮 tick，继续等。
            if (ex.isEmpty() || ex == beforeExecId) continue
            lastStatus = job.execStatus
            when (job.execStatus) {
                "completed" -> {
                    val tail = job.execDuration
                    AppLog.log("job", "执行完成 " + who + " 耗时=" + tail)
                    _jobsNote.value = "已完成：" + who + (if (tail.isEmpty()) "" else "（耗时 " + tail + "）")
                    val summary = fetchRunSummary(a, jobId)
                    if (summary.isNotEmpty()) {
                        _jobsNote.value = _jobsNote.value + "\n产出：" + summary
                    }
                    return
                }
                "failed" -> {
                    val why = job.execError.ifEmpty { "未知原因" }
                    _jobsNote.value = ""
                    AppLog.log("job", "执行失败 " + who + " — " + why.take(200))
                    _jobsErr.value = "执行失败：" + who + " — " + why.take(200)
                    return
                }
                "unknown" -> {
                    _jobsNote.value = ""
                    _jobsErr.value = "执行状态未知：" + who + "（进程可能异常退出，请查服务端）"
                    return
                }
                else -> { /* claimed / running：继续等 */ }
            }
        }
        // 盯满 2 分钟还没结束：如实说还在跑，不谎报完成。
        val still = if (lastStatus == "running") "仍在执行中" else "已排入队列，仍在等待"
        _jobsNote.value = who + "：已触发，" + still + "（超过 2 分钟，可稍后刷新查看）"
    }

    /**
     * 取本次 cron 执行的产出摘要：找该任务最新一次运行会话，取最后一条助手消息。
     * 认不出会话或没有正文就返回空串（绝不编造）。
     */
    private suspend fun fetchRunSummary(a: HermesApi, jobId: String): String {
        return try {
            val resp = a.listSessions(limit = 50)
            val arr = resp.optJSONArray("data") ?: return ""
            val prefix = "cron_" + jobId + "_"
            var best: JSONObject? = null
            var bestTs = Double.NEGATIVE_INFINITY
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val sid = s.optString("id", "")
                if (!sid.startsWith(prefix)) continue
                val ts = s.optDouble("started_at", 0.0)
                if (ts > bestTs) { bestTs = ts; best = s }
            }
            val sid = best?.optString("id", "").orEmpty()
            if (sid.isEmpty()) return ""
            val m = a.sessionMessages(sid)
            val msgs = m.optJSONArray("data") ?: return ""
            var text = ""
            for (i in 0 until msgs.length()) {
                val o = msgs.optJSONObject(i) ?: continue
                if (o.optString("role", "") != "assistant") continue
                val c = o.optString("content", "")
                if (c.isNotBlank()) text = c
            }
            val one = text.replace(Regex("\\s+"), " ").trim()
            when {
                one.isEmpty() -> ""
                one.length > 160 -> one.take(160) + "…"
                else -> one
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = api?.sysinfo() ?: return@launch
                _statusHero.value = buildHero(h)
                _statusMetrics.value = buildMetrics(h)
                _statusSections.value = buildStatus(h)
                _statusErr.value = ""
            } catch (e: Exception) {
                AppLog.err("status", "获取系统状态失败", e)
                _statusErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    /** 顶部概览：网关活着没、跑什么模型、跑了多久、在干几件事。 */
    private fun buildHero(h: JSONObject): StatusHero {
        val up = h.optLong("uptime_seconds", -1)
        val uptime = if (up < 0) "" else
            (up / 86400).toString() + " 天 " + ((up % 86400) / 3600).toString() + " 时 " + ((up % 3600) / 60).toString() + " 分"
        return StatusHero(
            ok = h.optString("status") == "ok",
            statusText = if (h.optString("status") == "ok") "运行正常" else h.optString("status", "?"),
            model = h.optString("model", "").ifEmpty { "未知" },
            uptimeText = uptime,
            pid = h.optInt("pid", 0),
            activeRuns = h.optInt("active_runs", 0),
            delegations = h.optInt("active_delegations", 0),
        )
    }

    /**
     * 进度条数据。只在服务端真的回了该字段时才出条（optDouble 取不到给 -1），
     * 免得老网关（没打 sysinfo swap 补丁）显示一排 0% 的假条。
     */
    private fun buildMetrics(h: JSONObject): List<StatusMetric> {
        val out = mutableListOf<StatusMetric>()
        fun bar(key: String, label: String, pctKey: String, sub: String = "") {
            val p = h.optDouble(pctKey, -1.0)
            if (p >= 0) out.add(StatusMetric(key, label, p.coerceIn(0.0, 100.0), String.format("%.1f%%", p), sub))
        }
        val cores = h.optInt("cpu_count", 0)
        val freq = h.optInt("cpu_freq_mhz", 0)
        bar("cpu", "CPU 使用率", "cpu_percent",
            listOfNotNull(
                cores.takeIf { it > 0 }?.let { it.toString() + " 核" },
                freq.takeIf { it > 0 }?.let { it.toString() + " MHz" },
                h.optString("cpu_model", "").takeIf { it.isNotEmpty() },
            ).joinToString(" · "))
        val mUsed = h.optInt("memory_used_mb", 0)
        val mTotal = h.optInt("memory_total_mb", 0)
        bar("mem", "内存", "memory_percent",
            if (mTotal > 0) fmtMb(mUsed) + " / " + fmtMb(mTotal) else "")
        val swTotal = h.optInt("swap_total_mb", 0)
        bar("swap", "Swap", "swap_percent",
            if (swTotal > 0) fmtMb(h.optInt("swap_used_mb", 0)) + " / " + fmtMb(swTotal) else "未启用" )
        bar("disk", "磁盘", "disk_percent",
            h.optDouble("disk_total_gb", -1.0).takeIf { it >= 0 }?.let {
                String.format("已用 %.1f / %.1f GB", h.optDouble("disk_used_gb", 0.0), it)
            } ?: "")
        // 负载：百分比按「核数 = 100%」折算，超核即满条并标红。
        val la = h.optJSONArray("load_avg")
        if (la != null && la.length() >= 1 && cores > 0) {
            val l1 = la.optDouble(0, 0.0)
            val p = (l1 / cores * 100.0).coerceIn(0.0, 100.0)
            val txt = String.format("%.2f / %.2f / %.2f", l1,
                la.optDouble(1, 0.0), la.optDouble(2, 0.0))
            out.add(StatusMetric("load", "系统负载", p, txt, cores.toString() + " 核基准"))
        }
        return out
    }

    private fun fmtMb(mb: Int): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else mb.toString() + " MB"

    /** 把 /health/sysinfo 的扁平字段整理成「分组 → 行」结构，供状态页排版渲染。 */
    private fun buildStatus(h: JSONObject): List<StatusSection> {
        val out = mutableListOf<StatusSection>()

        fun sec(title: String, items: List<StatusItem>) {
            val keep = items.filter { it.value.isNotEmpty() }
            if (keep.isNotEmpty()) out.add(StatusSection(title, keep))
        }
        fun it(label: String, v: String?): StatusItem =
            StatusItem(label, v.orEmpty())

        val gw = mutableListOf<StatusItem>()
        gw.add(it("运行状态", if (h.optString("status") == "ok") "正常" else h.optString("status", "?")))
        val pid = h.optInt("pid", 0)
        if (pid > 0) gw.add(it("进程 PID", pid.toString()))
        gw.add(it("操作系统", h.optString("platform", "")))
        gw.add(it("Python", h.optString("python", "")))
        sec("网关", gw)

        val cpu = mutableListOf<StatusItem>()
        cpu.add(it("型号", h.optString("cpu_model", "")))
        val cpuPct = h.optDouble("cpu_percent", -1.0)
        if (cpuPct >= 0) cpu.add(it("使用率", String.format("%.1f%%", cpuPct)))
        val cores = h.optInt("cpu_count", 0)
        if (cores > 0) cpu.add(it("核心数", cores.toString() + " 核"))
        val freq = h.optInt("cpu_freq_mhz", 0)
        if (freq > 0) cpu.add(it("主频", freq.toString() + " MHz"))
        sec("CPU", cpu)

        val mem = mutableListOf<StatusItem>()
        val memPct = h.optDouble("memory_percent", -1.0)
        if (memPct >= 0) mem.add(it("使用率", String.format("%.1f%%", memPct)))
        val mUsed = h.optInt("memory_used_mb", 0)
        val mTotal = h.optInt("memory_total_mb", 0)
        if (mTotal > 0) mem.add(it("已用/总量", mUsed.toString() + " MB / " + mTotal.toString() + " MB"))
        val pmem = h.optInt("proc_memory_mb", 0)
        if (pmem > 0) mem.add(it("网关进程", pmem.toString() + " MB"))
        // 交换分区：内存满了靠 swap 顶，swap 也快满才是真要 OOM，故与内存同区显示。
        // 服务端未打 sysinfo swap 补丁时不回这三个字段，optDouble 取到 -1 / optInt 取到 0，整行自动不显示。
        val swPct = h.optDouble("swap_percent", -1.0)
        if (swPct >= 0) mem.add(it("Swap 使用率", String.format("%.1f%%", swPct)))
        val swUsed = h.optInt("swap_used_mb", 0)
        val swTotal = h.optInt("swap_total_mb", 0)
        if (swTotal > 0) mem.add(it("Swap 已用/总量", swUsed.toString() + " MB / " + swTotal.toString() + " MB"))
        sec("内存", mem)

        val disk = mutableListOf<StatusItem>()
        val dTotal = h.optDouble("disk_total_gb", -1.0)
        if (dTotal >= 0) disk.add(it("总大小", String.format("%.1f GB", dTotal)))
        val dUsed = h.optDouble("disk_used_gb", -1.0)
        if (dUsed >= 0) disk.add(it("已用", String.format("%.1f GB", dUsed)))
        val dFree = h.optDouble("disk_free_gb", -1.0)
        if (dFree >= 0) disk.add(it("可用", String.format("%.1f GB", dFree)))
        val dPct = h.optDouble("disk_percent", -1.0)
        if (dPct >= 0) disk.add(it("使用率", String.format("%.1f%%", dPct)))
        sec("磁盘", disk)

        val run = mutableListOf<StatusItem>()
        val la = h.optJSONArray("load_avg")
        if (la != null && la.length() >= 3) {
            run.add(it("负载 1/5/15", String.format("%.2f / %.2f / %.2f",
                la.optDouble(0, 0.0), la.optDouble(1, 0.0), la.optDouble(2, 0.0))))
        }
        val up = h.optLong("uptime_seconds", -1)
        if (up >= 0) run.add(it("已运行", (up / 86400).toString() + " 天 " + ((up % 86400) / 3600).toString() + " 小时 " + ((up % 3600) / 60).toString() + " 分"))
        sec("运行", run)

        val apiSec = mutableListOf<StatusItem>()
        apiSec.add(it("当前模型", h.optString("model", "").ifEmpty { "未知" }))
        val mt = h.optJSONObject("metrics_today")
        if (mt != null) {
            apiSec.add(it("今日请求", mt.optInt("requests", 0).toString()))
            apiSec.add(it("今日消息", mt.optInt("messages", 0).toString()))
        }
        apiSec.add(it("活跃任务", h.optInt("active_runs", 0).toString()))
        apiSec.add(it("子任务", h.optInt("active_delegations", 0).toString()))
        apiSec.add(it("队列深度", h.optInt("process_queue_depth", 0).toString()))
        val hb = h.optString("last_heartbeat", "")
        if (hb.isNotEmpty()) apiSec.add(it("最后心跳", TimeFmt.isoToBj(hb) + "（北京）"))
        sec("API 与任务", apiSec)

        return out
    }

    // ---------- 发送与流式接收 ----------

    fun send(text: String) {
        val a = api ?: return
        val imgs = _pendingImages.value
        if (text.isBlank() && imgs.isEmpty()) return
        // 空态（无选中会话）：直接输入即新建对话
        if (_currentId.value.isEmpty()) {
            val id = UUID.randomUUID().toString()
            _currentId.value = id
            prefs.sessionId = id
            val nOrder0 = if (_sessions.value.any { it.order > 0L }) nextTopOrder() else 0L
            _sessions.value = listOf(SessionMeta(id, "新对话", stamp(), false, nOrder0)) + _sessions.value
            saveIndexAsync(debounceMs = 0L)
            rt(id).loaded = true
        }
        val sid = _currentId.value
        val r = rt(sid)
        // 同一会话正在跑：不再直接拦下，改为排队（气泡先落下，本轮结束自动发）。
        // 其它会话照发，不受影响。
        val willQueue = r.busy.value
        ensureLoaded(sid)
        val wasEmpty = r.messages.value.none { it.role == "user" }
        // 引用回复：长按选中的那条，压成一行片段。发往服务端的正文前拼上「> 引用」，
        // 让模型知道在回应哪一句；气泡里只回显片段，不重复整段。
        val quoted = _quoteTarget.value
        val quoteSnip = if (quoted != null) quoteSnippet(quoted) else ""
        val sendText = if (quoteSnip.isNotEmpty()) "> " + quoteSnip + "\n" + text else text
        // 只选了附件、一个字没打时，正文是空的。服务端对 input 有非空校验，而且是先查
        // input、后处理附件——空正文直接被 400 挡回（Missing 'input' field），附件白选。
        // 所以发往服务端的正文补一个占位词；气泡里仍然一个字都不显示。
        // 引用/回执的位置锚点也用这一版，重发与「确认送达」才和服务端记录对得上。
        val wireText = sendText.ifBlank { attachmentLabel(imgs) }
        // 记下发送前的用户消息条数（翻历史时的位置锚点）与本轮正文（内容校验）。
        // 锚点用拼好引用的 sendText：服务端记录里存的就是这一版，才能对上。
        val prior = r.messages.value.count { it.role == "user" }
        r.priorUserCount = prior
        r.pendingSendText = wireText.trim()
        r.recoveryJob?.cancel()
        // 发出去的图先落进 App 私有「已发送」目录：相册给的 content:// 会被系统回收
        // （换机/清数据/授权到期），outbox 会被「清理缓存」清掉——两者都会让历史里的图变白框。
        // sent/ 不参与清理、又是私有文件，重开、清缓存后都还在。
        //
        // 关键：挪动之后，「上传」和「气泡回显」必须都认挪过去的新文件。老代码在这一步把
        // outbox 原文件删了，可后面发请求时还按老路径读 → 文件已不存在，附件必然发不出去。
        val durableImgs = mutableListOf<String>()
        val uploadFiles = mutableListOf<java.io.File>()
        for (p in imgs) {
            if (p.isImage) {
                val (f, uri) = persistOutgoing(p)
                durableImgs.add(uri)
                uploadFiles.add(f)
            } else {
                uploadFiles.add(p.file)
            }
        }
        // 投递状态挂在用户消息自己身上（按 msgId 认领，不靠位置）：POST 没回来前是 sending，
        // 拿到 run_id 才转 accepted，中途断了转 uncertain 等用户处置。
        // 幂等键在发送前一次性生成，写进回执并随请求发出：服务端凭它保证同一句话只执行
        // 一次，重发不会变成两遍。
        val idemKey = UUID.randomUUID().toString().replace("-", "")
        val userMsg = Msg(
            "user", text, ts = stamp(),
            images = durableImgs.toList(),
            files = imgs.filter { !it.isImage }.map { it.file.name },
            receipt = Receipt(
                status = if (willQueue) Receipt.QUEUED else Receipt.SENDING,
                rawText = wireText.trim(),
                priorUserCount = prior,
                idemKey = idemKey,
            ),
            quote = quoteSnip,
            idemKey = idemKey,
        )
        r.lastUserMsgId = userMsg.id
        setMsgs(r, r.messages.value + userMsg)
        touchSession(if (wasEmpty) text.ifBlank { attachmentLabel(imgs) } else null)
        _pendingImages.value = emptyList()
        _quoteTarget.value = null
        if (willQueue) {
            // 排队：气泡先落下（回执标「排队中」），本轮一结束由 drainQueue 自动发。
            r.queue.add(QueuedSend(wireText, uploadFiles.toList(), userMsg.id, prior, idemKey))
            r.queued.value = r.queue.size
            AppLog.log("queue", "入队 sid=" + sid.take(8) + " msg=" + userMsg.id + " 队列=" + r.queue.size)
            return
        }
        startRunWith(a, sid, wireText, uploadFiles.toList(), receiptMsgId = userMsg.id, idemKey = idemKey)
    }

    /**
     * 排空队列：本会话空闲且队列非空时，取出最早的一条发出去。
     * 在每处「本轮结束」（doneOk / failPending / stopSession / 翻历史收尾）后调用。
     */
    private fun drainQueue(sid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.busy.value || r.queue.isEmpty() || r.queuePaused.value) return
        val next = r.queue.removeAt(0)
        r.queued.value = r.queue.size
        AppLog.log("queue", "出队发送 sid=" + sid.take(8) + " msg=" + next.msgId + " 剩余=" + r.queue.size)
        r.priorUserCount = next.priorUserCount
        r.pendingSendText = next.text.trim()
        r.recoveryJob?.cancel()
        advanceReceipt(sid, next.msgId, Receipt.SENDING, note = "")
        startRunWith(a, sid, next.text, next.files, receiptMsgId = next.msgId, idemKey = next.idemKey)
    }

    /** 用户点「继续」：解除「停止」对队列的按住，接着发。 */
    fun resumeQueue() {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        r.queuePaused.value = false
        AppLog.log("queue", "继续队列 sid=" + sid.take(8) + " 待发=" + r.queue.size)
        drainQueue(sid)
    }

    /**
     * 撤回一条还没发出去的排队消息：从队列摘掉，连那条用户气泡一起收掉。
     * 排队中的消息本轮结束会自动发，此前没有任何入口能把它收回来。
     */
    fun cancelQueued(msgId: Long) {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val i = r.queue.indexOfFirst { it.msgId == msgId }
        if (i >= 0) r.queue.removeAt(i)
        r.queued.value = r.queue.size
        AppLog.log("queue", "撤回排队 sid=" + sid.take(8) + " msg=" + msgId + " 剩余=" + r.queue.size)
        removeUserMsg(r, msgId)
    }

    /**
     * 编辑一条排队消息：从队列摘掉、收掉原气泡，正文回填到输入框由用户改完重发。
     * 附件不回填（待发区依赖 uri，还原不了），需要就重新选。
     */
    fun editQueued(msgId: Long) {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val i = r.queue.indexOfFirst { it.msgId == msgId }
        if (i < 0) return
        val q = r.queue.removeAt(i)
        r.queued.value = r.queue.size
        AppLog.log("queue", "编辑排队 sid=" + sid.take(8) + " msg=" + msgId + " 剩余=" + r.queue.size)
        _queuedEdit.value = q.text
        removeUserMsg(r, msgId)
    }

    /** 编辑回填：输入框取走后清空。 */
    fun clearQueuedEdit() { _queuedEdit.value = null }

    private fun removeUserMsg(r: SessionRuntime, msgId: Long) {
        val list = r.messages.value.toMutableList()
        val k = list.indexOfFirst { it.id == msgId }
        if (k >= 0) { list.removeAt(k); setMsgs(r, list) }
    }

    /**
     * 中途插话（steer）：把这句话注入当前正在跑的这一轮，服务端 agent 会读到。
     * 与「排队」不同——排队是下一轮才发，插话是立刻影响本轮方向。
     */
    fun steerCurrent(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val a = api ?: return
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        // 插话要在聊天界面看得见：直接插一条用户气泡（标「插话」），
        // 不再只往折叠的过程里塞一行——那样用户以为没发出去。
        run {
            val list = r.messages.value.toMutableList()
            list.add(Msg("user", t, pending = false, ts = stamp(), steer = true))
            setMsgs(r, list)
        }
        // 插话结果要可见：200 才算送进本轮；409/其它说明本轮已收尾、这句没赶上。
        // 原来 runCatching 把返回整个吞了，用户看不到任何反应，才觉得「插不进去」。
        viewModelScope.launch(Dispatchers.IO) {
            val ok = a.steer(rid, t)
            AppLog.log("steer", "插话 run=" + rid.take(12) + " 结果=" + (if (ok) "送达" else "未送达") + " len=" + t.length)
            withContext(Dispatchers.Main) {
                r.retryNote.value = if (ok) "插话已送达，本轮会读到"
                else "插话没送达：本轮可能已收尾，这句话没赶上"
            }
        }
    }

    /** 按消息 id 改投递状态（找不到就忽略——消息可能已被「清理缓存」截掉）。 */
    private fun setReceipt(sid: String, msgId: Long, receipt: Receipt?) {
        if (msgId <= 0) return
        val r = rt(sid)
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        list[i] = list[i].copy(receipt = receipt)
        setMsgs(r, list)
    }

    /** 把某条用户消息的投递状态推到下一档，其余字段保留。 */
    private fun advanceReceipt(
        sid: String,
        msgId: Long,
        status: String,
        runId: String? = null,
        note: String = "",
        idemKey: String? = null,
        artifactIds: List<String>? = null,
    ) {
        if (msgId <= 0) return
        val r = rt(sid)
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val old = list[i].receipt
        AppLog.log("receipt", "回执 " + (old?.status ?: "无") + " -> " + status +
            " sid=" + sid.take(8) + " msg=" + msgId + (if (note.isNotEmpty()) " note=" + note.take(60) else ""))
        list[i] = list[i].copy(
            receipt = Receipt(
                status = status,
                runId = runId ?: old?.runId.orEmpty(),
                note = note,
                rawText = old?.rawText ?: list[i].text.trim(),
                priorUserCount = old?.priorUserCount ?: -1,
                // 幂等键与附件 id 一旦定下就不再变：传入才覆盖，否则沿用旧的。
                idemKey = idemKey ?: old?.idemKey.orEmpty(),
                artifactIds = artifactIds ?: old?.artifactIds ?: emptyList(),
            )
        )
        setMsgs(r, list)
    }

    /** 发送明确失败时把那个空气泡收掉，别在界面留一个空壳。 */
    private fun dropEmptyPending(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) return
        val m = list[i]
        if (m.text.isEmpty() && m.trace.isEmpty() && m.approval == null && m.clarify == null &&
            m.usage == null && m.subagents.isEmpty()
        ) {
            list.removeAt(i)
            setMsgs(r, list)
        }
    }

    // ---------- 投递回执：服务端到底收下这条没有 ----------

    private val _receiptMenu = MutableStateFlow(0L)
    /** 当前展开处置按钮的用户消息 id；0 表示没有。 */
    val receiptMenu = _receiptMenu.asStateFlow()

    fun openReceiptMenu(msgId: Long) {
        _receiptMenu.value = if (_receiptMenu.value == msgId) 0L else msgId
    }

    fun closeReceiptMenu() {
        _receiptMenu.value = 0L
    }

    /**
     * 「知道了，不重发」：把「不确定」标成已确认忽略。
     *
     * 原来这条消息卡在黄问号时只有「点这里处理」，用户想收掉角标就只能选「重新发送」——
     * 等于拿一句话去赌会不会发两遍。现在确认收到即可，角标与提示一起收起，不动网络。
     */
    fun acknowledgeReceipt(msgId: Long) {
        val sid = _currentId.value
        _receiptMenu.value = 0L
        advanceReceipt(sid, msgId, Receipt.ACKED, note = "")
    }

    /**
     * 「确认送达」：去服务端会话记录里按位置锚点 + 正文核对这条消息在不在。
     * 在 → 标已送达并接着把答案等回来（复用断流翻历史那套轮询）；
     * 不在 → 标失败，提示可重发。认不出锚点就报失败，绝不硬认。
     */
    fun confirmReceipt(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        val list = r.messages.value
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val rc = list[i].receipt ?: return
        _receiptMenu.value = 0L
        viewModelScope.launch(Dispatchers.IO) {
            val rows = try { historyRows(a, sid) } catch (_: Exception) { emptyList() }
            val anchor = if (rows.isEmpty()) -1 else resolveAnchor(rows, rc.rawText, rc.priorUserCount)
            if (anchor < 0) {
                advanceReceipt(sid, msgId, Receipt.FAILED, note = "服务端记录里没有这条消息，可重新发送")
                return@launch
            }
            advanceReceipt(sid, msgId, Receipt.ACCEPTED, note = "")
            r.pendingSendText = rc.rawText
            r.priorUserCount = rc.priorUserCount
            r.confirmingMsgId = msgId
            r.busy.value = true
            r.finished = false
            r.retryNote.value = "已确认送达，正在取回结果…"
            setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r)))
            updateRunService()
            startHistoryRecovery(sid, "")
        }
    }

    /**
     * 「重新发送」：拿原正文再 POST 一次（图片附件能找回就一并带上）。
     *
     * 复用首次发送时的幂等键与附件 id：服务端凭键认出这是同一条，24 小时内只执行
     * 一次，重发不会变成发两遍；若首次其实已经收下，服务端会把**原来那轮的 run_id**
     * 还回来，这里直接接上那一轮取结果。这就是「不确定」不再需要用户赌一把的原因。
     */
    fun resendReceipt(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        val list = r.messages.value
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val m = list[i]
        val rc = m.receipt ?: return
        _receiptMenu.value = 0L
        if (r.busy.value) {
            r.retryNote.value = "本会话还在跑，等这轮结束再重发"
            return
        }
        r.priorUserCount = list.take(i).count { it.role == "user" }
        r.pendingSendText = rc.rawText
        r.recoveryJob?.cancel()
        val files = m.images.mapNotNull { u ->
            runCatching { File(Uri.parse(u).path ?: "") }.getOrNull()?.takeIf { it.exists() }
        }
        // 附件已有 artifact id 就复用，不再重传（重传会换 id、指纹不符会被判冲突）。
        val reuse = rc.artifactIds
        if (m.files.isNotEmpty() && reuse.isEmpty()) {
            r.retryNote.value = "这条带过附件，重发只带上图片，其他附件请重新选"
        }
        advanceReceipt(sid, msgId, Receipt.SENDING, note = "")
        startRunWith(
            a, sid, rc.rawText, files,
            receiptMsgId = msgId,
            idemKey = rc.idemKey,
            reuseArtifacts = reuse,
        )
    }

    /**
     * 只发附件、没打字时，发给服务端的占位正文（服务端不允许空 input）。
     * 纯占位，气泡里不显示；图片本身走原生多模态附件，模型照样看得到图。
     */
    private fun attachmentLabel(imgs: List<PendingImage>): String {
        val hasImg = imgs.any { it.isImage }
        val hasFile = imgs.any { !it.isImage }
        return when {
            hasImg && hasFile -> "（图片和文件）"
            hasImg -> "（图片）"
            else -> "（文件）"
        }
    }

    /**
     * 把待发图片从 outbox 挪进「已发送」目录，返回 (挪过去的文件, 气泡要存的 file:// 地址)。
     *
     * 为什么要把文件也返回来：挪动之后上传必须用新路径。老实现只返回地址、却把 outbox
     * 原文件删了，调用方随后仍拿 p.file 去读字节 → 文件不存在，附件发送必然失败。
     * 落盘失败时退回原文件与原地址（至少本次还能发、还能看），不阻断发送。
     */
    private fun persistOutgoing(p: PendingImage): Pair<File, String> {
        val app = getApplication<Application>()
        return try {
            val dir = File(app.filesDir, "sent").apply { mkdirs() }
            val dst = File(dir, p.file.name)
            if (!dst.exists()) p.file.copyTo(dst, overwrite = true)
            if (p.file.absolutePath != dst.absolutePath) p.file.delete()
            dst to android.net.Uri.fromFile(dst).toString()
        } catch (_: Exception) {
            p.file to p.uri
        }
    }

    /** 把选中的图片拷进 App 沙盒，加入待发列表。 */
    fun addImage(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 20L * 1024 * 1024) {
                    _imageNote.value = "图片超过 20MB：" + name
                    return@launch
                }
                val dir = File(app.filesDir, "outbox").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "jpg").lowercase().let {
                    if (it.length in 1..5) it else "jpg"
                }
                val dst = File(dir, "img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    _imageNote.value = "读取图片失败"
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    dst.delete()
                    return@launch
                }
                _imageNote.value = ""
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = true)
            } catch (e: Exception) {
                AppLog.err("attach", "读取图片失败 uri=" + uri, e)
                _imageNote.value = "读取图片失败：" + (e.message ?: "?")
            }
        }
    }

    /** 把任意文件拷进 App 沙盒，加入待发列表（非图片走文件卡片展示）。 */
    fun addFile(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 50L * 1024 * 1024) {
                    _imageNote.value = "文件超过 50MB：" + name
                    return@launch
                }
                val dir = File(app.filesDir, "outbox").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "").lowercase().let {
                    if (it.length in 1..5) it else "bin"
                }
                val dst = File(dir, "file_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    _imageNote.value = "读取文件失败"
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    dst.delete()
                    return@launch
                }
                val isImg = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                _imageNote.value = ""
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = isImg)
            } catch (e: Exception) {
                AppLog.err("attach", "读取文件失败 uri=" + uri, e)
                _imageNote.value = "读取文件失败：" + (e.message ?: "?")
            }
        }
    }

    fun removeImage(id: Long) {
        val list = _pendingImages.value
        list.firstOrNull { it.id == id }?.file?.delete()
        _pendingImages.value = list.filter { it.id != id }
    }

    private fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "image.jpg"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    // 按真实扩展名给 MIME：图片走 image 类，其余按常见类型给，未知一律 octet-stream。
    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "html", "htm" -> "text/html"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun startRunWith(
        a: HermesApi,
        sid: String,
        text: String,
        files: List<File> = emptyList(),
        receiptMsgId: Long = 0L,
        idemKey: String = "",
        reuseArtifacts: List<String> = emptyList(),
        autoRetryLeft: Int = 1,
    ) {
        val r = rt(sid)
        // 本轮计时起点先定好：气泡与最终耗时都锚在它上面，中途气泡被重建也不会漂。
        r.startedAt = stamp()
        setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp(), startedAt = r.startedAt))
        r.busy.value = true
        r.finished = false
        r.lastSeq = -1
        r.autoContinue = 0
        r.resumed = false
        updateRunService()
        val ids = mutableListOf<String>()
        var uploadsDone = reuseArtifacts.isNotEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 附件 id：重发必须复用首次那份（服务端算指纹含请求体，换了 id 会被判冲突）。
                if (reuseArtifacts.isNotEmpty()) {
                    ids.addAll(reuseArtifacts)
                } else {
                    for ((i, f) in files.withIndex()) {
                        _imageNote.value = "上传附件 ${i + 1}/${files.size}…"
                        ids.add(a.uploadImage(f.readBytes(), f.name, mimeOf(f)))
                    }
                    uploadsDone = true
                }
                _imageNote.value = ""
                val run = a.startRun(text, sid, ids, idemKey)
                r.runId = run.optString("run_id", run.optString("id", ""))
                AppLog.log("send", "已建 run=" + r.runId.take(12) + " 重放=" + run.optBoolean("replayed", false) + " 附件=" + ids.size)
                // 计时起点不在这里重设：上传附件+建 run 的往返也算本轮耗时，
                // 重设会把这一段抹掉，最终值比界面实时值小一截。
                prefs.putActiveRun(sid, r.runId)
                prefs.putLastSeq(sid, -1)   // 新 run 从 0 开始，清掉上一轮的续接序号
                // 拿到 run_id 才算「服务端已收下」，此时回执才转已送达。
                // 幂等键与附件 id 一并落进回执，供后续重发原样复用。
                advanceReceipt(sid, receiptMsgId, Receipt.ACCEPTED, runId = r.runId,
                    idemKey = idemKey, artifactIds = ids)
                streamRun(a, sid)
            } catch (e: Exception) {
                _imageNote.value = ""
                val msg = e.message ?: "?"
                val httpReject = msg.startsWith("HTTP ")
                AppLog.err("send", "发送失败 服务端拒绝=" + httpReject + " 内容=" + msg.take(200), e)
                // 网络中断（收不到回执）、带幂等键、且还没重试过 → 自动安全重试一次。
                // 为什么现在敢自动重试：服务端对同一个键只执行一次，重试要么接上原来那轮
                // （首次其实已收下，只是回包丢了），要么全新执行一次，绝不会变成发两遍。
                // 这正是加幂等键最大的收益——「不确定」不再需要用户赌一把。
                if (!httpReject && idemKey.isNotEmpty() && autoRetryLeft > 0) {
                    dropEmptyPending(r)
                    r.retryNote.value = "发送未确认，正在自动重试…"
                    delay(2000)
                    // 附件已传完就复用那份 id（保证指纹一致、能命中重放）；
                    // 上传阶段就断了则重新上传（此时服务端没记下任何键，不会冲突）。
                    startRunWith(
                        a, sid, text, files, receiptMsgId,
                        idemKey = idemKey,
                        reuseArtifacts = if (uploadsDone) ids.toList() else emptyList(),
                        autoRetryLeft = autoRetryLeft - 1,
                    )
                    return@launch
                }
                // 服务端有明确回执（HTTP 4xx/5xx）= 拒绝，标失败；
                // 重试用尽仍收不到回执 = 标「不确定」等用户处置（此时仍可手动重发，也安全）。
                dropEmptyPending(r)
                if (httpReject) {
                    advanceReceipt(sid, receiptMsgId, Receipt.FAILED, note = msg)
                } else {
                    r.retryNote.value = "发送结果不确定：网络中断，服务端可能已收下，可重发（不会重复）"
                    advanceReceipt(sid, receiptMsgId, Receipt.UNCERTAIN, note = msg)
                }
                failPending(sid)
            }
        }
    }

    /** 澄清请求：挂到该会话当前助手气泡上，等用户选选项回执。 */
    private fun attachClarify(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        AppLog.log("clarify", "收到澄清请求 sid=" + r.id.take(8) + " id=" + ev.data.optString("clarify_id", "").take(12))
        notifyNeedAction(r.id, "需要你选一下", ev.data.optString("question", ""))
        placeClarify(r, ev.data)
    }

    /**
     * 把澄清卡片挂到该会话。挂载位置按「保守」原则：优先当前进行中的助手气泡
     * （pending），没有就挂最后一条助手消息（重开 App 后本地消息的 pending 已归
     * false），都没有才新建。同一 clarify_id 已挂且未作废的不重复挂。
     */
    private fun placeClarify(r: SessionRuntime, data: org.json.JSONObject) {
        val cid = data.optString("clarify_id", "")
        val q = data.optString("question", "")
        if (cid.isEmpty() || q.isEmpty()) return
        val chs = mutableListOf<String>()
        data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        val cur = r.messages.value
        if (cur.any { it.clarify?.clarifyId == cid && it.clarify?.resolved?.isEmpty() == true }) return
        val card = ClarifyCard(cid, q, chs, data.optBoolean("multi_select", false))
        val list = cur.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(clarify = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), clarify = card))
        setMsgs(r, list)
    }

    /** 用户点了澄清选项：回执给服务端，并把卡片置为已选。 */
    fun respondClarify(msgId: Long, choice: String) {
        val a = api ?: return
        val r = rt(_currentId.value)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].clarify ?: return
        if (card.resolved.isNotEmpty()) return
        // 发成功才标「已选择」：先发请求，成功才把卡片定下来；失败就保持按钮可点，
        // 提示里的「可重试」才真的能重试（原来先置已选、再发，失败后界面已无入口）。
        if (!receiptInFlight.add(msgId)) return
        r.retryNote.value = "正在送达回执…"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = a.respondClarify(rid, card.clarifyId, choice)
            withContext(Dispatchers.Main) {
                receiptInFlight.remove(msgId)
                if (ok) {
                    val cur = r.messages.value.toMutableList()
                    val k = cur.indexOfFirst { it.id == msgId }
                    if (k >= 0) {
                        val cc = cur[k].clarify
                        if (cc != null && cc.resolved.isEmpty()) {
                            cur[k] = cur[k].copy(clarify = cc.copy(resolved = choice))
                            setMsgs(r, cur)
                        }
                    }
                    r.retryNote.value = "已选择「" + choice + "」，已送达服务端"
                } else {
                    r.retryNote.value = "回执没送到：本轮可能已收尾，可重试"
                }
            }
        }
    }

    /** 审批请求：挂到该会话当前助手气泡上，等用户点按钮回执。 */
    private fun attachApproval(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        AppLog.log("approval", "收到审批请求 sid=" + r.id.take(8) + " id=" + ev.data.optString("request_id", "").take(12))
        notifyNeedAction(r.id, "需要你确认",
            ev.data.optString("description", "").ifEmpty { ev.data.optString("command", "") })
        placeApproval(r, ev.data)
    }

    /** 把审批卡片挂到该会话。位置与去重规则同 [placeClarify]。 */
    private fun placeApproval(r: SessionRuntime, data: org.json.JSONObject) {
        val rid = data.optString("request_id", "")
        val cmd = data.optString("command", "")
        val desc = data.optString("description", "")
        val chs = mutableListOf<String>()
        data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        if (rid.isEmpty() || chs.isEmpty()) return
        val cur = r.messages.value
        if (cur.any { it.approval?.requestId == rid && it.approval?.resolved?.isEmpty() == true }) return
        val card = ApprovalCard(rid, cmd, desc, chs)
        val list = cur.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(approval = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), approval = card))
        setMsgs(r, list)
    }

    /**
     * 重开 App / 断流恢复：服务端 run 状态里若仍挂着待办卡片（waiting_for_clarify /
     * waiting_for_approval），把卡片重新挂回会话。
     *
     * 这就是「重启后等你点头的卡片消失」的修法：老代码探测 run 只读了 status
     * 字符串，把 run 状态里一并下发的 clarify/approval 载荷丢掉，卡片自然挂不上。
     */
    private fun restorePendingCard(r: SessionRuntime, payload: org.json.JSONObject?) {
        val p = payload ?: return
        when (p.optString("status", "")) {
            "waiting_for_clarify" -> placeClarify(r, p.optJSONObject("clarify") ?: return)
            "waiting_for_approval" -> placeApproval(r, p.optJSONObject("approval") ?: return)
        }
    }

    /**
     * run 已结束或不存在：清掉该会话里未作废的待办卡片（多为重启后从本地落盘读回的
     * 过期卡片）。已作废（用户点过）的保留，让「已选择：…」留痕。
     */
    private fun clearStaleCards(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        var changed = false
        for (k in list.indices) {
            val m = list[k]
            if (m.approval?.resolved?.isEmpty() == true) {
                list[k] = m.copy(approval = null); changed = true
            } else if (m.clarify?.resolved?.isEmpty() == true) {
                list[k] = m.copy(clarify = null); changed = true
            }
        }
        if (changed) setMsgs(r, list)
    }

    /** 用户点了审批按钮：回执给服务端，并把卡片置为已选。 */
    fun respondApproval(msgId: Long, choice: String) {
        val a = api ?: return
        val r = rt(_currentId.value)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].approval ?: return
        if (card.resolved.isNotEmpty()) return
        // 同 respondClarify：发成功才标「已选择」，失败保持可点，让提示里的重试有入口。
        if (!receiptInFlight.add(msgId)) return
        r.retryNote.value = "正在送达回执…"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = a.respondApproval(rid, card.requestId, choice)
            withContext(Dispatchers.Main) {
                receiptInFlight.remove(msgId)
                if (ok) {
                    val cur = r.messages.value.toMutableList()
                    val k = cur.indexOfFirst { it.id == msgId }
                    if (k >= 0) {
                        val cc = cur[k].approval
                        if (cc != null && cc.resolved.isEmpty()) {
                            cur[k] = cur[k].copy(approval = cc.copy(resolved = choice))
                            setMsgs(r, cur)
                        }
                    }
                    r.retryNote.value = "已选择「" + choice + "」，已送达服务端"
                } else {
                    r.retryNote.value = "回执没送到：本轮可能已收尾，可重试"
                }
            }
        }
    }

    /** 轮末 token 用量：挂到该会话最后一条助手消息上。 */
    private fun attachUsage(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        val u = ev.data.optJSONObject("usage") ?: return
        // 耗时起点与界面实时计时同源：优先用会话级的本轮起点 r.startedAt
        // （整轮唯一、不会被气泡重建冲掉），它没有才回落气泡自己的。
        val bubbleStart = r.messages.value.lastOrNull { it.role == "assistant" }?.startedAt ?: 0L
        // 优先用会话级的本轮起点（整轮唯一、不会被气泡重建冲掉）；它没有才回落气泡的。
        val baseStart = if (r.startedAt > 0) r.startedAt else bubbleStart
        val usage = Usage(
            input = u.optInt("input_tokens", 0),
            output = u.optInt("output_tokens", 0),
            total = u.optInt("total_tokens", 0),
            cacheRead = u.optInt("cache_read_tokens", 0),
            cacheWrite = u.optInt("cache_write_tokens", 0),
            // 耗时以服务端为准：服务端随 usage 下发的 duration_seconds 是本轮真实执行耗时，
            // 不含 App↔服务端网络往返、排队与断线重连等待，数字稳定。它没有才回落本地掐表。
            durationMs = if (u.has("duration_seconds"))
                (u.optDouble("duration_seconds", 0.0) * 1000.0).toLong()
            else if (baseStart > 0) System.currentTimeMillis() - baseStart else 0L,
        )
        // 只有耗时（token 全 0）的轮次也要挂上：耗时本身就是用户要看的统计。
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0 && usage.durationMs <= 0) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(usage = usage)
        setMsgs(r, list)
    }

    /** 正在发送回执的卡片消息 id：防连点重复 POST，发完（无论成败）都放回。 */
    private val receiptInFlight = ConcurrentHashMap.newKeySet<Long>()

    /** 子任务开始/结束：按 subagent_id 或 goal 归并成一行进度。 */
    private fun upsertSubagent(r: SessionRuntime, ev: com.hermesapp.net.SseEvent, running: Boolean) {
        val id = ev.data.optString("subagent_id", "").ifEmpty { ev.data.optString("delegation_id", "") }
        val goal = ev.data.optString("goal", "")
        val summary = ev.data.optString("summary", "")
        val statusRaw = ev.data.optString("status", "")
        val child = ev.data.optString("child_session_id", "")
        val toks = ev.data.optInt("input_tokens", 0) + ev.data.optInt("output_tokens", 0)
        val key = id.ifEmpty { goal }
        if (key.isEmpty()) return
        val status = when {
            running -> "running"
            statusRaw.isNotEmpty() -> statusRaw
            else -> "completed"
        }
        val list = r.messages.value.toMutableList()
        // 优先挂在本轮那条「进行中」的助手气泡上；本轮已收尾（后台子代理跑得比父 run 久，
        // 完成事件晚到）就退而挂在最后一条助手气泡上——旧逻辑找不到 pending 直接 return，
        // 这类事件会整条被丢掉，进度永远停在「▶ 运行中」。
        val pendingIdx = list.indexOfLast { it.role == "assistant" && it.pending }
        val mi = if (pendingIdx >= 0) pendingIdx else list.indexOfLast { it.role == "assistant" }
        if (mi < 0) return
        val old = list[mi].subagents
        val idx = old.indexOfFirst { it.id == key }
        val prev = old.getOrNull(idx)
        val now = System.currentTimeMillis()
        val line = SubagentLine(
            id = key,
            goal = goal.ifEmpty { prev?.goal ?: "" },
            status = status,
            summary = summary.ifEmpty { prev?.summary ?: "" },
            childSessionId = child.ifEmpty { prev?.childSessionId ?: "" },
            steps = prev?.steps ?: 0,
            startedAt = prev?.startedAt?.takeIf { it > 0 } ?: if (running) now else 0L,
            seenAt = prev?.seenAt ?: 0L,
            endedAt = if (running) 0L else now,
            tokens = if (toks > 0) toks else (prev?.tokens ?: 0),
        )
        val next = if (idx >= 0) old.toMutableList().also { it[idx] = line } else old + line
        list[mi] = list[mi].copy(subagents = next)
        setMsgs(r, list)
        // 开跑就把进度轮询挂上：运行中的进度只能靠读子代理自己的会话拿到。
        if (running && line.childSessionId.isNotEmpty()) ensureSubagentSweep(r.id)
    }

    /** 子任务进度面板：null = 没打开。 */
    private val _subDetail = MutableStateFlow<SubagentDetail?>(null)
    val subDetail = _subDetail.asStateFlow()
    private var subDetailJob: Job? = null

    /**
     * 子代理实时进度轮询（每个会话一条，全部收工自动退出）。
     *
     * 为什么必须轮询：网关那条 run 事件流只转发 subagent.start / subagent.complete，
     * 中间的 subagent.tool（子代理每调一次工具）与 subagent.progress（每满 5 次一批）
     * 被当「界面噪音」丢掉了，所以推送里根本没有运行中的进度。
     * 但子代理自己的会话是实时的（source=subagent），读它的 tool_call_count / ended_at
     * 就够出进度。全程只读，不改服务端。
     */
    private fun ensureSubagentSweep(sid: String) {
        if (sid.isEmpty()) return
        val r = rt(sid)
        if (r.subSweep?.isActive == true) return
        // 必须显式切到 IO：RuntimeHub.scope 是 Dispatchers.Main.immediate，
        // 而 sessionDetail() 是阻塞式 HTTP。挂主线程的话，打开有子任务的会话时
        // 第一次拉进度就把 UI 卡住（这正是「有子任务的会话打开卡顿」的根因）。
        r.subSweep = RuntimeHub.scope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            val deadline = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                val kids = runningChildren(r)
                if (kids.isEmpty()) break
                var changed = false
                for (k in kids) {
                    val s = runCatching { a.sessionDetail(k.childSessionId).optJSONObject("session") }
                        .getOrNull() ?: continue
                    if (applyChildProgress(r, k.id, s)) changed = true
                }
                delay(if (changed) 4_000L else 8_000L)
            }
            r.subSweep = null
        }
    }

    /** 本会话还在跑、且拿到了子会话 id 的子任务。 */
    private fun runningChildren(r: SessionRuntime): List<SubagentLine> =
        r.messages.value.flatMap { it.subagents }
            .filter { it.status == "running" && it.childSessionId.isNotEmpty() && it.endedAt == 0L }

    /** 把子代理会话的进度写回对应那一行。返回是否有变化。 */
    private fun applyChildProgress(r: SessionRuntime, key: String, s: JSONObject): Boolean {
        val list = r.messages.value.toMutableList()
        for (i in list.indices.reversed()) {
            val m = list[i]
            val idx = m.subagents.indexOfFirst { it.id == key }
            if (idx < 0) continue
            val old = m.subagents[idx]
            val endSec = s.optDouble("ended_at", 0.0)
            val endMs = if (endSec > 0) (endSec * 1000.0).toLong() else 0L
            // 子代理会话已结束、但那条 subagent.complete 没等到（后台子代理跑得比父 run 久时
            // 很常见，父 run 的事件流早断了）：按 end_reason 据实标注，不一律当成功。
            val st = if (endMs > 0 && old.status == "running") {
                val why = s.optString("end_reason", "")
                if (why.isEmpty() || why.contains("close") || why.contains("complete") ||
                    why.contains("normal")) "completed" else "ended"
            } else old.status
            val toks = s.optInt("input_tokens", 0) + s.optInt("output_tokens", 0)
            val next = old.copy(
                steps = s.optInt("tool_call_count", old.steps),
                seenAt = System.currentTimeMillis(),
                endedAt = endMs,
                status = st,
                tokens = if (toks > 0) toks else old.tokens,
            )
            if (next == old) return false
            val ns = m.subagents.toMutableList()
            ns[idx] = next
            list[i] = m.copy(subagents = ns)
            setMsgs(r, list)
            // 返回「真有进度」与否：只影响下一轮隔多久再拉（4 秒 / 8 秒），
            // seenAt 每轮都写，界面上的「刚刚取的进度」才不会看着像卡死。
            return next.steps != old.steps || next.status != old.status
        }
        return false
    }

    /**
     * 打开「子任务进度」面板：拉这个子代理会话的末尾消息，拆成步骤流水。
     * 面板开着时每 3 秒刷新一次，子任务收工就停（不再空转）。
     */
    /**
     * 停止一个正在跑的子任务。
     *
     * 为什么不复用 stop()：子任务是后台子代理，可能比父轮次活得久，父 run 的
     * /v1/runs/{id}/stop 管不到它。服务端另开了 POST /api/subagents/{id}/stop，
     * 对子代理对象直接发协作式中断（与 TUI 的 subagent.interrupt 同一条链路）。
     * 「停止」语义是到下一个迭代边界就停，不是立即杀进程；found=false 表示它已经不在跑了。
     */
    fun stopSubagent(sid: String, key: String) {
        if (sid.isEmpty() || key.isEmpty()) return
        val a = api ?: return
        AppLog.log("stop", "停止子任务 sid=" + sid.take(8) + " key=" + key)
        RuntimeHub.scope.launch(Dispatchers.IO) {
            val resp = runCatching { a.stopSubagent(key) }.getOrNull()
            val found = resp?.optBoolean("found", false) ?: false
            rt(sid).retryNote.value =
                if (found) "已请求停止该子任务（到下一个步骤边界停下）" else "那个子任务已经不在跑了"
        }
    }

    fun openSubagentDetail(sid: String, key: String) {
        val line = rt(sid).messages.value.flatMap { it.subagents }.firstOrNull { it.id == key } ?: return
        _subDetail.value = SubagentDetail(
            key = key, goal = line.goal, childSessionId = line.childSessionId,
            status = line.status, startedAt = line.startedAt, endedAt = line.endedAt, tokens = line.tokens,
        )
        subDetailJob?.cancel()
        // 同上：面板的 3 秒刷新也是阻塞 HTTP，不能在主线程上跑。
        subDetailJob = RuntimeHub.scope.launch(Dispatchers.IO) {
            val a = api ?: return@launch
            val deadline = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                val cur = _subDetail.value ?: break
                if (cur.key != key) break
                if (cur.childSessionId.isEmpty()) {
                    _subDetail.value = cur.copy(note = "这次派发没带回子会话 id，看不到它的内部步骤")
                    break
                }
                val resp = runCatching { a.sessionMessagesTail(cur.childSessionId, 40) }.getOrNull()
                val nowLine = rt(sid).messages.value.flatMap { it.subagents }.firstOrNull { it.id == key }
                _subDetail.value = cur.copy(
                    steps = if (resp != null) parseSubagentSteps(resp.optJSONArray("data")) else cur.steps,
                    status = nowLine?.status ?: cur.status,
                    endedAt = nowLine?.endedAt ?: cur.endedAt,
                    tokens = nowLine?.tokens ?: cur.tokens,
                    startedAt = nowLine?.startedAt?.takeIf { it > 0 } ?: cur.startedAt,
                    updatedAt = System.currentTimeMillis(),
                    note = if (resp == null) "这一下没读到，正在重试…" else "",
                )
                if ((nowLine?.status ?: "running") != "running") break
                delay(3_000L)
            }
        }
    }

    fun closeSubagentDetail() {
        subDetailJob?.cancel()
        subDetailJob = null
        _subDetail.value = null
    }

    /**
     * 把子代理会话末尾的消息拆成步骤流水：一次工具调用 = 一步。
     * 参数从它前面那条 assistant 消息的 tool_calls 取（结果行里没有参数），
     * 对不上就退化成只有工具名——不编造。
     */
    private fun parseSubagentSteps(arr: JSONArray?): List<SubagentStep> {
        if (arr == null) return emptyList()
        val args = HashMap<String, Pair<String, String>>()
        val out = mutableListOf<SubagentStep>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("role", "")) {
                "assistant" -> {
                    val tcs = o.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until tcs.length()) {
                        val tc = tcs.optJSONObject(j) ?: continue
                        val fn = tc.optJSONObject("function") ?: continue
                        val nm = fn.optString("name", "")
                        val tcid = tc.optString("id", "").ifEmpty { tc.optString("call_id", "") }
                        if (tcid.isNotEmpty()) args[tcid] = nm to toolArgBrief(fn.optString("arguments", ""))
                    }
                }
                "tool" -> {
                    val pair = args[o.optString("tool_call_id", "")]
                    val nm = pair?.first?.takeIf { it.isNotEmpty() } ?: o.optString("tool_name", "")
                    out.add(
                        SubagentStep(
                            out.size + 1, nm, pair?.second ?: "",
                            toolResultBrief(o.optString("content", "")),
                        )
                    )
                }
            }
        }
        return out
    }

    /** 工具参数一句话：从参数 JSON 里挑最能说明意图的字段（路径/命令/搜索词）。 */
    private fun toolArgBrief(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val obj = runCatching { JSONObject(s) }.getOrNull() ?: return oneLine(s, 90)
        for (k in listOf("path", "command", "pattern", "query", "url", "file", "name", "goal", "prompt", "text")) {
            val v = obj.optString(k, "")
            if (v.isNotEmpty()) return oneLine(v, 110)
        }
        return oneLine(s, 90)
    }

    /** 工具结果一句话：是 JSON 就挑 output/content/success 之类，纯文本直接截断。 */
    private fun toolResultBrief(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        runCatching { JSONObject(s) }.getOrNull()?.let { o ->
            for (k in listOf("output", "content", "error", "message", "text")) {
                val v = o.optString(k, "")
                if (v.isNotEmpty()) return oneLine(v, 110)
            }
            if (o.has("total_count")) return "命中 " + o.optInt("total_count") + " 处"
            if (o.has("success")) return if (o.optBoolean("success", false)) "成功" else "失败"
            if (o.has("exit_code")) return "exit " + o.optInt("exit_code")
        }
        return oneLine(s, 110)
    }

    private fun oneLine(s: String, n: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > n) t.take(n) + "…" else t
    }

    private fun toolLine(ev: com.hermesapp.net.SseEvent, failed: Boolean): String {
        val name = ev.data.optString("tool", "")
        if (name.isEmpty() || name.startsWith("_")) return ""
        val sb = StringBuilder("\n· ")
        sb.append(if (failed) "✗ " else "✓ ").append(name)
        val dur = ev.data.optDouble("duration", -1.0)
        if (dur >= 0) sb.append("  ").append(String.format("%.1f", dur)).append("s")
        val pv = ev.data.optString("preview", "").replace(Regex("\\s+"), " ").trim()
        if (pv.isNotEmpty()) {
            sb.append("\n   ").append(if (pv.length > 120) pv.take(120) + "…" else pv)
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun streamRun(a: HermesApi, sid: String) {
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        // 起新流前先掐掉上一条并作废它的回调：r.call 被覆盖而旧流还活着时，两条流会同时收到
        // run.completed，正文、通知与语音都执行两遍（实测 2.81 回前台重复播报）。
        val gen = r.streamGen + 1
        r.streamGen = gen
        r.call?.let { old -> if (!old.isCanceled()) { AppLog.log("stream", "重起流先掐旧流 run=" + rid.take(12)); old.cancel() } }
        r.lastEventAt = System.currentTimeMillis()   // 重新起流即重置活跃时间，避免刚连上就被判假死
        r.evCount = 0                                 // 本轮事件计数：流关闭时汇总，一眼看出事件到底到没到
        r.toolCount = 0
        AppLog.log("stream", "起流 run=" + rid.take(12) + " lastSeq=" + r.lastSeq + " 第" + r.autoContinue + "次续接 gen=" + gen + "（本轮事件计数清零）")
        // 每轮流一个攒帧器：碎字按帧放送。重起流时丢弃上一轮的残余。
        r.coalescer?.discard()
        r.coalescer = StreamDeltaCoalescer(RuntimeHub.scope, onFlush = { s -> appendDelta(r, s) })
        r.call = a.streamEvents(
            runId = rid,
            lastSeq = r.lastSeq,
            onEvent = { ev ->
                if (gen != r.streamGen) return@streamEvents   // 已被新流取代，作废
                if (ev.id != null) r.lastSeq = ev.id
                r.lastEventAt = System.currentTimeMillis()
                val name = ev.event ?: ev.data.optString("event", "")
                r.evCount++
                if (name.startsWith("tool.")) r.toolCount++
                // 收到任何真实事件即视为连接已恢复正常：清掉重试提示与计数。
                if (name != "replay.truncated" && r.autoContinue != 0) {
                    r.autoContinue = 0
                    r.retryNote.value = ""
                }
                // 流已恢复：翻历史的盯梢任务作废。
                if (name != "replay.truncated") r.recoveryJob?.cancel()
                when (name) {
                    // 服务端明确告知：断线期间的事件已超出保留窗口、拿不回来了。
                    // 不能静默——在气泡里标一行，并拉一次服务端消息兜底。
                    "replay.truncated" -> {
                        appendDelta(r, TRUNCATED_NOTICE)
                        val s = sid
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(600)
                            if (s == _currentId.value) refreshFromServer()
                        }
                    }
                    // 入攒帧缓冲，由 StreamDeltaCoalescer 按帧放送（避免一大块一大块地跳）。
                    "message.delta" -> r.coalescer?.append(ev.data.optString("delta", ""))
                    "message.interim" -> {}
                    "tool.started" -> {}
                    "tool.completed" -> {
                        r.coalescer?.flushNow()
                        val line = toolLine(ev, ev.data.optBoolean("error", false))
                        if (line.isNotEmpty()) appendTrace(r, line)
                        AppLog.log("stream", "过程行已加 工具=" + ev.data.optString("tool", "") +
                            " 本轮事件=" + r.evCount + " 其中工具=" + r.toolCount)
                    }
                    "tool.failed" -> {
                        r.coalescer?.flushNow()
                        val line = toolLine(ev, true)
                        if (line.isNotEmpty()) appendTrace(r, line)
                    }
                    // 流式语音（方案丙）：服务端边合成边推，这里边收边播。
                    // run.completed 已先到（finished 已置位），音频晚到不会触发误重连。
                    "audio.start" -> {
                        AppLog.log("stream", "语音流开始 run=" + rid.take(12))
                        if (prefs.playCompletionVoice) {
                            StreamVoicePlayer.rate = prefs.voiceRate
                            // 带上归属会话：多任务排队时提示条要显示「正在播放：X 的语音」。
                            val vTitle = _sessions.value.firstOrNull { it.id == sid }?.title ?: "对话"
                            StreamVoicePlayer.begin(getApplication(), "stream:" + rid, rid, sid, vTitle)
                        }
                    }
                    "audio.delta" -> {
                        if (prefs.playCompletionVoice) {
                            StreamVoicePlayer.append(getApplication(), "stream:" + rid, ev.data.optString("data", ""))
                        }
                    }
                    "audio.end" -> {
                        AppLog.log("stream", "语音流结束 run=" + rid.take(12) +
                            " 块数=" + ev.data.optInt("chunks", 0))
                        if (prefs.playCompletionVoice) {
                            StreamVoicePlayer.end(getApplication(), "stream:" + rid)
                        }
                    }
                    "approval.request" -> attachApproval(r, ev)
                    "clarify.request" -> attachClarify(r, ev)
                    "subagent.start" -> upsertSubagent(r, ev, running = true)
                    "subagent.complete" -> upsertSubagent(r, ev, running = false)
                    "run.completed" -> {
                        r.coalescer?.flushNow()
                        AppLog.log("stream", "run 完成 run=" + rid.take(12) + " 正文长度=" + ev.data.optString("output", "").length)
                        r.finished = true
                        tagLastAssistantRunId(r, rid)
                        val out = ev.data.optString("output", "")
                        if (out.isNotEmpty()) setPendingText(r, out) else finishPending(r)
                        attachUsage(r, ev)
                        doneOk(sid)
                        // 插话没赶上本轮：服务端把未送达的插话文本随终态放在 pending_steer 里下发。
                        // 不静默丢——放回输入框（若为空）并提示，用户点发送即可重发。
                        // 放在 doneOk 之后，避免被它的 retryNote 清空覆盖。
                        val ps = ev.data.optString("pending_steer", "").trim()
                        if (ps.isNotEmpty()) {
                            if (prefs.draftFor(sid).isEmpty()) prefs.setDraft(sid, ps)
                            r.retryNote.value = "上一句插话没赶上本轮，已放回输入框，点发送重发"
                        }
                        notifyCompletion(sid, out)
                        // 完成语音：服务端把整段回复合成音频随 output 下发，这里自动播一遍。
                        if (out.isNotEmpty()) {
                            VoicePlayer.playFromReply(getApplication(), out, prefs.playCompletionVoice)
                        }
                    }
                    "run.failed" -> {
                        r.coalescer?.flushNow()
                        AppLog.log("stream", "run 失败 run=" + rid.take(12) + " 错误=" + ev.data.optString("error", "未知").take(120))
                        r.finished = true
                        appendDelta(r, "\n[失败] " + ev.data.optString("error", "未知错误"))
                        finishPending(r)
                        maybeContinue(sid)
                    }
                    "run.cancelled", "run.interrupted" -> {
                        r.coalescer?.flushNow()
                        r.finished = true
                        appendDelta(r, "\n[已中断]")
                        doneOk(sid)
                    }
                }
            },
            onClosed = {
                if (gen != r.streamGen) return@streamEvents   // 已被新流取代，作废
                r.coalescer?.flushNow()
                AppLog.log("stream", "流关闭 run=" + rid.take(12) + " busy=" + r.busy.value + " finished=" + r.finished + " lastSeq=" + r.lastSeq +
                    " 本轮共收事件=" + r.evCount + " 其中工具=" + r.toolCount)
                if (r.busy.value && !r.finished) maybeContinue(sid)
            },
            onError = { e ->
                if (gen != r.streamGen) return@streamEvents   // 已被新流取代，作废
                AppLog.err("stream", "流出错 run=" + rid.take(12) + " busy=" + r.busy.value + " finished=" + r.finished +
                    " 本轮共收事件=" + r.evCount + " 其中工具=" + r.toolCount, e)
                // 不再往正文塞「[连接断开]」——断流期间的提示统一走 retryNote（气泡上方一行），
                // 正文只保留任务真实产出，避免一次抖动就在会话里留一条错行。
                r.coalescer?.flushNow()
                if (r.busy.value && !r.finished) {
                    // 2026-10-08：404 不是网络抖动，是服务端已经没有这条 run 的 SSE 缓冲了。
                    // 再按退避重起流只会每次都秒 404（实测一条 run 连撞 24 次，用户得手动点停止），
                    // 所以这里不走 maybeContinue 的「探测→重起流」，直接转翻历史等答案落盘。
                    if (e is com.hermesapp.net.RunStreamGoneException) {
                        AppLog.log("retry", "流 404（服务端缓冲已回收），转翻历史 run=" + rid.take(12))
                        r.retryNote.value = "事件流已断开，正在从服务端取回结果…"
                        r.autoContinue = MAX_RECONNECT_ATTEMPTS   // 跳过退避重起流
                        startHistoryRecovery(sid, rid)
                    } else {
                        if (r.retryNote.value.isEmpty()) r.retryNote.value = "连接中断：" + (e.message ?: "未知")
                        maybeContinue(sid)
                    }
                }
            },
            // 心跳等任何一行都刷新活跃时间：长工具执行期间只有心跳、没有真实事件，
            // 不刷就会让「回到前台」的 25 秒看门狗把健康流误判成假死（用户报的「一直在重连」）。
            onActivity = { if (gen == r.streamGen) r.lastEventAt = System.currentTimeMillis() }
        )
    }

    /**
     * SSE 流提前断开（没收到 run.completed）时的续接：
     * 绝不伪造用户消息——只查同一 run 的状态，仍在跑就续接它的事件流；
     * 已结束就从服务端拉回结果。仅作用于该 run 所属的会话。
     *
     * 重试用退避而非固定间隔：网络抖动（地铁、切基站、网络切换）往往几秒内恢复，
     * 原来的「固定 1.2 秒 × 3 次」会在 4 秒内烧完次数然后彻底放弃；现在
     * 1→2→4→8→16→30 秒封顶、最多 8 次，覆盖约 1 分钟的窗口，且首次仍只等 1 秒。
     */
    private fun maybeContinue(sid: String) {
        val r = rt(sid)
        if (r.finished) return
        val a = api ?: return
        val rid = r.runId
        if (rid.isEmpty()) { failPending(sid); return }
        if (r.autoContinue >= MAX_RECONNECT_ATTEMPTS) {
            // 重连退避用尽：不判死，改去翻服务端会话记录等答案落盘。
            startHistoryRecovery(sid, rid)
            return
        }
        // 同一次断流可能从多个口子进来（onClosed 与 onError 都会调、切回前台的
        // 体检也会间接触发），各起一条退避链就会重复探测、重复加计数，
        // 严重时一边判「还在跑」一边判「已结束」，接流与收尾互相打架。
        // 这里做单飞：同一会话同时只允许一条退避链在等。
        if (!r.probing.compareAndSet(false, true)) return
        val attempt = r.autoContinue + 1
        r.autoContinue = attempt
        val waitMs = backoffDelayMs(attempt)
        r.retryNote.value = "连接中断，${waitMs / 1000} 秒后重试（$attempt/$MAX_RECONNECT_ATTEMPTS）"
        AppLog.log("retry", "第 $attempt/$MAX_RECONNECT_ATTEMPTS 次重试，${waitMs / 1000}s 后探 run=" + rid.take(12))
        RuntimeHub.scope.launch(Dispatchers.IO) {
            delay(waitMs)
            // 退出前必须留一行：这条退避链可能已被别的路径（切回前台的体检、
            // 翻历史取回、手动停止）抢先收尾。静默 return 会让日志里只剩
            // 「第 N 次重试」却没有「探测结果」，排查时被误读成重试卡死。
            if (!r.busy.value || r.finished) {
                r.probing.set(false)
                AppLog.log("retry", "退避作废 run=" + rid.take(12) +
                    "（本轮已由其它路径收尾 busy=" + r.busy.value + " finished=" + r.finished + "）")
                return@launch
            }
            val st = a.probeRun(rid)
            r.probing.set(false)   // 探测出结果即释放单飞位，后续该重试还能再进
            AppLog.log("retry", "探测结果 " + when (st) {
                is HermesApi.RunStatus.Known -> "Known(" + st.status + ")"
                HermesApi.RunStatus.Missing -> "Missing(404)"
                HermesApi.RunStatus.Unknown -> "Unknown(探不出来)"
            })
            when (st) {
                // 服务端明确回答「还在跑」：续接同一 run，不新增任何用户消息。
                is HermesApi.RunStatus.Known -> {
                    if (st.status in RUNNING_STATES) {
                        r.retryNote.value = ""
                        // 断流期间服务端可能在等审批/澄清：续接前先把卡片挂回来。
                        restorePendingCard(r, st.payload)
                        streamRun(a, sid)
                    } else {
                        // 服务端明确回答「已结束」：收尾并拉回产出。
                        r.retryNote.value = ""
                        r.finished = true
                        clearStaleCards(r)
                        finishPending(r)
                        notifyRecoveredCompletion(sid, st.payload?.optString("output", "").orEmpty())
                        r.busy.value = false
                        prefs.removeActiveRun(sid)
                        updateRunService()
                        if (sid == _currentId.value) refreshFromServer()
                    }
                }
                // 服务端明确说没这个 run：判结束（极少见，run 记录被清）。
                HermesApi.RunStatus.Missing -> {
                    r.retryNote.value = ""
                    r.finished = true
                    clearStaleCards(r)
                    finishPending(r)
                    notifyRecoveredCompletion(sid, "")
                    r.busy.value = false
                    prefs.removeActiveRun(sid)
                    updateRunService()
                    if (sid == _currentId.value) refreshFromServer()
                }
                // 探不出来（网还没通）：不知道 ≠ 已结束，继续退避重试。
                // 这是原实现最大的坑——把「不知道」当成了「已结束」，第一次探测
                // 失败就把任务判死，8 次退避等于摆设。
                HermesApi.RunStatus.Unknown -> {
                    if (r.autoContinue >= MAX_RECONNECT_ATTEMPTS) {
                        r.retryNote.value = ""
                        startHistoryRecovery(sid, rid)
                    } else {
                        maybeContinue(sid)
                    }
                }
            }
        }
    }

    /** 退避等待：第 n 次重试 2^(n-1) 秒（首次 1 秒），封顶 30 秒。 */
    private fun backoffDelayMs(attempt: Int): Long {
        val base = 1000L shl (attempt - 1).coerceIn(0, 5)   // 1,2,4,8,16,32…
        return base.coerceAtMost(30_000L)
    }

    /**
     * 断流翻历史兜底。
     *
     * 重连退避用尽后不把回合判死：手机 SSE 常被系统掐死，而服务端其实还在跑，
     * 跑完会把答案写进会话记录。这里改为轮询服务端历史，等答案落盘后再合并回来。
     *
     * 认哪一条是本次的答案，靠「位置」不靠「文字」：发送前记下的用户消息条数 N，
     * 历史里第 N+1 条用户消息就是本次发送（再用正文做二次校验，防历史被编辑/分叉）。
     * 它之后最后一条非空助手消息即答案，且必须连续两次读到一致才算定下来
     * （签名里带记录总条数，服务端还在追加工具记录时签名会变，就继续等）。
     * 认不出锚点就放弃——宁可报错，也不认错答案。
     */
    private fun startHistoryRecovery(sid: String, rid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.finished) return
        if (r.recoveryJob?.isActive == true) return
        val pending = r.pendingSendText
        val prior = r.priorUserCount
        if (pending.isEmpty()) {
            // 重开 App 恢复出来的任务没有本地发送上下文，拿不到位置锚点，不能据此判死：
            // 保留活跃标记与气泡，等下次进前台（onAppForeground 会把退避计数归零）再续接。
            if (r.resumed) {
                r.retryNote.value = "连接不稳，仍在尝试接回本轮…"
                r.autoContinue = 0
                return
            }
            r.retryNote.value = "连接中断，已重试 $MAX_RECONNECT_ATTEMPTS 次仍未完成"
            failPending(sid)
            return
        }
        r.retryNote.value = "连接中断，正在从服务端取回结果…"
        r.recoveryJob = RuntimeHub.scope.launch(Dispatchers.IO) {
            var delayMs = 5_000L
            var elapsed = 0L
            var lastSig: String? = null
            var unanchored = 0
            var failStreak = 0
            while (elapsed < HISTORY_RECOVERY_WINDOW_MS) {
                delay(delayMs)
                elapsed += delayMs
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
                if (!r.busy.value || r.finished) return@launch
                val rows = try {
                    historyRows(a, sid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failStreak++
                    if (failStreak >= 3) {
                        giveUpRecovery(sid, "服务端会话记录读取失败，可稍后再看")
                        return@launch
                    }
                    continue
                }
                failStreak = 0
                if (rows.isEmpty()) continue
                val anchor = resolveAnchor(rows, pending, prior)
                if (anchor < 0) {
                    lastSig = null
                    if (++unanchored >= 2) {
                        giveUpRecovery(sid, "本次发送未落到服务端，可重发")
                        return@launch
                    }
                    continue
                }
                unanchored = 0
                val sig = answerSignature(rows, anchor)
                if (sig != null && sig == lastSig) {
                    adoptRecovered(sid)
                    return@launch
                }
                lastSig = sig
            }
            giveUpRecovery(sid, "等 30 分钟仍未取回结果，可稍后再看")
        }
    }

    /** 服务端会话记录解析成位置锚点所需的最小行。 */
    private fun historyRows(a: HermesApi, sid: String): List<HistRow> {
        val resp = a.sessionMessages(sid)
        val arr = resp.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<HistRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "")
            if (role != "user" && role != "assistant") continue
            out.add(HistRow(role, o.optString("content", ""), o.optString("id", i.toString())))
        }
        return out
    }

    /** 位置锚点：第 prior+1 条用户消息的下标；对不上返回 -1（不硬认）。 */
    private fun resolveAnchor(rows: List<HistRow>, pendingText: String, prior: Int): Int {
        val userIdx = rows.indices.filter { rows[it].role == "user" }
        if (userIdx.size <= prior) return -1
        val pos = userIdx[prior]
        if (rows[pos].text.trim() != pendingText) return -1
        return pos
    }

    /** 稳定性签名：锚点之后最后一条非空助手消息；带总条数，服务端还在追加时会变。 */
    private fun answerSignature(rows: List<HistRow>, anchor: Int): String? {
        val ans = rows.drop(anchor + 1)
            .lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return null
        return "${rows.size}|${ans.id}|${ans.text.length}"
    }

    /** 答案已落盘：结束本轮并按服务端记录合并回来。 */
    private fun adoptRecovered(sid: String) {
        val r = rt(sid)
        if (r.confirmingMsgId > 0L) {
            advanceReceipt(sid, r.confirmingMsgId, Receipt.ACCEPTED, note = "")
            r.confirmingMsgId = 0L
        }
        r.finished = true
        r.retryNote.value = ""
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        refreshFromServerFor(sid)
        drainQueue(sid)   // 翻历史取回结果后，队列接着走
    }

    /** 翻历史也没捞到：明确收尾，不留一个永远转圈的空气泡。 */
    private fun giveUpRecovery(sid: String, why: String) {
        val r = rt(sid)
        r.retryNote.value = why
        if (r.confirmingMsgId > 0L) {
            advanceReceipt(sid, r.confirmingMsgId, Receipt.FAILED, note = why)
            r.confirmingMsgId = 0L
        }
        failPending(sid)
    }

    /** 停止「当前会话」正在跑的任务。 */
    fun stop() = stopSession(_currentId.value)

    private fun stopSession(sid: String) {
        if (sid.isEmpty()) return
        val r = rt(sid)
        if (!r.busy.value && r.runId.isEmpty()) return
        val a = api
        val rid = r.runId
        AppLog.log("stop", "用户停止 sid=" + sid.take(8) + " run=" + rid.take(12))
        r.finished = true
        r.recoveryJob?.cancel()
        r.coalescer?.discard()
        if (a != null && rid.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { a.stopRun(rid) }
        r.call?.cancel()
        appendDelta(r, "\n[已请求停止]")
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        // 停止时把待发队列一起按住：不自动发下一条，等用户点「继续」。
        if (r.queue.isNotEmpty()) r.queuePaused.value = true else drainQueue(sid)
    }

    /** 工具轨迹追加到该会话当前 assistant 消息的 trace（界面默认折叠，不进正文）。 */
    private fun appendTrace(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(trace = list[i].trace + d)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), trace = d))
        setMsgs(r, list)
    }

    private fun appendDelta(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = list[i].text + d)
        else list.add(Msg("assistant", d, pending = true, ts = stamp(), startedAt = turnStart(r)))
        setMsgs(r, list)
    }

    /**
     * 给最后一条助手消息记上本轮 run_id：语音重播按钮靠它取音频。
     * 必须在 setPendingText/finishPending 之前调用——那两步用的是 copy()，
     * 先记上才能被带过去；记晚了这条就永远没有按钮。
     */
    private fun tagLastAssistantRunId(r: SessionRuntime, runId: String) {
        if (runId.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" }
        if (i < 0 || list[i].runId.isNotEmpty()) return
        list[i] = list[i].copy(runId = runId)
        setMsgs(r, list)
    }

    private fun setPendingText(r: SessionRuntime, t: String) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = t, pending = false)
        setMsgs(r, list)
    }

    private fun finishPending(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(pending = false)
        setMsgs(r, list)
    }

    private fun failPending(sid: String) {
        val r = rt(sid)
        r.recoveryJob?.cancel()
        clearStaleCards(r)
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        // 同上：本轮失败了也要把「回前台该同步」这件事补上，否则界面会一直停在旧内容。
        if (r.needSync) {
            r.needSync = false
            AppLog.log("sync", "收尾补拉（本轮失败）sid=" + sid.take(8))
            refreshFromServerFor(sid)
        }
        updateRunService()
        drainQueue(sid)   // 本轮失败也要把排队的下一条发出去，别把队列卡死
    }

    private fun doneOk(sid: String) {
        val r = rt(sid)
        r.autoContinue = 0
        r.recoveryJob?.cancel()
        r.retryNote.value = ""
        clearStaleCards(r)
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        // 回前台时若因本轮在跑而没能同步，收尾后补拉一次：后台期间别的端产生的轮次、
        // 或本地漏收的事件，靠它补齐。标记先清掉，避免反复拉。
        if (r.needSync) {
            r.needSync = false
            AppLog.log("sync", "收尾补拉 sid=" + sid.take(8))
            refreshFromServerFor(sid)
        }
        updateRunService()
        if (sid == _currentId.value) drainPendingReply()
        drainQueue(sid)   // 本轮结束：把排队的下一条发出去
        // 本轮跑完：服务端此刻多半已生成了正式标题，同步一次（节流 5 秒）。
        syncFromServer(5_000L)
    }

    /**
     * 「后台运行」开着就保持前台服务与常驻通知，跟有没有任务在跑无关；关掉才停。
     * 原来判据是「有任务才起、跑完就停」，所以没任务时切后台，状态栏一条通知都不剩。
     */
    private fun updateRunService() {
        val running = runtimes.filterValues { it.busy.value }.keys.toSet()
        // 顺带刷一次侧边栏提示：子任务/排队也算「有动静」，只按 busy 打点会漏。
        refreshRunFlags()
        // 治本：只要有任务在跑就举牌保活（抄 relay 的「活跃轮次登记」），
        // 不再只看「后台运行」开关：开关关着时切后台照样会被系统冻结，
        // 进度与完成通知都丢。
        if (!prefs.keepAlive && running.isEmpty()) {
            AppLog.log("service", "停前台服务（后台运行关 且 无任务）")
            RunService.stop(getApplication())
            return
        }
        // Android 12+ 禁止从后台启动前台服务：后台硬启会撞墙，反而触发
        // ForegroundServiceDidNotStartInTime 崩溃。切回前台时 onAppForeground
        // 会再调一次这里把服务补上。
        if (AppForeground.isForeground) {
            AppLog.log("service", "起前台服务 运行中会话=" + running.size + " keepAlive=" + prefs.keepAlive)
            RunService.start(getApplication(), running.isNotEmpty())
        } else {
            AppLog.log("service", "跳过起服务（App 不在前台）运行中会话=" + running.size)
        }
    }

    /** 进主界面时调一次：开关开着但还没发过消息，也要把常驻通知挂上。 */
    fun ensureRunService() {
        updateRunService()
    }

    /**
     * 侧边栏会话说状态提示的心跳：每 2 秒扫一遍内存里的运行态，有变化才推给界面。
     *
     * 为什么用「定时扫」而不是在每处状态变化打点：状态有三个来源（本地 busy、子任务行、
     * 待发队列），而子任务是个后台子代理——它可能比父轮次活得久，靠「任务开始/结束」打点
     * 会漏掉这段窗口。扫描纯读内存、不发请求，开销可忽略；值没变就不推，界面也不重组。
     */
    private fun startRunFlagsTicker() {
        if (runFlagsTicker?.isActive == true) return
        runFlagsTicker = RuntimeHub.scope.launch {
            var tick = 0
            while (isActive) {
                refreshRunFlags()
                // 每 ~30 秒顺带刷新一次服务端会话列表：把「别的端刚起的轮次」收进来（active_run），
                // 交给 syncFromServer 内部的 reconcileServerRuns 接管。不带这个，用户开着 App
                // 时别的端起的任务要等切前后台/切会话才被发现。
                tick++
                if (tick % 15 == 0) syncFromServer(30_000L)
                delay(2_000L)
            }
        }
    }

    /** 重算每条会话的运行态摘要（只留有动静的），并同步旧的 runningIds。 */
    private fun refreshRunFlags() {
        val out = LinkedHashMap<String, SessionRunFlag>()
        for ((sid, r) in runtimes) {
            val remote = _serverRuns.value.containsKey(sid) && !r.busy.value
            val flag = SessionRunFlag(
                busy = r.busy.value,
                // 正在跑的子任务数：子代理可能比父轮次活得久，这是「会话里还有活」的证据。
                subagents = r.messages.value.sumOf { m -> m.subagents.count { it.status == "running" } },
                queued = r.queue.size,
                remote = remote,
            )
            if (flag.active) out[sid] = flag
        }
        // 服务端在跑、本地还没建 runtime 的会话（别的端起的轮次）也要亮角标。
        for (m in _sessions.value) {
            if (_serverRuns.value.containsKey(m.id) && out[m.id] == null) {
                out[m.id] = SessionRunFlag(remote = true)
            }
        }
        if (out != _runFlags.value) _runFlags.value = out
        val busyIds = out.filterValues { it.busy }.keys
        if (busyIds != _runningIds.value) _runningIds.value = busyIds
    }

    /**
     * 任务停下来等人点头（审批/澄清）时弹提醒。
     *
     * 2026-10-08 改：原来「App 在前台就一律不弹」，导致用户开着 App 但在看别的页面 /
     * 别的会话时，审批来了完全没提示 —— 静默错过（用户报「经常静默错过」）。
     * 新判据：只有「就在当前这个会话里」才不弹（卡片已在屏幕上），其余情况都弹，前台也弹。
     */
    private fun notifyNeedAction(sid: String, title: String, text: String) {
        val onThisSession = AppForeground.isForeground && sid == _currentId.value
        if (onThisSession) return
        // 落盘一份：App 若在用户点通知前被系统杀掉，冷启动的 Intent extra 可能丢，
        // 靠这份落盘仍能跳回那条待处理卡片。
        prefs.pendingOpenSession = sid
        val app = getApplication<Application>()
        val body = text.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "有任务在等你处理" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyAction(app, title, body, sid)
    }

    /**
     * 探测兜底收尾时也要通知+播报。
     *
     * 为什么需要：通知与语音原来只写在 run.completed 分支里，而流被掐断时那条事件
     * 根本收不到，只能靠探测「Known(completed)」收尾——结果任务跑完了手机不响、
     * 不播报（实测 2.88 用户报「没有任务完成的通知」）。这里用服务端返回的 output，
     * 没有就回落本地最后一条助手正文。
     */
    private fun notifyRecoveredCompletion(sid: String, out: String) {
        val r = rt(sid)
        val text = out.ifEmpty {
            r.messages.value.lastOrNull { it.role == "assistant" && it.text.isNotEmpty() }?.text.orEmpty()
        }
        if (text.isEmpty()) return
        notifyCompletion(sid, text)
        VoicePlayer.playFromReply(getApplication(), text, prefs.playCompletionVoice)
    }

    /**
     * 任务完成提醒。
     * 后台：一直提醒（原行为），前提是「后台运行」开着——否则根本没有连接能收到事件。
     * 前台：只在「别的会话跑完」且用户开了「其它会话完成也提醒」时弹；
     *       当前会话的内容就在屏幕上，再弹是骚扰。
     */
    private fun notifyCompletion(sid: String, output: String) {
        val isCurrent = sid == _currentId.value
        val should = if (!AppForeground.isForeground) prefs.keepAlive
                     else !isCurrent && prefs.notifySessionCompletions
        if (!should) return
        val app = getApplication<Application>()
        val body = output.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "任务已完成" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyMessage(app, "Hermes 回复", body, sid)
    }

    /** 缓存占用文案（待发图片 + 安装包 + 图片缓存），供设置页显示。 */
    private val _cacheText = MutableStateFlow("")
    val cacheText = _cacheText.asStateFlow()

    fun refreshCache() {
        val app = getApplication<Application>()
        _cacheText.value = fmtSize(CacheUtil.total(app))
    }

    /** 清理临时缓存；会话记录与草稿不动。 */
    fun clearCache() {
        val app = getApplication<Application>()
        CacheUtil.clear(app)
        refreshCache()
    }

    /** 设置页切换「后台运行」时调用：关掉立即停掉前台服务，常驻通知随之消失。 */
    fun setKeepAlive(on: Boolean) {
        prefs.keepAlive = on
        // 关掉开关也不能直接停：还有任务在跑就得继续举牌。
        updateRunService()
    }

    /** 设置页「其它会话完成也提醒」：需一条活连接才能观察到别的会话收尾。 */
    fun setNotifySessionCompletions(on: Boolean) {
        prefs.notifySessionCompletions = on
    }

    /** 设置页「完成语音播报」：任务跑完自动播服务端下发的整段语音。 */
    fun setPlayCompletionVoice(on: Boolean) {
        prefs.playCompletionVoice = on
        if (!on) { VoicePlayer.stop(); StreamVoicePlayer.stop(); VoiceReplayPlayer.stop() }
    }

    /**
     * 提示条上的「跳过」：跳过当前正在播的这条语音，接着播队列里的下一条。
     * 流式那条在播就跳过它；否则是手动重播在响，停掉它（会接着推进流式队列）。
     */
    fun skipVoice() {
        if (StreamVoicePlayer.nowPlaying.value.isNotEmpty()) {
            StreamVoicePlayer.skipCurrent()
        } else {
            VoiceReplayPlayer.stop()
        }
    }

    /**
     * 设置页调播报语速：写入 prefs 并立刻灌进**三个**播放器。
     *
     * 为什么必须三个都灌：自动播报走 StreamVoicePlayer、重播按钮走 VoiceReplayPlayer、
     * 老的整段附件走 VoicePlayer。它们各自持有 rate 字段、互不同步；只灌 VoicePlayer 时，
     * 设置页改完语速对自动播报（流式）与点重播都不生效——正是用户报的「语速功能失效」。
     */
    fun setVoiceRate(rate: Float) {
        prefs.voiceRate = rate
        VoicePlayer.rate = rate
        StreamVoicePlayer.rate = rate
        VoiceReplayPlayer.rate = rate
        // 正在播的流式语音立刻变速（ExoPlayer 支持播放中改速）；重播是 MediaPlayer，
        // 用 playWhenReady 的实时 setSpeed 会在部分机型上抛异常，故只对新播放生效。
        StreamVoicePlayer.applyRateNow()
    }

    // ---------- 自更新 ----------

    /** 只检查，不下载。有新版本时挂到 pendingUpdate，由界面弹确认框。 */
    fun checkUpdate(currentVersionCode: Int, ctx: Context) {
        val a = api ?: return
        _updateNote.value = "检查中…"
        _pendingUpdate.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate()
                if (info == null) {
                    _updateNote.value = "检查失败：无法获取版本信息"
                    return@launch
                }
                if (info.versionCode <= currentVersionCode) {
                    _updateNote.value = "已是最新版本 " + info.versionName
                    _updateBadge.value = false
                    return@launch
                }
                _updateNote.value = ""
                _updateBadge.value = true
                _pendingUpdate.value = info
            } catch (e: Exception) {
                _updateNote.value = "检查失败：" + (e.message ?: "?")
            }
        }
    }

    fun dismissUpdate() {
        _pendingUpdate.value = null
    }

    /** 用户点「下载并安装」后调用：带进度下载，完成后拉起安装。 */
    fun confirmUpdate(ctx: Context) {
        val a = api ?: return
        val info = _pendingUpdate.value ?: return
        _pendingUpdate.value = null
        _updateNote.value = "下载中…"
        _downloadPct.value = 0
        _downloadText.value = "0%"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = a.downloadApk(info.url, ctx, info.versionName, info.md5) { done, total ->
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        _downloadPct.value = pct
                        _downloadText.value = pct.toString() + "%  " + fmtSize(done) + "/" + fmtSize(total)
                    } else {
                        _downloadText.value = fmtSize(done)
                    }
                }
                if (f == null) {
                    _updateNote.value = "下载失败（安装包校验没过或链路中断，请重试）"
                    AppLog.log("update", "下载/校验失败，未交给安装器")
                    _downloadPct.value = -1
                    return@launch
                }
                _downloadPct.value = 100
                _downloadText.value = "100%"
                _updateNote.value = "下载完成，请在弹出的提示中安装"
                AppLog.log("update", "拉起安装器 " + f.absolutePath)
                launch(Dispatchers.Main) { installApk(ctx, f) }
            } catch (e: Exception) {
                _updateNote.value = "下载失败：" + (e.message ?: "?")
                AppLog.log("update", "下载异常：" + (e.message ?: "?"))
                _downloadPct.value = -1
            }
        }
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
        b >= 1024L -> String.format("%.0f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    private fun installApk(ctx: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            _updateNote.value = "安装失败：" + (e.message ?: "?")
        }
    }
}
